package ai.yuriilab.recursivecoder

import java.time.Instant

/**
 * In-process contract for ONE role invocation. It does not extend Session Protocol v1,
 * authorize transitions, persist artifacts, or grant workspace/tool capabilities.
 */
data class StageRoleRequest(
    val graph: StageGraph,
    val stageRunId: StageRunId,
    /** Host-issued request identity, including for unknown stage IDs. */
    val requestId: String,
    val expectedRole: EngineeringRole,
    val hostAcceptedArtifactIds: Set<ArtifactId>,
    val model: ModelConfig,
    val instruction: String,
    val deadline: Instant,
    val cancellation: Cancellation,
    val requestedTools: Set<String> = emptySet(),
    val maxModelRequests: Int = 1
)

data class StageRoleUsage(
    val modelRequests: Int = 0,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null
)

/**
 * A proposed response is UNTRUSTED model text, never an accepted stage or artifact.
 * The host must validate it, materialize trusted artifacts, and re-run StageGraphPolicy.
 */
sealed interface StageRoleOutcome {
    data class Proposed(val text: String) : StageRoleOutcome
    data class Denied(val code: String) : StageRoleOutcome
    data class Failed(val code: String) : StageRoleOutcome
    data object Cancelled : StageRoleOutcome
    data object TimedOut : StageRoleOutcome
}

data class StageRoleResult(
    val correlation: StageCorrelation?,
    val role: EngineeringRole,
    val agentId: AgentId?,
    val outcome: StageRoleOutcome,
    val usage: StageRoleUsage
)

enum class StageRoleEventKind { STARTED, PROPOSED, DENIED, FAILED, CANCELLED, TIMED_OUT }

/** Metadata only: never include model text, prompts, secrets or raw provider errors. */
data class StageRoleEvent(
    val correlation: StageCorrelation,
    val role: EngineeringRole,
    /** Unknown stage or unassigned actor has no verified agent identity. */
    val agentId: AgentId?,
    val kind: StageRoleEventKind,
    val usage: StageRoleUsage,
    val code: String? = null
)

fun interface StageRoleEventSink {
    fun emit(event: StageRoleEvent)
}

interface StageRoleExecutor {
    suspend fun execute(request: StageRoleRequest, events: StageRoleEventSink): StageRoleResult
}
