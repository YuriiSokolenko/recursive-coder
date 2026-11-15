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
import java.time.Instant
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

internal data class ResumeReconciliationAudit(
    val generation: Long,
    val fenceSha256: String,
    val anchorSha256: String,
    val checkpointSha256: String,
    val policySha256: String,
    val operatorId: String,
    val operatorApproved: Boolean,
    val recordedAt: String,
    val sha256: String
)

internal object ResumeFenceAnchor {
    private const val MAX = 256
    private val SHA = Regex("[0-9a-f]{64}")
    private val TEMP_SUFFIX = Regex("[a-f0-9-]{36}\\.tmp")
    private fun tempPrefix(ledger: Path) =
        "." + ledger.toAbsolutePath().normalize().fileName.toString() + ".resume-anchor-pending-"
    private fun auditTempPrefix(ledger: Path) =
        "." + ledger.toAbsolutePath().normalize().fileName.toString() + ".resume-audit-pending-"
    private fun auditName(ledger: Path, generation: Long) =
        ledger.toAbsolutePath().normalize().fileName.toString() + ".resume-audit-" +
            String.format(Locale.ROOT, "%012d.rcaudit", generation)
    private fun auditBody(gen: Long, fence: String, anchor: String, checkpoint: String,
                          policy: String, operator: String, approved: Boolean, at: String) =
        listOf("RCAUD1", gen.toString(), fence, anchor, checkpoint, policy,
            operator, approved.toString(), at).joinToString("\n", postfix = "\n")
    private fun fail(code: String): Nothing = throw SessionResumeUnconfirmed(code)
    private fun parent(ledger: Path): Path {
        val absolute = ledger.toAbsolutePath().normalize()
        val directory = absolute.parent ?: fail("RESUME_FENCE_ANCHOR_UNCONFIRMED")
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
                directory.toRealPath() != directory ||
                Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS) != setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE))
                fail("RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY")
            LinuxCheckpointOwner.requireMatchingUid(
                Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS),
                LinuxCheckpointOwner.effectiveUid())
            return directory
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY", e)
        }
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

    /** Strict immutable audit record: these fields attest the host decision,
     * not cryptographic proof of a human identity or permission grant.
     */
    private fun readAudit(file: Path): ResumeReconciliationAudit {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(file) !in 1L..2048L) fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
            val raw = Files.readAllBytes(file).toString(StandardCharsets.UTF_8)
            val parts = raw.split("\n")
            if (parts.size != 11 || parts[0] != "RCAUD1" || parts.last() != "")
                fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
            val generation = parts[1].toLongOrNull()
                ?: fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
            if (generation !in 1L..MAX.toLong() ||
                !listOf(parts[2], parts[3], parts[4], parts[5], parts[9]).all(SHA::matches) ||
                !ToolAuthorizationPolicy.validHostIdentifier(parts[6]) ||
                parts[7] != "true" ||
                runCatching { Instant.parse(parts[8]) }.isFailure)
                fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
            val canonical = auditBody(generation, parts[2], parts[3], parts[4],
                parts[5], parts[6], true, parts[8])
            if (raw != canonical + parts[9] + "\n" || digest(canonical) != parts[9])
                fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
            return ResumeReconciliationAudit(generation, parts[2], parts[3],
                parts[4], parts[5], parts[6], true, parts[8], parts[9])
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_AUDIT_UNCONFIRMED", e)
        }
    }

    private fun verifyAuditIfPresent(ledger: Path, anchor: ResumeFenceAnchorRecord) {
        val file = parent(ledger).resolve(auditName(ledger, anchor.generation))
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return
        val record = readAudit(file)
        if (record.generation != anchor.generation ||
            record.anchorSha256 != anchor.sha256 ||
            record.fenceSha256 != anchor.fenceSha256)
            fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
    }

    /** Write-once audited operator approval, fsynced before the missing fence. */
    fun recordOperatorApproval(ledger: Path, fence: ResumeFenceRecord,
                               anchor: ResumeFenceAnchorRecord,
                               operator: PreparedFenceReconciliation): ResumeReconciliationAudit {
        val directory = parent(ledger)
        if (fence.generation != anchor.generation ||
            fence.sha256 != anchor.fenceSha256 ||
            operator.expectedPreparedGeneration != fence.generation ||
            operator.expectedPreparedFenceSha256 != fence.sha256 ||
            !operator.operatorApproved ||
            !ToolAuthorizationPolicy.validHostIdentifier(operator.operatorId))
            fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
        val target = directory.resolve(auditName(ledger, fence.generation))
        fun requireSame(record: ResumeReconciliationAudit): ResumeReconciliationAudit {
            if (record.generation != fence.generation || record.fenceSha256 != fence.sha256 ||
                record.anchorSha256 != anchor.sha256 ||
                record.checkpointSha256 != fence.checkpointSha256 ||
                record.policySha256 != fence.policySha256 ||
                record.operatorId != operator.operatorId || !record.operatorApproved)
                fail("RESUME_FENCE_AUDIT_CONFLICT")
            return record
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            return requireSame(readAudit(target))
        val recordedAt = Instant.now().toString()
        val header = auditBody(fence.generation, fence.sha256, anchor.sha256,
            fence.checkpointSha256, fence.policySha256, operator.operatorId, true, recordedAt)
        val checksum = digest(header)
        val payload = (header + checksum + "\n").toByteArray(StandardCharsets.UTF_8)
        val temporary = directory.resolve(auditTempPrefix(ledger) + UUID.randomUUID() + ".tmp")
        try {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
            FileChannel.open(temporary, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(payload)
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
                }
                channel.force(true)
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                fail("RESUME_FENCE_AUDIT_CONFLICT")
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            return requireSame(readAudit(target))
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_AUDIT_UNCONFIRMED", e)
        } finally {
            try { Files.deleteIfExists(temporary) } catch (_: Exception) { /* unconfirmed */ }
        }
    }

    /**
     * No directory enumeration for unfenced legacy checkpoints. A prepared
     * generation one is still found by its exact filename and refused.
     */
    fun verify(ledger: Path, head: ResumeFenceRecord?): ResumeFenceAnchorRecord? {
        try {
            val count = head?.generation ?: 0L
            val first = ledger.toAbsolutePath().normalize().resolveSibling(name(ledger, 1L))
            if (count == 0L && !Files.exists(first, LinkOption.NOFOLLOW_LINKS)) {
                val auditOne = first.resolveSibling(auditName(ledger, 1L))
                if (Files.exists(auditOne, LinkOption.NOFOLLOW_LINKS))
                    fail("RESUME_FENCE_AUDIT_UNCONFIRMED")
                return null
            }
            val directory = parent(ledger)
            var previous: ResumeFenceAnchorRecord? = null
            for (gen in 1L..count) {
                val record = read(directory.resolve(name(ledger, gen)))
                if (record.generation != gen || record.previousSha256 != previous?.sha256)
                    fail("RESUME_FENCE_ANCHOR_MISMATCH")
                verifyAuditIfPresent(ledger, record)
                previous = record
            }
            if (previous?.fenceSha256 != head?.sha256 ||
                Files.exists(directory.resolve(name(ledger, count + 1L)),
                    LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(directory.resolve(auditName(ledger, count + 1L)),
                    LinkOption.NOFOLLOW_LINKS))
                fail("RESUME_FENCE_ANCHOR_MISMATCH")
            return previous
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_ANCHOR_UNCONFIRMED", e)
        }
    }

    /** The only allowed repair: exactly one validated prepared head. */
    fun verifyPrepared(ledger: Path, head: ResumeFenceRecord?,
                       proposed: ResumeFenceRecord): ResumeFenceAnchorRecord {
        try {
            val directory = parent(ledger)
            val priorCount = head?.generation ?: 0L
            if (proposed.generation != priorCount + 1L ||
                proposed.generation !in 1L..MAX.toLong())
                fail("RESUME_FENCE_RECONCILIATION_UNCONFIRMED")
            var previous: ResumeFenceAnchorRecord? = null
            for (gen in 1L..priorCount) {
                val record = read(directory.resolve(name(ledger, gen)))
                if (record.generation != gen || record.previousSha256 != previous?.sha256)
                    fail("RESUME_FENCE_RECONCILIATION_UNCONFIRMED")
                verifyAuditIfPresent(ledger, record)
                previous = record
            }
            if (previous?.fenceSha256 != head?.sha256)
                fail("RESUME_FENCE_RECONCILIATION_UNCONFIRMED")
            val prepared = read(directory.resolve(name(ledger, proposed.generation)))
            if (prepared.generation != proposed.generation ||
                prepared.previousSha256 != previous?.sha256 ||
                prepared.fenceSha256 != proposed.sha256 ||
                Files.exists(directory.resolve(name(ledger, proposed.generation + 1L)),
                    LinkOption.NOFOLLOW_LINKS))
                fail("RESUME_FENCE_RECONCILIATION_UNCONFIRMED")
            return prepared
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_RECONCILIATION_UNCONFIRMED", e)
        }
    }

    /** Admission-only cleanup, never invoked by read-only legacy inspection. */
    fun cleanOrphanedTemps(ledger: Path) {
        val directory = parent(ledger)
        val ownPrefix = tempPrefix(ledger)
        var removed = false
        try {
            Files.newDirectoryStream(directory).use { entries ->
                entries.filter { file ->
                    val name = file.fileName.toString()
                    listOf(ownPrefix, auditTempPrefix(ledger)).any { prefix ->
                        name.startsWith(prefix) &&
                            TEMP_SUFFIX.matches(name.removePrefix(prefix))
                    }
                }.forEach { file ->
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        fail("RESUME_FENCE_ANCHOR_UNCONFIRMED")
                    Files.delete(file)
                    removed = true
                }
            }
            if (removed)
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_ANCHOR_CLEANUP_UNCONFIRMED", e)
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
        val temporary = directory.resolve(tempPrefix(ledger) + UUID.randomUUID() + ".tmp")
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
        verifyAuditIfPresent(ledger, actual)
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_FENCE_ANCHOR_UNCONFIRMED", e)
        }
    }
}
