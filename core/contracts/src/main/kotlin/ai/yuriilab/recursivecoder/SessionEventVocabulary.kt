package ai.yuriilab.recursivecoder

/** Session Protocol v1 event kinds, distinct from lifecycle phases and outcomes. */
object SessionEventKinds {
    const val SESSION_TRANSITION = "session.transition"
    const val SESSION_RESUMED = "session.resumed"
    const val AGENT_STARTED = "agent.started"
    const val AGENT_COMPLETED = "agent.completed"
    const val LLM_CAPABILITIES = "llm.capabilities"
    const val LLM_REQUEST = "llm.request"
    const val LLM_RESPONSE = "llm.response"
    const val TOOL_STARTED = "tool.started"
    const val TOOL_COMPLETED = "tool.completed"
    const val CHECKPOINT_SAVED = "checkpoint.saved"
    const val POLICY_DENIED = "policy.denied"
    const val SESSION_ERROR = "session.error"
    const val REDACTION_APPLIED = "redaction.applied"
}

/** Status of an individual event, not the terminal session result. */
object SessionEventStatuses {
    const val STARTED = "started"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val DENIED = "denied"
    const val CANCELLED = "cancelled"
    const val TIMED_OUT = "timed_out"
}
