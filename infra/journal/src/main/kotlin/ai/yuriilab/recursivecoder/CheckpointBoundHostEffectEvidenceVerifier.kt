package ai.yuriilab.recursivecoder

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A read-only probe of ACTUAL host state, not a receipt or the journal.
 * Implementations belong to trusted, kind-specific host adapters; returning
 * a digest supplied by the model/journal is not independent verification.
 *
 * Before production wiring: register per-EffectKind trusted probes, resolve
 * targets from host-owned content-addressed inputs keyed by inputSha256,
 * specify a shared versioned canonical observed-state digest, and enforce
 * a host-side timeout/deadline. This port alone provides NONE of these.
 */
fun interface IndependentEffectStateProbe {
    fun observedCommittedStateSha256(identity: EffectIdentity): String?
}

/**
 * Complete RCLDG1 projection of physical RCEFX1 entries.
 *
 * Only a host-emitted event with the exact effect metadata field set is
 * interpreted as an effect. Every physical RCEFX1 record must have exactly
 * one corresponding event, in order, with the identical HMAC, identity and
 * record fields. A tool event lacking the effect envelope is ambiguous, so
 * this verifier denies it rather than guessing whether it was read-only.
 *
 * This is a STRICT reader, not a publication API. Adding the metadata names
 * to the durable ledger's allowlist does not allow production effect execution.
 */
internal object EffectEvidenceProjection {
    private val sha = Regex("[0-9a-f]{64}")
    private val toolEvents = setOf("tool.started", "tool.completed")
    private fun value(o: JsonObject, field: String): String? =
        (o[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(o: JsonObject, field: String): Long? =
        (o[field] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    sealed interface Result {
        data class Verified(val entries: List<EffectJournalEntry>) : Result
        /** Stable denial code, with no raw host paths, secrets or operation data. */
        data class Denied(val code: String) : Result
    }

    fun fromSessionLedger(
        events: List<JsonObject>, physical: VerifiedEffectRecordJournal
    ): List<EffectJournalEntry>? =
        (inspect(events, physical) as? Result.Verified)?.entries

    /**
     * Diagnostic classification only. An explicit denial code does NOT grant
     * an operator permission to ignore a failed physical authenticity check.
     */
    fun inspect(
        events: List<JsonObject>, physical: VerifiedEffectRecordJournal
    ): Result {
        fun deny(code: String): Result.Denied = Result.Denied(code)
        if (physical.tailKind != null || physical.tornTailOffset != null ||
            physical.entries.size.toLong() != physical.lastSequence)
            return deny("PROJECTION_PHYSICAL_INCOMPLETE")
        val output = mutableListOf<EffectJournalEntry>()
        var previousEventSequence = 0L
        for (event in events) {
            val type = value(event, "type") ?: return deny("PROJECTION_EVENT_INVALID")
            val status = value(event, "status") ?: return deny("PROJECTION_EVENT_INVALID")
            val metadata = event["metadata"] as? JsonObject
            if (metadata == null) {
                if (type in toolEvents) return deny("PROJECTION_UNMARKED_TOOL_EVENT")
                if ("metadata" in event) return deny("PROJECTION_METADATA_INVALID")
                continue
            }
            if (!EffectEvidenceEnvelopePolicy.hasReservedKey(metadata)) {
                if (type in toolEvents) return deny("PROJECTION_UNMARKED_TOOL_EVENT")
                continue
            }
            if (!EffectEvidenceEnvelopePolicy.validForEvent(type, status, metadata))
                return deny("PROJECTION_ENVELOPE_INVALID")
            val state = value(metadata, "effectState")
                ?: return deny("PROJECTION_ENVELOPE_INVALID")
            val sessionId = value(event, "sessionId")
                ?: return deny("PROJECTION_EVENT_INVALID")
            val eventSequence = number(event, "sequence")
                ?: return deny("PROJECTION_EVENT_INVALID")
            val physicalSequence = number(metadata, "effectRecordSequence")
                ?: return deny("PROJECTION_ENVELOPE_INVALID")
            val fence = number(metadata, "effectFenceGeneration")
                ?: return deny("PROJECTION_ENVELOPE_INVALID")
            val operationId = value(metadata, "effectOperationId")
                ?: return deny("PROJECTION_ENVELOPE_INVALID")
            val kind = EffectKind.entries.firstOrNull {
                it.name == value(metadata, "effectKind")
            } ?: return deny("PROJECTION_ENVELOPE_INVALID")
            val input = value(metadata, "effectInputSha256")
                ?: return deny("PROJECTION_ENVELOPE_INVALID")
            val mac = value(metadata, "effectRecordMacSha256")
                ?: return deny("PROJECTION_ENVELOPE_INVALID")
            if (eventSequence <= previousEventSequence ||
                physicalSequence != output.size.toLong() + 1L)
                return deny("PROJECTION_SEQUENCE_MISMATCH")
            if (sessionId != physical.header.sessionId ||
                fence != physical.header.fenceGeneration)
                return deny("PROJECTION_CONTEXT_MISMATCH")
            if (!ToolAuthorizationPolicy.validHostIdentifier(sessionId) ||
                !ToolAuthorizationPolicy.validHostIdentifier(operationId) ||
                fence <= 0L || !sha.matches(input) || !sha.matches(mac))
                return deny("PROJECTION_ENVELOPE_INVALID")
            val identity = EffectIdentity(sessionId, operationId, fence, kind, input)
            val record = when (state) {
                "intent" -> EffectRecord.Intent(identity)
                "started" -> EffectRecord.Started(identity)
                "committed" -> {
                    val observed = value(metadata, "effectObservedStateSha256")
                        ?: return deny("PROJECTION_ENVELOPE_INVALID")
                    if (!sha.matches(observed))
                        return deny("PROJECTION_ENVELOPE_INVALID")
                    EffectRecord.Committed(identity, observed)
                }
                else -> {
                    val code = value(metadata, "effectErrorCode")
                        ?: return deny("PROJECTION_ENVELOPE_INVALID")
                    if (!ToolAuthorizationPolicy.validHostIdentifier(code))
                        return deny("PROJECTION_ENVELOPE_INVALID")
                    EffectRecord.Unconfirmed(identity, code)
                }
            }
            val next = physical.entries.getOrNull(output.size)
                ?: return deny("PROJECTION_EXTRA_EVENT")
            if (next.sequence != physicalSequence)
                return deny("PROJECTION_SEQUENCE_MISMATCH")
            if (next.hostMacSha256 != mac)
                return deny("PROJECTION_MAC_MISMATCH")
            if (next.record != record)
                return deny("PROJECTION_RECORD_MISMATCH")
            // Event sequence is bound to RCEFX1 sequence/MAC by RCLDG1 SHA,
            // NOT by the RCEFX1 MAC alone.
            output.add(EffectJournalEntry(eventSequence, record, mac))
            previousEventSequence = eventSequence
        }
        return if (output.size == physical.entries.size) Result.Verified(output)
            else deny("PROJECTION_EXTRA_PHYSICAL")
    }
}

/**
 * Post-authentication classification, extracted for deterministic probe tests.
 * Production MUST supply ::recheck, which reauthenticates complete physical
 * sources on every invocation. This helper is not a recovery capability.
 * VM-fatal Errors intentionally escape; the host must abort/quarantine the
 * entire recovery attempt rather than continuing after one.
 */
internal object CommittedEffectStatePolicy {
    private val sha = Regex("[0-9a-f]{64}")

    fun verify(
        expected: EffectIdentity,
        intent: EffectRecord.Intent,
        started: EffectRecord.Started,
        committed: EffectRecord.Committed,
        authenticatedRead: () -> List<EffectJournalEntry>?,
        probe: IndependentEffectStateProbe
    ): Boolean {
        if (intent.identity != expected ||
            started.identity != expected ||
            committed.identity != expected ||
            !sha.matches(committed.observedStateSha256))
            return false
        val before = try { authenticatedRead() } catch (_: Exception) { null }
            ?: return false
        val records = before.filter {
            it.record.identity.sessionId == expected.sessionId &&
                it.record.identity.operationId == expected.operationId
        }.map { it.record }
        if (records != listOf(intent, started, committed)) return false
        val observed = try {
            probe.observedCommittedStateSha256(expected)
        } catch (_: Exception) {
            null
        }
        if (observed == null || !sha.matches(observed) ||
            observed != committed.observedStateSha256)
            return false
        val after = try { authenticatedRead() } catch (_: Exception) { null }
        return after != null && after == before
    }
}

/**
 * Read-only, closed-world host verifier:
 * - live lease with authentic latest fence and anchor;
 * - SHA-chained checkpoint and its pinned RCLDG1 prefix;
 * - complete HMAC-verified, exact-pin RCEFX1 sidecar;
 * - all RCLDG1 effect envelopes, in order, matching ALL RCEFX1 records;
 * - independent actual host state for any Committed classification.
 *
 * ONLY the live issuing generation N is supported. A successor N+1
 * lease must fail closed; authenticating historical N -> N+1 authority
 * is a separate future protocol, not implemented by this verifier.
 *
 * It does NOT verify an execution/no-start barrier. Consequently
 * independentlyVerifyNotStarted ALWAYS returns false. Current pre-execution
 * checkpoint/ledger policies prevent this adapter from being used as a real
 * external-effect executor; do not weaken them to make verification succeed.
 * Neither authentication nor a positive diagnostic grants permission to act.
 *
 * The declared view and identity must be frozen by the trusted caller before
 * invocation. Each method re-reads the physical sources independently:
 * a prior authenticateCompleteJournal(true) is not a reusable capability.
 * Coherent rollback of all source files remains unsolved.
 * Caller must zero its original hostKey. Only this adapter's copy is cleared.
 */
@ResumeOperatorOnlyApi
class CheckpointBoundHostEffectEvidenceVerifier(
    private val directory: Path,
    private val context: CheckpointHostContext,
    private val ledgerPath: Path,
    private val sidecarPath: Path,
    hostKey: ByteArray,
    private val lease: SessionResumeLease,
    private val boundIdentity: EffectIdentity,
    private val boundView: EffectJournalView,
    private val stateProbe: IndependentEffectStateProbe
) : HostEffectEvidenceVerifier, AutoCloseable {
    private val key = hostKey.copyOf()
    private var closed = false

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
    }

    @Synchronized private fun recheck(): List<EffectJournalEntry>? {
        if (closed) return null
        return try {
        lease.assertCurrent()
        val checkpoint = SessionCheckpointStore.inspect(directory, context, ledgerPath)
        val head = checkpoint.snapshot.effectJournalHead ?: return null
        if (lease.generation != head.fenceGeneration ||
            head.sessionId != boundIdentity.sessionId ||
            head.fenceGeneration != boundIdentity.fenceGeneration)
            return null
        val sessionEvents = DurableSessionEventLedger.inspect(ledgerPath)
        if (boundView.headSha256 != sessionEvents.headSha256 ||
            boundView.lastSequence != sessionEvents.lastSequence.toLong() ||
            checkpoint.snapshot.journalHeadSha256 != sessionEvents.headSha256 ||
            checkpoint.snapshot.journalSequence.toLong() != sessionEvents.lastSequence.toLong())
            return null
        val physical = SessionCheckpointStore.inspectEffectHeadBound(
            directory, context, ledgerPath, sidecarPath, key)
        val observed = EffectEvidenceProjection.fromSessionLedger(
            sessionEvents.events, physical) ?: return null
        if (observed != boundView.entries) return null
        // Do not allow a concurrent checkpoint publication or expired lease
        // to convert a stale positive into host evidence.
        lease.assertCurrent()
        val after = SessionCheckpointStore.inspect(directory, context, ledgerPath)
        if (after.sha256 != checkpoint.sha256) return null
        observed
    } catch (_: Exception) {
        null // All unavailable / corrupt / mismatched evidence fails closed.
    }
    }

    override fun authenticateCompleteJournal(
        view: EffectJournalView, expected: EffectIdentity,
        currentFenceGeneration: Long
    ): Boolean =
        expected == boundIdentity && view == boundView &&
            currentFenceGeneration == lease.generation && recheck() != null

    override fun independentlyVerifyCommitted(
        intent: EffectRecord.Intent, started: EffectRecord.Started,
        committed: EffectRecord.Committed
    ): Boolean = CommittedEffectStatePolicy.verify(
        boundIdentity, intent, started, committed, ::recheck, stateProbe)

    override fun independentlyVerifyNotStarted(
        intent: EffectRecord.Intent, proof: EffectNotStartedEvidence,
        currentFenceGeneration: Long
    ): Boolean = false
}
