package ai.yuriilab.recursivecoder

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A read-only probe of ACTUAL host state, not a receipt or the journal.
 * Implementations belong to trusted, kind-specific host adapters; returning
 * a digest supplied by the model/journal is not independent verification.
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
    private val base = setOf("effectOperationId", "effectFenceGeneration",
        "effectKind", "effectInputSha256", "effectRecordSequence",
        "effectRecordMacSha256", "effectState")
    private val sha = Regex("[0-9a-f]{64}")
    private val toolEvents = setOf("tool.started", "tool.completed")
    private fun value(o: JsonObject, field: String): String? =
        (o[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(o: JsonObject, field: String): Long? =
        (o[field] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    /**
     * Returns null on any incomplete/ambiguous event, including an effect-like
     * field on a non-effect event. Only this method constructs the comparison
     * projection; callers cannot select a subset of authenticated entries.
     */
    fun fromSessionLedger(
        events: List<JsonObject>, physical: VerifiedEffectRecordJournal
    ): List<EffectJournalEntry>? {
        if (physical.tailKind != null || physical.tornTailOffset != null ||
            physical.entries.size.toLong() != physical.lastSequence)
            return null
        val output = mutableListOf<EffectJournalEntry>()
        var previousEventSequence = 0L
        for (event in events) {
            val metadata = event["metadata"] as? JsonObject ?: return null
            val type = value(event, "type") ?: return null
            val status = value(event, "status") ?: return null
            val effectKeys = metadata.keys.filter { it.startsWith("effect") }
            if (effectKeys.isEmpty()) {
                // No tool-effect entry may be silently omitted as an
                // unmarked tool-start/completion event.
                if (type in toolEvents) return null
                continue
            }
            val state = value(metadata, "effectState") ?: return null
            val extras = when (state) {
                "intent", "started" -> emptySet()
                "committed" -> setOf("effectObservedStateSha256")
                "unconfirmed" -> setOf("effectErrorCode")
                else -> return null
            }
            if (metadata.keys != base + extras) return null
            val expectedType = when (state) {
                "intent", "started" -> "tool.started"
                "committed" -> "tool.completed"
                else -> "session.error"
            }
            val expectedStatus = when (state) {
                "intent", "started" -> "started"
                "committed" -> "completed"
                else -> "failed"
            }
            if (type != expectedType || status != expectedStatus)
                return null
            val sessionId = value(event, "sessionId") ?: return null
            val eventSequence = number(event, "sequence") ?: return null
            val physicalSequence = number(metadata, "effectRecordSequence") ?: return null
            val fence = number(metadata, "effectFenceGeneration") ?: return null
            val operationId = value(metadata, "effectOperationId") ?: return null
            val kind = EffectKind.entries.firstOrNull {
                it.name == value(metadata, "effectKind")
            } ?: return null
            val input = value(metadata, "effectInputSha256") ?: return null
            val mac = value(metadata, "effectRecordMacSha256") ?: return null
            if (eventSequence <= previousEventSequence ||
                physicalSequence != output.size.toLong() + 1L ||
                !ToolAuthorizationPolicy.validHostIdentifier(sessionId) ||
                !ToolAuthorizationPolicy.validHostIdentifier(operationId) ||
                fence <= 0L || !sha.matches(input) || !sha.matches(mac) ||
                sessionId != physical.header.sessionId ||
                fence != physical.header.fenceGeneration)
                return null
            val identity = EffectIdentity(sessionId, operationId, fence, kind, input)
            val record = when (state) {
                "intent" -> EffectRecord.Intent(identity)
                "started" -> EffectRecord.Started(identity)
                "committed" -> {
                    val observed = value(metadata, "effectObservedStateSha256")
                        ?: return null
                    if (!sha.matches(observed)) return null
                    EffectRecord.Committed(identity, observed)
                }
                else -> {
                    val code = value(metadata, "effectErrorCode") ?: return null
                    if (!ToolAuthorizationPolicy.validHostIdentifier(code)) return null
                    EffectRecord.Unconfirmed(identity, code)
                }
            }
            val next = physical.entries.getOrNull(output.size) ?: return null
            if (next.sequence != physicalSequence ||
                next.record != record || next.hostMacSha256 != mac)
                return null
            output.add(EffectJournalEntry(eventSequence, record, mac))
            previousEventSequence = eventSequence
        }
        return output.takeIf { it.size == physical.entries.size }
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
    private val sha = Regex("[0-9a-f]{64}")
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
    ): Boolean {
        if (intent.identity != boundIdentity ||
            started.identity != boundIdentity ||
            committed.identity != boundIdentity ||
            !sha.matches(committed.observedStateSha256))
            return false
        val entries = recheck() ?: return false
        val records = entries.filter {
            it.record.identity.sessionId == boundIdentity.sessionId &&
                it.record.identity.operationId == boundIdentity.operationId
        }.map { it.record }
        if (records != listOf(intent, started, committed)) return false
        val actual = try {
            stateProbe.observedCommittedStateSha256(boundIdentity)
        } catch (_: Exception) { null }
        if (actual == null || !sha.matches(actual) ||
            actual != committed.observedStateSha256)
            return false
        // The probe is an independent read, not a durable no-start barrier;
        // keep the returned result informational only.
        return recheck() == entries
    }

    override fun independentlyVerifyNotStarted(
        intent: EffectRecord.Intent, proof: EffectNotStartedEvidence,
        currentFenceGeneration: Long
    ): Boolean = false
}
