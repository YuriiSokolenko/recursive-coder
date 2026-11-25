package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class SharedDenialVocabularyUnitTest {
    @Test fun sessionDenialCodesRetainSpelling() {
        assertEquals("UNSUPPORTED_RESUME", SessionDenialCodes.UNSUPPORTED_RESUME)
    }

    @Test fun modelDenialCodesRetainSpelling() {
        assertEquals("UNSUPPORTED_MODEL", ModelDenialCodes.UNSUPPORTED_MODEL)
        assertEquals("MODEL_CAPABILITY_UNSUPPORTED", ModelDenialCodes.MODEL_CAPABILITY_UNSUPPORTED)
        assertEquals("MODEL_INVALID_RESPONSE", ModelDenialCodes.MODEL_INVALID_RESPONSE)
        assertEquals("MODEL_REQUEST_BUDGET_EXCEEDED", ModelDenialCodes.MODEL_REQUEST_BUDGET_EXCEEDED)
        assertEquals("TOKEN_BUDGET_EXCEEDED", ModelDenialCodes.TOKEN_BUDGET_EXCEEDED)
    }

    @Test fun workspaceDenialCodesRetainSpelling() {
        assertEquals("INVALID_WORKSPACE", WorkspaceDenialCodes.INVALID_WORKSPACE)
        assertEquals("UNSUPPORTED_WORKSPACE", WorkspaceDenialCodes.UNSUPPORTED_WORKSPACE)
        assertEquals("GIT_UNAVAILABLE", WorkspaceDenialCodes.GIT_UNAVAILABLE)
        assertEquals("UNSAFE_GIT_CONFIGURATION", WorkspaceDenialCodes.UNSAFE_GIT_CONFIGURATION)
        assertEquals("WORKSPACE_BASELINE_MISMATCH", WorkspaceDenialCodes.WORKSPACE_BASELINE_MISMATCH)
        assertEquals("SANDBOX_UNAVAILABLE", WorkspaceDenialCodes.SANDBOX_UNAVAILABLE)
        assertEquals("UNSUPPORTED_GIT_TREE", WorkspaceDenialCodes.UNSUPPORTED_GIT_TREE)
        assertEquals("WORKSPACE_LOCK_UNAVAILABLE", WorkspaceDenialCodes.WORKSPACE_LOCK_UNAVAILABLE)
        assertEquals("WORKSPACE_LOCKED", WorkspaceDenialCodes.WORKSPACE_LOCKED)
        assertEquals("GIT_CONFIGURATION_CHANGED", WorkspaceDenialCodes.GIT_CONFIGURATION_CHANGED)
        assertEquals("WORKSPACE_REPLACED", WorkspaceDenialCodes.WORKSPACE_REPLACED)
        assertEquals("UNSAFE_PATH_COMPONENT", WorkspaceDenialCodes.UNSAFE_PATH_COMPONENT)
        assertEquals("UNSAFE_FILE", WorkspaceDenialCodes.UNSAFE_FILE)
        assertEquals("BINARY_FILE_DENIED", WorkspaceDenialCodes.BINARY_FILE_DENIED)
    }

    @Test fun toolDenialCodesRetainSpelling() {
        assertEquals("FORGED_TOOL_ID", ToolDenialCodes.FORGED_TOOL_ID)
        assertEquals("TOOL_QUOTA_EXCEEDED", ToolDenialCodes.TOOL_QUOTA_EXCEEDED)
        assertEquals("UNSUPPORTED_TOOL_POLICY", ToolDenialCodes.UNSUPPORTED_TOOL_POLICY)
        assertEquals("INVALID_TOOL_PATH", ToolDenialCodes.INVALID_TOOL_PATH)
        assertEquals("ROLE_NOT_AUTHORIZED", ToolDenialCodes.ROLE_NOT_AUTHORIZED)
        assertEquals("PROTECTED_PATH", ToolDenialCodes.PROTECTED_PATH)
        assertEquals("SUBMODULE_BOUNDARY", ToolDenialCodes.SUBMODULE_BOUNDARY)
        assertEquals("INVALID_WRITE_POLICY", ToolDenialCodes.INVALID_WRITE_POLICY)
    }

    @Test fun stageDenialCodesRetainSpelling() {
        assertEquals("MISSING_STAGE_ACTOR", StageDenialCodes.MISSING_STAGE_ACTOR)
        assertEquals("UNKNOWN_ARTIFACT", StageDenialCodes.UNKNOWN_ARTIFACT)
        assertEquals("INVALID_STAGE_OUTPUT", StageDenialCodes.INVALID_STAGE_OUTPUT)
        assertEquals("INVALID_REQUEST_ID", StageDenialCodes.INVALID_REQUEST_ID)
        assertEquals("REQUEST_CORRELATION_MISMATCH", StageDenialCodes.REQUEST_CORRELATION_MISMATCH)
        assertEquals("RECOVERY_INTERRUPTED", StageDenialCodes.RECOVERY_INTERRUPTED)
        assertEquals("RECOVERY_UNCONFIRMED", StageDenialCodes.RECOVERY_UNCONFIRMED)
        assertEquals("STAGE_FILE_TOO_LARGE", StageDenialCodes.STAGE_FILE_TOO_LARGE)
        assertEquals("UNKNOWN_STAGE", StageDenialCodes.UNKNOWN_STAGE)
        assertEquals("STAGE_NOT_READY", StageDenialCodes.STAGE_NOT_READY)
        assertEquals("STAGE_AUTHORITY_UNAVAILABLE", StageDenialCodes.STAGE_AUTHORITY_UNAVAILABLE)
        assertEquals("STALE_STAGE_AUTHORITY", StageDenialCodes.STALE_STAGE_AUTHORITY)
        assertEquals("ROLE_TIMED_OUT", StageDenialCodes.ROLE_TIMED_OUT)
        assertEquals("ROLE_CANCELLED", StageDenialCodes.ROLE_CANCELLED)
        assertEquals("EMPTY_STAGE_INSTRUCTION", StageDenialCodes.EMPTY_STAGE_INSTRUCTION)
        assertEquals("ARTIFACT_MISSING", StageDenialCodes.ARTIFACT_MISSING)
        assertEquals("ARTIFACT_ENCODING_INVALID", StageDenialCodes.ARTIFACT_ENCODING_INVALID)
        assertEquals("ARTIFACT_DIGEST_MISMATCH", StageDenialCodes.ARTIFACT_DIGEST_MISMATCH)
        assertEquals("STAGE_FINISH_UNCONFIRMED", StageDenialCodes.STAGE_FINISH_UNCONFIRMED)
        assertEquals("STALE_STAGE_LEASE", StageDenialCodes.STALE_STAGE_LEASE)
        assertEquals("STAGE_ABORT_UNCONFIRMED", StageDenialCodes.STAGE_ABORT_UNCONFIRMED)
        assertEquals("INPUT_READ_UNAVAILABLE", StageDenialCodes.INPUT_READ_UNAVAILABLE)
        assertEquals("STAGE_INPUT_TOO_LARGE", StageDenialCodes.STAGE_INPUT_TOO_LARGE)
        assertEquals("STAGE_BEGIN_UNCONFIRMED", StageDenialCodes.STAGE_BEGIN_UNCONFIRMED)
        assertEquals("STAGE_ADMISSION_REVOKED", StageDenialCodes.STAGE_ADMISSION_REVOKED)
        assertEquals("INVALID_STAGE_LEASE", StageDenialCodes.INVALID_STAGE_LEASE)
        assertEquals("ROLE_RESULT_IDENTITY_MISMATCH", StageDenialCodes.ROLE_RESULT_IDENTITY_MISMATCH)
        assertEquals("STAGE_AUTHORITY_MISMATCH", StageDenialCodes.STAGE_AUTHORITY_MISMATCH)
        assertEquals("STAGE_CHECKS_REQUIRED", StageDenialCodes.STAGE_CHECKS_REQUIRED)
        assertEquals("STAGE_WRITE_FAILED", StageDenialCodes.STAGE_WRITE_FAILED)
        assertEquals("STAGE_CHECK_FAILED", StageDenialCodes.STAGE_CHECK_FAILED)
        assertEquals("STAGE_TOOL_VALIDATION_FAILED", StageDenialCodes.STAGE_TOOL_VALIDATION_FAILED)
        assertEquals("STAGE_VALIDATION_FAILED", StageDenialCodes.STAGE_VALIDATION_FAILED)
        assertEquals("OUTPUT_READ_UNAVAILABLE", StageDenialCodes.OUTPUT_READ_UNAVAILABLE)
        assertEquals("HOST_APPROVAL_UNAVAILABLE", StageDenialCodes.HOST_APPROVAL_UNAVAILABLE)
        assertEquals("HOST_REJECTED", StageDenialCodes.HOST_REJECTED)
        assertEquals("INVALID_STAGE_MANIFEST", StageDenialCodes.INVALID_STAGE_MANIFEST)
        assertEquals("STAGE_ACCEPTANCE_DENIED", StageDenialCodes.STAGE_ACCEPTANCE_DENIED)
        assertEquals("INVALID_DEVELOPER_HANDOFF", StageDenialCodes.INVALID_DEVELOPER_HANDOFF)
        assertEquals("UNVERIFIED_DEVELOPER_EVIDENCE", StageDenialCodes.UNVERIFIED_DEVELOPER_EVIDENCE)
        assertEquals("MISSING_DEVELOPER_ACTOR", StageDenialCodes.MISSING_DEVELOPER_ACTOR)
        assertEquals("MISSING_REVIEWER_ACTOR", StageDenialCodes.MISSING_REVIEWER_ACTOR)
        assertEquals("FROZEN_INPUT_OR_SOURCE_CHANGED", StageDenialCodes.FROZEN_INPUT_OR_SOURCE_CHANGED)
        assertEquals("HUMAN_APPROVAL_REQUIRED", StageDenialCodes.HUMAN_APPROVAL_REQUIRED)
        assertEquals("STAGE_TOOL_UNAVAILABLE", StageDenialCodes.STAGE_TOOL_UNAVAILABLE)
    }

    @Test fun recursiveDenialCodesRetainSpelling() {
        assertEquals("ORIGIN_CANCELLED", RecursiveDenialCodes.ORIGIN_CANCELLED)
        assertEquals("ORIGIN_EXECUTION_UNCONFIRMED", RecursiveDenialCodes.ORIGIN_EXECUTION_UNCONFIRMED)
        assertEquals("UNKNOWN_PARENT", RecursiveDenialCodes.UNKNOWN_PARENT)
        assertEquals("PARENT_NOT_RUNNING", RecursiveDenialCodes.PARENT_NOT_RUNNING)
        assertEquals("GLOBAL_DEADLINE", RecursiveDenialCodes.GLOBAL_DEADLINE)
        assertEquals("STAGE_DEADLINE", RecursiveDenialCodes.STAGE_DEADLINE)
        assertEquals("REPEATED_REQUEST", RecursiveDenialCodes.REPEATED_REQUEST)
        assertEquals("UNKNOWN_CHILD_ARTIFACT", RecursiveDenialCodes.UNKNOWN_CHILD_ARTIFACT)
        assertEquals("CHILD_ARTIFACT_MISSING", RecursiveDenialCodes.CHILD_ARTIFACT_MISSING)
        assertEquals("CHILD_ARTIFACT_DIGEST_MISMATCH", RecursiveDenialCodes.CHILD_ARTIFACT_DIGEST_MISMATCH)
        assertEquals("CHILD_RESULTS_NOT_INTEGRATED", RecursiveDenialCodes.CHILD_RESULTS_NOT_INTEGRATED)
        assertEquals("STAGE_OWNERSHIP_MISMATCH", RecursiveDenialCodes.STAGE_OWNERSHIP_MISMATCH)
        assertEquals("CHILD_INTEGRATION_STALE", RecursiveDenialCodes.CHILD_INTEGRATION_STALE)
        assertEquals("TOKEN_BUDGET_EXHAUSTED", RecursiveDenialCodes.TOKEN_BUDGET_EXHAUSTED)
        assertEquals("TOOL_BUDGET_EXHAUSTED", RecursiveDenialCodes.TOOL_BUDGET_EXHAUSTED)
    }
}
