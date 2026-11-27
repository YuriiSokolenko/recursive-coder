package ai.yuriilab.recursivecoder

/** Shared metadata keys used to verify effect evidence in the session ledger. */
internal object EffectEvidenceFields {
    const val EFFECT_OPERATION_ID = "effectOperationId"
    const val EFFECT_FENCE_GENERATION = "effectFenceGeneration"
    const val EFFECT_KIND = "effectKind"
    const val EFFECT_INPUT_SHA256 = "effectInputSha256"
    const val EFFECT_RECORD_SEQUENCE = "effectRecordSequence"
    const val EFFECT_RECORD_MAC_SHA256 = "effectRecordMacSha256"
    const val EFFECT_STATE = "effectState"
    const val EFFECT_OBSERVED_STATE_SHA256 = "effectObservedStateSha256"
    const val EFFECT_ERROR_CODE = "effectErrorCode"
}

/** Effect record state wire names, not Session event statuses. */
internal object EffectRecordStates {
    const val INTENT = "intent"
    const val STARTED = "started"
    const val COMMITTED = "committed"
    const val UNCONFIRMED = "unconfirmed"
}

/** Physical effect-record capacity shared by the journal and checkpoint codec. */
internal object EffectJournalLimits {
    const val MAX_RECORDS = 100_000
}

internal const val CHECKPOINT_LOCK_NAME = "checkpoint.lock"
internal const val RESUME_LOCK_NAME = "resume.lock"

/** Checkpoint task snapshot states, independent of session and child phases. */
internal object CheckpointTaskStates {
    const val CREATED = "created"
    const val READY = "ready"
    const val SUCCEEDED = "succeeded"
    const val BLOCKED = "blocked"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val FAILED = "failed"
    const val NEEDS_ATTENTION = "needs-attention"
    const val CANCELLED = "cancelled"
}

/** Checkpoint child states are independent of task states. */
internal object CheckpointChildStates {
    const val READY = "ready"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val TIMED_OUT = "timed_out"
}

/** Host-asserted checkpoint usage dimensions, not Session event prefixes. */
internal object CheckpointUsageKinds {
    const val MODEL = "model"
    const val TOOL = "tool"
}

/** Host-asserted checkpoint usage certainty, not effect reconciliation state. */
internal object CheckpointUsageCertainty {
    const val KNOWN = "known"
    const val UNKNOWN = "unknown"
}
