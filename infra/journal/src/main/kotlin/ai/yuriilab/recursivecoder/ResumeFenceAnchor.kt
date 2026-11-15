package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.UUID

/**
 * Separate durable fence-head anchor next to the closed event ledger, not in
 * the checkpoint directory. It detects a partial rollback of checkpoint files.
 * Publish anchor first: a crash before fence publication requires reconciliation.
 * Both directories rolled back by a trusted same-UID owner remain outside scope.
 */
internal data class ResumeFenceAnchorRecord(
    val generation: Long,
    val fenceSha256: String,
    val previousSha256: String?,
    val sha256: String
)

internal object ResumeFenceAnchor {
    private const val MAX = 256
    private val SHA = Regex("[0-9a-f]{64}")
    private fun fail(code: String): Nothing = throw SessionResumeUnconfirmed(code)
    private fun parent(ledger: Path): Path {
        val absolute = ledger.toAbsolutePath().normalize()
        val directory = absolute.parent ?: fail("RESUME_FENCE_ANCHOR_UNCONFIRMED")
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
            directory.toRealPath() != directory)
            fail("RESUME_FENCE_ANCHOR_UNCONFIRMED")
        return directory
    }

    private fun prefix(ledger: Path) =
        ledger.toAbsolutePath().normalize().fileName.toString() + ".resume-anchor-"
    private fun name(ledger: Path, gen: Long) =
        prefix(ledger) + String.format(Locale.ROOT, "%012d.rca", gen)
    private fun body(gen: Long, fence: String, previous: String?) =
        listOf("RCANCH1", gen.toString(), fence, previous ?: "-").joinToString("\n", postfix = "\n")
    private fun digest(content: String) =
        SessionCheckpointCodec.sha256(content.toByteArray(StandardCharsets.UTF_8))

    private fun read(path: Path): ResumeFenceAnchorRecord {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(path) !in 1L..512L) fail("RESUME_FENCE_ANCHOR_MISMATCH")
        val raw = Files.readAllBytes(path).toString(StandardCharsets.UTF_8)
        val parts = raw.split("\n")
        if (parts.size != 6 || parts[0] != "RCANCH1" || parts.last() != "")
            fail("RESUME_FENCE_ANCHOR_MISMATCH")
        val gen = parts[1].toLongOrNull() ?: fail("RESUME_FENCE_ANCHOR_MISMATCH")
        val previous = parts[3].takeUnless { it == "-" }
        if (gen !in 1L..MAX.toLong() || !SHA.matches(parts[2]) ||
            (gen == 1L) != (previous == null) ||
            (previous != null && !SHA.matches(previous)) ||
            raw != body(gen, parts[2], previous) + parts[4] + "\n" ||
            digest(body(gen, parts[2], previous)) != parts[4])
            fail("RESUME_FENCE_ANCHOR_MISMATCH")
        return ResumeFenceAnchorRecord(gen, parts[2], previous, parts[4])
    }

    fun verify(ledger: Path, head: ResumeFenceRecord?): ResumeFenceAnchorRecord? {
        try {
        val directory = parent(ledger)
        val names = Files.newDirectoryStream(directory).use { stream ->
            stream.filter { it.fileName.toString().startsWith(prefix(ledger)) &&
                it.fileName.toString().endsWith(".rca") }.toList()
        }
        if (names.size > MAX || names.size.toLong() != (head?.generation ?: 0L))
            fail("RESUME_FENCE_ANCHOR_MISMATCH")
        var previous: ResumeFenceAnchorRecord? = null
        for ((index, file) in names.sortedBy { it.fileName.toString() }.withIndex()) {
            val record = read(file)
            if (file.fileName.toString() != name(ledger, index.toLong() + 1L) ||
                record.generation != index.toLong() + 1L ||
                record.previousSha256 != previous?.sha256)
                fail("RESUME_FENCE_ANCHOR_MISMATCH")
            previous = record
        }
        if (previous?.fenceSha256 != head?.sha256)
            fail("RESUME_FENCE_ANCHOR_MISMATCH")
        return previous
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_ANCHOR_UNCONFIRMED", e)
        }
    }

    /** Durable PREPARE before immutable fence publication. */
    fun publish(ledger: Path, fence: ResumeFenceRecord,
                previous: ResumeFenceAnchorRecord?): ResumeFenceAnchorRecord {
        val directory = parent(ledger)
        if (fence.generation !in 1L..MAX.toLong() ||
            fence.generation != (previous?.generation ?: 0L) + 1L)
            fail("RESUME_FENCE_ANCHOR_MISMATCH")
        val header = body(fence.generation, fence.sha256, previous?.sha256)
        val checksum = digest(header)
        val value = ResumeFenceAnchorRecord(fence.generation, fence.sha256,
            previous?.sha256, checksum)
        val bytes = (header + checksum + "\n").toByteArray(StandardCharsets.UTF_8)
        val destination = directory.resolve(name(ledger, fence.generation))
        val temporary = directory.resolve(".resume-anchor-pending-" + UUID.randomUUID() + ".tmp")
        try {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                fail("RESUME_FENCE_CAS_FAILED")
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
            FileChannel.open(temporary, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) fail("RESUME_FENCE_ANCHOR_UNCONFIRMED")
                }
                channel.force(true)
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                fail("RESUME_FENCE_CAS_FAILED")
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            if (read(destination) != value) fail("RESUME_FENCE_ANCHOR_UNCONFIRMED")
            return value
        } catch (e: Exception) {
            if (e is SessionResumeUnconfirmed) throw e
            throw SessionResumeUnconfirmed("RESUME_FENCE_ANCHOR_UNCONFIRMED", e)
        } finally {
            try { Files.deleteIfExists(temporary) } catch (_: Exception) { /* unconfirmed */ }
        }
    }

    /** Read only the admitted anchor head for bounded callback guards. */
    fun requireHead(ledger: Path, generation: Long, fenceSha: String, anchorSha: String) {
        try {
        val directory = parent(ledger)
        val actual = read(directory.resolve(name(ledger, generation)))
        if (actual.generation != generation || actual.fenceSha256 != fenceSha ||
            actual.sha256 != anchorSha ||
            Files.exists(directory.resolve(name(ledger, generation + 1L)),
                LinkOption.NOFOLLOW_LINKS))
            fail("RESUME_FENCE_STALE_WRITER")
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_ANCHOR_UNCONFIRMED", e)
        }
    }
}
