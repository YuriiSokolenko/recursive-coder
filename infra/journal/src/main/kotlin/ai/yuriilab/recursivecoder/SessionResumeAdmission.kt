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
    val readPolicyFacts: ReadPolicyFacts,
    val externalActionsReconciled: Boolean
)

class SessionResumeUnconfirmed(val code: String, cause: Throwable? = null) :
    IllegalStateException(code, cause)

/**
 * Snapshot constraints remain in infra because SessionCheckpoint is the
 * durable adapter type; phase/event decisions live in core:session.
 */
internal object PreExecutionSnapshotPolicy {
    fun safe(snapshot: SessionCheckpoint): Boolean =
        snapshot.phase in setOf(
            SessionPhaseVocabulary.createdWire, SessionPhaseVocabulary.acceptedWire) &&
            snapshot.taskState in setOf("created", "ready") &&
            snapshot.taskAttempt == 0 &&
            snapshot.acceptedArtifacts.isEmpty() &&
            snapshot.pendingEffects.isEmpty() &&
            snapshot.children.all { it.state == "ready" } &&
            snapshot.hostAssertedUsage.all { it.certainty == "known" && it.units == 0L }
}

/** Compile-time acknowledgement required at trusted operator recovery call sites. */
@RequiresOptIn(
    message = "Explicit trusted-host authorization is required for resume operator reconciliation.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ResumeOperatorOnlyApi

/** Explicit host/operator acknowledgement of one exact interrupted prepare. */
@ResumeOperatorOnlyApi
data class PreparedFenceReconciliation(
    val expectedPreparedGeneration: Long,
    val expectedPreparedFenceSha256: String,
    val operatorId: String,
    val operatorApproved: Boolean
)

/** Informational state only. It never authenticates or authorizes resume. */
enum class ResumeReconciliationState {
    PREPARED_UNAPPROVED,
    APPROVED_PENDING,
    COMPLETED
}

data class ResumeReconciliationStatus(
    val generation: Long,
    val fenceSha256: String,
    val operatorId: String?,
    val state: ResumeReconciliationState
)

/** Read-only authority is evaluated and canonically hashed from host policy facts. */
internal object ResumeReadAuthority {
    fun fingerprint(context: CheckpointHostContext, facts: ReadPolicyFacts): String {
        if (ToolAuthorizationPolicy.admitReadOnlyPolicy(facts) != ToolAuthorizationDecision.Permit)
            throw SessionResumeUnconfirmed("RESUME_TOOL_AUTHORITY_UNCONFIRMED")
        val canonical = listOf("RCREAD1", context.policySha256, facts.mode,
            facts.allowedTools.sorted().joinToString(","), facts.writablePathsPresent.toString(),
            facts.immutableInputsPresent.toString()).joinToString("\n", postfix = "\n")
        return SessionCheckpointCodec.sha256(canonical.toByteArray(StandardCharsets.UTF_8))
    }
}

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

    /** Read an exact committed generation; callers first validate the full chain. */
    fun atGeneration(directory: Path, generation: Long): ResumeFenceRecord =
        decode(directory.resolve(name(generation)))

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
                checkpoint: VerifiedSessionCheckpoint, authority: String,
                beforePublish: (ResumeFenceRecord) -> Unit): ResumeFenceRecord {
        val generation = (previous?.generation ?: 0L) + 1L
        if (generation > MAX_FENCES) fail("RESUME_FENCE_LIMIT")
        val snapshot = checkpoint.snapshot
        val header = body(generation, previous?.sha256, checkpoint.sha256, authority,
            snapshot.sessionId, snapshot.traceId, snapshot.binding.policySha256)
        val checksum = digest(header.toByteArray(StandardCharsets.UTF_8))
        val bytes = (header + checksum + "\n").toByteArray(StandardCharsets.UTF_8)
        val destination = directory.resolve(name(generation))
        // The independent durable anchor must precede this fence's publication.
        beforePublish(ResumeFenceRecord(generation, previous?.sha256, checkpoint.sha256,
            authority, snapshot.sessionId, snapshot.traceId, snapshot.binding.policySha256,
            checksum))
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
            ResumeImmutablePublication.linkAndSync(directory, temporary, destination,
                "RESUME_FENCE_CAS_FAILED", "RESUME_FENCE_PUBLISH_UNCONFIRMED")
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

    /** Bounded single-head check; the entire chain was verified at admission. */
    fun requireHead(directory: Path, generation: Long, sha256: String): ResumeFenceRecord {
        val record = decode(directory.resolve(name(generation)))
        if (record.generation != generation || record.sha256 != sha256 ||
            Files.exists(directory.resolve(name(generation + 1L)), LinkOption.NOFOLLOW_LINKS))
            fail("RESUME_FENCE_STALE_WRITER")
        return record
    }

    /** Clean abandoned uncommitted fence temp files only under both OS locks. */
    fun cleanOrphanedTemps(directory: Path) {
        var removed = false
        Files.newDirectoryStream(directory).use { entries ->
            entries.filter { TEMP.matches(it.fileName.toString()) }.forEach { file ->
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    fail("RESUME_FENCE_CORRUPT")
                try {
                    Files.delete(file)
                    removed = true
                } catch (e: Exception) {
                    throw SessionResumeUnconfirmed("RESUME_FENCE_CLEANUP_UNCONFIRMED", e)
                }
            }
        }
        if (removed) {
            try { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
            catch (e: Exception) {
                throw SessionResumeUnconfirmed("RESUME_FENCE_CLEANUP_UNCONFIRMED", e)
            }
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
    private val ledgerPath: Path,
    private val admittedFenceSha256: String,
    private val admittedAnchorSha256: String,
    private val admittedLedgerFileKey: String,
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

    /** Both the admitted path and Linux inode identity must remain unchanged. */
    @Synchronized internal fun requireLedgerPath(candidate: Path) {
        if (closed || candidate.toAbsolutePath().normalize() != ledgerPath.toAbsolutePath().normalize() ||
            SessionResumeAdmission.ledgerFileKey(candidate) != admittedLedgerFileKey)
            throw SessionResumeUnconfirmed("RESUME_LEDGER_PATH_CHANGED")
    }

    /** Required before callbacks or trusted receipt publication. */
    @Synchronized fun assertCurrent() {
        if (closed || !lock.isValid)
            throw SessionResumeUnconfirmed("RESUME_FENCE_STALE_WRITER")
        requireLedgerPath(ledgerPath)
        val fence = ResumeFenceStorage.requireHead(directory, generation, admittedFenceSha256)
        ResumeFenceAnchor.requireHead(ledgerPath, fence, admittedAnchorSha256)
    }

    /**
     * Serialize short host-owned state/receipt publications with close(), so
     * a callback cannot pass a check, lose its lease and commit afterward.
     * Never run model calls, external tools or blocking network I/O here.
     */
    @Synchronized fun <T> publishIfCurrent(publication: () -> T): T {
        assertCurrent()
        return publication()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        try { lock.release() } catch (e: Exception) { failure = e }
        try { owner.close() } catch (e: Exception) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        // An uncertain OS release leaves the JVM reservation in place.
        if (failure != null) throw SessionResumeUnconfirmed("RESUME_LEASE_RELEASE_UNCONFIRMED", failure)
        onClosed(lockPath)
    }
}

/**
 * Strict pre-execution admission only; no automatic restart or effect replay.
 * Only a checkpoint with no physical work and an independently verified ledger
 * boundary can obtain a new generation. Later states require operator review.
 */
object SessionResumeAdmission {
    private val heldLocks = ConcurrentHashMap.newKeySet<Path>()
    private fun fail(code: String): Nothing = throw SessionResumeUnconfirmed(code)
    private val SHA = Regex("[0-9a-f]{64}")

    internal fun ledgerFileKey(path: Path): String {
        try {
            val file = path.toAbsolutePath().normalize()
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS)
            if (!attrs.isRegularFile) fail("RESUME_LEDGER_IDENTITY_UNCONFIRMED")
            return attrs.fileKey()?.toString()
                ?: fail("RESUME_LEDGER_IDENTITY_UNCONFIRMED")
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed("RESUME_LEDGER_IDENTITY_UNCONFIRMED", e)
        }
    }

    fun currentGeneration(directory: Path, context: CheckpointHostContext,
                          ledgerPath: Path): Long = try {
        SessionCheckpointStore.withAdmissionEvidence(directory, context, ledgerPath) { dir, _, _ ->
            val head = ResumeFenceStorage.latest(dir)
            ResumeFenceAnchor.verify(ledgerPath, head, dir)
            head?.generation ?: 0L
        }
    } catch (e: CheckpointUnconfirmed) {
        val cause = e.cause as? SessionResumeUnconfirmed
        if (cause != null) throw cause
        throw e
    }

    /**
     * Read-only operator diagnostics. Unlike currentGeneration/inspect, this
     * can report a *validated* prepared head without treating it as admission.
     * Any corrupt/inconsistent evidence fails closed; no state is mutated.
     */
    fun reconciliationStatus(directory: Path, context: CheckpointHostContext,
                             ledgerPath: Path): ResumeReconciliationStatus? =
        SessionCheckpointStore.withAdmissionEvidence(directory, context, ledgerPath) {
                checkedDir, verified, _ ->
            val head = ResumeFenceStorage.latest(checkedDir)
            ResumeFenceAnchor.recoveryStatus(ledgerPath, head, checkedDir, verified.sha256,
                verified.snapshot.binding.policySha256)
        }

    @OptIn(ResumeOperatorOnlyApi::class)
    fun admit(directory: Path, context: CheckpointHostContext, ledgerPath: Path,
              attestation: ResumeHostAttestation): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, null) { }

    /** Explicit operator path: never selected by normal session admission. */
    @ResumeOperatorOnlyApi
    fun reconcilePreparedAdmission(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation, operator: PreparedFenceReconciliation
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, operator) { }

    /** Integration-test-only fault seam, not an application API or service port.
     * Compiled in production only to support deterministic crash-boundary tests.
     */
    @ResumeOperatorOnlyApi
    internal fun admitWithFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation,
        beforeInspection: () -> Unit = {},
        hardLinkProbe: (Path, String?) -> Unit = { directory, scope ->
            ResumeImmutablePublication.requireHardLinks(directory, scope)
        },
        afterAnchorCommitted: () -> Unit
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, null,
            beforeInspection = beforeInspection, hardLinkProbe = hardLinkProbe,
            afterAnchorCommitted = afterAnchorCommitted)

    /** Integration-test-only fault seam for a crash after durable operator audit. */
    @ResumeOperatorOnlyApi
    internal fun reconcilePreparedWithFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation, operator: PreparedFenceReconciliation,
        afterAuditCommitted: () -> Unit
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, operator,
            afterAnchorCommitted = afterAuditCommitted)

    @OptIn(ResumeOperatorOnlyApi::class)
    private fun admitChecked(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation, operator: PreparedFenceReconciliation?,
        beforeInspection: () -> Unit = {},
        hardLinkProbe: (Path, String?) -> Unit = { directory, scope ->
            ResumeImmutablePublication.requireHardLinks(directory, scope)
        },
        afterAnchorCommitted: () -> Unit
    ): SessionResumeLease {
        if (attestation.expectedFenceGeneration < 0)
            fail("RESUME_FENCE_EXPECTED_GENERATION_INVALID")
        if (operator != null && (!operator.operatorApproved ||
            !ToolAuthorizationPolicy.validHostIdentifier(operator.operatorId) ||
            !SHA.matches(operator.expectedPreparedFenceSha256) ||
            operator.expectedPreparedGeneration != attestation.expectedFenceGeneration + 1L))
            fail("RESUME_FENCE_RECONCILIATION_UNCONFIRMED")
        val authoritySha256 = ResumeReadAuthority.fingerprint(context, attestation.readPolicyFacts)
        if (!attestation.externalActionsReconciled)
            fail("RESUME_EXTERNAL_EFFECTS_UNCONFIRMED")
        val ledgerKeyBeforeInspect = ledgerFileKey(ledgerPath)
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
            // Integration test seam: must run after key capture and before
            // actual ledger inspection, while the resume OS lock is held.
            beforeInspection()
            val result = SessionCheckpointStore.withAdmissionEvidence(dir, context, ledgerPath) {
                    checkedDir, verified, ledger ->
                val s = verified.snapshot
                // Only before the first model, tool, agent or recursive execution.
                if (verified.reconciliationRequired ||
                    !PreExecutionSnapshotPolicy.safe(s) ||
                    PreExecutionPhasePolicy.phaseFromVerifiedHistory(
                        s.phase, ledger.events) == null)
                    fail("RESUME_UNSAFE_BOUNDARY")
                val prior = ResumeFenceStorage.latest(checkedDir)
                val priorAnchor = if (operator == null)
                    ResumeFenceAnchor.verify(ledgerPath, prior, checkedDir) else null
                if ((prior?.generation ?: 0L) != attestation.expectedFenceGeneration)
                    fail("RESUME_FENCE_CAS_FAILED")
                if (prior != null && (prior.sessionId != s.sessionId ||
                    prior.traceId != s.traceId ||
                    prior.policySha256 != s.binding.policySha256 ||
                    prior.toolAuthoritySha256 != authoritySha256))
                    fail("RESUME_FENCE_CONTEXT_CHANGED")
                val ledgerKey = ledgerFileKey(ledgerPath)
                if (ledgerKey != ledgerKeyBeforeInspect)
                    fail("RESUME_LEDGER_PATH_CHANGED")
                // Ignore probe crash leftovers during read-only inspection;
                // remove them only during admission under both OS locks.
                ResumeImmutablePublication.cleanOrphanedProbes(checkedDir)
                ResumeFenceAnchor.cleanOrphanedProbes(ledgerPath)
                // Test both mounts before the first irreversible anchor/audit.
                // Different filesystems are supported; the test seam must
                // not be exposed as a production injection point.
                hardLinkProbe(checkedDir, null)
                ResumeFenceAnchor.requirePublicationSupport(ledgerPath, hardLinkProbe)
                ResumeFenceStorage.cleanOrphanedTemps(checkedDir)
                ResumeFenceAnchor.cleanOrphanedTemps(ledgerPath)
                var preparedAnchor: ResumeFenceAnchorRecord? = null
                val next = ResumeFenceStorage.publish(checkedDir, prior, verified,
                    authoritySha256) { prepared ->
                    preparedAnchor = if (operator == null) {
                        ResumeFenceAnchor.publish(ledgerPath, prepared, priorAnchor)
                    } else {
                        if (operator.expectedPreparedGeneration != prepared.generation ||
                            operator.expectedPreparedFenceSha256 != prepared.sha256)
                            fail("RESUME_FENCE_RECONCILIATION_UNCONFIRMED")
                        ResumeFenceAnchor.verifyPrepared(ledgerPath, prior, prepared, checkedDir).also { anchor ->
                            ResumeFenceAnchor.recordOperatorApproval(ledgerPath, prepared,
                                anchor, operator)
                        }
                    }
                    afterAnchorCommitted()
                    // This must follow the test seam too, to cover an inode swap
                    // after a durable anchor/audit but before fence publication.
                    if (ledgerFileKey(ledgerPath) != ledgerKey)
                        fail("RESUME_LEDGER_PATH_CHANGED")
                }
                val anchor = preparedAnchor ?: fail("RESUME_FENCE_ANCHOR_UNCONFIRMED")
                SessionResumeLease(next.generation, next.checkpointSha256,
                    next.toolAuthoritySha256, checkedDir, ledgerPath, next.sha256,
                    anchor.sha256, ledgerKey, lockPath, acquiredOwner,
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
