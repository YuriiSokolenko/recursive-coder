package ai.yuriilab.recursivecoder

// Independent compiler-only verification of diagnostic-suppression shapes.
// This fixture must never be added to a production source collection.
@Suppress(ExternalSuppressionKeys.C3_ERROR)
internal fun constantSuppressedRunningPublication(
    runtime: DefaultRunSessionUseCase,
    request: SessionRequest,
    cancellation: Cancellation,
    host: TrustedSessionRunningHost
): TrustedSessionRunningReceipt =
    runtime.publishTrustedAcceptedRunning(request, cancellation, host)
