package ai.yuriilab.recursivecoder

// Outside production sources: Kotlin 2.4 can compile suppressed error-level
// opt-in calls without referencing the TrustedRunningBoundaryApi marker.
// The architecture guard must deny these suppressions in production.
@Suppress("OPT_IN_USAGE_ERROR")
internal fun suppressedRunningPublication(
    runtime: DefaultRunSessionUseCase,
    request: SessionRequest,
    cancellation: Cancellation,
    host: TrustedSessionRunningHost
): TrustedSessionRunningReceipt =
    runtime.publishTrustedAcceptedRunning(request, cancellation, host)
