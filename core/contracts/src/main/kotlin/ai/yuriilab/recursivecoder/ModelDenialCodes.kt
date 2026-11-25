package ai.yuriilab.recursivecoder

/** Stable model diagnostic codes shared by policies and adapters. */
object ModelDenialCodes {
    const val UNSUPPORTED_MODEL = "UNSUPPORTED_MODEL"
    const val MODEL_CAPABILITY_UNSUPPORTED = "MODEL_CAPABILITY_UNSUPPORTED"
    const val MODEL_INVALID_RESPONSE = "MODEL_INVALID_RESPONSE"
    const val MODEL_REQUEST_BUDGET_EXCEEDED = "MODEL_REQUEST_BUDGET_EXCEEDED"
    const val TOKEN_BUDGET_EXCEEDED = "TOKEN_BUDGET_EXCEEDED"
}
