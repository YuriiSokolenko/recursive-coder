package ai.yuriilab.recursivecoder

/**
 * Value-only #105 protocol. Neither these records nor a successful reconciliation
 * grant execution, checkpoint acceptance, a lease, or permission to retry an effect.
 * The trusted host owns identities, serialization, journal authentication and proofs.
 */
enum class EffectKind {
    TRUSTED_WRITE, ISOLATED_CHECK, GIT_CAS, ACCEPTANCE_HANDOFF, RESULT_PUBLICATION
}

/** The immutable operation identity, including the exact bytes of the proposed input. */
data class EffectIdentity(
    val sessionId: String,
    val operationId: String,
    val fenceGeneration: Long,
    val kind: EffectKind,
    val inputSha256: String
)

/**
 * The host must durably authenticate and force INTENT before STARTED, and
 * STARTED before invoking an external effect. An outcome is not inferred from
 * an exception, timeout, missing event, or model text.
 */
sealed interface EffectRecord {
    val identity: EffectIdentity

    data class Intent(override val identity: EffectIdentity) : EffectRecord
    data class Started(override val identity: EffectIdentity) : EffectRecord
    /** Host receipt of the observed post-effect state; independently recheck it on recovery. */
    data class Committed(
        override val identity: EffectIdentity,
        val observedStateSha256: String
    ) : EffectRecord
    /** A quarantined failure, not proof of non-execution. Only stable denial codes belong here. */
    data class Unconfirmed(
        override val identity: EffectIdentity,
        val code: String
    ) : EffectRecord
}

/**
 * The tag is an opaque proposed host MAC, NOT self-authenticating evidence.
 * A host implementation must authenticate canonical bytes (including sequence,
 * identity, record variant and all payload fields) with a secret unavailable to
 * the model, and verify the full durable journal prefix independently.
 */
data class EffectJournalEntry(
    val sequence: Long,
    val record: EffectRecord,
    val hostMacSha256: String
)

/** Only the external authenticated ledger may establish that this view is complete. */
data class EffectJournalView(
    val headSha256: String,
    val lastSequence: Long,
    val entries: List<EffectJournalEntry>
)

/**
 * Explicit host evidence that the effect could not have begun, not merely a
 * missing STARTED marker. Must bind an authoritative journal head AND an
 * independently checked execution barrier / external absence proof.
 */
data class EffectNotStartedEvidence(
    val identity: EffectIdentity,
    val journalHeadSha256: String,
    val journalLastSequence: Long,
    val observedStateSha256: String,
    val hostMacSha256: String
)

/**
 * Trusted-host port: never implement it from an agent response or by only
 * checking whether the supplied digest/MAC strings are well formed.
 *
 * authenticateCompleteJournal verifies the complete authoritative, ordered,
 * untampered prefix and authenticates every entry's MAC, not a caller-selected
 * subset. independentlyVerifyCommitted rechecks the actual external result
 * against the committed receipt. independentlyVerifyNotStarted must establish
 * that execution was impossible or that exact non-execution is independently
 * proven, and authenticate its head-bound evidence.
 *
 * A verifier exception or unavailable external proof fails closed.
 */
interface HostEffectEvidenceVerifier {
    fun authenticateCompleteJournal(view: EffectJournalView): Boolean
    fun independentlyVerifyCommitted(
        intent: EffectRecord.Intent,
        started: EffectRecord.Started,
        committed: EffectRecord.Committed
    ): Boolean
    fun independentlyVerifyNotStarted(
        intent: EffectRecord.Intent,
        proof: EffectNotStartedEvidence
    ): Boolean
}

/** Diagnostic classification only; no branch authorizes replay or mutation. */
sealed interface EffectRecoveryOutcome {
    data class ConfirmedCommitted(val receipt: EffectRecord.Committed) : EffectRecoveryOutcome
    data class ProvenNotStarted(val proof: EffectNotStartedEvidence) : EffectRecoveryOutcome
    data class Unconfirmed(val code: String) : EffectRecoveryOutcome
}

/**
 * I/O-free state machine over host-verified evidence. A missing STARTED or
 * COMMITTED record alone NEVER establishes absence or success. Any conflict,
 * reused operation ID, stale generation, malformed evidence, unsupported
 * transition or failed verification remains quarantined.
 */
object EffectReconciliationPolicy {
    private val sha256 = Regex("[0-9a-f]{64}")
    private const val MAX_ENTRIES = 10_000

    private fun valid(identity: EffectIdentity): Boolean =
        identity.fenceGeneration > 0L &&
            ToolAuthorizationPolicy.validHostIdentifier(identity.sessionId) &&
            ToolAuthorizationPolicy.validHostIdentifier(identity.operationId) &&
            sha256.matches(identity.inputSha256)

    private fun valid(entry: EffectJournalEntry): Boolean {
        val record = entry.record
        return entry.sequence > 0L && valid(record.identity) &&
            sha256.matches(entry.hostMacSha256) &&
            when (record) {
                is EffectRecord.Intent, is EffectRecord.Started -> true
                is EffectRecord.Committed -> sha256.matches(record.observedStateSha256)
                is EffectRecord.Unconfirmed ->
                    ToolAuthorizationPolicy.validHostIdentifier(record.code)
            }
    }

    private fun uncertain(code: String): EffectRecoveryOutcome =
        EffectRecoveryOutcome.Unconfirmed(code)

    private fun authenticated(
        verifier: HostEffectEvidenceVerifier, view: EffectJournalView
    ): Boolean = try {
        verifier.authenticateCompleteJournal(view)
    } catch (_: Exception) {
        false
    }

    fun reconcile(
        expected: EffectIdentity,
        currentFenceGeneration: Long,
        view: EffectJournalView,
        verifier: HostEffectEvidenceVerifier,
        notStartedProof: EffectNotStartedEvidence? = null
    ): EffectRecoveryOutcome {
        if (!valid(expected)) return uncertain("EFFECT_INVALID_IDENTITY")
        if (currentFenceGeneration <= 0L || expected.fenceGeneration != currentFenceGeneration)
            return uncertain("EFFECT_STALE_GENERATION")
        if (!sha256.matches(view.headSha256) || view.lastSequence <= 0L ||
            view.entries.size !in 1..MAX_ENTRIES ||
            view.entries.any { !valid(it) || it.sequence > view.lastSequence } ||
            view.entries.zipWithNext().any { (a, b) -> a.sequence >= b.sequence })
            return uncertain("EFFECT_INVALID_HISTORY")
        if (!authenticated(verifier, view))
            return uncertain("EFFECT_HISTORY_UNVERIFIED")

        // Operation IDs are unique within a session, even across generations
        // and effect kinds. Never silently select a newer duplicate.
        val entries = view.entries.filter {
            it.record.identity.sessionId == expected.sessionId &&
                it.record.identity.operationId == expected.operationId
        }
        if (entries.isEmpty()) return uncertain("EFFECT_INTENT_MISSING")
        if (entries.any { it.record.identity != expected })
            return uncertain("EFFECT_STALE_OR_REUSED_OPERATION")
        val records = entries.map { it.record }
        if (records.count { it is EffectRecord.Intent } != 1)
            return uncertain("EFFECT_DUPLICATE_OR_MISSING_INTENT")
        if (records.first() !is EffectRecord.Intent)
            return uncertain("EFFECT_INVALID_ORDER")
        if (records.any { it is EffectRecord.Unconfirmed })
            return uncertain("EFFECT_RECORDED_UNCONFIRMED")

        val intent = records.first() as EffectRecord.Intent
        if (records.size == 1) {
            val proof = notStartedProof ?: return uncertain("EFFECT_NOT_STARTED_UNPROVEN")
            if (proof.identity != expected ||
                proof.journalHeadSha256 != view.headSha256 ||
                proof.journalLastSequence != view.lastSequence ||
                !sha256.matches(proof.observedStateSha256) ||
                !sha256.matches(proof.hostMacSha256))
                return uncertain("EFFECT_NONSTART_PROOF_MISMATCH")
            val verified = try {
                verifier.independentlyVerifyNotStarted(intent, proof)
            } catch (_: Exception) { false }
            return if (verified) EffectRecoveryOutcome.ProvenNotStarted(proof)
                else uncertain("EFFECT_NOT_STARTED_UNPROVEN")
        }

        // A no-start certificate conflicting with any other stage is never
        // ignored, even if a committed receipt could otherwise be verified.
        if (notStartedProof != null)
            return uncertain("EFFECT_CONFLICTING_PROOF")
        if (records.size == 2 && records[1] is EffectRecord.Started)
            return uncertain("EFFECT_STARTED_WITHOUT_RECEIPT")
        if (records.size != 3 || records[1] !is EffectRecord.Started ||
            records[2] !is EffectRecord.Committed)
            return uncertain("EFFECT_INVALID_ORDER")

        val committed = records[2] as EffectRecord.Committed
        val verified = try {
            verifier.independentlyVerifyCommitted(intent,
                records[1] as EffectRecord.Started, committed)
        } catch (_: Exception) { false }
        return if (verified) EffectRecoveryOutcome.ConfirmedCommitted(committed)
            else uncertain("EFFECT_COMMIT_UNVERIFIED")
    }
}
