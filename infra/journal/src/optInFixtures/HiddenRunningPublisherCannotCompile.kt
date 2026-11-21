package boundary.fixtures

import ai.yuriilab.recursivecoder.*
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

// Must NOT compile across modules, even if the publisher's marker disappears.
fun cannotAccessInternalRunningPublisher(
    directory: Path,
    context: CheckpointHostContext,
    ledgerPath: Path,
    attestation: ResumeHostAttestation,
    accepted: VerifiedSessionCheckpoint,
    transition: JsonObject
) {
    DurableAcceptedRunningHandoff.publish(
        directory, context, ledgerPath, attestation, accepted, transition)
}
