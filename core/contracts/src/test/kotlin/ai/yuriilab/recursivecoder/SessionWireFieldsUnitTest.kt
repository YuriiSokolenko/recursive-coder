package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class SessionWireFieldsUnitTest {
    @Test fun v1KindsAndFieldsRemainStable() {
        assertEquals(listOf("session-request", "session-result", "session-event"),
            listOf(SessionEnvelopeKinds.REQUEST, SessionEnvelopeKinds.RESULT, SessionEnvelopeKinds.EVENT))
        assertEquals(listOf("kind", "version", "requestId", "sessionId", "traceId"),
            listOf(SessionEnvelopeFields.KIND, SessionEnvelopeFields.VERSION, SessionEnvelopeFields.REQUEST_ID, SessionEnvelopeFields.SESSION_ID, SessionEnvelopeFields.TRACE_ID))
        assertEquals(listOf("invocation", "workspace", "task", "model", "toolPolicy", "budgets", "outputs", "resume", "requiredCapabilities", "extensions"),
            listOf(SessionRequestFields.INVOCATION, SessionRequestFields.WORKSPACE, SessionRequestFields.TASK, SessionRequestFields.MODEL, SessionRequestFields.TOOL_POLICY, SessionRequestFields.BUDGETS, SessionRequestFields.OUTPUTS, SessionRequestFields.RESUME, SessionRequestFields.REQUIRED_CAPABILITIES, SessionRequestFields.EXTENSIONS))
        assertEquals(listOf("source", "invocationId", "attempt"),
            listOf(InvocationFields.SOURCE, InvocationFields.INVOCATION_ID, InvocationFields.ATTEMPT))
        assertEquals(listOf("workspaceId", "rootPath", "baselineSha", "headSha", "branch"),
            listOf(WorkspaceFields.WORKSPACE_ID, WorkspaceFields.ROOT_PATH, WorkspaceFields.BASELINE_SHA, WorkspaceFields.HEAD_SHA, WorkspaceFields.BRANCH))
        assertEquals(listOf("stage", "role", "taskId", "taskInputRef", "parentAgentId"),
            listOf(TaskInputFields.STAGE, TaskInputFields.ROLE, TaskInputFields.TASK_ID, TaskInputFields.TASK_INPUT_REF, TaskInputFields.PARENT_AGENT_ID))
        assertEquals(listOf("provider", "modelId", "baseUrl", "credentialRef", "contextTokens", "maxOutputTokens", "features", "temperature", "connectTimeoutMs", "requestTimeoutMs"),
            listOf(ModelConfigFields.PROVIDER, ModelConfigFields.MODEL_ID, ModelConfigFields.BASE_URL, ModelConfigFields.CREDENTIAL_REF, ModelConfigFields.CONTEXT_TOKENS, ModelConfigFields.MAX_OUTPUT_TOKENS, ModelConfigFields.FEATURES, ModelConfigFields.TEMPERATURE, ModelConfigFields.CONNECT_TIMEOUT_MS, ModelConfigFields.REQUEST_TIMEOUT_MS))
        assertEquals(listOf("allowedTools", "deniedTools", "protectedPaths", "writablePaths", "policySha256", "immutableInputs", "mode"),
            listOf(ToolPolicyFields.ALLOWED_TOOLS, ToolPolicyFields.DENIED_TOOLS, ToolPolicyFields.PROTECTED_PATHS, ToolPolicyFields.WRITABLE_PATHS, ToolPolicyFields.POLICY_SHA256, ToolPolicyFields.IMMUTABLE_INPUTS, ToolPolicyFields.MODE))
        assertEquals(listOf("deadlineAt", "maxWallTimeMs", "maxTotalTokens", "maxToolCalls", "maxChildAgents", "maxDepth", "maxModelRequests"),
            listOf(SessionBudgetFields.DEADLINE_AT, SessionBudgetFields.MAX_WALL_TIME_MS, SessionBudgetFields.MAX_TOTAL_TOKENS, SessionBudgetFields.MAX_TOOL_CALLS, SessionBudgetFields.MAX_CHILD_AGENTS, SessionBudgetFields.MAX_DEPTH, SessionBudgetFields.MAX_MODEL_REQUESTS))
        assertEquals(listOf("rootDir", "resultPath", "eventsPath", "checkpointDir", "diagnosticsPath"),
            listOf(SessionOutputFields.ROOT_DIR, SessionOutputFields.RESULT_PATH, SessionOutputFields.EVENTS_PATH, SessionOutputFields.CHECKPOINT_DIR, SessionOutputFields.DIAGNOSTICS_PATH))
        assertEquals(listOf("fenceGeneration", "mode"),
            listOf(SessionResumeFields.FENCE_GENERATION, SessionResumeFields.MODE))
    }
}
