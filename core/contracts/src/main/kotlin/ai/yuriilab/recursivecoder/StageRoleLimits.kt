package ai.yuriilab.recursivecoder

/** Fixed host-side limits for all implementations of StageRoleExecutor. */
object StageRoleLimits {
    const val MAX_INSTRUCTION_BYTES: Int = 16_384
    const val MAX_PROPOSAL_BYTES: Int = 32_768
}
