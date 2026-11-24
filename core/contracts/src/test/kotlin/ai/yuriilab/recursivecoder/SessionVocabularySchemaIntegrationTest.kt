package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The independent, checked-in v1 schema is the oracle; no schema generation from Kotlin. */
@Tag("integration")
class SessionVocabularySchemaIntegrationTest {
    private val schema = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("contracts/v1/session-v1.schema.json") }
        .first { Files.isRegularFile(it) }
    private val definitions = Json.parseToJsonElement(Files.readString(schema)).jsonObject
        .getValue("\$defs").jsonObject

    private fun properties(name: String) = definitions.getValue(name).jsonObject.getValue("properties").jsonObject
    private fun enumValues(property: JsonElement) =
        property.jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Test fun requestFieldsAndEnvelopeDiscriminatorsMatchSchema() {
        for ((definition, kind) in mapOf(
            "SessionRequest" to SessionEnvelopeKinds.REQUEST,
            "SessionResult" to SessionEnvelopeKinds.RESULT,
            "SessionEvent" to SessionEnvelopeKinds.EVENT
        )) {
            assertEquals(properties(definition).getValue("kind").jsonObject.getValue("const").jsonPrimitive.content, kind)
        }
        assertEquals(properties("SessionRequest").keys, setOf(SessionEnvelopeFields.KIND, SessionEnvelopeFields.VERSION, SessionEnvelopeFields.REQUEST_ID, SessionEnvelopeFields.SESSION_ID, SessionEnvelopeFields.TRACE_ID, SessionRequestFields.INVOCATION, SessionRequestFields.WORKSPACE, SessionRequestFields.TASK, SessionRequestFields.MODEL, SessionRequestFields.TOOL_POLICY, SessionRequestFields.BUDGETS, SessionRequestFields.OUTPUTS, SessionRequestFields.RESUME, SessionRequestFields.REQUIRED_CAPABILITIES, SessionRequestFields.EXTENSIONS), "SessionRequest fields")
        assertEquals(properties("Invocation").keys, setOf(InvocationFields.SOURCE, InvocationFields.INVOCATION_ID, InvocationFields.ATTEMPT), "Invocation fields")
        assertEquals(properties("Workspace").keys, setOf(WorkspaceFields.WORKSPACE_ID, WorkspaceFields.ROOT_PATH, WorkspaceFields.BASELINE_SHA, WorkspaceFields.HEAD_SHA, WorkspaceFields.BRANCH), "Workspace fields")
        assertEquals(properties("Role").keys, setOf(TaskInputFields.STAGE, TaskInputFields.ROLE, TaskInputFields.TASK_ID, TaskInputFields.TASK_INPUT_REF, TaskInputFields.PARENT_AGENT_ID), "Role fields")
        assertEquals(properties("Model").keys, setOf(ModelConfigFields.PROVIDER, ModelConfigFields.MODEL_ID, ModelConfigFields.BASE_URL, ModelConfigFields.CREDENTIAL_REF, ModelConfigFields.CONTEXT_TOKENS, ModelConfigFields.MAX_OUTPUT_TOKENS, ModelConfigFields.FEATURES, ModelConfigFields.TEMPERATURE, ModelConfigFields.CONNECT_TIMEOUT_MS, ModelConfigFields.REQUEST_TIMEOUT_MS), "Model fields")
        assertEquals(properties("ToolPolicy").keys, setOf(ToolPolicyFields.ALLOWED_TOOLS, ToolPolicyFields.DENIED_TOOLS, ToolPolicyFields.PROTECTED_PATHS, ToolPolicyFields.WRITABLE_PATHS, ToolPolicyFields.POLICY_SHA256, ToolPolicyFields.IMMUTABLE_INPUTS, ToolPolicyFields.MODE), "ToolPolicy fields")
        assertEquals(properties("Budgets").keys, setOf(SessionBudgetFields.DEADLINE_AT, SessionBudgetFields.MAX_WALL_TIME_MS, SessionBudgetFields.MAX_TOTAL_TOKENS, SessionBudgetFields.MAX_TOOL_CALLS, SessionBudgetFields.MAX_CHILD_AGENTS, SessionBudgetFields.MAX_DEPTH, SessionBudgetFields.MAX_MODEL_REQUESTS), "Budgets fields")
        assertEquals(properties("OutputLocations").keys, setOf(SessionOutputFields.ROOT_DIR, SessionOutputFields.RESULT_PATH, SessionOutputFields.EVENTS_PATH, SessionOutputFields.CHECKPOINT_DIR, SessionOutputFields.DIAGNOSTICS_PATH), "OutputLocations fields")
        assertEquals(properties("Resume").keys, setOf(SessionResumeFields.FENCE_GENERATION, SessionResumeFields.MODE, "checkpoint"), "Resume fields")
        assertEquals(properties("ArtifactRef").keys, setOf(SessionArtifactReferenceFields.PATH, SessionArtifactReferenceFields.SHA256), "ArtifactRef fields")
        assertEquals(enumValues(properties("Resume").getValue("mode")), setOf(SessionResumeModes.FRESH, SessionResumeModes.RESUME))
    }

    @Test fun eventFieldsAndVocabularyMatchSchema() {
        val event = properties("SessionEvent")
        // Event envelopes omit requestId. usage/error have separate ownership.
        assertEquals(event.keys, setOf(SessionEventFields.EVENT_ID, SessionEventFields.SEQUENCE, SessionEventFields.TIMESTAMP, SessionEventFields.TYPE, SessionEventFields.STATUS, SessionEventFields.CORRELATION_ID, SessionEventFields.CAUSATION_ID, SessionEventFields.ACTOR, SessionEventFields.TRANSITION, SessionEventFields.TIMING, SessionEventFields.REDACTION, SessionEventFields.METADATA, SessionEnvelopeFields.KIND, SessionEnvelopeFields.VERSION, SessionEnvelopeFields.SESSION_ID, SessionEnvelopeFields.TRACE_ID, SessionRequestFields.EXTENSIONS, "usage", "error"))
        assertEquals(enumValues(event.getValue("type")), setOf(SessionEventKinds.SESSION_TRANSITION, SessionEventKinds.SESSION_RESUMED, SessionEventKinds.AGENT_STARTED, SessionEventKinds.AGENT_COMPLETED, SessionEventKinds.LLM_CAPABILITIES, SessionEventKinds.LLM_REQUEST, SessionEventKinds.LLM_RESPONSE, SessionEventKinds.TOOL_STARTED, SessionEventKinds.TOOL_COMPLETED, SessionEventKinds.CHECKPOINT_SAVED, SessionEventKinds.POLICY_DENIED, SessionEventKinds.SESSION_ERROR, SessionEventKinds.REDACTION_APPLIED))
        assertEquals(enumValues(event.getValue("status")), setOf(SessionEventStatuses.STARTED, SessionEventStatuses.COMPLETED, SessionEventStatuses.FAILED, SessionEventStatuses.DENIED, SessionEventStatuses.CANCELLED, SessionEventStatuses.TIMED_OUT))
        assertEquals(properties("Actor").keys, setOf(SessionEventActorFields.AGENT_ID, SessionEventActorFields.PARENT_AGENT_ID, SessionEventActorFields.CHILD_AGENT_ID, SessionEventActorFields.TOOL_EXECUTION_ID, SessionEventActorFields.CHILD_SESSION_ID), "Actor fields")
        for ((field, keys) in mapOf(
            SessionEventFields.TRANSITION to setOf(SessionEventTransitionFields.FROM, SessionEventTransitionFields.TO),
            SessionEventFields.TIMING to setOf(SessionEventTimingFields.STARTED_AT, SessionEventTimingFields.DURATION_MS),
            SessionEventFields.REDACTION to setOf(SessionEventRedactionFields.MODE, SessionEventRedactionFields.REMOVED_FIELDS)
        )) {
            assertEquals(event.getValue(field).jsonObject.getValue("properties").jsonObject.keys, keys, field)
        }
        val modes = event.getValue("redaction").jsonObject.getValue("properties").jsonObject.getValue("mode")
        assertTrue(SessionEventRedactionMode.METADATA_ONLY in enumValues(modes))
    }
    @Test fun diagnosticCategoriesMatchSchema() {
        assertEquals(enumValues(properties("SessionError").getValue("category")), setOf(
            SessionDiagnosticCategories.POLICY,
            SessionDiagnosticCategories.VALIDATION,
            SessionDiagnosticCategories.PROVIDER,
            SessionDiagnosticCategories.TOOL,
            SessionDiagnosticCategories.INFRASTRUCTURE,
            SessionDiagnosticCategories.TIMEOUT,
            SessionDiagnosticCategories.CANCELLATION,
            SessionDiagnosticCategories.CHECKPOINT,
            SessionDiagnosticCategories.PROTOCOL
        ))
    }
}
