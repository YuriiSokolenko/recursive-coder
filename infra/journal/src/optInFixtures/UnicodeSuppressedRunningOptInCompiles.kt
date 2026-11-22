package ai.yuriilab.recursivecoder

// Independent compiler-only verification of diagnostic-suppression shapes.
// This fixture must never be added to a production source collection.
@Suppress("OPT_IN_USAGE\u005FERROR")
internal fun unicodeSuppressedRunningPublication(
    runtime: DefaultRunSessionUseCase,
    request: SessionRequest,
    cancellation: Cancellation,
    host: TrustedSessionRunningHost
): TrustedSessionRunningReceipt =
    runtime.publishTrustedAcceptedRunning(request, cancellation, host)
