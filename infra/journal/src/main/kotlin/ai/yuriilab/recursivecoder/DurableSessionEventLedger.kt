package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A verified on-disk prefix, not permission to replay any model/tool operation.
 * Callers must reconcile side effects and checkpoint authority separately (#103-#105).
 */
data class VerifiedSessionEventLedger(
    val events: List<JsonObject>,
    val lastSequence: Int,
    val headSha256: String,
    val fileSha256: String
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
    private var closed = false
    private var closeVerified = false
    private var poisoned = false
    private var committedSequence = 0
    private var lastEventId: String? = null
    private var lastDigest = ByteArray(32)
    private var pending: Pair<String, ByteArray>? = null

    companion object {
        private val MAGIC = "RCLDG1\n".toByteArray(StandardCharsets.US_ASCII)
        private const val MAX_EVENT_BYTES = 16 * 1024
        private const val MAX_LEDGER_BYTES = 32L * 1024 * 1024
        private const val MAX_EVENTS = 100_000
        private val ROOT_FIELDS = setOf("kind", "version", "sessionId", "traceId",
            "eventId", "sequence", "timestamp", "type", "status", "correlationId",
            "causationId", "actor", "transition", "timing", "redaction", "metadata")

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

        private fun acquire(path: Path, create: Boolean): Pair<FileChannel, FileLock> {
            val target = leasePath(path)
            if (Files.isSymbolicLink(target))
                throw SessionLedgerUnconfirmed("UNSAFE_LEDGER_LOCK")
            val options = if (create)
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            else arrayOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val owner = try {
                FileChannel.open(target, *options)
            } catch (e: Exception) {
                throw SessionLedgerUnconfirmed("LEDGER_LEASE_UNAVAILABLE", e)
            }
            try {
                val lock = try {
                    owner.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                return owner to (lock ?: throw SessionLedgerUnconfirmed("LEDGER_LEASE_HELD"))
            } catch (e: Exception) {
                owner.close()
                throw e
            }
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
            val (owner, lock) = acquire(file, create = true)
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
                channel?.close()
                lock.release()
                owner.close()
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

        private fun metadataSafe(event: JsonObject) {
            if (!event.keys.all { it in ROOT_FIELDS })
                throw SessionLedgerUnconfirmed("LEDGER_UNSUPPORTED_FIELDS")
            val redaction = event["redaction"] as? JsonObject
            if ((redaction?.get("mode") as? JsonPrimitive)?.content != "metadata-only")
                throw SessionLedgerUnconfirmed("LEDGER_REDACTION_REQUIRED")
            val metadata = event["metadata"] ?: return
            val obj = metadata as? JsonObject
                ?: throw SessionLedgerUnconfirmed("LEDGER_UNSUPPORTED_METADATA")
            for ((key, value) in obj) {
                val normalized = key.lowercase().replace("-", "").replace("_", "")
                if (!Regex("[A-Za-z][A-Za-z0-9_-]{0,63}").matches(key) ||
                    listOf("prompt", "raw", "secret", "password", "apikey",
                        "credential", "authorization", "privatekey", "sourcecode",
                        "tooloutput", "requestbody", "responsebody", "filecontents",
                        "inputtext", "outputtext").any { it in normalized }) {
                    throw SessionLedgerUnconfirmed("LEDGER_SENSITIVE_METADATA")
                }
                if (value !is JsonPrimitive ||
                    (value is JsonPrimitive && value.content.length > 512))
                    throw SessionLedgerUnconfirmed("LEDGER_UNSUPPORTED_METADATA")
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
            if (id != "evt-" + session + "-" + expectedSequence ||
                (previousId == null && event["causationId"] != JsonNull) ||
                (previousId != null && (event["causationId"] as? JsonPrimitive)?.content != previousId))
                throw SessionLedgerUnconfirmed("LEDGER_EVENT_CHAIN_INVALID")
            if (event["actor"] !is JsonObject ||
                event["traceId"] !is JsonPrimitive ||
                event["timestamp"] !is JsonPrimitive ||
                event["type"] !is JsonPrimitive || event["status"] !is JsonPrimitive)
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

        /** Immutable, fully checked read. Never interprets a prefix as safe resume. */
        fun inspect(path: Path): VerifiedSessionEventLedger {
            val file = guardedPath(path)
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw SessionLedgerUnconfirmed("INVALID_LEDGER_PATH")
            val (owner, lock) = acquire(file, create = false)
            try {
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
                        previousId = (event["eventId"] as JsonPrimitive).content
                        previousHash = digest
                        events.add(event)
                    }
                    return VerifiedSessionEventLedger(events, sequence,
                        hex(previousHash), hex(whole.digest()))
                }
            } finally {
                lock.release()
                owner.close()
            }
        }
    }

    @Synchronized override fun append(event: JsonObject) {
        check(!closed && !poisoned && pending == null) { "Ledger not ready for append" }
        val next = committedSequence + 1
        validate(event, next, lastEventId)
        val payload = event.toString().toByteArray(StandardCharsets.UTF_8)
        if (payload.size !in 1..MAX_EVENT_BYTES ||
            channel.position() + 4L + 32 + payload.size > MAX_LEDGER_BYTES)
            throw SessionLedgerUnconfirmed("LEDGER_RECORD_SIZE_INVALID")
        val hash = chain(lastDigest, payload)
        val frame = ByteBuffer.allocate(4 + 32 + payload.size)
            .putInt(payload.size).put(hash).put(payload).array()
        try {
            writeFully(channel, frame)
            pending = (event["eventId"] as JsonPrimitive).content to hash
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
            lease.release()
            leaseChannel.close()
            if (unconfirmed) throw SessionLedgerUnconfirmed("LEDGER_UNACKNOWLEDGED_APPEND")
            closeVerified = true
        } catch (e: Exception) {
            poisoned = true
            try { channel.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            try { lease.release() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            try { leaseChannel.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
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
