# Diagnostic literal audit #218

Baseline and implementation merge base:
`944cea58d2a6606499c884e7ae3359d5a168e05d`.

The 99 original deferred file/value pairs are recorded below, plus the
separately required pre-existing private owner. The immutable baseline and
independent specification review establish that these 99 values do not occur
in other production files or in `contracts/v1`; no shared or schema owner is
introduced. The owner objects are internal to the existing module, co-located
with the producer/consumer file. Module-internal visibility permits
independent direct literal unit assertions without adding public Kotlin API.
Existing behavioral fixtures remain literal. Source paths in the table
abbreviate the common `src/main/kotlin/ai/yuriilab/recursivecoder/` component;
unit and integration tests live in the corresponding module's
`src/test/kotlin/ai/yuriilab/recursivecoder/`.

## Baseline disposition ledger

| Production file (module/file) | Literal | Disposition | Owner or reason | Shared producer/consumer | Test/oracle (class and method) |
| --- | --- | --- | --- | --- | --- |
| `agent/runtime/KoogStageRoleExecutor.kt` | `EVENT_SINK_FAILED` | Migrated to local owner | `KoogStageRoleExecutorDiagnosticCodes.EVENT_SINK_FAILED` | Not shared | `KoogStageRoleExecutorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `agent/runtime/KoogStageRoleExecutor.kt` | `ROLE_IDENTITY_MISMATCH` | Migrated to local owner | `KoogStageRoleExecutorDiagnosticCodes.ROLE_IDENTITY_MISMATCH` | Not shared | `KoogStageRoleExecutorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `agent/runtime/KoogStageRoleExecutor.kt` | `ROLE_TOOL_SURFACE_UNAVAILABLE` | Migrated to local owner | `KoogStageRoleExecutorDiagnosticCodes.ROLE_TOOL_SURFACE_UNAVAILABLE` | Not shared | `KoogStageRoleExecutorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `agent/runtime/KoogStageRoleExecutor.kt` | `INVALID_ROLE_BUDGET_OR_INPUT` | Migrated to local owner | `KoogStageRoleExecutorDiagnosticCodes.INVALID_ROLE_BUDGET_OR_INPUT` | Not shared | `KoogStageRoleExecutorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `agent/runtime/KoogStageRoleExecutor.kt` | `MODEL_BRIDGE_UNSUPPORTED` | Migrated to local owner | `KoogStageRoleExecutorDiagnosticCodes.MODEL_BRIDGE_UNSUPPORTED` | Not shared | `KoogStageRoleExecutorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `app/cli/InstalledChildRuntimeGrants.kt` | `CHILD_USAGE_INVALID` | Migrated to local owner | `InstalledChildRuntimeGrantsDiagnosticCodes.CHILD_USAGE_INVALID` | Not shared | `InstalledChildRuntimeGrantsDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `app/cli/InstalledRecursiveWorkflow.kt` | `AUTHENTICATED_CHILD_RECEIPT_MISSING` | Migrated to local owner | `InstalledRecursiveWorkflowDiagnosticCodes.AUTHENTICATED_CHILD_RECEIPT_MISSING` | Not shared | `InstalledRecursiveWorkflowDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `app/cli/LinuxFiveRoleWorkflowHost.kt` | `CREATE_PREIMAGE_EXISTS` | Migrated to local owner | `LinuxFiveRoleWorkflowHostDiagnosticCodes.CREATE_PREIMAGE_EXISTS` | Not shared | `LinuxFiveRoleWorkflowHostDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `app/cli/LinuxFiveRoleWorkflowHost.kt` | `EDIT_PREIMAGE_CHANGED` | Migrated to local owner | `LinuxFiveRoleWorkflowHostDiagnosticCodes.EDIT_PREIMAGE_CHANGED` | Not shared | `LinuxFiveRoleWorkflowHostDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `app/cli/LinuxFiveRoleWorkflowHost.kt` | `RECOVERY_PREIMAGE_UNAVAILABLE` | Migrated to local owner | `LinuxFiveRoleWorkflowHostDiagnosticCodes.RECOVERY_PREIMAGE_UNAVAILABLE` | Not shared | `LinuxFiveRoleWorkflowHostDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `app/cli/LinuxFiveRoleWorkflowHost.kt` | `AUTHORITATIVE_ACCEPTANCE_UNCONFIRMED` | Migrated to local owner | `LinuxFiveRoleWorkflowHostDiagnosticCodes.AUTHORITATIVE_ACCEPTANCE_UNCONFIRMED` | Not shared | `LinuxFiveRoleWorkflowHostDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveGraphPolicy.kt` | `INVALID_ROOT` | Migrated to local owner | `RecursiveGraphPolicyDiagnosticCodes.INVALID_ROOT` | Not shared | `RecursiveGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveGraphPolicy.kt` | `GLOBAL_CHILD_LIMIT` | Migrated to local owner | `RecursiveGraphPolicyDiagnosticCodes.GLOBAL_CHILD_LIMIT` | Not shared | `RecursiveGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveGraphPolicy.kt` | `CHILD_AUTHORITY_ESCALATION` | Migrated to local owner | `RecursiveGraphPolicyDiagnosticCodes.CHILD_AUTHORITY_ESCALATION` | Not shared | `RecursiveGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveGraphPolicy.kt` | `INVALID_CHILD_DEPENDENCY` | Migrated to local owner | `RecursiveGraphPolicyDiagnosticCodes.INVALID_CHILD_DEPENDENCY` | Not shared | `RecursiveGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveGraphPolicy.kt` | `CYCLIC_RECURSIVE_GRAPH` | Migrated to local owner | `RecursiveGraphPolicyDiagnosticCodes.CYCLIC_RECURSIVE_GRAPH` | Not shared | `RecursiveGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveGraphPolicy.kt` | `INVALID_CHILD_RESERVATION` | Migrated to local owner | `RecursiveGraphPolicyDiagnosticCodes.INVALID_CHILD_RESERVATION` | Not shared | `RecursiveGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveGraphPolicy.kt` | `NO_PROGRESS_LOOP` | Migrated to local owner | `RecursiveGraphPolicyDiagnosticCodes.NO_PROGRESS_LOOP` | Not shared | `RecursiveGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/RecursiveChildIntegrationPolicy.kt` | `CHILD_ARTIFACT_UNACCEPTED` | Migrated to local owner | `RecursiveChildIntegrationPolicyDiagnosticCodes.CHILD_ARTIFACT_UNACCEPTED` | Not shared | `RecursiveChildIntegrationPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/StageGraphPolicy.kt` | `SHARED_ROLE_ACTOR` | Migrated to local owner | `StageGraphPolicyDiagnosticCodes.SHARED_ROLE_ACTOR` | Not shared | `StageGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/StageGraphPolicy.kt` | `INVALID_STAGE_SUPERSESSION` | Migrated to local owner | `StageGraphPolicyDiagnosticCodes.INVALID_STAGE_SUPERSESSION` | Not shared | `StageGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/StageGraphPolicy.kt` | `INVALID_STAGE_REVIEW` | Migrated to local owner | `StageGraphPolicyDiagnosticCodes.INVALID_STAGE_REVIEW` | Not shared | `StageGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/StageGraphPolicy.kt` | `UNACCEPTED_STAGE_OUTPUT` | Migrated to local owner | `StageGraphPolicyDiagnosticCodes.UNACCEPTED_STAGE_OUTPUT` | Not shared | `StageGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/StageGraphPolicy.kt` | `GRAPH_NOT_RUNNING` | Migrated to local owner | `StageGraphPolicyDiagnosticCodes.GRAPH_NOT_RUNNING` | Not shared | `StageGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/StageGraphPolicy.kt` | `INVALID_INDEPENDENT_REVIEW` | Migrated to local owner | `StageGraphPolicyDiagnosticCodes.INVALID_INDEPENDENT_REVIEW` | Not shared | `StageGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/StageGraphPolicy.kt` | `GRAPH_NOT_ACCEPTED` | Migrated to local owner | `StageGraphPolicyDiagnosticCodes.GRAPH_NOT_ACCEPTED` | Not shared | `StageGraphPolicyDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RecursiveGraphScheduler.kt` | `CHILD_EVIDENCE_UNAVAILABLE` | Migrated to local owner | `RecursiveGraphSchedulerDiagnosticCodes.CHILD_EVIDENCE_UNAVAILABLE` | Not shared | `RecursiveGraphSchedulerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RecursiveGraphScheduler.kt` | `INTEGRATION_ATTEMPT_LIMIT` | Migrated to local owner | `RecursiveGraphSchedulerDiagnosticCodes.INTEGRATION_ATTEMPT_LIMIT` | Not shared | `RecursiveGraphSchedulerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RecursiveGraphScheduler.kt` | `STAGE_EXECUTOR_FAILED` | Migrated to local owner | `RecursiveGraphSchedulerDiagnosticCodes.STAGE_EXECUTOR_FAILED` | Not shared | `RecursiveGraphSchedulerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunDeveloperStageUseCase.kt` | `UNSUPPORTED_DEVELOPER_STAGE` | Migrated to local owner | `RunDeveloperStageUseCaseDiagnosticCodes.UNSUPPORTED_DEVELOPER_STAGE` | Not shared | `RunDeveloperStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunDeveloperStageUseCase.kt` | `TARGET_READ_UNAVAILABLE` | Migrated to local owner | `RunDeveloperStageUseCaseDiagnosticCodes.TARGET_READ_UNAVAILABLE` | Not shared | `RunDeveloperStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunDeveloperStageUseCase.kt` | `STALE_TARGET_BASELINE` | Migrated to local owner | `RunDeveloperStageUseCaseDiagnosticCodes.STALE_TARGET_BASELINE` | Not shared | `RunDeveloperStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunDeveloperStageUseCase.kt` | `CREATE_TARGET_EXISTS` | Migrated to local owner | `RunDeveloperStageUseCaseDiagnosticCodes.CREATE_TARGET_EXISTS` | Not shared | `RunDeveloperStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunDeveloperStageUseCase.kt` | `TARGET_ENCODING_INVALID` | Migrated to local owner | `RunDeveloperStageUseCaseDiagnosticCodes.TARGET_ENCODING_INVALID` | Not shared | `RunDeveloperStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunDeveloperStageUseCase.kt` | `INVALID_DEVELOPER_PROPOSAL` | Migrated to local owner | `RunDeveloperStageUseCaseDiagnosticCodes.INVALID_DEVELOPER_PROPOSAL` | Not shared | `RunDeveloperStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunFiveRoleWorkflowUseCase.kt` | `HOST_SNAPSHOT_UNAVAILABLE` | Migrated to local owner | `RunFiveRoleWorkflowUseCaseDiagnosticCodes.HOST_SNAPSHOT_UNAVAILABLE` | Not shared | `RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunFiveRoleWorkflowUseCase.kt` | `GRAPH_TOKEN_BUDGET_EXHAUSTED` | Migrated to local owner | `RunFiveRoleWorkflowUseCaseDiagnosticCodes.GRAPH_TOKEN_BUDGET_EXHAUSTED` | Not shared | `RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunFiveRoleWorkflowUseCase.kt` | `WORKFLOW_OUTPUT_MISMATCH` | Migrated to local owner | `RunFiveRoleWorkflowUseCaseDiagnosticCodes.WORKFLOW_OUTPUT_MISMATCH` | Not shared | `RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunFiveRoleWorkflowUseCase.kt` | `MISSING_COMMITTED_GRAPH` | Migrated to local owner | `RunFiveRoleWorkflowUseCaseDiagnosticCodes.MISSING_COMMITTED_GRAPH` | Not shared | `RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunFiveRoleWorkflowUseCase.kt` | `MISSING_HOST_ACCEPTANCE` | Migrated to local owner | `RunFiveRoleWorkflowUseCaseDiagnosticCodes.MISSING_HOST_ACCEPTANCE` | Not shared | `RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunFiveRoleWorkflowUseCase.kt` | `STAGE_RECONCILIATION_UNCONFIRMED` | Migrated to local owner | `RunFiveRoleWorkflowUseCaseDiagnosticCodes.STAGE_RECONCILIATION_UNCONFIRMED` | Not shared | `RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunFiveRoleWorkflowUseCase.kt` | `GRAPH_FINISH_UNCONFIRMED` | Migrated to local owner | `RunFiveRoleWorkflowUseCaseDiagnosticCodes.GRAPH_FINISH_UNCONFIRMED` | Not shared | `RunFiveRoleWorkflowUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `UNSUPPORTED_PREPARATION_STAGE` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.UNSUPPORTED_PREPARATION_STAGE` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `STALE_ARTIFACT_APPROVAL` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.STALE_ARTIFACT_APPROVAL` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `HUMAN_APPROVAL_STOPPED` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_STOPPED` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `HUMAN_APPROVAL_TIMED_OUT` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_TIMED_OUT` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `NO_PENDING_HUMAN_APPROVAL` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.NO_PENDING_HUMAN_APPROVAL` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `INVALID_HUMAN_APPROVAL_LEASE` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.INVALID_HUMAN_APPROVAL_LEASE` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `STALE_HUMAN_APPROVAL` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.STALE_HUMAN_APPROVAL` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunPreparationStageUseCase.kt` | `HUMAN_APPROVAL_READ_UNAVAILABLE` | Migrated to local owner | `RunPreparationStageUseCaseDiagnosticCodes.HUMAN_APPROVAL_READ_UNAVAILABLE` | Not shared | `RunPreparationStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunTwoOriginRecursiveChildrenUseCase.kt` | `INVALID_ORIGIN_PAIR` | Migrated to local owner | `RunTwoOriginRecursiveChildrenUseCaseDiagnosticCodes.INVALID_ORIGIN_PAIR` | Not shared | `RunTwoOriginRecursiveChildrenUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_FENCE_LIMIT` | Migrated to local owner | `SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_LIMIT` | Not shared | `SessionResumeAdmissionDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_FENCE_CLEANUP_UNCONFIRMED` | Migrated to local owner | `SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CLEANUP_UNCONFIRMED` | Not shared | `SessionResumeAdmissionDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_FENCE_CONTEXT_CHANGED` | Migrated to local owner | `SessionResumeAdmissionDiagnosticCodes.RESUME_FENCE_CONTEXT_CHANGED` | Not shared | `SessionResumeAdmissionDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_LEDGER_PATH_CHANGED` | Migrated to local owner | `SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_PATH_CHANGED` | Not shared | `SessionResumeAdmissionDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_LEDGER_IDENTITY_UNCONFIRMED` | Migrated to local owner | `SessionResumeAdmissionDiagnosticCodes.RESUME_LEDGER_IDENTITY_UNCONFIRMED` | Not shared | `SessionResumeAdmissionDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_EFFECT_RECONCILIATION_INVALID` | Migrated to local owner | `SessionResumeAdmissionDiagnosticCodes.RESUME_EFFECT_RECONCILIATION_INVALID` | Not shared | `SessionResumeAdmissionDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_LEASE_HELD` | Migrated to local owner | `SessionResumeAdmissionDiagnosticCodes.RESUME_LEASE_HELD` | Not shared | `SessionResumeAdmissionDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/ResumeFenceAnchor.kt` | `RESUME_FENCE_HARD_LINK_PROBE_CLEANUP_UNCONFIRMED` | Migrated to local owner | `ResumeFenceAnchorDiagnosticCodes.RESUME_FENCE_HARD_LINK_PROBE_CLEANUP_UNCONFIRMED` | Not shared | `ResumeFenceAnchorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/ResumeFenceAnchor.kt` | `RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY` | Migrated to local owner | `ResumeFenceAnchorDiagnosticCodes.RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY` | Not shared | `ResumeFenceAnchorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/journal/ResumeFenceAnchor.kt` | `RESUME_FENCE_AUDIT_CONFLICT` | Migrated to local owner | `ResumeFenceAnchorDiagnosticCodes.RESUME_FENCE_AUDIT_CONFLICT` | Not shared | `ResumeFenceAnchorDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/model/ModelGateway.kt` | `MODEL_RETRY_EXHAUSTED` | Migrated to local owner | `ModelGatewayDiagnosticCodes.MODEL_RETRY_EXHAUSTED` | Not shared | `ModelGatewayDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/model/ModelGateway.kt` | `MODEL_STREAM_INCOMPLETE` | Migrated to local owner | `ModelGatewayDiagnosticCodes.MODEL_STREAM_INCOMPLETE` | Not shared | `ModelGatewayDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/model/ModelGateway.kt` | `MODEL_TRANSPORT_FAILED` | Migrated to local owner | `ModelGatewayDiagnosticCodes.MODEL_TRANSPORT_FAILED` | Not shared | `ModelGatewayDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/ReviewerStageContracts.kt` | `REVIEW_MUTATED_OTHER_STATE` | Migrated to local owner | `ReviewerStageContractsDiagnosticCodes.REVIEW_MUTATED_OTHER_STATE` | Not shared | `ReviewerStageContractsDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/contracts/ReviewerStageContracts.kt` | `UNAUTHORIZED_REPAIR` | Migrated to local owner | `ReviewerStageContractsDiagnosticCodes.UNAUTHORIZED_REPAIR` | Not shared | `ReviewerStageContractsDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunReviewerStageUseCase.kt` | `INVALID_REVIEW_RESPONSE` | Migrated to local owner | `RunReviewerStageUseCaseDiagnosticCodes.INVALID_REVIEW_RESPONSE` | Not shared | `RunReviewerStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `core/session/RunReviewerStageUseCase.kt` | `REVIEW_FINISH_UNCONFIRMED` | Migrated to local owner | `RunReviewerStageUseCaseDiagnosticCodes.REVIEW_FINISH_UNCONFIRMED` | Not shared | `RunReviewerStageUseCaseDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `PATH_CASE_COLLISION` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.PATH_CASE_COLLISION` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `INDEX_MUTATION_DENIED` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.INDEX_MUTATION_DENIED` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `FILE_SIZE_LIMIT` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.FILE_SIZE_LIMIT` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `WRITE_IO_DENIED` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.WRITE_IO_DENIED` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `MUTATION_DIGEST_MISMATCH` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.MUTATION_DIGEST_MISMATCH` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `WRITE_EXECUTION_DENIED` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.WRITE_EXECUTION_DENIED` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `CHECK_OUTPUT_LIMIT` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.CHECK_OUTPUT_LIMIT` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/TrustedWriteRuntime.kt` | `CHECK_OUTPUT_UNAVAILABLE` | Migrated to local owner | `TrustedWriteRuntimeDiagnosticCodes.CHECK_OUTPUT_UNAVAILABLE` | Not shared | `TrustedWriteRuntimeDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_BASELINE_TIMEOUT` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_BASELINE_TIMEOUT` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `INVALID_STAGE_PATH` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.INVALID_STAGE_PATH` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit); historical post-CAS syntax oracle replaced in [#220](https://github.com/YuriiSokolenko/recursive-coder/issues/220) ([details](#follow-up-220-request-snapshots-and-path-guard-reachability)) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `UNSAFE_STAGE_FILE_MODE` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.UNSAFE_STAGE_FILE_MODE` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_COMMAND_INTERRUPTED` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_COMMAND_INTERRUPTED` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_BASELINE_INTERRUPTED` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_BASELINE_INTERRUPTED` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_COMMAND_UNAVAILABLE` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_COMMAND_UNAVAILABLE` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `UNEXPECTED_GIT_CHANGE` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.UNEXPECTED_GIT_CHANGE` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `STAGE_BRANCH_CHANGED` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.STAGE_BRANCH_CHANGED` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `STAGE_WORKSPACE_CHANGED` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.STAGE_WORKSPACE_CHANGED` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `FROZEN_ARTIFACT_CHANGED` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.FROZEN_ARTIFACT_CHANGED` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `INVALID_GIT_BASELINE` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.INVALID_GIT_BASELINE` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `INVALID_STAGE_CHANGESET` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.INVALID_STAGE_CHANGESET` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `UNSAFE_GIT_ENTRY` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.UNSAFE_GIT_ENTRY` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `UNCERTAIN_GIT_HANDOFF` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.UNCERTAIN_GIT_HANDOFF` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `FOREIGN_GIT_OR_INDEX_EDIT` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.FOREIGN_GIT_OR_INDEX_EDIT` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `POST_RESTORE_IMAGE_UNCONFIRMED` | Migrated to local owner | `LinuxStageGitBaselineReconcilerDiagnosticCodes.POST_RESTORE_IMAGE_UNCONFIRMED` | Not shared | `LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/model/FailClosedProviderUsageGateway.kt` | `MODEL_REQUEST_TIMEOUT_INVALID` | Migrated to local owner | `FailClosedProviderUsageGatewayDiagnosticCodes.MODEL_REQUEST_TIMEOUT_INVALID` | Not shared | `FailClosedProviderUsageGatewayDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/WorkspaceManager.kt` | `READ_LIMIT_EXCEEDED` | Migrated to local owner | `WorkspaceManagerDiagnosticCodes.READ_LIMIT_EXCEEDED` | Not shared | `WorkspaceManagerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/WorkspaceManager.kt` | `INVALID_TASK_PATH` | Migrated to local owner | `WorkspaceManagerDiagnosticCodes.INVALID_TASK_PATH` | Not shared | `WorkspaceManagerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/WorkspaceManager.kt` | `RESULT_LIMIT_EXCEEDED` | Migrated to local owner | `WorkspaceManagerDiagnosticCodes.RESULT_LIMIT_EXCEEDED` | Not shared | `WorkspaceManagerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/WorkspaceManager.kt` | `GIT_OUTPUT_INVALID` | Migrated to local owner | `WorkspaceManagerDiagnosticCodes.GIT_OUTPUT_INVALID` | Not shared | `WorkspaceManagerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |
| `infra/workspace/WorkspaceManager.kt` | `GIT_INSPECTION_DENIED` | Migrated to local owner | `WorkspaceManagerDiagnosticCodes.GIT_INSPECTION_DENIED` | Not shared | `WorkspaceManagerDiagnosticCodesUnitTest.exactDiagnosticCodes` (unit) |

## Additional private owner from #212 (not a baseline pair)

| Production file | Literal | Disposition | Owner or reason | Shared producer/consumer | Test/oracle |
| --- | --- | --- | --- | --- | --- |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `UNSAFE_STAGE_PATH` | Intentionally retained | Existing private constant; secure traversal/path safety, not syntax | Not shared | `LinuxStageGitBaselineReconcilerTest.validDeclaredPathWithMissingIntermediateDirectoryIsUnsafe` (integration) |

### Follow-up #220: request snapshots and path-guard reachability

At the #218 merge baseline, the syntax integration oracle depended on a
caller mutating the frozen-path map after CAS. #220 intentionally removes
that aliasing: accepted-stage reconciliation copies the list and ordered
map before canonical-root, lease or preflight validation and uses only the
captured request for preflight, object construction, re-verification, ref
CAS and final readback. List elements, map keys and values are immutable.
No caller mutation is permitted during capture; copying does not make
concurrent writes safe or provide a linearizable multi-collection snapshot.
Integration tests inject collection traversal exceptions in each capture and
assert `GIT_HANDOFF_UNAVAILABLE` with unchanged ref, index, working-tree
status and bytes, and no lock directory creation. Paired ordering tests pin
the first denial for both accepted and frozen collections in both orders.

For stable inputs, preflight still checks baseline syntax, change-set
cardinality/uniqueness and overlap, role authorization and frozen syntax/hash
before Git identity, status, digest and mode checks in the original order.
No authorization grant, receipt authentication, filesystem guard, ref CAS,
exception classification or post-CAS quarantine rule is changed.

The private `read` syntax guard cannot be reached with a malformed path via
`reconcile` now: all accepted paths must pass authorization (whose first
predicate is `validDeclaredPath`) and frozen paths pass the same syntax check
in preflight; strings and the private collections cannot drift afterwards.
Keep the guard as defense in depth, not a public test seam. The former
`invalidDeclaredPathAtPostCasReadbackRetainsExactDenial` test is intentionally
superseded by before/after-CAS collection drift regressions, an exact
`INVALID_STAGE_CHANGESET` preflight behavior test for malformed accepted and
frozen paths, and the existing independent literal unit oracle for
`INVALID_STAGE_PATH`. The unit oracle pins spelling, not guard execution.
The separate valid-path missing-directory behavior test still reaches secure
traversal and asserts `UNSAFE_STAGE_PATH`.

The same `read` guard and the private `withFailedWriteParent` guard also
serve `recoverFailedDeveloper`. Stable recovery inputs pass initial
authorization or frozen-path validation before reaching either guard.
At the #220 baseline, recovery still aliased `request.files` and
`request.frozenArtifacts`, as well as the mutable
`StageGitUnacceptedFile.beforeBytes`. A shallow list copy would not isolate
those bytes. Recovery was intentionally unchanged in #220; see the separate
#221 follow-up below. The read-only `inspectCleanBaseline` and its Boolean
wrapper take immutable strings, not caller collections.

The existing Linux integration observer is after the second verification
at BEFORE_REF_UPDATE, or after the CAS at AFTER_REF_UPDATE; it is not a seam
between preflight and object construction. The regression tests exercise
both existing checkpoints, literal outcomes, exact committed paths/content,
parent/ref integrity and final readback mode failure even when Git status is
clean. Coverage of earlier stages follows from the single private snapshot
and source review, not a new public or internal observer. Existing CAS
competition and post-CAS fault tests remain in the integration tier.
No C3 source, pin, verifier, contract, module edge or test-tier tag changes.

### Follow-up #221: failed Developer recovery snapshot

Failed-write recovery now preserves the immutable baseline syntax denial
first, then bounds the file count and each before-image length before copying.
It captures an ordered list of new entries with independent arrays and an
ordered map before authorization or filesystem/lease work. For stable inputs
the moved size gates still report `INVALID_FAILED_WRITE_EVIDENCE`, as do
malformed paths, duplicates or invalid hashes; later Git and frozen-fact
checks retain their original order and diagnostics. No evidence or snapshot
is returned. The producer ownership and exclusive-capture contract are
documented in [DEVELOPER_STAGE.md](DEVELOPER_STAGE.md).

With recovery also using a private snapshot, neither `read` nor
`withFailedWriteParent` can receive a syntactically invalid path through the
current recovery entry point after successful evidence validation. Write
authorization checks path syntax, and frozen path validation uses the same
predicate. Neither input can subsequently drift. Retain both private guards
as defense in depth for future callers. The independent literal unit oracle
for `INVALID_STAGE_PATH` remains unchanged; it establishes spelling, not
execution. Malformed failed-write paths are rejected at evidence validation
with the literal `INVALID_FAILED_WRITE_EVIDENCE`; existing valid-path
filesystem-denial tests remain intact.

Deterministic Linux tests extend the existing internal observer with points
after evidence authorization and after Git blob/status preflight, before
restoration. They exercise caller list, map and byte-array mutations at
both early and late checkpoints, and reuse the final-baseline observation
point for late frozen-map mutation. A changing traversal tests authorization
from the captured list; a nine-entry traversal reporting size two tests
the second count gate before lease creation. The tests verify the exact
recovered audit, restored bytes, literal denials, raw index/HEAD/ref bytes
and unchanged frozen files; capture failures and bounds are checked before
lease creation. No new public test seam, model capability, C3 source/pin,
CI gate, or accepted-stage behavior changes.

The existing 4 MiB per-file stage limit is now owned by the reconciler's
private `MAX_STAGE_FILE_BYTES` constant. Capture, regular-file attribute
checks, bounded reads and failed-write restore checks share that limit;
no value, denial code or check ordering changes. Test bounds remain literal
as independent oracles.

## Cross-file and non-diagnostic scanner reconciliation

The following table records the existing documented exclusions individually,
as well as the two journal-local resume codes whose unpinned producer and
consumer can use the existing journal owner. The pinned runtime projections
stay literal without introducing a core-to-infra dependency. The baseline 99
remain separate. Non-diagnostic keys are retained under CONTRIBUTING.md.

| Production file (module/file) | Literal | Disposition | Owner or reason | Shared producer/consumer | Test/oracle or justification |
| --- | --- | --- | --- | --- | --- |
| `infra/workspace/WorkspaceManager.kt` | `LC_ALL` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `LC_ALL` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `GIT_CONFIG_NOSYSTEM` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_CONFIG_NOSYSTEM` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `GIT_CONFIG_GLOBAL` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_CONFIG_GLOBAL` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `GIT_CONFIG_SYSTEM` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_CONFIG_SYSTEM` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `GIT_ATTR_NOSYSTEM` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_ATTR_NOSYSTEM` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `GIT_NO_REPLACE_OBJECTS` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_NO_REPLACE_OBJECTS` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `GIT_OPTIONAL_LOCKS` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_OPTIONAL_LOCKS` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `GIT_TERMINAL_PROMPT` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_TERMINAL_PROMPT` | Intentionally retained | External Git/locale process environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/LinuxStageGitBaselineReconciler.kt` | `GIT_INDEX_FILE` | Intentionally retained | External Git environment key | Not shared | Not a diagnostic; exact external key |
| `infra/workspace/WorkspaceManager.kt` | `UNCHECKED_CAST` | Intentionally retained | Compiler-defined suppression | Not shared | Not a diagnostic; compiler vocabulary |
| `infra/workspace/TrustedWriteRuntime.kt` | `UNCHECKED_CAST` | Intentionally retained | Compiler-defined suppression | Not shared | Not a diagnostic; compiler vocabulary |
| `core/contracts/StageGraphPolicy.kt` | `DUPLICATE_GRAPH_ID` | Intentionally retained | Unique stage-run and artifact IDs within a stage graph | Not shared | Existing graph validation; no changed value or producer |
| `core/contracts/RecursiveGraphPolicy.kt` | `DUPLICATE_GRAPH_ID` | Intentionally retained | Unique graph/stage-run IDs across recursive nodes | Not shared | Existing graph validation; no changed value or producer |
| `infra/workspace/TrustedWriteRuntime.kt` | `CHECK_FAILED` | Intentionally retained | Physical check failure; not a Reviewer reason | Not shared | Existing check behavior; no changed value or producer |
| `core/contracts/ReviewerStageContracts.kt` | `CHECK_FAILED` | Intentionally retained | ReviewerReasonCode wire member; not physical check failure | Not shared | Existing Reviewer vocabulary and schema-independent enum tests |
| `infra/journal/DurableAcceptedRunningHandoff.kt` | `RESUME_UNSAFE_BOUNDARY` | Intentionally retained | Pinned C3 source; #207/#214; no cross-layer dependency | Not shared | Existing approved source pin and unchanged literal; no new migration |
| `infra/journal/DurableAcceptedRunningHandoff.kt` | `RESUME_CHECKPOINT_CHANGED` | Intentionally retained | Pinned C3 source; #207/#214; no cross-layer dependency | Not shared | Existing approved source pin and unchanged literal; no new migration |
| `infra/journal/DurableSessionEventLedger.kt` | `LEDGER_CHECKPOINT_SIZE_EXCEEDED` | Intentionally retained | Pinned C3 source; #207/#214; no cross-layer dependency | Not shared | Existing approved source pin and unchanged literal; no new migration |
| `infra/journal/DurableSessionEventLedger.kt` | `LEDGER_REOPEN_CHECKPOINT_CAPACITY` | Intentionally retained | Pinned C3 source; #207/#214; no cross-layer dependency | Not shared | Existing approved source pin and unchanged literal; no new migration |
| `core/session/SessionRuntime.kt` | `RESUME_ADMISSION_UNCONFIRMED` | Intentionally retained | Pinned C3 source; #207/#214; no cross-layer dependency | Not shared | Existing approved source pin and unchanged literal; no new migration |
| `core/session/SessionRuntime.kt` | `RESUME_FILESYSTEM_UNSUPPORTED` | Intentionally retained | Pinned C3 source; #207/#214; no cross-layer dependency | Not shared | Existing approved source pin and unchanged literal; no new migration |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_FILESYSTEM_UNSUPPORTED` | Migrated to existing owner | ResumeDenialCodes.RESUME_FILESYSTEM_UNSUPPORTED | Producer `ResumeLocalFileStorePolicy.requireSupportedPaths` in `SessionResumeAdmission.kt`; consumer `ResumeRuntimeFilesystemPreflight.deny`; pinned C3 projection `SessionRuntime.kt:91` (`override val code`) | ResumeDenialCodesUnitTest.resumeCodesRetainDiagnosticSpelling |
| `infra/journal/ResumeRuntimeFilesystemPreflight.kt` | `RESUME_FILESYSTEM_UNSUPPORTED` | Migrated to existing owner | ResumeDenialCodes.RESUME_FILESYSTEM_UNSUPPORTED | Producer `ResumeLocalFileStorePolicy.requireSupportedPaths` in `SessionResumeAdmission.kt`; consumer `ResumeRuntimeFilesystemPreflight.deny`; pinned C3 projection `SessionRuntime.kt:91` (`override val code`) | ResumeDenialCodesUnitTest.resumeCodesRetainDiagnosticSpelling |
| `infra/journal/SessionResumeAdmission.kt` | `RESUME_ADMISSION_UNCONFIRMED` | Migrated to existing owner | ResumeDenialCodes.RESUME_ADMISSION_UNCONFIRMED; matching runtime literal is pinned | Producer `SessionResumeAdmission.admitChecked` (`SessionResumeUnconfirmed` throw); pinned C3 projection `SessionRuntime.kt:100` (`override val code`) | ResumeDenialCodesUnitTest.resumeCodesRetainDiagnosticSpelling |

## Retained exclusions

The existing C3, distinct-semantic cross-file, Git environment and compiler
exclusions remain explicitly listed in
[SEMANTIC_VOCABULARY.md](SEMANTIC_VOCABULARY.md). They are not unresolved
baseline deferrals. The Git and locale environment keys and Kotlin suppression
identifiers are external vocabulary, not diagnostic-code oracles; no new
diagnostic oracle is applicable. The two graph identity checks and Reviewer
reason versus physical check failure remain independent.

The implementation PR records the exact audited head and scanner/pin evidence.
Do not consider the audit complete until all three scanner sections are
reconciled against the actual merge base and head and the required CI and
strict review pass.
