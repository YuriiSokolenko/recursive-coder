package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.jsonPrimitive

/**
 * Host attestation, not a claim produced by the model or a checkpoint. The
 * independent authority digest must be derived from the current host policy.
 * expectedFenceGeneration is a compare-and-swap precondition, not a hint.
 */
data class ResumeHostAttestation(
    val expectedFenceGeneration: Long,
    val toolAuthoritySha256: String,
    val expectedToolAuthoritySha256: String,
    val toolAuthorityReconciled: Boolean,
    val externalActionsReconciled: Boolean
)

class SessionResumeUnconfirmed(val code: String, cause: Throwable? = null) :
    IllegalStateException(code, cause)

internal data class ResumeFenceRecord(
    val generation: Long,
    val previousSha256: String?,
    val checkpointSha256: String,
    val toolAuthoritySha256: String,
    val sessionId: String,
    val traceId: String,
    val policySha256: String,
    val sha256: String
)

/**
 * Immutable, hash-chained fence records. Each publication is serialized with
 * checkpoint.lock, NOT with the long-lived resume.lock. An unexpected published
 * fence is never truncated, replaced or treated as an empty generation.
 */
internal object ResumeFenceStorage {
    val NAME = Regex("resume-[0-9]{12}\\.rcf")
    val TEMP = Regex("\\.resume-pending-[a-f0-9-]{36}\\.tmp")
    private val SHA = Regex("[0-9a-f]{64}")
    private const val MAX_FENCES = 256
    private fun fail(code: String): Nothing = throw SessionResumeUnconfirmed(code)
    private fun digest(bytes: ByteArray) = SessionCheckpointCodec.sha256(bytes)
    private fun name(generation: Long) =
        String.format(Locale.ROOT, "resume-%012d.rcf", generation)

    private fun body(generation: Long, previous: String?, checkpoint: String,
                     authority: String, session: String, trace: String, policy: String) =
        listOf("RCFEN1", generation.toString(), previous ?: "-", checkpoint,
            authority, session, trace, policy).joinToString("\n", postfix = "\n")

    private fun decode(path: Path): ResumeFenceRecord {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(path) !in 1L..1024L) fail("RESUME_FENCE_CORRUPT")
        val raw = Files.readAllBytes(path).toString(StandardCharsets.UTF_8)
        val fields = raw.split("\n")
        if (fields.size != 10 || fields.last() != "" || fields[0] != "RCFEN1")
            fail("RESUME_FENCE_VERSION_UNSUPPORTED")
        val generation = fields[1].toLongOrNull() ?: fail("RESUME_FENCE_CORRUPT")
        val previous = fields[2].takeUnless { it == "-" }
        if (generation !in 1L..999_999_999_999L ||
            (generation == 1L) != (previous == null) ||
            previous != null && !SHA.matches(previous) ||
            !listOf(fields[3], fields[4], fields[7], fields[8]).all(SHA::matches) ||
            !ToolAuthorizationPolicy.validHostIdentifier(fields[5]) ||
            !ToolAuthorizationPolicy.validHostIdentifier(fields[6]))
            fail("RESUME_FENCE_CORRUPT")
        val canonical = body(generation, previous, fields[3], fields[4],
            fields[5], fields[6], fields[7])
        if (digest(canonical.toByteArray(StandardCharsets.UTF_8)) != fields[8] ||
            raw != canonical + fields[8] + "\n")
            fail("RESUME_FENCE_HASH_MISMATCH")
        return ResumeFenceRecord(generation, previous, fields[3], fields[4],
            fields[5], fields[6], fields[7], fields[8])
    }

    fun latest(directory: Path): ResumeFenceRecord? {
        val files = Files.newDirectoryStream(directory).use { entries ->
            entries.filter { NAME.matches(it.fileName.toString()) }.toList()
        }
        if (files.size > MAX_FENCES) fail("RESUME_FENCE_LIMIT")
        var last: ResumeFenceRecord? = null
        files.sortedBy { it.fileName.toString() }.forEach { path ->
            val next = decode(path)
            if (next.generation != (last?.generation ?: 0L) + 1L ||
                path.fileName.toString() != name(next.generation) ||
                next.previousSha256 != last?.sha256 ||
                (last != null && (next.sessionId != last!!.sessionId ||
                    next.traceId != last!!.traceId ||
                    next.policySha256 != last!!.policySha256 ||
                    next.toolAuthoritySha256 != last!!.toolAuthoritySha256)))
                fail("RESUME_FENCE_CHAIN_MISMATCH")
            last = next
        }
        return last
    }

    fun publish(directory: Path, previous: ResumeFenceRecord?,
                checkpoint: VerifiedSessionCheckpoint, authority: String): ResumeFenceRecord {
        val generation = (previous?.generation ?: 0L) + 1L
        if (generation > MAX_FENCES) fail("RESUME_FENCE_LIMIT")
        val snapshot = checkpoint.snapshot
        val header = body(generation, previous?.sha256, checkpoint.sha256, authority,
            snapshot.sessionId, snapshot.traceId, snapshot.binding.policySha256)
        val checksum = digest(header.toByteArray(StandardCharsets.UTF_8))
        val bytes = (header + checksum + "\n").toByteArray(StandardCharsets.UTF_8)
        val destination = directory.resolve(name(generation))
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            fail("RESUME_FENCE_CAS_FAILED")
        val temporary = directory.resolve(".resume-pending-" + UUID.randomUUID() + ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) fail("RESUME_FENCE_PUBLISH_UNCONFIRMED")
                }
                channel.force(true)
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                fail("RESUME_FENCE_CAS_FAILED")
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            return decode(destination)
        } catch (error: Exception) {
            if (error is SessionResumeUnconfirmed) throw error
            throw SessionResumeUnconfirmed("RESUME_FENCE_PUBLISH_UNCONFIRMED", error)
        } finally {
            // A renamed generation is authoritative even when parent fsync fails.
            // Never delete or overwrite it during cleanup.
            try { Files.deleteIfExists(temporary) } catch (_: Exception) { /* unconfirmed */ }
        }
    }

    /** Must be called while the checkpoint OS lock is held for any publication. */
    fun requireCurrentForSave(directory: Path, lease: SessionResumeLease?,
                              snapshot: SessionCheckpoint) {
        val current = latest(directory)
        if (current == null) {
            if (lease != null) fail("RESUME_FENCE_STALE_WRITER")
            return
        }
        if (lease == null) fail("RESUME_FENCE_REQUIRED")
        lease.requireCurrent(current, directory)
        if (snapshot.sessionId != current.sessionId ||
            snapshot.traceId != current.traceId ||
            snapshot.binding.policySha256 != current.policySha256)
            fail("RESUME_FENCE_CONTEXT_CHANGED")
    }
}

/**
 * A cooperating process holds this OS lease for the entire resumed execution
 * and terminal publication. Checkpoint writes additionally compare the
 * immutable generation under checkpoint.lock; close invalidates the token.
 * Do not treat the token as permission to replay external effects.
 */
class SessionResumeLease internal constructor(
    val generation: Long,
    val admittedCheckpointSha256: String,
    val toolAuthoritySha256: String,
    private val directory: Path,
    private val lockPath: Path,
    private val owner: FileChannel,
    private val lock: FileLock,
    private val onClosed: (Path) -> Unit
) : AutoCloseable {
    @Volatile private var closed = false

    @Synchronized internal fun requireCurrent(current: ResumeFenceRecord,
                                              expectedDirectory: Path) {
        if (closed || !lock.isValid || directory != expectedDirectory ||
            generation != current.generation ||
            toolAuthoritySha256 != current.toolAuthoritySha256 ||
            admittedCheckpointSha256 != current.checkpointSha256)
            throw SessionResumeUnconfirmed("RESUME_FENCE_STALE_WRITER")
    }

    /** Required before callbacks or trusted receipt publication. */
    @Synchronized fun assertCurrent() {
        if (closed || !lock.isValid)
            throw SessionResumeUnconfirmed("RESUME_FENCE_STALE_WRITER")
        requireCurrent(ResumeFenceStorage.latest(directory)
            ?: throw SessionResumeUnconfirmed("RESUME_FENCE_STALE_WRITER"), directory)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        try { lock.release() } catch (e: Exception) { failure = e }
        try { owner.close() } catch (e: Exception) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        onClosed(lockPath)
        if (failure != null) throw SessionResumeUnconfirmed("RESUME_LEASE_RELEASE_UNCONFIRMED", failure)
    }
}

/**
 * Strict pre-execution admission only; no automatic restart or effect replay.
 * Only a checkpoint with no physical work and an independently verified ledger
 * boundary can obtain a new generation. Later states require operator review.
 */
object SessionResumeAdmission {
    private val heldLocks = ConcurrentHashMap.newKeySet<Path>()
    private val SHA = Regex("[0-9a-f]{64}")
    private fun fail(code: String): Nothing = throw SessionResumeUnconfirmed(code)

    fun currentGeneration(directory: Path, context: CheckpointHostContext,
                          ledgerPath: Path): Long =
        SessionCheckpointStore.withAdmissionEvidence(directory, context, ledgerPath) { dir, _, _ ->
            ResumeFenceStorage.latest(dir)?.generation ?: 0L
        }

    fun admit(directory: Path, context: CheckpointHostContext, ledgerPath: Path,
              attestation: ResumeHostAttestation): SessionResumeLease {
        if (attestation.expectedFenceGeneration < 0 ||
            !SHA.matches(attestation.toolAuthoritySha256) ||
            !SHA.matches(attestation.expectedToolAuthoritySha256) ||
            attestation.toolAuthoritySha256 != attestation.expectedToolAuthoritySha256 ||
            !attestation.toolAuthorityReconciled)
            fail("RESUME_TOOL_AUTHORITY_UNCONFIRMED")
        if (!attestation.externalActionsReconciled)
            fail("RESUME_EXTERNAL_EFFECTS_UNCONFIRMED")
        val dir = SessionCheckpointStore.checkedAdmissionDirectory(directory, context)
        val lockPath = dir.resolve("resume.lock")
        if (!heldLocks.add(lockPath)) fail("RESUME_LEASE_HELD")
        var owner: FileChannel? = null
        var lock: FileLock? = null
        var transferred = false
        try {
            if (Files.isSymbolicLink(lockPath)) fail("RESUME_UNSAFE_LOCK")
            val created = !Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
            owner = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            lock = try { owner.tryLock() }
                catch (_: OverlappingFileLockException) { null }
            if (lock == null) fail("RESUME_LEASE_HELD")
            if (created) {
                owner.force(true)
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            }
            val acquiredOwner = owner
            val acquiredLock = lock
            val result = SessionCheckpointStore.withAdmissionEvidence(dir, context, ledgerPath) {
                    checkedDir, verified, ledger ->
                val s = verified.snapshot
                // Only before the first model, tool, agent or recursive execution.
                if (verified.reconciliationRequired ||
                    s.phase !in setOf("created", "accepted") ||
                    s.taskState !in setOf("created", "ready") ||
                    s.taskAttempt != 0 || s.acceptedArtifacts.isNotEmpty() ||
                    s.pendingEffects.isNotEmpty() ||
                    s.children.any { it.state != "ready" } ||
                    s.hostAssertedUsage.any { it.certainty != "known" || it.units != 0L } ||
                    ledger.events.any {
                        it["type"]?.jsonPrimitive?.content !in
                            setOf("session.transition", "checkpoint.saved")
                    }) fail("RESUME_UNSAFE_BOUNDARY")
                val prior = ResumeFenceStorage.latest(checkedDir)
                if ((prior?.generation ?: 0L) != attestation.expectedFenceGeneration)
                    fail("RESUME_FENCE_CAS_FAILED")
                if (prior != null && (prior.sessionId != s.sessionId ||
                    prior.traceId != s.traceId ||
                    prior.policySha256 != s.binding.policySha256 ||
                    prior.toolAuthoritySha256 != attestation.toolAuthoritySha256))
                    fail("RESUME_FENCE_CONTEXT_CHANGED")
                val next = ResumeFenceStorage.publish(checkedDir, prior, verified,
                    attestation.toolAuthoritySha256)
                SessionResumeLease(next.generation, next.checkpointSha256,
                    next.toolAuthoritySha256, checkedDir, lockPath, acquiredOwner,
                    acquiredLock) { heldLocks.remove(it) }
            }
            transferred = true
            return result
        } catch (error: Exception) {
            if (error is SessionResumeUnconfirmed || error is CheckpointUnconfirmed) throw error
            throw SessionResumeUnconfirmed("RESUME_ADMISSION_UNCONFIRMED", error)
        } finally {
            if (!transferred) {
                try { lock?.release() } catch (_: Exception) { /* fail closed */ }
                try { owner?.close() } catch (_: Exception) { /* fail closed */ }
                heldLocks.remove(lockPath)
            }
        }
    }
}
