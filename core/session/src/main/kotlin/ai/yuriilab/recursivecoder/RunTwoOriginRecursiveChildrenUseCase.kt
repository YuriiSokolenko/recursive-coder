package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * A single session may admit recursive work from two distinct, independently
 * authorized stage origins. Both roots use the same bounded scheduler/policy,
 * but have separate authority, child IDs, budget ledgers and receipt sources.
 *
 * The caller, never a model executor, supplies a trusted integration host for
 * each origin. This coordinator does NOT fabricate acceptance, grant workspace
 * writes, or alter the production five-role host's stage transitions.
 * Installed CLI host integration is a separate trust-boundary step.
 */
data class RecursiveOriginSource(
    val root: RecursiveRootGrant,
    val limits: RecursiveLimits,
    val integrationHost: RecursiveChildIntegrationHost,
    val executor: RecursiveStageExecutor
)

sealed interface TwoOriginRecursiveOutcome {
    data class Completed(
        val integrated: Map<StageKind, IntegratedChildResults>,
        val snapshots: Map<StageKind, RecursiveSnapshot>
    ) : TwoOriginRecursiveOutcome

    data class Stopped(val origin: StageKind?, val code: String) : TwoOriginRecursiveOutcome
}

/**
 * One ordered session-level transaction over two independently admitted trees.
 * Every tree must explicitly integrate its children; no root can be reported
 * complete on execution success alone. The second root never starts if the
 * first fails. Cross-origin path/ID conflicts are rejected rather than merged.
 *
 * Events are emitted through the same host-provided sink, allowing one
 * SessionEventJournal to correlate both origins. The journal owner MUST finish
 * and verify its checksum before publishing an overall successful result.
 */
class RunTwoOriginRecursiveChildrenUseCase(
    private val clock: Clock = Clock.systemUTC(),
    private val events: RecursiveEventSink = RecursiveEventSink { }
) {
    suspend fun execute(
        first: RecursiveOriginSource,
        second: RecursiveOriginSource
    ): TwoOriginRecursiveOutcome {
        val sources = listOf(first, second)
        val stages = sources.map { source ->
            source.root.graph.stages.singleOrNull {
                it.correlation.stageRunId == source.root.stageRunId &&
                    it.supersededBy == null
            }
        }
        if (stages.any { it == null }) {
            return TwoOriginRecursiveOutcome.Stopped(null, "INVALID_ORIGIN_PAIR")
        }
        val kinds = stages.map { requireNotNull(it).kind }
        if (kinds.toSet().size != 2 || StageKind.REVIEW in kinds ||
            sources.map { it.root.graph.correlation.graphId }.toSet().size != 2 ||
            sources.map { it.root.stageRunId }.toSet().size != 2 ||
            sources.map { it.root.graph.correlation.sessionId }.toSet().size != 1) {
            return TwoOriginRecursiveOutcome.Stopped(null, "INVALID_ORIGIN_PAIR")
        }

        val ordered = sources.zip(kinds).sortedBy { (_, kind) ->
            StageKind.entries.indexOf(kind)
        }
        val integrated = linkedMapOf<StageKind, IntegratedChildResults>()
        val snapshots = linkedMapOf<StageKind, RecursiveSnapshot>()
        val usedIds = mutableSetOf<ArtifactId>()
        val usedPaths = mutableSetOf<String>()
        for ((source, kind) in ordered) {
            val scheduler = try {
                RecursiveGraphScheduler(source.root, source.limits, clock,
                    events, source.integrationHost)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return TwoOriginRecursiveOutcome.Stopped(kind, "ORIGIN_NOT_READY")
            }
            val outcome = try {
                scheduler.execute(scheduler.rootGraphId, source.executor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return TwoOriginRecursiveOutcome.Stopped(kind, "ORIGIN_EXECUTION_UNCONFIRMED")
            }
            if (scheduler.eventDeliveryFailed) {
                return TwoOriginRecursiveOutcome.Stopped(kind, "EVENT_DELIVERY_FAILED")
            }
            when (outcome) {
                RecursiveExecutionOutcome.Completed -> Unit
                is RecursiveExecutionOutcome.Denied ->
                    return TwoOriginRecursiveOutcome.Stopped(kind, outcome.code)
                is RecursiveExecutionOutcome.Failed ->
                    return TwoOriginRecursiveOutcome.Stopped(kind, outcome.code)
                RecursiveExecutionOutcome.Cancelled ->
                    return TwoOriginRecursiveOutcome.Stopped(kind, "ORIGIN_CANCELLED")
                RecursiveExecutionOutcome.TimedOut ->
                    return TwoOriginRecursiveOutcome.Stopped(kind, "ORIGIN_TIMED_OUT")
            }
            val results = scheduler.integratedResults(scheduler.rootGraphId)
                ?: return TwoOriginRecursiveOutcome.Stopped(kind, "CHILD_RESULTS_NOT_INTEGRATED")
            if (results.childOrder.isEmpty() || results.artifacts.isEmpty()) {
                return TwoOriginRecursiveOutcome.Stopped(kind, "CHILD_RESULTS_MISSING")
            }
            if (results.artifacts.any {
                    !usedIds.add(it.manifest.id) || !usedPaths.add(it.manifest.declaredPath)
                }) {
                return TwoOriginRecursiveOutcome.Stopped(kind, "CROSS_ORIGIN_ARTIFACT_CONFLICT")
            }
            integrated[kind] = results
            snapshots[kind] = scheduler.current()
        }
        // Re-read both hosts only after the second tree completes. Otherwise
        // the second executor could invalidate the first tree's accepted bytes
        // while an earlier receipt snapshot still appears successful.
        for ((source, kind) in ordered) {
            val results = integrated.getValue(kind)
            val snapshot = snapshots.getValue(kind)
            val intact = try {
                val approved = source.integrationHost.acceptedArtifactIds()
                results.artifacts.all { receipt ->
                    val producer = snapshot.node(receipt.childGraphId)
                    val bytes = source.integrationHost.readBytes(receipt.manifest.declaredPath)
                    producer != null && bytes != null &&
                        receipt.manifest.id in approved &&
                        source.integrationHost.manifestsFor(producer)
                            ?.contains(receipt.manifest) == true &&
                        MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) } == receipt.observedSha256
                }
            } catch (_: Exception) { false }
            if (!intact) {
                return TwoOriginRecursiveOutcome.Stopped(kind, "CHILD_EVIDENCE_STALE")
            }
        }
        return TwoOriginRecursiveOutcome.Completed(integrated.toMap(), snapshots.toMap())
    }
}
