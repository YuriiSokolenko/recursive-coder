package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class StageVocabularyUnitTest {
    @Test fun fiveRoleOrderAndSharedMetadataRemainStable() {
        assertEquals(listOf("SDD", "ATDD", "TDD", "DEVELOP", "REVIEW"),
            FiveRoleWorkflowStages.order.map { it.name })
        assertEquals("origin", StageEventMetadataFields.ORIGIN)
        assertEquals("requestId", StageEventMetadataFields.REQUEST_ID)
        assertEquals("code", StageEventMetadataFields.CODE)
        assertEquals(16_384, StageRoleLimits.MAX_INSTRUCTION_BYTES)
        assertEquals(32_768, StageRoleLimits.MAX_PROPOSAL_BYTES)
        assertEquals(512, RecursiveGraphPolicy.MAX_TOTAL_CHILDREN)
    }

    @Test fun reviewAndChildAuditMetadataKeysRemainStable() {
        assertEquals("kind", StageEventMetadataFields.KIND)
        assertEquals("approval", StageEventMetadataFields.APPROVAL)
        assertEquals("verdict", StageEventMetadataFields.VERDICT)
        assertEquals("reasonCode", StageEventMetadataFields.REASON_CODE)
        assertEquals("scope", StageEventMetadataFields.SCOPE)
        assertEquals("reviewedSha256", StageEventMetadataFields.REVIEWED_SHA256)
        assertEquals("evidenceCount", StageEventMetadataFields.EVIDENCE_COUNT)
        assertEquals("childResourceAudit", StageEventMetadataFields.CHILD_RESOURCE_AUDIT)
        assertEquals("observedTokens", StageEventMetadataFields.OBSERVED_TOKENS)
        assertEquals("observedToolCalls", StageEventMetadataFields.OBSERVED_TOOL_CALLS)
        assertEquals("accountedTokens", StageEventMetadataFields.ACCOUNTED_TOKENS)
        assertEquals("accountedToolCalls", StageEventMetadataFields.ACCOUNTED_TOOL_CALLS)
        assertEquals("usageUncertain", StageEventMetadataFields.USAGE_UNCERTAIN)
        assertEquals("physicalCompleted", StageEventMetadataFields.PHYSICAL_COMPLETED)
        assertEquals("schedulerChargeRecorded", StageEventMetadataFields.SCHEDULER_CHARGE_RECORDED)
        assertEquals("recursiveStopCode", StageEventMetadataFields.RECURSIVE_STOP_CODE)
        assertEquals("requiresReconciliation", StageEventMetadataFields.REQUIRES_RECONCILIATION)
    }

    @Test fun sessionUsageEventMetadataKeysRemainStable() {
        assertEquals("modelRequests", SessionUsageEventMetadataFields.MODEL_REQUESTS)
        assertEquals("inputTokens", SessionUsageEventMetadataFields.INPUT_TOKENS)
        assertEquals("outputTokens", SessionUsageEventMetadataFields.OUTPUT_TOKENS)
    }

    @Test fun stageNamesAndMetadataKeysRemainStable() {
        assertEquals(listOf("SDD", "ATDD", "TDD", "DEVELOP", "REVIEW"), StageKind.entries.map { it.name })
        assertEquals(listOf("SDD", "ATDD", "TDD", "DEVELOPER", "REVIEWER"),
            StageKind.entries.map { it.role.name })
        assertEquals(listOf("graphId", "stageRunId", "role", "parentGraphId", "childGraphId", "recursiveEvent", "roleEvent", "stageState", "grantTokens", "grantToolCalls", "spentTokens", "spentToolCalls", "delegatedTokens", "delegatedToolCalls", "budgetScopeGraphId"),
            listOf(StageEventMetadataFields.GRAPH_ID, StageEventMetadataFields.STAGE_RUN_ID, StageEventMetadataFields.ROLE, StageEventMetadataFields.PARENT_GRAPH_ID, StageEventMetadataFields.CHILD_GRAPH_ID, StageEventMetadataFields.RECURSIVE_EVENT, StageEventMetadataFields.ROLE_EVENT, StageEventMetadataFields.STAGE_STATE, StageEventMetadataFields.GRANT_TOKENS, StageEventMetadataFields.GRANT_TOOL_CALLS, StageEventMetadataFields.SPENT_TOKENS, StageEventMetadataFields.SPENT_TOOL_CALLS, StageEventMetadataFields.DELEGATED_TOKENS, StageEventMetadataFields.DELEGATED_TOOL_CALLS, StageEventMetadataFields.BUDGET_SCOPE_GRAPH_ID))
    }

    @Test fun artifactEvidenceKeysRemainStable() {
        assertEquals(listOf("artifactId", "artifactSha256"),
            listOf(StageEventMetadataFields.ARTIFACT_ID,
                StageEventMetadataFields.ARTIFACT_SHA256))
    }
}
