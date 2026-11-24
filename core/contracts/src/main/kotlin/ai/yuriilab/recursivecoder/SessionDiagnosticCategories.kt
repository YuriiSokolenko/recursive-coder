package ai.yuriilab.recursivecoder

/** Session protocol v1 error categories; codes and messages are independent. */
object SessionDiagnosticCategories {
    const val POLICY = "policy"
    const val VALIDATION = "validation"
    const val PROVIDER = "provider"
    const val TOOL = "tool"
    const val INFRASTRUCTURE = "infrastructure"
    const val TIMEOUT = "timeout"
    const val CANCELLATION = "cancellation"
    const val CHECKPOINT = "checkpoint"
    const val PROTOCOL = "protocol"
}
