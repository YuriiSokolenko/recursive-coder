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
import java.nio.file.attribute.PosixFilePermissions
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

/** Authenticated, immutable genesis identity; NOT independent checkpoint authority. */
data class EffectJournalHeader(
    val sessionId: String,
    val fenceGeneration: Long,
    val admittedCheckpointSha256: String,
    val toolAuthoritySha256: String
)

data class VerifiedEffectRecordJournal(
    val header: EffectJournalHeader,
    val entries: List<EffectJournalEntry>,
    val lastSequence: Long,
    val headMacSha256: String,
    val fileSha256: String,
    val byteLength: Long,
    /** Verified prefix end; less than byteLength only for diagnostic torn tails. */
    val verifiedByteLength: Long,
    /** Non-null ONLY for inspection diagnostics; never execution/recovery authority. */
    val tornTailOffset: Long? = null
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
    private val header: EffectJournalHeader,
    private val key: ByteArray,
    private val fileKey: Any?,
    genesisMac: ByteArray
) : AutoCloseable {
    private val entries = mutableListOf<EffectJournalEntry>()
    private val latestByOperation = mutableMapOf<String, EffectRecord>()
    private var previousMac = genesisMac.copyOf()
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

        /** HMAC-based, protocol-specific subkey: never share raw MAC keys. */
        private fun checkedKey(hostKey: ByteArray): ByteArray {
            if (hostKey.size < 32) refuse("EFFECT_JOURNAL_KEY_INVALID")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(hostKey, "HmacSHA256"))
            return mac.doFinal("recursive-coder/effect-record-journal/v1"
                .toByteArray(StandardCharsets.US_ASCII))
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
                    FileChannel.open(lockFile, setOf<java.nio.file.OpenOption>(
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(filePermissions))
                else
                    FileChannel.open(lockFile, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)
                // Never chmod existing lock inodes: verify their original mode.
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

        /** O(1) amortized transition check, also used for authenticated replay. */
        private fun checkedTransition(
            latest: Map<String, EffectRecord>, record: EffectRecord,
            header: EffectJournalHeader
        ) {
            val identity = record.identity
            if (!validIdentity(identity) || identity.sessionId != header.sessionId ||
                identity.fenceGeneration != header.fenceGeneration)
                refuse("EFFECT_JOURNAL_INVALID_RECORD")
            if (record is EffectRecord.Committed &&
                !digestPattern.matches(record.observedStateSha256))
                refuse("EFFECT_JOURNAL_INVALID_RECORD")
            if (record is EffectRecord.Unconfirmed &&
                !ToolAuthorizationPolicy.validHostIdentifier(record.code))
                refuse("EFFECT_JOURNAL_INVALID_RECORD")
            val last = latest[identity.operationId]
            if (last != null && last.identity != identity)
                refuse("EFFECT_JOURNAL_REUSED_OPERATION")
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

        private fun headerPayload(header: EffectJournalHeader): ByteArray =
            buildJsonObject {
                put("type", "genesis")
                put("sessionId", header.sessionId)
                put("fenceGeneration", header.fenceGeneration)
                put("admittedCheckpointSha256", header.admittedCheckpointSha256)
                put("toolAuthoritySha256", header.toolAuthoritySha256)
            }.toString().toByteArray(StandardCharsets.UTF_8)

        private fun decodeHeader(bytes: ByteArray): EffectJournalHeader {
            try {
                val obj = Json.parseToJsonElement(
                    bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
                    ?: refuse("EFFECT_JOURNAL_GENESIS_INVALID")
                fun string(key: String): String {
                    val value = obj[key] as? JsonPrimitive
                        ?: refuse("EFFECT_JOURNAL_GENESIS_INVALID")
                    if (!value.isString) refuse("EFFECT_JOURNAL_GENESIS_INVALID")
                    return value.content
                }
                if (string("type") != "genesis")
                    refuse("EFFECT_JOURNAL_GENESIS_INVALID")
                val generation = (obj["fenceGeneration"] as? JsonPrimitive)
                    ?.takeUnless { it.isString }?.longOrNull
                    ?: refuse("EFFECT_JOURNAL_GENESIS_INVALID")
                val header = EffectJournalHeader(string("sessionId"), generation,
                    string("admittedCheckpointSha256"), string("toolAuthoritySha256"))
                if (!ToolAuthorizationPolicy.validHostIdentifier(header.sessionId) ||
                    header.fenceGeneration <= 0L ||
                    !digestPattern.matches(header.admittedCheckpointSha256) ||
                    !digestPattern.matches(header.toolAuthoritySha256) ||
                    !bytes.contentEquals(headerPayload(header)))
                    refuse("EFFECT_JOURNAL_GENESIS_INVALID")
                return header
            } catch (e: Exception) {
                if (e is EffectJournalStorageUnconfirmed) throw e
                throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_GENESIS_INVALID", e)
            }
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
                refuse("EFFECT_JOURNAL_CONTEXT_MISMATCH")
            val derived = checkedKey(hostKey)
            try {
                return lease.publishIfCurrent {
                    val admittedSession = lease.admittedSessionId()
                    if (sessionId != admittedSession)
                        refuse("EFFECT_JOURNAL_CONTEXT_MISMATCH")
                    val header = EffectJournalHeader(
                        admittedSession, lease.generation,
                        lease.admittedCheckpointSha256, lease.toolAuthoritySha256)
                    val (lockChannel, lock) = acquire(file, create = true)
                    var channel: FileChannel? = null
                    try {
                        channel = FileChannel.open(file, setOf<java.nio.file.OpenOption>(
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                            PosixFilePermissions.asFileAttribute(filePermissions))
                        // POSIX creation attributes are masked by the process umask.
                        // Refuse before genesis publication or any acknowledged append
                        // if the actual mode is not the required owner-only 0600.
                        if (Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) !=
                            filePermissions)
                            refuse("EFFECT_JOURNAL_UNSAFE_PATH")
                        val genesisPayload = headerPayload(header)
                        val genesisMac = tag(derived, file, ByteArray(32), genesisPayload)
                        val genesisFrame = ByteBuffer.allocate(36 + genesisPayload.size)
                            .putInt(genesisPayload.size).put(genesisMac)
                            .put(genesisPayload).array()
                        writeFully(channel, magic)
                        writeFully(channel, genesisFrame)
                        channel.force(true)
                        FileChannel.open(file.parent, StandardOpenOption.READ).use { it.force(true) }
                        val inode = Files.readAttributes(file, BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS).fileKey()
                        DurableEffectRecordJournal(file, channel, lockChannel, lock,
                            lease, header, derived, inode, genesisMac)
                    } catch (e: Exception) {
                        try { channel?.close() } catch (c: Exception) { e.addSuppressed(c) }
                        try { release(file, lockChannel, lock) }
                        catch (c: Exception) { e.addSuppressed(c) }
                        throw e
                    }
                }
            } catch (e: Exception) {
                derived.fill(0)
                if (e is java.nio.file.FileAlreadyExistsException)
                    throw EffectJournalStorageUnconfirmed("EFFECT_JOURNAL_EXISTS", e)
                throw e
            }
        }

        /** Strict read-only replay: refuses even a torn final frame. */
        fun inspect(
            path: Path, workspaceRoot: Path, hostKey: ByteArray,
            expectedSessionId: String, expectedFenceGeneration: Long? = null,
            expectedCheckpointSha256: String? = null,
            expectedToolAuthoritySha256: String? = null
        ): VerifiedEffectRecordJournal = inspectInternal(path, workspaceRoot, hostKey,
            expectedSessionId, expectedFenceGeneration, expectedCheckpointSha256,
            expectedToolAuthoritySha256, allowTornTail = false)

        /**
         * Read-only operator diagnostics ONLY. A verified prefix with a torn
         * final frame is NOT a recovery classification or a writable ledger.
         * MAC, noncanonical, genesis and identity failures still refuse.
         */
        fun inspectForDiagnostics(
            path: Path, workspaceRoot: Path, hostKey: ByteArray,
            expectedSessionId: String, expectedFenceGeneration: Long? = null,
            expectedCheckpointSha256: String? = null,
            expectedToolAuthoritySha256: String? = null
        ): VerifiedEffectRecordJournal = inspectInternal(path, workspaceRoot, hostKey,
            expectedSessionId, expectedFenceGeneration, expectedCheckpointSha256,
            expectedToolAuthoritySha256, allowTornTail = true)

        private fun inspectInternal(
            path: Path, workspaceRoot: Path, hostKey: ByteArray,
            expectedSessionId: String, expectedFenceGeneration: Long?,
            expectedCheckpointSha256: String?, expectedToolAuthoritySha256: String?,
            allowTornTail: Boolean
        ): VerifiedEffectRecordJournal {
            val file = checkedPath(path, workspaceRoot)
            val derived = checkedKey(hostKey)
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
                        if (bytes.size < magic.size + 36)
                            refuse("EFFECT_JOURNAL_GENESIS_INVALID")

                        // Genesis is an authenticated mandatory first frame;
                        // an empty effect stream still has a provable identity.
                        var offset = magic.size
                        val genesisLength = ByteBuffer.wrap(bytes, offset, 4).int
                        if (genesisLength !in 1..MAX_RECORD_BYTES ||
                            genesisLength > bytes.size - offset - 36)
                            refuse("EFFECT_JOURNAL_GENESIS_INVALID")
                        val genesisMac = bytes.copyOfRange(offset + 4, offset + 36)
                        val genesisBody = bytes.copyOfRange(offset + 36,
                            offset + 36 + genesisLength)
                        if (!MessageDigest.isEqual(genesisMac,
                                tag(derived, file, ByteArray(32), genesisBody)))
                            refuse("EFFECT_JOURNAL_AUTH_FAILED")
                        val header = decodeHeader(genesisBody)
                        if (header.sessionId != expectedSessionId ||
                            expectedFenceGeneration != null &&
                                header.fenceGeneration != expectedFenceGeneration ||
                            expectedCheckpointSha256 != null &&
                                header.admittedCheckpointSha256 != expectedCheckpointSha256 ||
                            expectedToolAuthoritySha256 != null &&
                                header.toolAuthoritySha256 != expectedToolAuthoritySha256)
                            refuse("EFFECT_JOURNAL_CONTEXT_MISMATCH")
                        offset += 36 + genesisLength

                        var previous = genesisMac
                        val entries = mutableListOf<EffectJournalEntry>()
                        val latest = mutableMapOf<String, EffectRecord>()
                        var tornTailOffset: Long? = null
                        while (offset < bytes.size) {
                            if (entries.size >= MAX_RECORDS)
                                refuse("EFFECT_JOURNAL_SIZE_INVALID")
                            val remaining = bytes.size - offset
                            if (remaining < 36) {
                                tornTailOffset = offset.toLong()
                                break
                            }
                            val length = ByteBuffer.wrap(bytes, offset, 4).int
                            if (length !in 1..MAX_RECORD_BYTES)
                                refuse("EFFECT_JOURNAL_RECORD_CORRUPT")
                            if (length > remaining - 36) {
                                tornTailOffset = offset.toLong()
                                break
                            }
                            val mac = bytes.copyOfRange(offset + 4, offset + 36)
                            val body = bytes.copyOfRange(offset + 36, offset + 36 + length)
                            val next = tag(derived, file, previous, body)
                            if (!MessageDigest.isEqual(mac, next))
                                refuse("EFFECT_JOURNAL_AUTH_FAILED")
                            val record = decode(body, entries.size.toLong() + 1L)
                            checkedTransition(latest, record, header)
                            entries.add(EffectJournalEntry(entries.size.toLong() + 1L,
                                record, mac.toHex()))
                            latest[record.identity.operationId] = record
                            previous = next
                            offset += 36 + length
                        }
                        if (tornTailOffset != null && !allowTornTail)
                            refuse("EFFECT_JOURNAL_TRUNCATED")
                        return VerifiedEffectRecordJournal(header, entries,
                            entries.size.toLong(), previous.toHex(), digest(bytes),
                            bytes.size.toLong(), offset.toLong(), tornTailOffset)
                    }
                } finally {
                    release(file, owner, lock)
                }
            } finally {
                derived.fill(0)
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
            checkedTransition(latestByOperation, record, header)
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
                .also {
                    entries.add(it)
                    latestByOperation[record.identity.operationId] = record
                }
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
