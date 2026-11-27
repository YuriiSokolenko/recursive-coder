package ai.yuriilab.recursivecoder

/** Stage and recursive metadata carried in Session Protocol v1 events. */
object StageEventMetadataFields {
    const val GRAPH_ID = "graphId"
    const val STAGE_RUN_ID = "stageRunId"
    const val ROLE = "role"
    const val ORIGIN = "origin"
    const val REQUEST_ID = "requestId"
    const val CODE = "code"
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
    const val KIND = "kind"
    const val APPROVAL = "approval"
    const val VERDICT = "verdict"
    const val REASON_CODE = "reasonCode"
    const val SCOPE = "scope"
    const val REVIEWED_SHA256 = "reviewedSha256"
    const val EVIDENCE_COUNT = "evidenceCount"
    const val CHILD_RESOURCE_AUDIT = "childResourceAudit"
    const val OBSERVED_TOKENS = "observedTokens"
    const val OBSERVED_TOOL_CALLS = "observedToolCalls"
    const val ACCOUNTED_TOKENS = "accountedTokens"
    const val ACCOUNTED_TOOL_CALLS = "accountedToolCalls"
    const val USAGE_UNCERTAIN = "usageUncertain"
    const val PHYSICAL_COMPLETED = "physicalCompleted"
    const val SCHEDULER_CHARGE_RECORDED = "schedulerChargeRecorded"
    const val RECURSIVE_STOP_CODE = "recursiveStopCode"
    const val REQUIRES_RECONCILIATION = "requiresReconciliation"
}

/** Usage metadata in session events, distinct from result usage fields. */
object SessionUsageEventMetadataFields {
    const val MODEL_REQUESTS = "modelRequests"
    const val INPUT_TOKENS = "inputTokens"
    const val OUTPUT_TOKENS = "outputTokens"
}
