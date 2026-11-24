package ai.yuriilab.recursivecoder

/** Task-owned priority bounds and default; no scheduler attempt semantics. */
object TaskPriority {
    const val MIN = 0
    const val MAX = 100
    const val DEFAULT = 50
}

/** Task CLI projection field names; durable snapshot keys belong to TaskStore. */
object TaskWireFields {
    const val STATE = "state"
    const val PRIORITY = "priority"
    const val DEPENDENCIES = "dependencies"
    const val ATTEMPT = "attempt"
    const val PRUNED_TRANSITIONS = "prunedTransitions"
    const val SESSION_ID = "sessionId"
    const val LAST_RESULT_STATUS = "lastResultStatus"
    const val REASON = "reason"
    const val TRANSITIONS = "transitions"
}

/** Task CLI transition projection field names. */
object TaskTransitionWireFields {
    const val FROM = "from"
    const val TO = "to"
    const val AT = "at"
}
