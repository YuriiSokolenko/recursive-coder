package ai.yuriilab.recursivecoder

// Independent compiler-only verification of diagnostic-suppression shapes.
// This fixture must never be added to a production source collection.
@Suppress("OPT_IN_" + "USAGE_ERROR")
internal fun concatSuppressedRunningPublication(
    runtime: DefaultRunSessionUseCase,
    request: SessionRequest,
    cancellation: Cancellation,
    host: TrustedSessionRunningHost
): TrustedSessionRunningReceipt =
    runtime.publishTrustedAcceptedRunning(request, cancellation, host)
