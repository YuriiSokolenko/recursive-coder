package boundary.fixtures

import ai.yuriilab.recursivecoder.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

// Must NOT compile. A caller opted in only to C1/C2 may neither call RUNNING
// on that host, nor instantiate the independently protected C3 host.
@OptIn(TrustedPreExecutionHandoff::class)
fun preExecutionOnlyCannotPublishRunning(
    checkpointDirectory: Path,
    ledgerPath: Path,
    context: () -> CheckpointHostContext,
    attestation: () -> ResumeHostAttestation,
    request: SessionRequest,
    cancellation: Cancellation,
    deadline: Instant,
    clock: Clock
) {
    val preExecution = DurableRuntimeCreatedAcceptedHost(
        checkpointDirectory, ledgerPath, context, attestation)
    preExecution.publishAcceptedRunning(request, cancellation, deadline, clock)

    val running = DurableRuntimeAcceptedRunningHost(
        checkpointDirectory, ledgerPath, context, attestation)
    running.publishAcceptedRunning(request, cancellation, deadline, clock)
}
