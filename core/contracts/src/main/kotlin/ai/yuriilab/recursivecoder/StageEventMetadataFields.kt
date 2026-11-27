package ai.yuriilab.recursivecoder

/** Stage and recursive metadata carried in Session Protocol v1 events. */
object StageEventMetadataFields {
    const val GRAPH_ID = "graphId"
    const val STAGE_RUN_ID = "stageRunId"
    const val ROLE = "role"
    const val PARENT_GRAPH_ID = "parentGraphId"
    const val CHILD_GRAPH_ID = "childGraphId"
    const val RECURSIVE_EVENT = "recursiveEvent"
    const val ROLE_EVENT = "roleEvent"
    const val STAGE_STATE = "stageState"
    const val GRANT_TOKENS = "grantTokens"
    const val GRANT_TOOL_CALLS = "grantToolCalls"
    const val SPENT_TOKENS = "spentTokens"
    const val SPENT_TOOL_CALLS = "spentToolCalls"
    const val DELEGATED_TOKENS = "delegatedTokens"
    const val DELEGATED_TOOL_CALLS = "delegatedToolCalls"
    const val BUDGET_SCOPE_GRAPH_ID = "budgetScopeGraphId"
    const val ARTIFACT_ID = "artifactId"
    const val ARTIFACT_SHA256 = "artifactSha256"
}
