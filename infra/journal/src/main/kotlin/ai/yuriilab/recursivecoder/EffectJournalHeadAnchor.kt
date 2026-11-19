package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.UUID

/**
 * Host-only immutable per-generation effect heads in the existing trusted
 * checkpoint directory, independent from the sidecar's inode and HMAC chain.
 *
 * A pin supplied from these files alone does NOT establish non-rollback:
 * callers must obtain the exact expected SHA from an independently trusted,
 * durable checkpoint/authority root. No effect is safe to replay from this.
 */
data class EffectJournalHeadPin(
    val lastSequence: Long,
    val headMacSha256: String,
    val anchorSha256: String
)

internal object EffectJournalHeadAnchor {
    private val sha = Regex("[0-9a-f]{64}")
    private val filePermissions = setOf(PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE)
    private val namePattern = Regex("effect-head-[0-9]{12}-[0-9]{12}\\.rca")
    private const val MAX_HEADS = 100_001
    private fun fail(code: String): Nothing =
        throw EffectJournalStorageUnconfirmed(code)

    private fun pathHash(path: Path) = SessionCheckpointCodec.sha256(
        path.toAbsolutePath().normalize().toString().toByteArray(StandardCharsets.UTF_8))

    private fun filename(generation: Long, sequence: Long) = String.format(
        Locale.ROOT, "effect-head-%012d-%012d.rca", generation, sequence)

    private fun body(header: EffectJournalHeader, path: Path, sequence: Long,
                     mac: String, previous: String?) =
        listOf("RCEAH1", header.sessionId, header.fenceGeneration.toString(),
            header.admittedCheckpointSha256, header.toolAuthoritySha256, pathHash(path),
            sequence.toString(), mac, previous ?: "-").joinToString("\n", postfix = "\n")

    private fun signature(body: String): String =
        SessionCheckpointCodec.sha256(body.toByteArray(StandardCharsets.UTF_8))

    private fun decode(file: Path, header: EffectJournalHeader, path: Path,
                       sequence: Long, previous: String?): EffectJournalHeadPin {
        val raw = try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS) !=
                    filePermissions || Files.size(file) !in 1L..1024L)
                fail("EFFECT_ANCHOR_CORRUPT")
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (error: EffectJournalStorageUnconfirmed) {
            throw error
        } catch (error: Exception) {
            throw EffectJournalStorageUnconfirmed("EFFECT_ANCHOR_UNAVAILABLE", error)
        }
        val fields = raw.split("\n")
        if (fields.size != 11 || fields.last() != "" ||
            fields[0] != "RCEAH1" || fields[1] != header.sessionId ||
            fields[2] != header.fenceGeneration.toString() ||
            fields[3] != header.admittedCheckpointSha256 ||
            fields[4] != header.toolAuthoritySha256 ||
            fields[5] != pathHash(path) || fields[6] != sequence.toString() ||
            !sha.matches(fields[7]) || fields[8] != (previous ?: "-") ||
            !sha.matches(fields[9]))
            fail("EFFECT_ANCHOR_CORRUPT")
        val canonical = body(header, path, sequence, fields[7], previous)
        if (raw != canonical + fields[9] + "\n" ||
            signature(canonical) != fields[9])
            fail("EFFECT_ANCHOR_CORRUPT")
        return EffectJournalHeadPin(sequence, fields[7], fields[9])
    }

    fun publish(checkpointDir: Path, header: EffectJournalHeader, sidecar: Path,
                sequence: Long, headMac: String, previous: String?): EffectJournalHeadPin {
        if (sequence < 0L || sequence >= MAX_HEADS.toLong() ||
            !sha.matches(headMac) ||
            (sequence == 0L) != (previous == null) ||
            previous != null && !sha.matches(previous))
            fail("EFFECT_ANCHOR_INVALID")
        // Checkpoint-directory ownership and 0700 mode were checked when the
        // host acquired the live lease. Recheck the directory before publishing.
        try {
            if (!Files.isDirectory(checkpointDir, LinkOption.NOFOLLOW_LINKS) ||
                checkpointDir.toRealPath() != checkpointDir ||
                Files.getPosixFilePermissions(checkpointDir, LinkOption.NOFOLLOW_LINKS) !=
                    setOf(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE) ||
                (Files.getAttribute(checkpointDir, "unix:uid",
                    LinkOption.NOFOLLOW_LINKS) as Number).toLong() !=
                    LinuxCheckpointOwner.effectiveUid())
                fail("EFFECT_ANCHOR_UNSAFE_DIRECTORY")
        } catch (error: EffectJournalStorageUnconfirmed) {
            throw error
        } catch (error: Exception) {
            throw EffectJournalStorageUnconfirmed(
                "EFFECT_ANCHOR_UNSAFE_DIRECTORY", error)
        }
        if (sequence > 0L) {
            val last = decode(checkpointDir.resolve(
                filename(header.fenceGeneration, sequence - 1L)),
                header, sidecar, sequence - 1L,
                // Decode the previous anchor's predecessor only after a
                // complete chain check; the verifier checks all records.
                if (sequence == 1L) null else predecessor(checkpointDir,
                    header.fenceGeneration, sequence - 1L))
            if (last.anchorSha256 != previous)
                fail("EFFECT_ANCHOR_STALE_HEAD")
        }
        val text = body(header, sidecar, sequence, headMac, previous)
        val digest = signature(text)
        val destination = checkpointDir.resolve(filename(header.fenceGeneration, sequence))
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            fail("EFFECT_ANCHOR_EXISTS")
        val temporary = checkpointDir.resolve(".effect-anchor-" + UUID.randomUUID() + ".tmp")
        try {
            FileChannel.open(temporary, setOf<java.nio.file.OpenOption>(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(filePermissions)).use { channel ->
                val content = (text + digest + "\n").toByteArray(StandardCharsets.UTF_8)
                val buffer = ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) fail("EFFECT_ANCHOR_PUBLISH_UNCONFIRMED")
                }
                channel.force(true)
            }
            try {
                ResumeImmutablePublication.linkAndSync(checkpointDir, temporary, destination,
                    "EFFECT_ANCHOR_EXISTS", "EFFECT_ANCHOR_PUBLISH_UNCONFIRMED")
            } catch (error: SessionResumeUnconfirmed) {
                throw EffectJournalStorageUnconfirmed("EFFECT_ANCHOR_PUBLISH_UNCONFIRMED", error)
            }
            return decode(destination, header, sidecar, sequence, previous)
        } catch (error: Exception) {
            if (error is EffectJournalStorageUnconfirmed) throw error
            throw EffectJournalStorageUnconfirmed("EFFECT_ANCHOR_PUBLISH_UNCONFIRMED", error)
        } finally {
            try { Files.deleteIfExists(temporary) } catch (_: Exception) { /* fail closed */ }
        }
    }

    /** Read predecessor hash from an exact immutable anchor under host lock. */
    private fun predecessor(directory: Path, generation: Long, sequence: Long): String {
        val file = directory.resolve(filename(generation, sequence))
        val raw = Files.readString(file, StandardCharsets.UTF_8).split("\n")
        if (raw.size != 11 || raw[8].length != 64 || !sha.matches(raw[8]))
            fail("EFFECT_ANCHOR_CORRUPT")
        return raw[8]
    }

    /**
     * Replays all independently persisted per-effect heads. Requires an
     * external exact SHA pin: deriving it from this directory is unsafe.
     */
    fun verify(checkpointDir: Path, header: EffectJournalHeader, sidecar: Path,
               expected: EffectJournalHeadPin) {
        if (expected.lastSequence !in 0L until MAX_HEADS ||
            !sha.matches(expected.headMacSha256) ||
            !sha.matches(expected.anchorSha256))
            fail("EFFECT_ANCHOR_PIN_INVALID")
        // Reject extra generations, missing records, torn names or surprising
        // heads in this generation. The pinned final SHA detects rollback
        // if the trusted host retained its independent expected value.
        val prefix = "effect-head-" + String.format(Locale.ROOT, "%012d", header.fenceGeneration) + "-"
        Files.newDirectoryStream(checkpointDir).use { files ->
            val actual = files.filter { it.fileName.toString().startsWith(prefix) }.toList()
            if (actual.size.toLong() != expected.lastSequence + 1L ||
                actual.any { !namePattern.matches(it.fileName.toString()) })
                fail("EFFECT_ANCHOR_CHAIN_MISMATCH")
        }
        var previous: String? = null
        var last: EffectJournalHeadPin? = null
        for (n in 0L..expected.lastSequence) {
            last = decode(checkpointDir.resolve(filename(header.fenceGeneration, n)),
                header, sidecar, n, previous)
            previous = last.anchorSha256
        }
        if (last != expected)
            fail("EFFECT_ANCHOR_PIN_MISMATCH")
    }
}
