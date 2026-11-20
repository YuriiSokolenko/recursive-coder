package ai.yuriilab.recursivecoder

/**
 * Explicit opt-in for operator-owned pre-execution metadata publication.
 * This is NOT authority to resume, run a model, run tools or replay effects.
 * No untrusted CLI, model or normal session path should opt into this API.
 */
@RequiresOptIn(
    message = "Trusted host-only metadata handoff; not runtime execution authority (#104/#105).",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TrustedSessionPreExecutionApi

/**
 * Informational durable checkpoint acknowledgement, not an execution grant.
 * There is deliberately no Boolean 'admitted' flag or resume capability.
 */
data class TrustedSessionHandoffReceipt(
    val checkpointSequence: Long,
    val checkpointSha256: String
) {
    init {
        require(checkpointSequence > 1L)
        require(Regex("[a-f0-9]{64}").matches(checkpointSha256))
    }
}

/**
 * A trusted host implements this port with its own current Git, workspace,
 * policy and filesystem evidence. Request fields cannot authenticate C1.
 * The port may throw after C2 was committed; never retry on exception.
 */
@TrustedSessionPreExecutionApi
fun interface TrustedSessionPreExecutionHost {
    fun publishCreatedAccepted(request: SessionRequest): TrustedSessionHandoffReceipt
}
