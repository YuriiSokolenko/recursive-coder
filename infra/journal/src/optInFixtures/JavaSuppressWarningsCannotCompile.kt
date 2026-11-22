package ai.yuriilab.recursivecoder

import java.lang.SuppressWarnings

// Standalone K2 check: does Java's annotation suppress a Kotlin opt-in error?
// The production source guard rejects this spelling regardless of outcome.
@SuppressWarnings("OPT_IN_" + "USAGE_ERROR")
internal fun javaSuppressWarningsRunningPublication(
    runtime: DefaultRunSessionUseCase,
    request: SessionRequest,
    cancellation: Cancellation,
    host: TrustedSessionRunningHost
): TrustedSessionRunningReceipt =
    runtime.publishTrustedAcceptedRunning(request, cancellation, host)
