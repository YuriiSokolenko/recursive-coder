package ai.yuriilab.recursivecoder

/** Stable Session Protocol v1 envelope discriminators. */
object SessionEnvelopeKinds {
    const val REQUEST = "session-request"
    const val RESULT = "session-result"
    const val EVENT = "session-event"
}

/** Session Envelope Fields in the Session Protocol v1 envelope. */
object SessionEnvelopeFields {
    const val KIND = "kind"
    const val VERSION = "version"
    const val REQUEST_ID = "requestId"
    const val SESSION_ID = "sessionId"
    const val TRACE_ID = "traceId"
}

/** Session Request Fields in the Session Protocol v1 envelope. */
object SessionRequestFields {
    const val INVOCATION = "invocation"
    const val WORKSPACE = "workspace"
    const val TASK = "task"
    const val MODEL = "model"
    const val TOOL_POLICY = "toolPolicy"
    const val BUDGETS = "budgets"
    const val OUTPUTS = "outputs"
    const val RESUME = "resume"
    const val REQUIRED_CAPABILITIES = "requiredCapabilities"
    const val EXTENSIONS = "extensions"
}

/** Invocation Fields in the Session Protocol v1 envelope. */
object InvocationFields {
    const val SOURCE = "source"
    const val INVOCATION_ID = "invocationId"
    const val ATTEMPT = "attempt"
}

/** Workspace Fields in the Session Protocol v1 envelope. */
object WorkspaceFields {
    const val WORKSPACE_ID = "workspaceId"
    const val ROOT_PATH = "rootPath"
    const val BASELINE_SHA = "baselineSha"
    const val HEAD_SHA = "headSha"
    const val BRANCH = "branch"
}

/** Task Input Fields in the Session Protocol v1 envelope. */
object TaskInputFields {
    const val STAGE = "stage"
    const val ROLE = "role"
    const val TASK_ID = "taskId"
    const val TASK_INPUT_REF = "taskInputRef"
    const val PARENT_AGENT_ID = "parentAgentId"
}

/** Model Config Fields in the Session Protocol v1 envelope. */
object ModelConfigFields {
    const val PROVIDER = "provider"
    const val MODEL_ID = "modelId"
    const val BASE_URL = "baseUrl"
    const val CREDENTIAL_REF = "credentialRef"
    const val CONTEXT_TOKENS = "contextTokens"
    const val MAX_OUTPUT_TOKENS = "maxOutputTokens"
    const val FEATURES = "features"
    const val TEMPERATURE = "temperature"
    const val CONNECT_TIMEOUT_MS = "connectTimeoutMs"
    const val REQUEST_TIMEOUT_MS = "requestTimeoutMs"
}

/** Tool Policy Fields in the Session Protocol v1 envelope. */
object ToolPolicyFields {
    const val ALLOWED_TOOLS = "allowedTools"
    const val DENIED_TOOLS = "deniedTools"
    const val PROTECTED_PATHS = "protectedPaths"
    const val WRITABLE_PATHS = "writablePaths"
    const val POLICY_SHA256 = "policySha256"
    const val IMMUTABLE_INPUTS = "immutableInputs"
    const val MODE = "mode"
}

/** Session Budget Fields in the Session Protocol v1 envelope. */
object SessionBudgetFields {
    const val DEADLINE_AT = "deadlineAt"
    const val MAX_WALL_TIME_MS = "maxWallTimeMs"
    const val MAX_TOTAL_TOKENS = "maxTotalTokens"
    const val MAX_TOOL_CALLS = "maxToolCalls"
    const val MAX_CHILD_AGENTS = "maxChildAgents"
    const val MAX_DEPTH = "maxDepth"
    const val MAX_MODEL_REQUESTS = "maxModelRequests"
}

/** Session Output Fields in the Session Protocol v1 envelope. */
object SessionOutputFields {
    const val ROOT_DIR = "rootDir"
    const val RESULT_PATH = "resultPath"
    const val EVENTS_PATH = "eventsPath"
    const val CHECKPOINT_DIR = "checkpointDir"
    const val DIAGNOSTICS_PATH = "diagnosticsPath"
}

/** Session Resume Fields in the Session Protocol v1 envelope. */
object SessionResumeFields {
    const val FENCE_GENERATION = "fenceGeneration"
    const val MODE = "mode"
}

/** Session Event Fields for Session Protocol v1. */
object SessionEventFields {
    const val EVENT_ID = "eventId"
    const val SEQUENCE = "sequence"
    const val TIMESTAMP = "timestamp"
    const val TYPE = "type"
    const val STATUS = "status"
    const val CORRELATION_ID = "correlationId"
    const val CAUSATION_ID = "causationId"
    const val ACTOR = "actor"
    const val TRANSITION = "transition"
    const val TIMING = "timing"
    const val REDACTION = "redaction"
    const val METADATA = "metadata"
}

/** Session Event Actor Fields for Session Protocol v1. */
object SessionEventActorFields {
    const val AGENT_ID = "agentId"
    const val PARENT_AGENT_ID = "parentAgentId"
    const val CHILD_AGENT_ID = "childAgentId"
    const val TOOL_EXECUTION_ID = "toolExecutionId"
    const val CHILD_SESSION_ID = "childSessionId"
}

/** Session Event Transition Fields for Session Protocol v1. */
object SessionEventTransitionFields {
    const val FROM = "from"
    const val TO = "to"
}

/** Session Event Timing Fields for Session Protocol v1. */
object SessionEventTimingFields {
    const val STARTED_AT = "startedAt"
    const val DURATION_MS = "durationMs"
}

/** Session Event Redaction Fields for Session Protocol v1. */
object SessionEventRedactionFields {
    const val MODE = "mode"
    const val REMOVED_FIELDS = "removedFields"
}

/** Session Artifact Reference Fields for Session Protocol v1. */
object SessionArtifactReferenceFields {
    const val PATH = "path"
    const val SHA256 = "sha256"
}

/** Session Resume Modes for Session Protocol v1. */
object SessionResumeModes {
    const val FRESH = "fresh"
    const val RESUME = "resume"
}

/** Stable metadata-only redaction mode emitted by v1 sessions. */
object SessionEventRedactionMode {
    const val METADATA_ONLY = "metadata-only"
}
