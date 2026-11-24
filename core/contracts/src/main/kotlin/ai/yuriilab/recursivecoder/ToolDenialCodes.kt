package ai.yuriilab.recursivecoder

/** Stable tool diagnostic codes shared by policies and adapters. */
object ToolDenialCodes {
    const val UNSUPPORTED_TOOL_POLICY = "UNSUPPORTED_TOOL_POLICY"
    const val INVALID_TOOL_PATH = "INVALID_TOOL_PATH"
    const val ROLE_NOT_AUTHORIZED = "ROLE_NOT_AUTHORIZED"
    const val PROTECTED_PATH = "PROTECTED_PATH"
    const val SUBMODULE_BOUNDARY = "SUBMODULE_BOUNDARY"
    const val INVALID_WRITE_POLICY = "INVALID_WRITE_POLICY"
    const val FORGED_TOOL_ID = "FORGED_TOOL_ID"
    const val TOOL_QUOTA_EXCEEDED = "TOOL_QUOTA_EXCEEDED"
}
