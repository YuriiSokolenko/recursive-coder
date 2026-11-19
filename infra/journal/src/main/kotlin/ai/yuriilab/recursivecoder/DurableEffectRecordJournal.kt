package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Host-only, isolated physical effect-record storage (#105). This is NOT a
 * Session Protocol event journal, a checkpoint, or a HostEffectEvidenceVerifier.
 * Its authenticated records cannot authorize execution, resume or replay.
 */
class EffectJournalStorageUnconfirmed(val code: String, cause: Throwable? = null) :
    IllegalStateException(code, cause)

data class VerifiedEffectRecordJournal(
    val entries: List<EffectJournalEntry>,
    val lastSequence: Long,
    val headMacSha256: String,
    val fileSha256: String,
    val byteLength: Long
)

/** Test-only fault callback; production callers must use append(). */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
internal annotation class EffectJournalFaultInjection

@ResumeOperatorOnlyApi
class DurableEffectRecordJournal private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val lease: SessionResumeLease,
    private val sessionId: String,
    private val key: ByteArray,
    private val fileKey: Any?
) : AutoCloseable {
    private val entries = mutableListOf<EffectJournalEntry>()
    private var previousMac = ByteArray(32)
    private var poisoned = false
    private var closed = false

    companion object {
        private val magic = "RCEFX1\n".toByteArray(StandardCharsets.US_ASCII)
        private const val MAX_RECORD_BYTES = 4096
        private const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private const val MAX_RECORDS = 100_000
        private val digestPattern = Regex("[0-9a-f]{64}")
        private val heldLocks = ConcurrentHashMap.newKeySet<Path>()
        private val privatePermissions = setOf(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        private val filePermissions = setOf(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE)

        private fun refuse(code: String): Nothing =
            throw EffectJournalStorageUnconfirmed(code)

        private fun checkedPath(path: Path, workspaceRoot: Path): Path {
            val file = path.toAbsolutePath().normalize()
            val dir = file.parent ?: refuse("EFFECT_JOURNAL_UNSAFE_PATH")
            try {
                val workspace = workspaceRoot.toRealPath()
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ||
                    dir.toRealPath() != dir || dir.startsWith(workspace) ||
                    workspace.startsWith(dir) ||
                    Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS) !=
                        privatePermissions)
                    refuse("EFFECT_JOURNAL_UNSAFE_PATH")
                LinuxCheckpointOwner.requireMatchingUid(
                    Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS),
                    LinuxCheckpointOwner.effectiveUid())
            } catch (e: Exception) {
                if (e is EffectJournalStorageUnconfirmed) throw e
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_UNSAFE_PATH", e)
            }
            return file
        }

        private fun checkedKey(key: ByteArray): ByteArray {
            if (key.size < 32) refuse("EFFECT_JOURNAL_KEY_INVALID")
            return key.copyOf()
        }

        private fun lockPath(file: Path): Path =
            file.resolveSibling(file.fileName.toString() + ".lock")

        private fun acquire(file: Path, create: Boolean): Pair<FileChannel, FileLock> {
            val lockFile = lockPath(file)
            if (!heldLocks.add(lockFile)) refuse("EFFECT_JOURNAL_BUSY")
            var channel: FileChannel? = null
            try {
                if (Files.isSymbolicLink(lockFile))
                    refuse("EFFECT_JOURNAL_UNSAFE_LOCK")
                channel = if (create)
                    FileChannel.open(lockFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                else
                    FileChannel.open(lockFile, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)
                // Never change the inode or reopen this lock while held.
                if (create) Files.setPosixFilePermissions(lockFile, filePermissions)
                if (Files.getPosixFilePermissions(lockFile, LinkOption.NOFOLLOW_LINKS) !=
                    filePermissions)
                    refuse("EFFECT_JOURNAL_UNSAFE_LOCK")
                val held = try { channel.tryLock() }
                    catch (_: OverlappingFileLockException) { null }
                    ?: refuse("EFFECT_JOURNAL_BUSY")
                return channel to held
            } catch (e: Exception) {
                try { channel?.close() } catch (close: Exception) { e.addSuppressed(close) }
                heldLocks.remove(lockFile)
                if (e is EffectJournalStorageUnconfirmed) throw e
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_LOCK_UNAVAILABLE", e)
            }
        }

        private fun release(file: Path, channel: FileChannel, lock: FileLock) {
            var error: Exception? = null
            try { lock.release() } catch (e: Exception) { error = e }
            try { channel.close() } catch (e: Exception) {
                if (error == null) error = e else error.addSuppressed(e)
            }
            // A failed unlock retains the in-process reservation.
            if (error != null)
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_UNLOCK_UNCONFIRMED", error)
            heldLocks.remove(lockPath(file))
        }

        private fun digest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        private fun tag(key: ByteArray, path: Path, previous: ByteArray,
                        payload: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.update(magic)
            mac.update(path.toString().toByteArray(StandardCharsets.UTF_8))
            mac.update(0.toByte())
            mac.update(previous)
            return mac.doFinal(payload)
        }

        private fun validIdentity(id: EffectIdentity): Boolean =
            ToolAuthorizationPolicy.validHostIdentifier(id.sessionId) &&
                ToolAuthorizationPolicy.validHostIdentifier(id.operationId) &&
                id.fenceGeneration > 0 &&
                digestPattern.matches(id.inputSha256)

        private fun checkedTransition(
            existing: List<EffectJournalEntry>, record: EffectRecord, sessionId: String
        ) {
            val identity = record.identity
            if (!validIdentity(identity) || identity.sessionId != sessionId)
                refuse("EFFECT_JOURNAL_INVALID_RECORD")
            if (record is EffectRecord.Committed &&
                !digestPattern.matches(record.observedStateSha256))
                refuse("EFFECT_JOURNAL_INVALID_RECORD")
            if (record is EffectRecord.Unconfirmed &&
                !ToolAuthorizationPolicy.validHostIdentifier(record.code))
                refuse("EFFECT_JOURNAL_INVALID_RECORD")
            val history = existing.asSequence()
                .map { it.record }
                .filter { it.identity.operationId == identity.operationId }.toList()
            if (history.any { it.identity != identity })
                refuse("EFFECT_JOURNAL_REUSED_OPERATION")
            val last = history.lastOrNull()
            val permitted = when (last) {
                null -> record is EffectRecord.Intent
                is EffectRecord.Intent -> record is EffectRecord.Started ||
                    record is EffectRecord.Unconfirmed
                is EffectRecord.Started -> record is EffectRecord.Committed ||
                    record is EffectRecord.Unconfirmed
                is EffectRecord.Committed, is EffectRecord.Unconfirmed -> false
            }
            if (!permitted) refuse("EFFECT_JOURNAL_INVALID_ORDER")
        }

        private fun payload(sequence: Long, record: EffectRecord): ByteArray {
            val id = record.identity
            val obj = buildJsonObject {
                put("sequence", sequence)
                put("sessionId", id.sessionId)
                put("operationId", id.operationId)
                put("fenceGeneration", id.fenceGeneration)
                put("kind", id.kind.name)
                put("inputSha256", id.inputSha256)
                when (record) {
                    is EffectRecord.Intent -> put("state", "intent")
                    is EffectRecord.Started -> put("state", "started")
                    is EffectRecord.Committed -> {
                        put("state", "committed")
                        put("observedStateSha256", record.observedStateSha256)
                    }
                    is EffectRecord.Unconfirmed -> {
                        put("state", "unconfirmed")
                        put("code", record.code)
                    }
                }
            }
            return obj.toString().toByteArray(StandardCharsets.UTF_8)
        }

        private fun decode(bytes: ByteArray, sequence: Long): EffectRecord {
            try {
                val obj = Json.parseToJsonElement(
                    bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
                    ?: refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                fun string(name: String): String {
                    val value = obj[name] as? JsonPrimitive
                        ?: refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                    if (!value.isString) refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                    return value.content
                }
                val seq = (obj["sequence"] as? JsonPrimitive)?.longOrNull
                if (seq != sequence) refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                val generation = (obj["fenceGeneration"] as? JsonPrimitive)?.longOrNull
                    ?: refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                val kind = EffectKind.entries.firstOrNull { it.name == string("kind") }
                    ?: refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                val id = EffectIdentity(string("sessionId"), string("operationId"),
                    generation, kind, string("inputSha256"))
                val record = when (string("state")) {
                    "intent" -> EffectRecord.Intent(id)
                    "started" -> EffectRecord.Started(id)
                    "committed" -> EffectRecord.Committed(id, string("observedStateSha256"))
                    "unconfirmed" -> EffectRecord.Unconfirmed(id, string("code"))
                    else -> refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                }
                if (!bytes.contentEquals(payload(sequence, record)))
                    refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                return record
            } catch (e: Exception) {
                if (e is EffectJournalStorageUnconfirmed) throw e
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_RECORD_CORRUPT", e)
            }
        }

        private fun writeFully(channel: FileChannel, bytes: ByteArray) {
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) {
                if (channel.write(buf) <= 0)
                    refuse("EFFECT_JOURNAL_APPEND_UNCONFIRMED")
            }
        }

        /** Never reopens for append: a crashed fsync may have been acknowledged. */
        fun create(
            path: Path, workspaceRoot: Path, sessionId: String,
            hostKey: ByteArray, lease: SessionResumeLease
        ): DurableEffectRecordJournal {
            val file = checkedPath(path, workspaceRoot)
            if (!ToolAuthorizationPolicy.validHostIdentifier(sessionId))
                refuse("EFFECT_JOURNAL_INVALID_RECORD")
            val copied = checkedKey(hostKey)
            try {
                return lease.publishIfCurrent {
                    val (lockChannel, lock) = acquire(file, create = true)
                    var channel: FileChannel? = null
                    try {
                        channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.READ, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)
                        Files.setPosixFilePermissions(file, filePermissions)
                        writeFully(channel, magic)
                        channel.force(true)
                        FileChannel.open(file.parent, StandardOpenOption.READ).use { it.force(true) }
                        val inode = Files.readAttributes(file, BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS).fileKey()
                        DurableEffectRecordJournal(file, channel, lockChannel, lock,
                            lease, sessionId, copied, inode)
                    } catch (e: Exception) {
                        try { channel?.close() } catch (c: Exception) { e.addSuppressed(c) }
                        try { release(file, lockChannel, lock) }
                        catch (c: Exception) { e.addSuppressed(c) }
                        throw e
                    }
                }
            } catch (e: Exception) {
                copied.fill(0)
                if (e is java.nio.file.FileAlreadyExistsException)
                    throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_EXISTS", e)
                throw e
            }
        }

        /** Read-only, full-prefix verification; never creates or truncates files. */
        fun inspect(path: Path, workspaceRoot: Path, hostKey: ByteArray):
            VerifiedEffectRecordJournal {
            val file = checkedPath(path, workspaceRoot)
            val copied = checkedKey(hostKey)
            try {
                val (owner, lock) = acquire(file, create = false)
                try {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                        Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) !=
                            filePermissions)
                        refuse("EFFECT_JOURNAL_UNSAFE_PATH")
                    FileChannel.open(file, StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS).use { channel ->
                        val size = channel.size()
                        if (size < magic.size || size > MAX_FILE_BYTES ||
                            size > Int.MAX_VALUE)
                            refuse("EFFECT_JOURNAL_SIZE_INVALID")
                        val all = ByteBuffer.allocate(size.toInt())
                        while (all.hasRemaining()) {
                            if (channel.read(all) <= 0)
                                refuse("EFFECT_JOURNAL_TRUNCATED")
                        }
                        val bytes = all.array()
                        if (!bytes.copyOfRange(0, magic.size).contentEquals(magic))
                            refuse("EFFECT_JOURNAL_VERSION_INVALID")
                        var offset = magic.size
                        var previous = ByteArray(32)
                        val entries = mutableListOf<EffectJournalEntry>()
                        var session: String? = null
                        while (offset < bytes.size) {
                            if (entries.size >= MAX_RECORDS ||
                                bytes.size - offset < 36)
                                refuse("EFFECT_JOURNAL_TRUNCATED")
                            val length = ByteBuffer.wrap(bytes, offset, 4).int
                            if (length !in 1..MAX_RECORD_BYTES ||
                                length > bytes.size - offset - 36)
                                refuse("EFFECT_JOURNAL_TRUNCATED")
                            val mac = bytes.copyOfRange(offset + 4, offset + 36)
                            val body = bytes.copyOfRange(offset + 36, offset + 36 + length)
                            val next = tag(copied, file, previous, body)
                            if (!MessageDigest.isEqual(mac, next))
                                refuse("EFFECT_JOURNAL_AUTH_FAILED")
                            val record = decode(body, entries.size.toLong() + 1L)
                            if (session == null) session = record.identity.sessionId
                            checkedTransition(entries, record, session)
                            entries.add(EffectJournalEntry(entries.size.toLong() + 1L,
                                record, mac.toHex()))
                            previous = next
                            offset += 36 + length
                        }
                        return VerifiedEffectRecordJournal(entries, entries.size.toLong(),
                            previous.toHex(), digest(bytes), bytes.size.toLong())
                    }
                } finally {
                    release(file, owner, lock)
                }
            } finally {
                copied.fill(0)
            }
        }
    }

    /**
     * Before first physical effect, the host MUST fsync intent and started in
     * this order. This append does not itself grant that effect's execution.
     */
    @OptIn(EffectJournalFaultInjection::class)
    fun append(record: EffectRecord): EffectJournalEntry =
        appendWithFault(record) { }

    @EffectJournalFaultInjection
    internal fun appendWithFault(record: EffectRecord, afterPhase: (String) -> Unit):
        EffectJournalEntry = lease.publishIfCurrent {
        synchronized(this) {
            if (closed || poisoned)
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_WRITER_UNAVAILABLE")
            if (record.identity.fenceGeneration != lease.generation)
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_STALE_GENERATION")
            checkedTransition(entries, record, sessionId)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                Files.readAttributes(path, BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS).fileKey() != fileKey ||
                channel.size() != channel.position())
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_PATH_CHANGED")
            val seq = entries.size.toLong() + 1L
            if (entries.size >= MAX_RECORDS)
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_SIZE_INVALID")
            val body = payload(seq, record)
            if (body.size !in 1..MAX_RECORD_BYTES ||
                channel.position() + body.size + 36 > MAX_FILE_BYTES)
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_SIZE_INVALID")
            val mac = tag(key, path, previousMac, body)
            val frame = ByteBuffer.allocate(36 + body.size).putInt(body.size)
                .put(mac).put(body).array()
            try {
                writeFully(channel, frame)
                afterPhase("record-written")
                channel.force(true)
                afterPhase("forced-before-ack")
            } catch (error: Throwable) {
                poisoned = true
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_APPEND_UNCONFIRMED", error)
            }
            previousMac = mac
            return@synchronized EffectJournalEntry(seq, record, mac.toHex())
                .also { entries.add(it) }
        }
    }

    /**
     * Close before retiring resume lease. No truncation/repair after an
     * uncertain write; an intact tail alone is not external effect evidence.
     */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
        var error: Exception? = null
        try { channel.close() } catch (e: Exception) { error = e }
        try { release(path, lockChannel, lock) } catch (e: Exception) {
            if (error == null) error = e else error.addSuppressed(e)
        }
        if (error != null)
            throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_CLOSE_UNCONFIRMED", error)
        if (poisoned)
            throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_APPEND_UNCONFIRMED")
    }
}
