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
import java.util.HexFormat
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

/**
 * Explicit compile-time opt-in for test-only fault seams. This is NOT a JVM
 * security boundary; production wiring must never expose these callbacks.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
internal annotation class LedgerTestFaultInjection

/**
 * Single fail-closed event policy shared by resume admission and reopened
 * publication. Issue #114 authorizes metadata-only pre-execution work, not
 * arbitrary legal runtime lifecycle transitions (those remain gated by #105).
 */
internal object FencedGraphMetadataPolicy {
    fun permittedFencedGraphMetadata(event: JsonObject, rootGraphId: String): Boolean {
        val metadata = event["metadata"] ?: return true
        val obj = metadata as? JsonObject ?: return false
        if ("parentGraphId" in obj || "childGraphId" in obj) return false
        val graph = obj["graphId"] ?: return true
        return graph is JsonPrimitive && graph.isString && graph.content == rootGraphId
    }
}

/** A local pre-write policy rejection is not a loss of the resume lease. */
private class FencedPolicyRefusal : RuntimeException()

/** A normal frame validation failure has not attempted a physical write. */
private class FencedInputRefusal(val reason: SessionLedgerUnconfirmed) : RuntimeException(reason)

/** Known writer-state refusals must not convert checkpoint ambiguity to poisoning. */
private class FencedStateRefusal(val reason: SessionLedgerUnconfirmed) : RuntimeException(reason)

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
 * existing ledgers reopen only through a current lease and fenced atomic append.
 *
 * The caller provisions a private directory outside the workspace. Advisory OS
 * leases exclude cooperating processes, not hostile same-UID filesystem writers.
 */
class DurableSessionEventLedger private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val leaseChannel: FileChannel,
    private val lease: FileLock,
    private val resumeLease: SessionResumeLease? = null,
    private val fencedGraphId: String? = null
) : EventSink {
    /** Host-only path for verifying the checkpoint storage boundary. */
    internal val checkpointStoragePath: Path get() = path

    private var closed = false
    private var closeVerified = false
    private var poisoned = false
    /** Only failures after a physical write/force may imply unacknowledged bytes. */
    private var uncertainWrite = false
    /** Reopened writers must persist a matching checkpoint before clean close. */
    private var acknowledgedSinceCheckpoint = false
    /** Persist may have published C2 before returning a failure; never auto-retry. */
    private var checkpointPublicationUnconfirmed = false
    private var committedSequence = 0
    private var lastEventId: String? = null
    private var committedIdentity: Pair<String, String>? = null
    private var pendingIdentity: Pair<String, String>? = null
    private var lastDigest = ByteArray(32)
    /** Confirmed pre-execution phase, restored from the checkpoint and replay. */
    private var committedPhase: String? = null
    private var pendingPhase: String? = null
    private var pending: Pair<String, ByteArray>? = null

    companion object {
        private val MAGIC = "RCLDG1\n".toByteArray(StandardCharsets.US_ASCII)
        private const val MAX_EVENT_BYTES = 16 * 1024
        private const val FRAME_OVERHEAD_BYTES = 36L
        private const val MAX_LEDGER_BYTES = 32L * 1024 * 1024
        // Single host-only live checkpoint limit, enforced under the append monitor
        // before replay. The normal closed-ledger capacity is separate.
        internal const val MAX_LIVE_CHECKPOINT_LEDGER_BYTES = 4L * 1024 * 1024
        internal const val LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE =
            "LEDGER_CHECKPOINT_SIZE_EXCEEDED"
        internal const val REOPEN_CHECKPOINT_CAPACITY_CODE =
            "LEDGER_REOPEN_CHECKPOINT_CAPACITY"
        internal const val MAX_EVENTS = 100_000
        /** A closed-ledger prefix must fit within the live-checkpoint bound. */
        internal fun requireReopenCheckpointCapacity(position: Long) {
            if (position < 0 || position > MAX_LIVE_CHECKPOINT_LEDGER_BYTES)
                throw SessionLedgerUnconfirmed(REOPEN_CHECKPOINT_CAPACITY_CODE)
        }

        /** A new full framed record must leave the reopened ledger checkpointable. */
        internal fun requireReopenCheckpointCapacity(position: Long, payloadSize: Int) {
            if (payloadSize <= 0 || position < 0 ||
                position + FRAME_OVERHEAD_BYTES + payloadSize > MAX_LIVE_CHECKPOINT_LEDGER_BYTES)
                throw SessionLedgerUnconfirmed(REOPEN_CHECKPOINT_CAPACITY_CODE)
        }
        /** Both normal and reopened writers enforce the replayable whole-file caps. */
        internal fun requireAppendCapacity(committed: Int, position: Long, payloadSize: Int) {
            if (committed >= MAX_EVENTS)
                throw SessionLedgerUnconfirmed("LEDGER_EVENT_LIMIT_EXCEEDED")
            if (payloadSize !in 1..MAX_EVENT_BYTES ||
                position < MAGIC.size || position + FRAME_OVERHEAD_BYTES + payloadSize > MAX_LEDGER_BYTES)
                throw SessionLedgerUnconfirmed("LEDGER_RECORD_SIZE_INVALID")
        }

        private fun reopenCheckpointFailure(error: CheckpointUnconfirmed): SessionLedgerUnconfirmed =
            SessionLedgerUnconfirmed(
                if (error.code == "CHECKPOINT_LEASE_HELD") "LEDGER_REOPEN_BUSY"
                else "LEDGER_REOPEN_CHECKPOINT_MISMATCH", error)

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
            "observedTokens", "observedToolCalls", "accountedTokens", "accountedToolCalls",
            // Read-only #105 effect-projection envelope (NOT executor authority).
            "effectFenceGeneration", "effectRecordSequence"
        )
        private val BOOLEAN_KEYS = setOf(
            "physicalCompleted", "usageUncertain", "requiresReconciliation",
            "schedulerChargeRecorded"
        )
        private val SHA_KEYS = setOf("sha256", "artifactSha256", "reviewedSha256",
            "effectInputSha256", "effectRecordMacSha256", "effectObservedStateSha256")
        private val ID_KEYS = setOf(
            "receiptId", "graphId", "parentGraphId", "childGraphId",
            "stageRunId", "requestId", "artifactId", "budgetScopeGraphId",
            "effectOperationId"
        )
        private val TEXT_KEYS = setOf(
            "tool", "provider", "features", "deniedCode", "recursiveEvent",
            "roleEvent", "origin", "role", "kind", "stageState",
            "approval", "verdict", "reasonCode", "scope", "code",
            "childResourceAudit", "recursiveStopCode",
            "effectKind", "effectState", "effectErrorCode"
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
            // Reserve the entire effect* namespace. No producer may durably
            // append a stray/partial/wrong-event effect envelope. Even a
            // complete envelope is only metadata, NOT execution authority.
            if (EffectEvidenceEnvelopePolicy.hasReservedKey(obj)) {
                val type = (event["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                val status = (event["status"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                if (!EffectEvidenceEnvelopePolicy.validForEvent(type, status, obj))
                    throw SessionLedgerUnconfirmed("LEDGER_EFFECT_ENVELOPE_INVALID")
            }
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

        /** Read-only inspect owns a separate READ channel under its own lease. */
        private fun replayUnlocked(file: Path): VerifiedSessionEventLedger =
            FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                replayChannel(it)
            }

        /**
         * Replay from the exact channel that will append after fenced reopen.
         * No new path lookup or second descriptor can change the file identity
         * between validation and the long-lived writer's subsequent writes.
         */
        private fun replayChannel(reader: FileChannel): VerifiedSessionEventLedger {
                reader.position(0L)
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

        /**
         * Trusted host only. Inspect the checkpoint before taking the long-lived
         * ledger lock, then re-verify the entire prefix under that lock.
         */
        @OptIn(LedgerTestFaultInjection::class)
        fun reopenForAppend(
            path: Path, workspaceRoot: Path, checkpointDirectory: Path,
            context: CheckpointHostContext, lease: SessionResumeLease
        ): DurableSessionEventLedger =
            reopenForAppendWithFault(path, workspaceRoot, checkpointDirectory, context, lease) { }

        /** Internal fault seam only; do not expose callback injection to production hosts. */
        @LedgerTestFaultInjection
        internal fun reopenForAppendWithFault(
            path: Path, workspaceRoot: Path, checkpointDirectory: Path,
            context: CheckpointHostContext, lease: SessionResumeLease,
            afterPhase: (String) -> Unit
        ): DurableSessionEventLedger {
            lease.assertCurrent()
            val file = guardedPath(path)
            if (workspaceRoot.toRealPath() != context.workspaceRoot.toRealPath())
                throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CONTEXT_MISMATCH")
            if (file.startsWith(workspaceRoot.toRealPath()))
                throw SessionLedgerUnconfirmed("LEDGER_INSIDE_WORKSPACE")
            lease.requireLedgerPath(file)
            // Inspect immutable checkpoint evidence under the SHORT checkpoint
            // lock without taking a ledger lock; the full ledger is reverified
            // below under the reopened writer's long-lived ledger OS lock.
            val admitted = try {
                SessionCheckpointStore.admittedCheckpointForReopen(
                    lease, checkpointDirectory, context)
            } catch (error: CheckpointUnconfirmed) {
                throw reopenCheckpointFailure(error)
            }
            val expected = admitted.snapshot
            // Deliberately before acquiring the ledger lock: tests can
            // deterministically publish C2 after this C1 inspection.
            afterPhase("checkpoint-inspected")
            val (owner, lock) = acquire(file, createLock = false)
            var writerChannel: FileChannel? = null
            var opened = false
            var failure: Throwable? = null
            try {
                afterPhase("ledger-lock-acquired")
                lease.assertCurrent()
                lease.requireLedgerPath(file)
                writerChannel = FileChannel.open(file, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                lease.requireLedgerPath(file)
                val actualSize = writerChannel.size()
                // A closed-ledger checkpoint may cover up to 32 MiB, but an
                // *open* resumed writer can only checkpoint a 4 MiB prefix.
                // Refuse before any append that could strand this session.
                requireReopenCheckpointCapacity(expected.lastTrustedJournalOffset)
                if (actualSize > expected.lastTrustedJournalOffset)
                    throw SessionLedgerUnconfirmed("LEDGER_REOPEN_TAIL_UNCONFIRMED")
                if (actualSize < expected.lastTrustedJournalOffset)
                    throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH")
                val verified = try { replayChannel(writerChannel) }
                    catch (error: SessionLedgerUnconfirmed) {
                        throw SessionLedgerUnconfirmed(
                            "LEDGER_REOPEN_CHECKPOINT_MISMATCH", error)
                    }
                if (verified.lastSequence != expected.journalSequence ||
                    verified.headSha256 != expected.journalHeadSha256 ||
                    verified.fileSha256 != expected.journalFileSha256 ||
                    verified.lastTrustedOffset != expected.lastTrustedJournalOffset ||
                    verified.events.any {
                        (it["sessionId"] as? JsonPrimitive)?.content != expected.sessionId ||
                        (it["traceId"] as? JsonPrimitive)?.content != expected.traceId
                    })
                    throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH")
                val admittedPhase = PreExecutionPhasePolicy.phaseFromVerifiedHistory(
                    expected.phase, verified.events)
                    ?: throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH")
                try {
                    SessionCheckpointStore.verifyReopenLedger(expected, context, verified)
                } catch (error: CheckpointUnconfirmed) {
                    throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH", error)
                }
                afterPhase("ledger-prefix-verified")
                lease.assertCurrent()
                lease.requireLedgerPath(file)
                // Recheck the latest checkpoint while holding the ledger OS
                // lock. We have ALREADY entered the lease monitor above via
                // assertCurrent/requireLedgerPath, so this is not a global
                // ledger-before-lease ordering guarantee. The checkpoint
                // helper avoids ONE ADDITIONAL lease-monitor call; every OS
                // file lock used on this path is nonblocking tryLock.
                // A competing same-lease writer may have published C2 after
                // the first C1 inspection, before this lock was acquired.
                val currentCheckpoint = try {
                    SessionCheckpointStore.admittedCheckpointHeadForReopen(
                        lease.admittedCheckpointSha256, checkpointDirectory, context)
                } catch (error: CheckpointUnconfirmed) {
                    throw reopenCheckpointFailure(error)
                }
                if (currentCheckpoint.sha256 != admitted.sha256)
                    throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH")
                if (writerChannel.size() != expected.lastTrustedJournalOffset)
                    throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH")
                writerChannel.position(expected.lastTrustedJournalOffset)
                val writer = DurableSessionEventLedger(
                    file, writerChannel, owner, lock, lease, expected.binding.graphId)
                writer.committedSequence = verified.lastSequence
                writer.lastEventId = verified.events.lastOrNull()
                    ?.get("eventId")?.jsonPrimitive?.content
                writer.committedIdentity = expected.sessionId to expected.traceId
                writer.committedPhase = admittedPhase
                writer.lastDigest = try {
                    HexFormat.of().parseHex(verified.headSha256).also {
                        if (it.size != 32)
                            throw SessionLedgerUnconfirmed(
                                "LEDGER_REOPEN_CHECKPOINT_MISMATCH")
                    }
                } catch (error: IllegalArgumentException) {
                    throw SessionLedgerUnconfirmed(
                        "LEDGER_REOPEN_CHECKPOINT_MISMATCH", error)
                }
                opened = true
                return writer
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!opened) {
                    try { writerChannel?.close() }
                    catch (cleanup: Exception) { failure?.addSuppressed(cleanup) }
                    try { release(file, owner, lock) }
                    catch (cleanup: Exception) {
                        if (failure != null) failure.addSuppressed(cleanup)
                        else throw cleanup
                    }
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
        if (checkpointPublicationUnconfirmed)
            throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED")
        if (closed || poisoned)
            throw SessionLedgerUnconfirmed(if (resumeLease != null)
                "LEDGER_REOPEN_WRITER_POISONED" else "LEDGER_UNACKNOWLEDGED_APPEND")
        if (pending != null)
            throw SessionLedgerUnconfirmed("LEDGER_UNACKNOWLEDGED_APPEND")
        // Refuse before replay so checkpointing cannot hold the append monitor
        // across a larger ledger than the opt-in bounded persistence contract.
        if (channel.size() > MAX_LIVE_CHECKPOINT_LEDGER_BYTES)
            throw SessionLedgerUnconfirmed(LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE)
        // A reopened writer must replay its *own* descriptor, not a fresh
        // path lookup. Reader position is restored even on a failed replay,
        // so a subsequent append cannot accidentally overwrite the prefix.
        val verified = if (resumeLease != null) {
            val offset = channel.position()
            try { replayChannel(channel) }
            finally { channel.position(offset) }
        } else replayUnlocked(path)
        if (verified.lastSequence != committedSequence ||
            verified.headSha256 != hex(lastDigest) ||
            verified.lastTrustedOffset != channel.position())
            throw SessionLedgerUnconfirmed("LEDGER_COMMITTED_PREFIX_CHANGED")
        return block(verified)
    }

    /** Distinguish ordinary CREATE_NEW writer misuse from ledger drift. */
    internal fun requireFencedCheckpointWriter() {
        if (resumeLease == null) throw SessionLedgerUnconfirmed("LEDGER_NOT_FENCED")
    }

    /**
     * PRE-PUBLICATION proof. Called by the trusted host under the existing
     * lease → writer monitor lock order, before any checkpoint bytes are
     * committed. No post-persist validator may turn a completed publication
     * into a false failure.
     */
    @Synchronized internal fun requireFencedCheckpointPublication(
        currentLease: SessionResumeLease,
        anchor: VerifiedSessionEventLedger,
        snapshot: SessionCheckpoint
    ) {
        if (resumeLease == null)
            throw SessionLedgerUnconfirmed("LEDGER_NOT_FENCED")
        if (resumeLease !== currentLease ||
            closed || poisoned || pending != null || checkpointPublicationUnconfirmed ||
            committedSequence != anchor.lastSequence ||
            hex(lastDigest) != anchor.headSha256 ||
            channel.position() != anchor.lastTrustedOffset ||
            snapshot.journalSequence != anchor.lastSequence ||
            snapshot.journalHeadSha256 != anchor.headSha256 ||
            snapshot.journalFileSha256 != anchor.fileSha256 ||
            snapshot.lastTrustedJournalOffset != anchor.lastTrustedOffset ||
            !(
                (PreExecutionSnapshotPolicy.safe(snapshot) &&
                    PreExecutionPhasePolicy.phaseFromVerifiedHistory(
                        snapshot.phase, anchor.events) == committedPhase) ||
                (snapshot.phase == SessionPhaseVocabulary.runningWire && snapshot.taskState == "ready" &&
                    snapshot.effectJournalHead == null &&
                    snapshot.effectReconciliation == null &&
                    PreExecutionSnapshotPolicy.safe(snapshot.copy(phase = SessionPhaseVocabulary.acceptedWire)) &&
                    TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory(
                        snapshot.phase, anchor.events) == committedPhase)
            ))
            throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH")
    }

    /**
     * No-throw bookkeeping after a successful durable fenced save. Preconditions
     * (lease identity, writer state, snapshot/anchor) were proven while the
     * SAME writer monitor was held before persist; append cannot race this.
     */
    @Synchronized internal fun markFencedCheckpointPublished() {
        acknowledgedSinceCheckpoint = false
        checkpointPublicationUnconfirmed = false
    }

    /** Called only when checkpoint-store publication had an ambiguous result. */
    @Synchronized internal fun markFencedCheckpointPublicationUnconfirmed() {
        checkpointPublicationUnconfirmed = true
    }

    /**
     * Atomically fenced append: lease monitor precedes the writer monitor.
     * The whole append, force and acknowledgement share one publication.
     * The trusted host must close the writer BEFORE retiring its resume lease;
     * close does not acquire the lease monitor (avoids monitor inversion).
     */
    @OptIn(LedgerTestFaultInjection::class)
    fun appendFenced(event: JsonObject) = appendFencedWithFault(event) { }

    /**
     * Host-authorized one-way metadata transition. This does not activate any
     * executor. Ordinary appendFenced remains pre-execution-only.
     */
    @TrustedRunningBoundaryApi
    @OptIn(LedgerTestFaultInjection::class)
    internal fun appendTrustedAcceptedRunning(event: JsonObject) =
        appendFencedWithFault(event, allowRunningBoundary = true) { }

    /**
     * Integration-test-only seam. Deliberately internal: production hosts must
     * never execute arbitrary callbacks while owning the resume lease monitor.
     */
    @LedgerTestFaultInjection
    internal fun appendFencedWithFault(event: JsonObject,
        allowRunningBoundary: Boolean = false, afterPhase: (String) -> Unit) {
        val currentLease = resumeLease
            ?: throw SessionLedgerUnconfirmed("LEDGER_NOT_FENCED")
        try {
            // Reject closed/poisoned writers before event-type validation,
            // preserving the writer-state diagnostic on bad input.
            currentLease.publishIfCurrent {
                synchronized(this) {
                    if (closed)
                        throw FencedStateRefusal(
                            SessionLedgerUnconfirmed("LEDGER_REOPEN_WRITER_CLOSED"))
                    if (checkpointPublicationUnconfirmed)
                        throw FencedStateRefusal(
                            SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED"))
                    if (poisoned || pending != null)
                        throw FencedStateRefusal(
                            SessionLedgerUnconfirmed("LEDGER_REOPEN_WRITER_POISONED"))
                    currentLease.requireLedgerPath(path)
                    if (channel.size() != channel.position()) {
                        poisoned = true
                        throw SessionLedgerUnconfirmed("LEDGER_REOPEN_CHECKPOINT_MISMATCH")
                    }
                    val phase = committedPhase ?: throw FencedPolicyRefusal()
                    val nextPhase = (if (allowRunningBoundary)
                        TrustedRunningBoundaryPolicy.step(event, phase)
                    else PreExecutionPhasePolicy.step(event, phase))
                        ?: throw FencedPolicyRefusal()
                    if (!FencedGraphMetadataPolicy.permittedFencedGraphMetadata(
                            event, fencedGraphId ?: throw FencedPolicyRefusal()))
                        throw FencedPolicyRefusal()
                    // Fault seam for verifying that an authority exception
                    // with a policy-like public code still revokes the writer.
                    afterPhase("before-record-write")
                    try {
                        appendFrame(event)
                    } catch (error: SessionLedgerUnconfirmed) {
                        // appendFrame poisons itself on any attempted physical
                        // write failure. A still-healthy writer means this was
                        // only frame/sequence/identity validation before I/O.
                        if (!poisoned) throw FencedInputRefusal(error)
                        throw error
                    }
                    pendingPhase = nextPhase
                    afterPhase("record-written")
                    forcePending { afterPhase("forced-before-ack") }
                }
            }
        } catch (_: FencedPolicyRefusal) {
            // This specific local sentinel is the ONLY non-poisoning policy
            // refusal. A lease failure with the same public code is not one.
            throw SessionLedgerUnconfirmed("LEDGER_REOPEN_EVENT_REFUSED")
        } catch (error: FencedInputRefusal) {
            throw error.reason
        } catch (error: FencedStateRefusal) {
            // This is a known non-mutating state denial, not new I/O ambiguity.
            throw error.reason
        } catch (error: Throwable) {
            // Unknown failures and stale/missing lease authority revoke the
            // writer; uncertain physical writes are separately tracked by
            // appendFrame/forcePending for close() diagnostics.
            synchronized(this) { poisoned = true }
            throw error
        }
    }

    @Synchronized override fun append(event: JsonObject) {
        if (resumeLease != null) throw SessionLedgerUnconfirmed("LEDGER_FENCED_API_REQUIRED")
        appendFrame(event)
    }

    private fun appendFrame(event: JsonObject) {
        check(!closed && !poisoned && pending == null) { "Ledger not ready for append" }
        val next = committedSequence + 1
        val payload = event.toString().toByteArray(StandardCharsets.UTF_8)
        if (resumeLease != null)
            requireReopenCheckpointCapacity(channel.position(), payload.size)
        requireAppendCapacity(committedSequence, channel.position(), payload.size)
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
        } catch (e: Throwable) {
            poisoned = true
            uncertainWrite = true
            throw SessionLedgerUnconfirmed("LEDGER_WRITE_UNCONFIRMED", e)
        }
    }

    /** Ordinary EventSink flush is disallowed on reopened writers. */
    @Synchronized override fun flush() {
        if (resumeLease != null) throw SessionLedgerUnconfirmed("LEDGER_FENCED_API_REQUIRED")
        forcePending()
    }

    private fun forcePending(afterForce: () -> Unit = {}) {
        check(!closed && !poisoned) { "Ledger unavailable" }
        try {
            channel.force(true)
            afterForce()
            pending?.let { (eventId, digest) ->
                committedSequence++
                lastEventId = eventId
                lastDigest = digest
                committedIdentity = pendingIdentity
                if (resumeLease != null) {
                    committedPhase = pendingPhase
                    acknowledgedSinceCheckpoint = true
                }
                pendingPhase = null
                pendingIdentity = null
                pending = null
            }
        } catch (e: Throwable) {
            poisoned = true
            uncertainWrite = true
            throw SessionLedgerUnconfirmed("LEDGER_SYNC_UNCONFIRMED", e)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val unconfirmed = uncertainWrite || pending != null
        val revoked = poisoned && !unconfirmed && !checkpointPublicationUnconfirmed
        // Preserve primary C2 uncertainty, but also preserve lease revocation
        // as a suppressed cause; never force a revoked writer during close.
        val revokedWithAmbiguousCheckpoint =
            poisoned && !unconfirmed && checkpointPublicationUnconfirmed
        // Even a metadata-only save with no new append can leave an
        // uncertain new generation; never report clean close in that state.
        val missingCheckpoint = resumeLease != null &&
            (acknowledgedSinceCheckpoint || checkpointPublicationUnconfirmed)
        val checkpointDiagnostic = if (checkpointPublicationUnconfirmed)
            "LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED"
        else "LEDGER_REOPEN_CHECKPOINT_REQUIRED"
        var leaseReleased = false
        try {
            if (!unconfirmed && !revoked && !revokedWithAmbiguousCheckpoint)
                channel.force(true)
            channel.close()
            release(path, leaseChannel, lease)
            leaseReleased = true
            if (unconfirmed) throw SessionLedgerUnconfirmed("LEDGER_UNACKNOWLEDGED_APPEND")
            if (revoked) throw SessionLedgerUnconfirmed("LEDGER_REOPEN_WRITER_POISONED")
            // Do not truncate or repair a durable uncheckpointed tail. The
            // host learns about it now, not on a subsequent admission.
            if (missingCheckpoint)
                throw SessionLedgerUnconfirmed(checkpointDiagnostic)
            closeVerified = true
        } catch (e: Throwable) {
            if (revokedWithAmbiguousCheckpoint &&
                (e as? SessionLedgerUnconfirmed)?.code != "LEDGER_REOPEN_WRITER_POISONED" &&
                e.suppressed.none {
                    (it as? SessionLedgerUnconfirmed)?.code ==
                        "LEDGER_REOPEN_WRITER_POISONED"
                })
                e.addSuppressed(SessionLedgerUnconfirmed("LEDGER_REOPEN_WRITER_POISONED"))
            if (missingCheckpoint &&
                (e as? SessionLedgerUnconfirmed)?.code != checkpointDiagnostic &&
                e.suppressed.none {
                    (it as? SessionLedgerUnconfirmed)?.code == checkpointDiagnostic
                })
                e.addSuppressed(SessionLedgerUnconfirmed(checkpointDiagnostic))
            poisoned = true
            try { channel.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            // A successful release removed our JVM reservation. Never try to
            // release another writer's lock if it acquired the same path since.
            if (!leaseReleased && heldLockPaths.contains(leasePath(path))) {
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
