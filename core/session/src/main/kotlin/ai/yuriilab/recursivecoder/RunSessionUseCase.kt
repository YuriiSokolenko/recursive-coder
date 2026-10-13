package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.JsonObject

/**
 * Published result of one session: a typed terminal outcome and the exact validated
 * Session Protocol v1 document written to result.json. Both values are immutable.
 *
 * Detailed budgets, diagnostic calculations and artifact hashes remain private
 * implementation types of the Session bounded context.
 */
data class SessionExecutionResult(
    val outcome: SessionOutcome,
    val protocol: JsonObject
)

/**
 * Execute a session to its verified, already-serialized terminal result.
 * No per-session mutable resources are shared between invocations.
 */
interface RunSessionUseCase {
    operator fun invoke(request: SessionRequest, cancellation: Cancellation): SessionExecutionResult
}
