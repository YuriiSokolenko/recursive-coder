package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class StageVocabularyUnitTest {
    @Test fun stageNamesAndMetadataKeysRemainStable() {
        assertEquals(listOf("SDD", "ATDD", "TDD", "DEVELOP", "REVIEW"), StageKind.entries.map { it.name })
        assertEquals(listOf("SDD", "ATDD", "TDD", "DEVELOPER", "REVIEWER"),
            StageKind.entries.map { it.role.name })
        assertEquals(listOf("graphId", "stageRunId", "role", "parentGraphId", "childGraphId", "recursiveEvent", "roleEvent", "stageState", "grantTokens", "grantToolCalls", "spentTokens", "spentToolCalls", "delegatedTokens", "delegatedToolCalls", "budgetScopeGraphId"),
            listOf(StageEventMetadataFields.GRAPH_ID, StageEventMetadataFields.STAGE_RUN_ID, StageEventMetadataFields.ROLE, StageEventMetadataFields.PARENT_GRAPH_ID, StageEventMetadataFields.CHILD_GRAPH_ID, StageEventMetadataFields.RECURSIVE_EVENT, StageEventMetadataFields.ROLE_EVENT, StageEventMetadataFields.STAGE_STATE, StageEventMetadataFields.GRANT_TOKENS, StageEventMetadataFields.GRANT_TOOL_CALLS, StageEventMetadataFields.SPENT_TOKENS, StageEventMetadataFields.SPENT_TOOL_CALLS, StageEventMetadataFields.DELEGATED_TOKENS, StageEventMetadataFields.DELEGATED_TOOL_CALLS, StageEventMetadataFields.BUDGET_SCOPE_GRAPH_ID))
    }
}
