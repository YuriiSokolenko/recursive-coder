package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/**
 * A verified on-disk prefix, not permission to replay any model/tool operation.
 * Callers must reconcile side effects and checkpoint authority separately (#103-#105).
 */
data class VerifiedSessionEventLedger(
    val events: List<JsonObject>,
    val lastSequence: Int,
    val headSha256: String,
    val fileSha256: String,
    /** Exact verified byte boundary, not an acknowledgement of tool side effects. */
    val lastTrustedOffset: Long
)

/** No corrupt, incomplete or unsupported log may be treated as recoverable. */
class SessionLedgerUnconfirmed(val code: String, cause: Throwable? = null) :
    IllegalStateException(code, cause)

/**
 * Recovery-oriented event sink. Unlike JsonlEventSink, each acknowledged append
 * is backed by a successful FileChannel.force(true). A binary v1 record contains
 * (big-endian length, SHA-256(previousDigest || UTF8 event), UTF8 event).
 *
 * A valid prefix establishes only the events durably present. A crash during a
 * force may leave a complete but unacknowledged record; replay does not imply a
 * side effect was committed. A torn tail always requires operator reconciliation;
 * this class never truncates or appends to an existing ledger.
 *
 * The caller provisions a private directory outside the workspace. Advisory OS
 * leases exclude cooperating processes, not hostile same-UID filesystem writers.
 */
class DurableSessionEventLedger private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val leaseChannel: FileChannel,
    private val lease: FileLock
) : EventSink {
    /** Host-only path for verifying the checkpoint storage boundary. */
    internal val checkpointStoragePath: Path get() = path

    private var closed = false
    private var closeVerified = false
    private var poisoned = false
    private var committedSequence = 0
    private var lastEventId: String? = null
    private var committedIdentity: Pair<String, String>? = null
    private var pendingIdentity: Pair<String, String>? = null
    private var lastDigest = ByteArray(32)
    private var pending: Pair<String, ByteArray>? = null

    companion object {
        private val MAGIC = "RCLDG1\n".toByteArray(StandardCharsets.US_ASCII)
        private const val MAX_EVENT_BYTES = 16 * 1024
        private const val MAX_LEDGER_BYTES = 32L * 1024 * 1024
        // Single host-only live checkpoint limit, enforced under the append monitor
        // before replay. The normal closed-ledger capacity is separate.
        internal const val MAX_LIVE_CHECKPOINT_LEDGER_BYTES = 4L * 1024 * 1024
        internal const val LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE =
            "LEDGER_CHECKPOINT_SIZE_EXCEEDED"
        private const val MAX_EVENTS = 100_000
        private val ROOT_FIELDS = SessionEventJournal.emittedFields
        private val heldLockPaths = ConcurrentHashMap.newKeySet<Path>()
        private val SAFE_KEY = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
        private val SAFE_VALUE = Regex("[A-Za-z0-9][A-Za-z0-9_.:/,+ -]{0,255}")
        // Explicitly enumerated metadata emitted by the existing host journal adapters.
        // Anything new requires a deliberate schema/policy review before durability.
        private val NUMBER_KEYS = setOf(
            "bytes", "inputTokens", "outputTokens", "contextTokens",
            "maxOutputTokens", "attempts", "turn", "httpStatus", "modelRequests",
            "evidenceCount", "grantTokens", "grantToolCalls", "spentTokens",
            "spentToolCalls", "delegatedTokens", "delegatedToolCalls",
            "observedTokens", "observedToolCalls", "accountedTokens", "accountedToolCalls"
        )
        private val BOOLEAN_KEYS = setOf(
            "physicalCompleted", "usageUncertain", "requiresReconciliation",
            "schedulerChargeRecorded"
        )
        private val SHA_KEYS = setOf("sha256", "artifactSha256", "reviewedSha256")
        private val ID_KEYS = setOf(
            "receiptId", "graphId", "parentGraphId", "childGraphId",
            "stageRunId", "requestId", "artifactId", "budgetScopeGraphId"
        )
        private val TEXT_KEYS = setOf(
            "tool", "provider", "features", "deniedCode", "recursiveEvent",
            "roleEvent", "origin", "role", "kind", "stageState",
            "approval", "verdict", "reasonCode", "scope", "code",
            "childResourceAudit", "recursiveStopCode"
        )
        private val SAFE_EVENT_TYPES = setOf(
            "session.transition", "session.resumed", "agent.started",
            "agent.completed", "llm.capabilities", "llm.request", "llm.response",
            "tool.started", "tool.completed", "checkpoint.saved", "policy.denied",
            "session.error", "redaction.applied"
        )
        private val SAFE_STATUSES = setOf(
            "started", "completed", "failed", "denied", "cancelled", "timed_out"
        )
        private val PHASES = setOf("created", "accepted", "running",
            "checkpointing", "resuming", "paused", "succeeded", "failed",
            "cancelled", "timed_out", "rejected")
        private val REMOVED_FIELDS = setOf("rawPrompt", "rawResponse",
            "rawToolOutput", "credentialRef", "authorizationHeader")

        private fun sha(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun chain(previous: ByteArray, payload: ByteArray): ByteArray =
            sha(previous + payload)

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        private fun guardedPath(path: Path): Path {
            val normalized = path.toAbsolutePath().normalize()
            val parent = normalized.parent ?: throw SessionLedgerUnconfirmed("INVALID_LEDGER_PATH")
            // Fail closed on directory symlinks, including symlinked ancestors.
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
                parent.toRealPath() != parent || normalized.fileName == null)
                throw SessionLedgerUnconfirmed("INVALID_LEDGER_PATH")
            return normalized
        }

        private fun leasePath(path: Path): Path =
            path.resolveSibling(path.fileName.toString() + ".lock")


        /**
         * FileChannel/POSIX locks are process-scoped on Linux. Closing ANY
         * second channel to the same inode can drop the live owner's lock.
         * Reserve the canonical lock path BEFORE opening another channel.
         */
        private fun acquire(path: Path, createLock: Boolean): Pair<FileChannel, FileLock> {
            val target = leasePath(path)
            if (!heldLockPaths.add(target))
                throw SessionLedgerUnconfirmed("LEDGER_LEASE_HELD")
            var owner: FileChannel? = null
            try {
                if (Files.isSymbolicLink(target))
                    throw SessionLedgerUnconfirmed("UNSAFE_LEDGER_LOCK")
                // Inspection must never create or replace a missing lock file.
                // An existing ledger without its lock cannot be authenticated safely.
                owner = if (createLock)
                    FileChannel.open(target, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                else
                    FileChannel.open(target, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)
                val lock = try { owner.tryLock() }
                    catch (_: OverlappingFileLockException) { null }
                return owner to (lock ?: throw SessionLedgerUnconfirmed("LEDGER_LEASE_HELD"))
            } catch (error: Exception) {
                try { owner?.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                heldLockPaths.remove(target)
                if (error is SessionLedgerUnconfirmed) throw error
                if (error is NoSuchFileException && !createLock)
                    throw SessionLedgerUnconfirmed("LEDGER_LOCK_MISSING", error)
                throw SessionLedgerUnconfirmed("LEDGER_LEASE_UNAVAILABLE", error)
            }
        }

        /** Drop the in-JVM reservation only after the OS owner has closed. */
        private fun release(path: Path, owner: FileChannel, lock: FileLock) {
            var error: Exception? = null
            try { lock.release() } catch (e: Exception) { error = e }
            try { owner.close() } catch (e: Exception) {
                if (error == null) error = e else error.addSuppressed(e)
            }
            if (error != null) throw SessionLedgerUnconfirmed("LEDGER_LEASE_RELEASE_UNCONFIRMED", error)
            heldLockPaths.remove(leasePath(path))
        }

        /**
         * CREATE_NEW avoids silently reviving a prior process's output. The
         * workspace root is mandatory so journal storage cannot be placed under
         * a checkout whose files are controlled by a Developer stage.
         */
        fun create(path: Path, workspaceRoot: Path): DurableSessionEventLedger {
            val file = guardedPath(path)
            val workspace = workspaceRoot.toRealPath()
            if (file.startsWith(workspace))
                throw SessionLedgerUnconfirmed("LEDGER_INSIDE_WORKSPACE")
            if (heldLockPaths.contains(leasePath(file)))
                throw SessionLedgerUnconfirmed("LEDGER_LEASE_HELD")
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                throw SessionLedgerUnconfirmed("LEDGER_ALREADY_EXISTS")
            val (owner, lock) = acquire(file, createLock = true)
            var channel: FileChannel? = null
            try {
                channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                writeFully(channel, MAGIC)
                channel.force(true)
                // Persist new directory entries as well as the file content.
                FileChannel.open(file.parent, StandardOpenOption.READ).use { it.force(true) }
                return DurableSessionEventLedger(file, channel, owner, lock)
            } catch (e: Exception) {
                try { channel?.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                try { release(file, owner, lock) }
                catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                if (e is java.nio.file.FileAlreadyExistsException)
                    throw SessionLedgerUnconfirmed("LEDGER_ALREADY_EXISTS", e)
                throw e
            }
        }

        private fun writeFully(channel: FileChannel, bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0)
                    throw SessionLedgerUnconfirmed("LEDGER_WRITE_UNCONFIRMED")
            }
        }

        private fun readFully(channel: FileChannel, length: Int): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0)
                    throw SessionLedgerUnconfirmed("LEDGER_TRUNCATED")
            }
            return buffer.array()
        }

        private fun identifier(value: JsonElement?): Boolean =
            value is JsonPrimitive && value.isString && value.content.length <= 160 &&
                ToolAuthorizationPolicy.validHostIdentifier(value.content)

        private fun metadataSafe(event: JsonObject) {
            if (!event.keys.all { it in ROOT_FIELDS })
                throw SessionLedgerUnconfirmed("LEDGER_UNSUPPORTED_FIELDS")
            val redaction = event["redaction"] as? JsonObject
                ?: throw SessionLedgerUnconfirmed("LEDGER_REDACTION_REQUIRED")
            if (redaction.keys != setOf("mode", "removedFields") ||
                (redaction["mode"] as? JsonPrimitive)?.content != "metadata-only" ||
                redaction["removedFields"] !is JsonArray ||
                (redaction["removedFields"] as JsonArray).any {
                    it !is JsonPrimitive || !it.isString || it.content !in REMOVED_FIELDS
                })
                throw SessionLedgerUnconfirmed("LEDGER_REDACTION_REQUIRED")

            val actor = event["actor"] as? JsonObject
                ?: throw SessionLedgerUnconfirmed("LEDGER_EVENT_INVALID")
            if ("agentId" !in actor || actor.keys.any { it !in setOf("agentId",
                    "parentAgentId", "childAgentId", "toolExecutionId", "childSessionId") } ||
                !identifier(actor["agentId"]) ||
                actor.any { (key, value) -> key != "agentId" && value != JsonNull &&
                    !identifier(value) })
                throw SessionLedgerUnconfirmed("LEDGER_SENSITIVE_ACTOR")

            event["transition"]?.let {
                val transition = it as? JsonObject
                    ?: throw SessionLedgerUnconfirmed("LEDGER_SENSITIVE_TRANSITION")
                if (transition.keys != setOf("from", "to") ||
                    transition.values.any { value ->
                        value !is JsonPrimitive || !value.isString || value.content !in PHASES
                    })
                    throw SessionLedgerUnconfirmed("LEDGER_SENSITIVE_TRANSITION")
            }
            event["timing"]?.let {
                val timing = it as? JsonObject
                    ?: throw SessionLedgerUnconfirmed("LEDGER_SENSITIVE_TIMING")
                val started = timing["startedAt"] as? JsonPrimitive
                val duration = timing["durationMs"] as? JsonPrimitive
                if (timing.keys != setOf("startedAt", "durationMs") ||
                    started == null || !started.isString ||
                    runCatching { Instant.parse(started.content) }.isFailure ||
                    duration?.longOrNull == null || duration.longOrNull!! < 0 ||
                    duration.isString)
                    throw SessionLedgerUnconfirmed("LEDGER_SENSITIVE_TIMING")
            }
            val metadata = event["metadata"] ?: return
            val obj = metadata as? JsonObject
                ?: throw SessionLedgerUnconfirmed("LEDGER_UNSUPPORTED_METADATA")
            for ((key, value) in obj) {
                if (!SAFE_KEY.matches(key) ||
                    key !in (NUMBER_KEYS + BOOLEAN_KEYS + SHA_KEYS + ID_KEYS + TEXT_KEYS))
                    throw SessionLedgerUnconfirmed("LEDGER_SENSITIVE_METADATA")
                val scalar = value as? JsonPrimitive
                    ?: throw SessionLedgerUnconfirmed("LEDGER_UNSUPPORTED_METADATA")
                val valid = when (key) {
                    in NUMBER_KEYS -> !scalar.isString && scalar.longOrNull?.let { it >= 0 } == true
                    in BOOLEAN_KEYS -> !scalar.isString && scalar.content in setOf("true", "false")
                    in SHA_KEYS -> scalar.isString && scalar.content.matches(Regex("[0-9a-f]{64}"))
                    in ID_KEYS -> value == JsonNull || identifier(value)
                    in TEXT_KEYS -> scalar.isString && SAFE_VALUE.matches(scalar.content)
                    else -> false
                }
                if (!valid) throw SessionLedgerUnconfirmed("LEDGER_UNSUPPORTED_METADATA")
            }
        }

        private fun validate(event: JsonObject, expectedSequence: Int, previousId: String?) {
            metadataSafe(event)
            if ((event["kind"] as? JsonPrimitive)?.content != "session-event" ||
                (event["version"] as? JsonPrimitive)?.content != SessionProtocol.VERSION ||
                (event["sequence"] as? JsonPrimitive)?.intOrNull != expectedSequence)
                throw SessionLedgerUnconfirmed("LEDGER_EVENT_VERSION_OR_SEQUENCE")
            val session = (event["sessionId"] as? JsonPrimitive)?.content
                ?: throw SessionLedgerUnconfirmed("LEDGER_IDENTITY_MISSING")
            val id = (event["eventId"] as? JsonPrimitive)?.content
            if (id != SessionEventJournal.eventId(session, expectedSequence) ||
                (previousId == null && event["causationId"] != JsonNull) ||
                (previousId != null && (event["causationId"] as? JsonPrimitive)?.content != previousId))
                throw SessionLedgerUnconfirmed("LEDGER_EVENT_CHAIN_INVALID")
            if (!identifier(event["sessionId"]) ||
                !identifier(event["traceId"]) ||
                !identifier(event["correlationId"]) ||
                event["timestamp"] !is JsonPrimitive ||
                runCatching {
                    Instant.parse((event["timestamp"] as JsonPrimitive).content)
                }.isFailure ||
                (event["type"] as? JsonPrimitive)?.content !in SAFE_EVENT_TYPES ||
                (event["status"] as? JsonPrimitive)?.content !in SAFE_STATUSES)
                throw SessionLedgerUnconfirmed("LEDGER_EVENT_INVALID")
        }

        private fun decode(bytes: ByteArray): JsonObject = try {
            val utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            Json.parseToJsonElement(utf8.decode(ByteBuffer.wrap(bytes)).toString()) as JsonObject
        } catch (e: Exception) {
            throw SessionLedgerUnconfirmed("LEDGER_EVENT_INVALID_JSON", e)
        }

        /** Internal parser; external inspect still requires an exclusive ledger lease. */
        private fun replayUnlocked(file: Path): VerifiedSessionEventLedger {
            FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { reader ->
                val size = reader.size()
                if (size < MAGIC.size || size > MAX_LEDGER_BYTES)
                    throw SessionLedgerUnconfirmed("LEDGER_SIZE_INVALID")
                val whole = MessageDigest.getInstance("SHA-256")
                val header = readFully(reader, MAGIC.size)
                if (!header.contentEquals(MAGIC))
                    throw SessionLedgerUnconfirmed("LEDGER_VERSION_UNSUPPORTED")
                whole.update(header)
                val events = ArrayList<JsonObject>()
                var previousHash = ByteArray(32)
                var previousId: String? = null
                var identity: Pair<String, String>? = null
                var sequence = 0
                while (reader.position() < size) {
                    if (++sequence > MAX_EVENTS || size - reader.position() < 4)
                        throw SessionLedgerUnconfirmed("LEDGER_TRUNCATED")
                    val sizeBytes = readFully(reader, 4)
                    val payloadSize = ByteBuffer.wrap(sizeBytes).int
                    if (payloadSize !in 1..MAX_EVENT_BYTES)
                        throw SessionLedgerUnconfirmed("LEDGER_RECORD_SIZE_INVALID")
                    if (size - reader.position() < 32L + payloadSize)
                        throw SessionLedgerUnconfirmed("LEDGER_TRUNCATED")
                    val recordedHash = readFully(reader, 32)
                    val payload = readFully(reader, payloadSize)
                    whole.update(sizeBytes)
                    whole.update(recordedHash)
                    whole.update(payload)
                    val digest = chain(previousHash, payload)
                    if (!recordedHash.contentEquals(digest))
                        throw SessionLedgerUnconfirmed("LEDGER_HASH_MISMATCH")
                    val event = decode(payload)
                    validate(event, sequence, previousId)
                    val observedIdentity = (event["sessionId"] as JsonPrimitive).content to
                        (event["traceId"] as JsonPrimitive).content
                    if (identity != null && identity != observedIdentity)
                        throw SessionLedgerUnconfirmed("LEDGER_IDENTITY_CHANGED")
                    identity = observedIdentity
                    previousId = (event["eventId"] as JsonPrimitive).content
                    previousHash = digest
                    events.add(event)
                }
                return VerifiedSessionEventLedger(events, sequence,
                    hex(previousHash), hex(whole.digest()), size)
            }
    }

        /** Immutable, fully checked read. Never interprets a prefix as safe resume. */
        fun inspect(path: Path): VerifiedSessionEventLedger {
            val file = guardedPath(path)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw SessionLedgerUnconfirmed("INVALID_LEDGER_PATH")
            val (owner, lock) = acquire(file, createLock = false)
            var inspectionFailure: Throwable? = null
            try {
                return replayUnlocked(file)
            } catch (failure: Throwable) {
                inspectionFailure = failure
                throw failure
            } finally {
                try {
                    release(file, owner, lock)
                } catch (cleanup: Exception) {
                    // Never replace the corruption/verification refusal with cleanup.
                    if (inspectionFailure != null) inspectionFailure.addSuppressed(cleanup)
                    else throw cleanup
                }
            }
        }

        /** Adapts the workspace-aware creation contract to the existing EventSinkFactory port. */
        fun factory(workspaceRoot: Path): EventSinkFactory =
            EventSinkFactory { file -> create(file, workspaceRoot) }
    }

    /**
     * The active writer can take a checkpoint at an acknowledged event boundary.
     * Hold its monitor across the snapshot write so append/flush cannot race
     * with the journal anchor. The lock-file inode is never reopened.
     */
    @Synchronized internal fun <T> withCommittedCheckpointAnchor(
        block: (VerifiedSessionEventLedger) -> T
    ): T {
        if (closed || poisoned || pending != null)
            throw SessionLedgerUnconfirmed("LEDGER_UNACKNOWLEDGED_APPEND")
        // Refuse before replay so checkpointing cannot hold the append monitor
        // across a larger ledger than the opt-in bounded persistence contract.
        if (channel.size() > MAX_LIVE_CHECKPOINT_LEDGER_BYTES)
            throw SessionLedgerUnconfirmed(LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE)
        val verified = replayUnlocked(path)
        if (verified.lastSequence != committedSequence ||
            verified.headSha256 != hex(lastDigest) ||
            verified.lastTrustedOffset != channel.position())
            throw SessionLedgerUnconfirmed("LEDGER_COMMITTED_PREFIX_CHANGED")
        return block(verified)
    }

    @Synchronized override fun append(event: JsonObject) {
        check(!closed && !poisoned && pending == null) { "Ledger not ready for append" }
        val next = committedSequence + 1
        val payload = event.toString().toByteArray(StandardCharsets.UTF_8)
        if (payload.size !in 1..MAX_EVENT_BYTES ||
            channel.position() + 4L + 32 + payload.size > MAX_LEDGER_BYTES)
            throw SessionLedgerUnconfirmed("LEDGER_RECORD_SIZE_INVALID")
        validate(event, next, lastEventId)
        val identity = (event["sessionId"] as JsonPrimitive).content to
            (event["traceId"] as JsonPrimitive).content
        if (committedIdentity != null && committedIdentity != identity)
            throw SessionLedgerUnconfirmed("LEDGER_IDENTITY_CHANGED")
        val hash = chain(lastDigest, payload)
        val frame = ByteBuffer.allocate(4 + 32 + payload.size)
            .putInt(payload.size).put(hash).put(payload).array()
        try {
            writeFully(channel, frame)
            pending = (event["eventId"] as JsonPrimitive).content to hash
            pendingIdentity = identity
        } catch (e: Exception) {
            poisoned = true
            throw SessionLedgerUnconfirmed("LEDGER_WRITE_UNCONFIRMED", e)
        }
    }

    /** The sink must not acknowledge an append until FileChannel.force succeeds. */
    @Synchronized override fun flush() {
        check(!closed && !poisoned) { "Ledger unavailable" }
        try {
            channel.force(true)
            pending?.let { (eventId, digest) ->
                committedSequence++
                lastEventId = eventId
                lastDigest = digest
                committedIdentity = pendingIdentity
                pendingIdentity = null
                pending = null
            }
        } catch (e: Exception) {
            poisoned = true
            throw SessionLedgerUnconfirmed("LEDGER_SYNC_UNCONFIRMED", e)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val unconfirmed = poisoned || pending != null
        try {
            if (!unconfirmed) channel.force(true)
            channel.close()
            release(path, leaseChannel, lease)
            if (unconfirmed) throw SessionLedgerUnconfirmed("LEDGER_UNACKNOWLEDGED_APPEND")
            closeVerified = true
        } catch (e: Exception) {
            poisoned = true
            try { channel.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            if (heldLockPaths.contains(leasePath(path))) {
                try { release(path, leaseChannel, lease) }
                catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            }
            throw e
        }
    }

    @Synchronized override fun sha256(): String {
        check(closeVerified && !poisoned) { "Ledger not verified closed" }
        val verified = inspect(path)
        if (verified.lastSequence != committedSequence ||
            verified.headSha256 != hex(lastDigest))
            throw SessionLedgerUnconfirmed("LEDGER_CHANGED_AFTER_CLOSE")
        return verified.fileSha256
    }
}
