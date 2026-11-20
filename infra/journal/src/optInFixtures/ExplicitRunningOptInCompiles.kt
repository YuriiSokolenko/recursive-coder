package boundary.fixtures

import ai.yuriilab.recursivecoder.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

// Must compile. Both independent opt-in capabilities are explicit; neither
// receipt is interchangeable with the other.
@OptIn(TrustedPreExecutionHandoff::class, TrustedRunningBoundaryApi::class)
fun separatelyAuthorizedHostCallersCompile(
    checkpointDirectory: Path,
    ledgerPath: Path,
    context: () -> CheckpointHostContext,
    attestation: () -> ResumeHostAttestation,
    request: SessionRequest,
    cancellation: Cancellation,
    deadline: Instant,
    clock: Clock
) {
    val accepted: TrustedSessionHandoffReceipt =
        DurableRuntimeCreatedAcceptedHost(
            checkpointDirectory, ledgerPath, context, attestation
        ).publishCreatedAccepted(request, cancellation, deadline, clock)

    val running: TrustedSessionRunningReceipt =
        DurableRuntimeAcceptedRunningHost(
            checkpointDirectory, ledgerPath, context, attestation
        ).publishAcceptedRunning(request, cancellation, deadline, clock)

    require(accepted.checkpointSequence > 0 && running.checkpointSequence > 0)
}
