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

class SessionResumeUnconfirmed(
    val code: String,
    cause: Throwable? = null,
    val detail: String? = null
) : IllegalStateException(if (detail == null) code else "$code: $detail", cause)

/**
 * Admission supports only reviewed persistent local Linux filesystems.
 * A hard-link probe alone cannot establish advisory-lock or fsync semantics.
 * Reject unknown types and transient/user-space mounts instead of guessing.
 */
internal object ResumeLocalFileStorePolicy {
    /** The only production FileStore.type lookup; fault seams must supply it explicitly. */
    val systemType: (Path) -> String = { Files.getFileStore(it).type() }

    private val trustedTypes = setOf("ext2", "ext3", "ext4", "xfs",
        "btrfs", "f2fs", "zfs")

    // Do not expose paths or arbitrary host-controlled strings in errors.
    private fun shown(type: String?): String = type?.lowercase(Locale.ROOT)
        ?.takeIf { it.length in 1..64 && it.all { c ->
            c in 'a'..'z' || c in '0'..'9' || c in "._-"
        } } ?: "unknown"

    private fun refuse(checkpointType: String?, ledgerType: String?,
                       cause: Throwable? = null): Nothing =
        throw SessionResumeUnconfirmed(ResumeDenialCodes.RESUME_FILESYSTEM_UNSUPPORTED, cause,
            "checkpoint=${shown(checkpointType)} ledger=${shown(ledgerType)}")

    fun requireSupportedTypes(checkpointType: String, ledgerType: String) {
        if (checkpointType.lowercase(Locale.ROOT) !in trustedTypes ||
            ledgerType.lowercase(Locale.ROOT) !in trustedTypes)
            refuse(checkpointType, ledgerType)
    }

    private data class Lookup(val type: String?, val failure: Exception? = null)

    fun requireSupportedPaths(directory: Path, ledgerPath: Path,
                              fileStoreType: (Path) -> String) {
        val parent = ledgerPath.toAbsolutePath().normalize().parent
            ?: refuse("unknown", "unknown")
        fun lookup(path: Path): Lookup =
            try { Lookup(fileStoreType(path)) }
            catch (error: Exception) { Lookup(null, error) }

        // Resolve both independent mounts for actionable diagnostics.
        val checkpoint = lookup(directory)
        val ledger = lookup(parent)
        val primaryFailure = checkpoint.failure ?: ledger.failure
        if (primaryFailure != null) {
            // The first volume is the primary cause; preserve the other failure
            // without replacing or obscuring it. Avoid self-suppression if an
            // injected lookup throws the same Exception instance for both paths.
            if (checkpoint.failure != null && ledger.failure != null &&
                ledger.failure !== checkpoint.failure)
                primaryFailure.addSuppressed(ledger.failure)
            refuse(if (checkpoint.failure != null) "lookup-failed" else checkpoint.type,
                if (ledger.failure != null) "lookup-failed" else ledger.type,
                primaryFailure)
        }
        requireSupportedTypes(checkpoint.type ?: "", ledger.type ?: "")
    }
}

/**
 * Snapshot constraints remain in infra because SessionCheckpoint is the
 * durable adapter type; phase/event decisions live in core:session.
 */
internal object PreExecutionSnapshotPolicy {
    fun safe(snapshot: SessionCheckpoint): Boolean =
        snapshot.phase in setOf(
            SessionPhaseVocabulary.createdWire, SessionPhaseVocabulary.acceptedWire) &&
            snapshot.taskState in setOf(CheckpointTaskStates.CREATED, CheckpointTaskStates.READY) &&
            snapshot.taskAttempt == 0 &&
            snapshot.acceptedArtifacts.isEmpty() &&
            snapshot.pendingEffects.isEmpty() &&
            snapshot.children.all { it.state == CheckpointChildStates.READY } &&
            snapshot.hostAssertedUsage.all {
                it.certainty == CheckpointUsageCertainty.KNOWN && it.units == 0L
            }
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
    const val MAX_FENCES = 256
    private fun fail(code: String): Nothing = throw SessionResumeUnconfirmed(code)
    private fun digest(bytes: ByteArray) = SessionCheckpointCodec.sha256(bytes)
    private fun name(generation: Long) =
        String.format(Locale.ROOT, "resume-%012d.rcf", generation)

    private fun body(generation: Long, previous: String?, checkpoint: String,
                     authority: String, session: String, trace: String, policy: String) =
        listOf(FENCE_FORMAT_MARKER, generation.toString(), previous ?: "-", checkpoint,
            authority, session, trace, policy).joinToString("\n", postfix = "\n")

    private fun decode(path: Path): ResumeFenceRecord {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(path) !in 1L..1024L) fail(ResumeDenialCodes.RESUME_FENCE_CORRUPT)
        val raw = Files.readAllBytes(path).toString(StandardCharsets.UTF_8)
        val fields = raw.split("\n")
        if (fields.size != 10 || fields.last() != "" || fields[0] != FENCE_FORMAT_MARKER)
            fail("RESUME_FENCE_VERSION_UNSUPPORTED")
        val generation = fields[1].toLongOrNull() ?: fail(ResumeDenialCodes.RESUME_FENCE_CORRUPT)
        val previous = fields[2].takeUnless { it == "-" }
        if (generation !in 1L..999_999_999_999L ||
            (generation == 1L) != (previous == null) ||
            previous != null && !SHA.matches(previous) ||
            !listOf(fields[3], fields[4], fields[7], fields[8]).all(SHA::matches) ||
            !ToolAuthorizationPolicy.validHostIdentifier(fields[5]) ||
            !ToolAuthorizationPolicy.validHostIdentifier(fields[6]))
            fail(ResumeDenialCodes.RESUME_FENCE_CORRUPT)
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
        if (files.size > MAX_FENCES) fail(SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_LIMIT)
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
        if (generation > MAX_FENCES) fail(SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_LIMIT)
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
            fail(ResumeDenialCodes.RESUME_FENCE_CAS_FAILED)
        val temporary = directory.resolve(".resume-pending-" + UUID.randomUUID() + ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) fail(ResumeDenialCodes.RESUME_FENCE_PUBLISH_UNCONFIRMED)
                }
                channel.force(true)
            }
            ResumeImmutablePublication.linkAndSync(directory, temporary, destination,
                ResumeDenialCodes.RESUME_FENCE_CAS_FAILED, ResumeDenialCodes.RESUME_FENCE_PUBLISH_UNCONFIRMED)
            return decode(destination)
        } catch (error: Exception) {
            if (error is SessionResumeUnconfirmed) throw error
            throw SessionResumeUnconfirmed(ResumeDenialCodes.RESUME_FENCE_PUBLISH_UNCONFIRMED, error)
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
            fail(ResumeDenialCodes.RESUME_FENCE_STALE_WRITER)
        return record
    }

    /** Clean abandoned uncommitted fence temp files only under both OS locks. */
    fun cleanOrphanedTemps(directory: Path) {
        var removed = false
        Files.newDirectoryStream(directory).use { entries ->
            entries.filter { TEMP.matches(it.fileName.toString()) }.forEach { file ->
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    fail(ResumeDenialCodes.RESUME_FENCE_CORRUPT)
                try {
                    Files.delete(file)
                    removed = true
                } catch (e: Exception) {
                    throw SessionResumeUnconfirmed(
                        SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CLEANUP_UNCONFIRMED, e)
                }
            }
        }
        if (removed) {
            try { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
            catch (e: Exception) {
                throw SessionResumeUnconfirmed(
                    SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CLEANUP_UNCONFIRMED, e)
            }
        }
    }

    /** Must be called while the checkpoint OS lock is held for any publication. */
    fun requireCurrentForSave(directory: Path, lease: SessionResumeLease?,
                              snapshot: SessionCheckpoint) {
        val current = latest(directory)
        if (current == null) {
            if (lease != null) fail(ResumeDenialCodes.RESUME_FENCE_STALE_WRITER)
            return
        }
        if (lease == null) fail("RESUME_FENCE_REQUIRED")
        lease.requireCurrent(current, directory)
        if (snapshot.sessionId != current.sessionId ||
            snapshot.traceId != current.traceId ||
            snapshot.binding.policySha256 != current.policySha256)
            fail(SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CONTEXT_CHANGED)
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
    // Accessed only under this monitor. Nested publications must restore
    // the outer state rather than clear it when their callback returns.
    private var publishing = false

    @Synchronized internal fun requireCurrent(current: ResumeFenceRecord,
                                              expectedDirectory: Path) {
        if (closed || !lock.isValid || directory != expectedDirectory ||
            generation != current.generation ||
            toolAuthoritySha256 != current.toolAuthoritySha256 ||
            admittedCheckpointSha256 != current.checkpointSha256)
            throw SessionResumeUnconfirmed(ResumeDenialCodes.RESUME_FENCE_STALE_WRITER)
    }

    /** Both the admitted path and Linux inode identity must remain unchanged. */
    @Synchronized internal fun requireLedgerPath(candidate: Path) {
        if (closed || candidate.toAbsolutePath().normalize() != ledgerPath.toAbsolutePath().normalize() ||
            SessionResumeAdmission.ledgerFileKey(candidate) != admittedLedgerFileKey)
            throw SessionResumeUnconfirmed(SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_PATH_CHANGED)
    }

    /**
     * Fetch the actual issuing session from the immutable, verified fence.
     * Call ONLY from a live publishIfCurrent guard; never trust a caller's
     * claimed session identity to mint effect-journal genesis.
     */
    internal fun admittedSessionId(): String {
        if (!Thread.holdsLock(this))
            throw SessionResumeUnconfirmed("RESUME_FENCE_MONITOR_REQUIRED")
        return ResumeFenceStorage.requireHead(
            directory, generation, admittedFenceSha256).sessionId
    }

    /** Required before callbacks or trusted receipt publication. */
    @Synchronized fun assertCurrent() {
        if (closed || !lock.isValid)
            throw SessionResumeUnconfirmed(ResumeDenialCodes.RESUME_FENCE_STALE_WRITER)
        requireLedgerPath(ledgerPath)
        val fence = ResumeFenceStorage.requireHead(directory, generation, admittedFenceSha256)
        ResumeFenceAnchor.requireHead(ledgerPath, fence, admittedAnchorSha256)
    }

    /**
     * Serialize short host-owned state/receipt publications with close(), so
     * a callback cannot pass a check, lose its lease and commit afterward.
     * Never run model calls, external tools or blocking network I/O here.
     * Local durable FileChannel.force(true) operations are deliberate
     * exceptions for both the fenced session ledger appendFenced and the
     * isolated host-only effect journal create/append. Effect-journal genesis
     * creation also persists the parent directory entry within this guard.
     * These local fsync operations may delay lease retirement; they MUST stay
     * inside the same authority-checked publication. No model, tool or
     * network calls may run under this monitor.
     */
    @Synchronized fun <T> publishIfCurrent(publication: () -> T): T {
        assertCurrent()
        val outerPublication = publishing
        publishing = true
        try {
            return publication()
        } finally {
            publishing = outerPublication
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        // JVM monitors are reentrant: a callback on this thread could
        // otherwise release the OS lock while its publication is active.
        // Refuse before changing state; another thread still waits for
        // the entire outer publication to leave the monitor.
        if (publishing)
            throw SessionResumeUnconfirmed(
                SessionResumeAdmissionDiagnosticCodes.RESUME_LEASE_PUBLICATION_ACTIVE)
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
            if (!attrs.isRegularFile) fail(SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_IDENTITY_UNCONFIRMED)
            return attrs.fileKey()?.toString()
                ?: fail(SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_IDENTITY_UNCONFIRMED)
        } catch (e: SessionResumeUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw SessionResumeUnconfirmed(SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_IDENTITY_UNCONFIRMED, e)
        }
    }

    /**
     * The actual pre-lock filesystem stage of fenced admission. This is
     * deliberately read-only: even a successful return is not authorization.
     * A diagnostic worker may call it on a trusted host's existing store,
     * but must never continue into admit() or reuse the returned identity.
     */
    internal fun checkFilesystemBeforeLease(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        fileStoreType: (Path) -> String
    ): Pair<Path, String> {
        val ledgerKey = ledgerFileKey(ledgerPath)
        val dir = SessionCheckpointStore.checkedAdmissionDirectory(directory, context)
        ResumeLocalFileStorePolicy.requireSupportedPaths(dir, ledgerPath, fileStoreType)
        return dir to ledgerKey
    }

    fun currentGeneration(directory: Path, context: CheckpointHostContext,
                          ledgerPath: Path): Long =
        currentGenerationChecked(directory, context, ledgerPath, ResumeLocalFileStorePolicy.systemType)

    /** Deterministic filesystem fault seam; never used by production callers. */
    @LedgerTestFaultInjection
    internal fun currentGenerationWithFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        fileStoreType: (Path) -> String
    ): Long = currentGenerationChecked(directory, context, ledgerPath, fileStoreType)

    private fun currentGenerationChecked(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        fileStoreType: (Path) -> String
    ): Long = try {
        SessionCheckpointStore.withAdmissionEvidence(directory, context, ledgerPath,
            fileStoreType) { dir, _, _ ->
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
        reconciliationStatusChecked(directory, context, ledgerPath, ResumeLocalFileStorePolicy.systemType)

    /** Deterministic filesystem fault seam; never used by production callers. */
    @LedgerTestFaultInjection
    internal fun reconciliationStatusWithFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        fileStoreType: (Path) -> String
    ): ResumeReconciliationStatus? =
        reconciliationStatusChecked(directory, context, ledgerPath, fileStoreType)

    private fun reconciliationStatusChecked(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        fileStoreType: (Path) -> String
    ): ResumeReconciliationStatus? =
        SessionCheckpointStore.withAdmissionEvidence(directory, context, ledgerPath,
            fileStoreType) { checkedDir, verified, _ ->
            val head = ResumeFenceStorage.latest(checkedDir)
            ResumeFenceAnchor.recoveryStatus(ledgerPath, head, checkedDir, verified.sha256,
                verified.snapshot.binding.policySha256)
        }

    @OptIn(ResumeOperatorOnlyApi::class)
    fun admit(directory: Path, context: CheckpointHostContext, ledgerPath: Path,
              attestation: ResumeHostAttestation): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, null,
            fileStoreType = ResumeLocalFileStorePolicy.systemType) { }

    /**
     * Narrow, operator-only recovery: GENESIS (sequence zero) is independently
     * authenticated under the host key before an explicit v3 reconciliation
     * checkpoint and a successor fence can be durably published.
     *
     * No Intent/Started/Committed/Unconfirmed record is eligible. No external
     * effect, model call, or automatic replay is authorized by this API.
     */
    @ResumeOperatorOnlyApi
    fun reconcileGenesisEffectHead(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        sidecarPath: Path, hostKey: ByteArray,
        attestation: ResumeHostAttestation,
        operator: GenesisEffectHeadReconciliation
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, null,
            genesisOperator = operator, genesisSidecar = sidecarPath,
            genesisKey = hostKey, afterAnchorCommitted = {})

    /**
     * Resume after v3 was committed and the next immutable anchor PREPARE
     * exists, but its fence CAS has not committed. Requires BOTH exact
     * genesis HMAC evidence and the separate prepared-fence operator audit.
     * The normal genesis retry never assumes an orphan anchor is approved.
     */
    @ResumeOperatorOnlyApi
    fun reconcilePreparedGenesisEffectHead(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        sidecarPath: Path, hostKey: ByteArray,
        attestation: ResumeHostAttestation,
        genesis: GenesisEffectHeadReconciliation,
        prepared: PreparedFenceReconciliation
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, prepared,
            genesisOperator = genesis, genesisSidecar = sidecarPath,
            genesisKey = hostKey, afterAnchorCommitted = {})

    /** Test-only fault seam after successor anchor PREPARE, before fence CAS. */
    @ResumeOperatorOnlyApi
    @LedgerTestFaultInjection
    internal fun reconcileGenesisWithAnchorFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        sidecarPath: Path, hostKey: ByteArray,
        attestation: ResumeHostAttestation,
        operator: GenesisEffectHeadReconciliation,
        afterAnchorCommitted: () -> Unit
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, null,
            genesisOperator = operator, genesisSidecar = sidecarPath,
            genesisKey = hostKey, afterAnchorCommitted = afterAnchorCommitted)

    /** Test-only simulated crash after committed v3 and before fence PREPARE. */
    @ResumeOperatorOnlyApi
    @LedgerTestFaultInjection
    internal fun reconcileGenesisWithFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        sidecarPath: Path, hostKey: ByteArray,
        attestation: ResumeHostAttestation,
        operator: GenesisEffectHeadReconciliation,
        afterCheckpointCommitted: () -> Unit
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, null,
            genesisOperator = operator, genesisSidecar = sidecarPath,
            genesisKey = hostKey,
            afterGenesisCheckpointCommitted = afterCheckpointCommitted,
            afterAnchorCommitted = {})

    /** Explicit operator path: never selected by normal session admission. */
    @ResumeOperatorOnlyApi
    fun reconcilePreparedAdmission(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation, operator: PreparedFenceReconciliation
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, operator,
            fileStoreType = ResumeLocalFileStorePolicy.systemType) { }

    /** Integration-test-only fault seam, not an application API or service port.
     * Compiled in production only to support deterministic crash-boundary tests.
     */
    @LedgerTestFaultInjection
    @OptIn(ResumeOperatorOnlyApi::class)
    internal fun admitWithFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation,
        beforeInspection: () -> Unit = {},
        hardLinkProbe: (Path, String?) -> Unit = { directory, scope ->
            ResumeImmutablePublication.requireHardLinks(directory, scope)
        },
        fileStoreType: (Path) -> String = ResumeLocalFileStorePolicy.systemType,
        afterAnchorCommitted: () -> Unit
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, null,
            beforeInspection = beforeInspection, hardLinkProbe = hardLinkProbe,
            fileStoreType = fileStoreType, afterAnchorCommitted = afterAnchorCommitted)

    /** Integration-test-only fault seam for a crash after durable operator audit. */
    @LedgerTestFaultInjection
    @OptIn(ResumeOperatorOnlyApi::class)
    internal fun reconcilePreparedWithFault(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation, operator: PreparedFenceReconciliation,
        fileStoreType: (Path) -> String = ResumeLocalFileStorePolicy.systemType,
        afterAuditCommitted: () -> Unit
    ): SessionResumeLease =
        admitChecked(directory, context, ledgerPath, attestation, operator,
            fileStoreType = fileStoreType, afterAnchorCommitted = afterAuditCommitted)

    @OptIn(ResumeOperatorOnlyApi::class)
    private fun admitChecked(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        attestation: ResumeHostAttestation, operator: PreparedFenceReconciliation?,
        beforeInspection: () -> Unit = {},
        hardLinkProbe: (Path, String?) -> Unit = { directory, scope ->
            ResumeImmutablePublication.requireHardLinks(directory, scope)
        },
        fileStoreType: (Path) -> String = ResumeLocalFileStorePolicy.systemType,
        genesisOperator: GenesisEffectHeadReconciliation? = null,
        genesisSidecar: Path? = null,
        genesisKey: ByteArray? = null,
        afterGenesisCheckpointCommitted: () -> Unit = {},
        afterAnchorCommitted: () -> Unit
    ): SessionResumeLease {
        if (genesisOperator != null &&
            (genesisSidecar == null || genesisKey == null ||
                (operator != null && operator.operatorId != genesisOperator.operatorId)))
            fail(SessionResumeAdmissionDiagnosticCodes.RESUME_EFFECT_RECONCILIATION_INVALID)
        if (attestation.expectedFenceGeneration < 0)
            fail("RESUME_FENCE_EXPECTED_GENERATION_INVALID")
        if (operator != null && (!operator.operatorApproved ||
            !ToolAuthorizationPolicy.validHostIdentifier(operator.operatorId) ||
            !SHA.matches(operator.expectedPreparedFenceSha256) ||
            operator.expectedPreparedGeneration != attestation.expectedFenceGeneration + 1L))
            fail(ResumeDenialCodes.RESUME_FENCE_RECONCILIATION_UNCONFIRMED)
        val authoritySha256 = ResumeReadAuthority.fingerprint(context, attestation.readPolicyFacts)
        if (!attestation.externalActionsReconciled)
            fail("RESUME_EXTERNAL_EFFECTS_UNCONFIRMED")
        // This early pre-lock check is intentionally repeated inside
        // withAdmissionEvidence under resume.lock. Never remove the first one:
        // unsupported mounts must not cause resume.lock creation or fence changes.
        val (dir, ledgerKeyBeforeInspect) = checkFilesystemBeforeLease(
            directory, context, ledgerPath, fileStoreType)
        val lockPath = dir.resolve(RESUME_LOCK_NAME)
        if (!heldLocks.add(lockPath)) fail(SessionResumeAdmissionDiagnosticCodes.RESUME_LEASE_HELD)
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
            if (lock == null) fail(SessionResumeAdmissionDiagnosticCodes.RESUME_LEASE_HELD)
            if (created) {
                owner.force(true)
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            }
            val acquiredOwner = owner
            val acquiredLock = lock
            // Integration test seam: must run after key capture and before
            // actual ledger inspection, while the resume OS lock is held.
            beforeInspection()
            val commit: (Path, VerifiedSessionCheckpoint, VerifiedSessionEventLedger) ->
                SessionResumeLease = { checkedDir, verified, ledger ->
                val s = verified.snapshot
                // Only before the first model, tool, agent or recursive execution.
                if ((genesisOperator == null && verified.reconciliationRequired) ||
                    (genesisOperator != null &&
                        (s.effectReconciliation == null || s.effectJournalHead != null)) ||
                    !PreExecutionSnapshotPolicy.safe(s) ||
                    PreExecutionPhasePolicy.phaseFromVerifiedHistory(
                        s.phase, ledger.events) == null) fail(ResumeDenialCodes.RESUME_UNSAFE_BOUNDARY)
                val prior = ResumeFenceStorage.latest(checkedDir)
                val priorAnchor = if (operator == null)
                    ResumeFenceAnchor.verify(ledgerPath, prior, checkedDir) else null
                if ((prior?.generation ?: 0L) != attestation.expectedFenceGeneration)
                    fail(ResumeDenialCodes.RESUME_FENCE_CAS_FAILED)
                if (prior != null && (prior.sessionId != s.sessionId ||
                    prior.traceId != s.traceId ||
                    prior.policySha256 != s.binding.policySha256 ||
                    prior.toolAuthoritySha256 != authoritySha256))
                    fail(SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CONTEXT_CHANGED)
                val ledgerKey = ledgerFileKey(ledgerPath)
                if (ledgerKey != ledgerKeyBeforeInspect)
                    fail(SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_PATH_CHANGED)
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
                            fail(ResumeDenialCodes.RESUME_FENCE_RECONCILIATION_UNCONFIRMED)
                        ResumeFenceAnchor.verifyPrepared(ledgerPath, prior, prepared, checkedDir).also { anchor ->
                            ResumeFenceAnchor.recordOperatorApproval(ledgerPath, prepared,
                                anchor, operator)
                        }
                    }
                    afterAnchorCommitted()
                    // This must follow the test seam too, to cover an inode swap
                    // after a durable anchor/audit but before fence publication.
                    if (ledgerFileKey(ledgerPath) != ledgerKey)
                        fail(SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_PATH_CHANGED)
                }
                val anchor = preparedAnchor ?: fail(ResumeDenialCodes.RESUME_FENCE_ANCHOR_UNCONFIRMED)
                SessionResumeLease(next.generation, next.checkpointSha256,
                    next.toolAuthoritySha256, checkedDir, ledgerPath, next.sha256,
                    anchor.sha256, ledgerKey, lockPath, acquiredOwner,
                    acquiredLock) { heldLocks.remove(it) }
            }
            val result = if (genesisOperator == null)
                SessionCheckpointStore.withAdmissionEvidence(
                    dir, context, ledgerPath, fileStoreType, commit)
            else SessionCheckpointStore.withGenesisReconciliationEvidence(
                dir, context, ledgerPath,
                genesisSidecar ?: fail(SessionResumeAdmissionDiagnosticCodes.RESUME_EFFECT_RECONCILIATION_INVALID),
                genesisKey ?: fail(SessionResumeAdmissionDiagnosticCodes.RESUME_EFFECT_RECONCILIATION_INVALID),
                genesisOperator, operator, afterGenesisCheckpointCommitted, commit)
            transferred = true
            return result
        } catch (error: Exception) {
            if (error is SessionResumeUnconfirmed || error is CheckpointUnconfirmed) throw error
            throw SessionResumeUnconfirmed(ResumeDenialCodes.RESUME_ADMISSION_UNCONFIRMED, error)
        } finally {
            if (!transferred) {
                try { lock?.release() } catch (_: Exception) { /* fail closed */ }
                try { owner?.close() } catch (_: Exception) { /* fail closed */ }
                heldLocks.remove(lockPath)
            }
        }
    }
}

private const val FENCE_FORMAT_MARKER = "RCFEN1"

/** Diagnostic codes emitted by SessionResumeAdmission; not shared protocol vocabulary. */
internal object SessionResumeAdmissionDiagnosticCodes {
    const val RESUME_FENCE_LIMIT = "RESUME_FENCE_LIMIT"
    const val RESUME_FENCE_CLEANUP_UNCONFIRMED = "RESUME_FENCE_CLEANUP_UNCONFIRMED"
    const val RESUME_FENCE_CONTEXT_CHANGED = "RESUME_FENCE_CONTEXT_CHANGED"
    const val RESUME_LEDGER_PATH_CHANGED = "RESUME_LEDGER_PATH_CHANGED"
    const val RESUME_LEDGER_IDENTITY_UNCONFIRMED = "RESUME_LEDGER_IDENTITY_UNCONFIRMED"
    const val RESUME_EFFECT_RECONCILIATION_INVALID = "RESUME_EFFECT_RECONCILIATION_INVALID"
    const val RESUME_LEASE_HELD = "RESUME_LEASE_HELD"
    const val RESUME_LEASE_PUBLICATION_ACTIVE = "RESUME_LEASE_PUBLICATION_ACTIVE"
}
