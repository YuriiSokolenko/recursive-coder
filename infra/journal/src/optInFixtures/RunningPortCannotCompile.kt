package boundary.fixtures

import ai.yuriilab.recursivecoder.*

// Must NOT compile: even referencing the C3 host port requires opt-in.
fun cannotReferenceRunningPort(host: TrustedSessionRunningHost) {
    requireNotNull(host)
}
