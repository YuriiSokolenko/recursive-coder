package boundary.fixtures

import ai.yuriilab.recursivecoder.*

// Must NOT compile: both port references and the runtime method require opt-in.
fun cannotCallRunningRuntime(
    runtime: DefaultRunSessionUseCase,
    request: SessionRequest,
    cancellation: Cancellation,
    host: TrustedSessionRunningHost
) {
    runtime.publishTrustedAcceptedRunning(request, cancellation, host)
}
