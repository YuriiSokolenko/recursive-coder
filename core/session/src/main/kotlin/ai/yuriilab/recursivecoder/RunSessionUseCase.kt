package ai.yuriilab.recursivecoder

/**
 * Execute one validated SessionRequest to a verified terminal result.
 * Cancellation belongs to this invocation; a Use Case instance owns no session state.
 */
interface RunSessionUseCase {
    operator fun invoke(request: SessionRequest, cancellation: Cancellation): SessionRunResult
}
