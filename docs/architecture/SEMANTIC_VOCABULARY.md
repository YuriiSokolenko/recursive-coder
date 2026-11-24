# Semantic vocabulary cleanup (#194)

This is a staged refactoring, not a protocol revision. The first slice centralizes
the supported invocation-source values and the first valid invocation attempt.
The rest of #194 remains open; the inventory below is a candidate map, not a claim
that every literal has already been reviewed or migrated.

## Candidate inventory and ownership

| Area | Examples and existing ownership | Disposition |
| --- | --- | --- |
| Session request and admission | `SessionProtocol.VERSION`; invocation source and attempt in `SessionRequest`, `SessionAdmissionPolicy`, `TaskRequestMapper` | Invocation vocabulary and first attempt centralized here. Schema-reserved `api` remains unsupported by runtime admission. |
| Session envelopes and fields | `SessionRequest`, `SessionRuntime`, `SessionResultMapper`, `SessionEventJournal`; kind, version, invocation, resume, output and budget fields | Follow-up: group by envelope/field ownership in contracts; avoid a flat JSON-key dictionary. |
| Model configuration and capabilities | `ModelConfig`, `SessionAdmissionPolicy`, `ModelGateways`, `FakeModel`, CLI model gates; fake, openai-compatible, fake-v1, tool_calls, usage_metadata and required capabilities | Follow-up: contracts own configured provider/feature identifiers and session capabilities; provider-specific response keys remain in the adapter. The feature identifier tool_calls and external response field of the same spelling are not automatically the same contract. |
| Session lifecycle and events | `SessionOutcome` in contracts; `SessionPhase` and `SessionPhaseVocabulary` in session; journal adapters and event projections | Follow-up: reuse existing phase/outcome ownership; separate event kinds from phases and terminal result status. Audit ledger/checkpoint evidence vocabulary without changing persistence formats. |
| Task domain | `TaskState` and `TaskPolicy` in tasks; task-store and CLI task JSON | Follow-up: keep typed task state distinct from similarly named session states; review store/CLI fields and task invariants. The unclaimed Task attempt of zero is not a valid invocation attempt. |
| Workspace and tools | `WorkspaceTool.name`, `ToolAuthorizationPolicy`, trusted write schema and runtime, role execution adapter | Follow-up: identify shared tool identities, modes and denial codes by the owning contract. Do not confuse model-facing read_task with workspace read. |
| Stage and recursive domain | Stage graph, engineering role, recursive graph, preparation/reviewer policies and installed workflow/Koog adapters | Follow-up: prefer existing enums and typed decisions; audit adapter translations and diagnostics without widening authorization. |
| Journal and durable evidence | Ledger, checkpoint, resume fence, effect records and evidence envelope policies | Follow-up: classify stable format markers, field names and bounds by format owner; preserve independently verified bytes and rejection behavior. |
| Build and CI | Build-logic architecture graph, JVM convention, Gradle scripts, CI YAML and Python test-tier/bootstrap/bytecode guards | Follow-up: consolidate local repetitions only; independent guard expectations and external Gradle task names are not production vocabulary. |
| Independent protocol and integration checks | JSON schema/examples, Ajv tests, Python schema/installed CLI tests, Kotlin admission/mapper tests and fixtures | Retain literal wire values, invalid inputs and expected outcomes. Reuse test helpers only when it does not couple expectations to the implementation. |
| Documentation and CLI prose | README, architecture documents, help, human-facing errors | Keep readable text and examples literal; review documented behavior rather than replacing words with code references. |

## First slice compatibility

The schema and examples are intentionally unchanged. Parsing still validates
the invocation attempt minimum without deciding runtime admission; the pure
admission policy still checks protocol before invocation before resume.
CLI admits exactly the first attempt and scheduler admits every positive Long
attempt. Task mapping still projects a claimed Int attempt to the scheduler
wire source, reparsing the complete envelope. The task counter's overflow
protection and zero-valued unclaimed state are separate domain concerns.

The independent literal tests in `SessionPolicyUnitTest` cover unsupported
sources and `SessionPolicyCoverageUnitTest` covers invalid attempts,
`Long.MAX_VALUE` and denial precedence (see
`schedulerAttemptMustBePositive`, `cliAttemptMustBeExactlyOne` and
`schedulerAttemptDenialsPreserveResumePreflightPrecedence`).
`SessionProtocolTest` pins the new constant values. `TaskRequestMapperIntegrationTest`
reparses actual mapper output for first, repeated and maximum Int attempts,
and verifies that nonpositive attempts fail with the exact validation diagnostic.
The path-free malformed-envelope checks remain in `TaskRequestMapperUnitTest`.
The shared first-attempt constant is the protocol minimum; CLI currently admits
only that attempt. If source-specific admission bounds diverge, keep them in
admission policy rather than changing the protocol minimum.

## Remaining work

Review all production consumers and tests for each follow-up area before
changing that area's vocabulary. Keep #194 open until the full inventory,
migrations and architecture/unit/integration/installed CLI/protocol CI are
complete. The table is based on the current module/file layout and targeted
source inspection; it is not an exhaustive file-by-file literal scan.

## Diagnostic vocabulary follow-up (#201)

Initial audit against dev at `5a31bae85341a2083f0cf982eb4b1f83db2d67cb`;
rebased and review findings addressed on
`94b5629c44e1daaf1e38af726efe51cbc74b7d9e` (includes #202 and #213).
The following is a targeted semantic audit of diagnostic producers and
consumers, not a mechanical replacement of matching text.

| Reviewed area and files | Ownership and disposition |
| --- | --- |
| `SessionRequest`, `SessionPolicy`, `SessionResultMapper`, `SessionRuntime`, `RunSessionUseCase`, `PreExecutionPhasePolicy` | Keep `Refused` an open code/category/message carrier; admission and budget enums retain existing messages, categories and precedence. Share model request/token exhaustion codes with the role adapter through model contracts. C3 runtime stays byte-identical. |
| `ModelContracts`, `ModelGateway`, `FailClosedProviderUsageGateway`, `KoogStageRoleExecutor`, `RunPreparationStageUseCase`, `RunDeveloperStageUseCase` | `ModelDenialCodes` owns capability unsupported, invalid response and model request/token budget codes. Provider grant poison/exhaustion codes and HTTP/Koog-specific failures remain distinct (including MODEL_TOKEN_BUDGET_EXHAUSTED). |
| `RecursiveGraphPolicy`, `RecursiveChildIntegrationPolicy`, `RecursiveGraphScheduler`, `RecursiveChildIntegrationHost`, `RunTwoOriginRecursiveChildrenUseCase`, `InstalledRecursiveWorkflow`, `InstalledChildRuntimeGrants` | `RecursiveDenialCodes` owns shared recursive parent/deadline/request/integration and token/tool grant exhaustion diagnostics and origin cancellation/execution uncertainty, including the scheduler's corresponding decision/event labels. Keep unrelated stage graph validation and per-origin receipt/byte verification diagnostics separate. |
| `StageGraphPolicy`, `StageGraphContracts`, `ReviewerStageContracts`, `StageRoleExecutionContracts`, `RunReviewerStageUseCase`, `RunFiveRoleWorkflowUseCase`, `LinuxFiveRoleWorkflowHost`, `LinuxStageToolSessionOpener` | `StageDenialCodes` owns shared developer evidence/handoff and actor failures, frozen input/source change, human approval required, unknown stage, artifact/output/actor validation, request correlation, recovery and stage file size, and stage tool availability. Reviewer reason wire values belong to `ReviewerReasonCode`; disposition and scope reuse their existing enums. Graph-local validation remains distinct. |
| `WorkspaceManager`, `WorkspaceContracts`, `ToolAuthorizationPolicy`, `StageToolExecutionContracts`, `TrustedWriteRuntime`, `LinuxStageGitBaselineReconciler`, `WritableWorkflowCli` | Reuse existing workspace denial owners for workspace identity/configuration/lease failures. Keep Git command/environment names, stage baseline vs read-only baseline, write preimage/check diagnostics and tool-local validation separate; no authorization allowlist change. |
| `SessionResumeAdmission`, `ResumeFenceAnchor`, `ResumeRuntimeFilesystemPreflight`, `DurableCreatedAcceptedHandoff`, `DurableRuntimeCreatedAcceptedHost`, `SessionCheckpointStore`, `DurableEffectRecordJournal`, `CheckpointBoundHostEffectEvidenceVerifier`, `EffectEvidenceEnvelopePolicy`, `EffectGenesisReconciliation`, `EffectJournalContracts`, `DurableSessionEventLedger`, `DurableRuntimeAcceptedRunningHost`, `TrustedRunningBoundaryPolicy` | Journal-local `ResumeDenialCodes` owns shared fence anchor/reconciliation/CAS/stale writer, fence publication, audit unconfirmed, anchor mismatch, corrupt fence, changed checkpoint and unsafe resume boundary diagnostics. Physical ledger, checkpoint, effect and projection errors have distinct storage/evidence meanings; no wire markers or digests changed. Pinned ledger/runtime and their remaining overlapping literals are excluded from this change. |
| `TaskManager`, `TaskPolicy`, `TaskUseCases`, `TaskCli`, `Main`, `SessionProtocol` | Existing typed task/session outcomes remain authoritative; inspected task/CLI mappings contain no shared uppercase denial-code translations requiring change. |

Literal compatibility tests pin the added codes independently. Existing
behavior tests remain independent and unchanged. Open diagnostic codes
are not closed enums or decoders: adding an unknown-code rejection rule
would change behavior. Existing protocol and journal unknown-value
rejection remains the responsibility of their respective codecs.

The six C3 source pins and approval gate (#207) are unchanged. Explicit
pinned exclusions and remaining audit candidates are recorded below. No
change to diagnostic prose, category, redaction, ordering, dependency graph
or durable format is intended. Full CI and review remain required.

### Token budget denial ownership

- `TOKEN_BUDGET_EXCEEDED`: session/model request accounting and role execution
  limits; owned by `ModelDenialCodes`.
- `MODEL_TOKEN_BUDGET_EXHAUSTED`: fail-closed provider usage grant exhaustion;
  remains local to the provider gateway.
- `TOKEN_BUDGET_EXHAUSTED`: recursive node grant allocation/charging and
  installed child runtime metering; owned by `RecursiveDenialCodes`.
  `TOOL_BUDGET_EXHAUSTED` follows the same recursive ownership.

These codes have different authority boundaries and retain their existing
wire values and decision order.

### Session error categories

`SessionDiagnosticCategories` owns the nine v1 schema category spellings.
Admission, request validation, model and workspace refusals reuse the owner
where reviewed; the pinned runtime and other unchanged adapter mappings keep
their existing literals. The independently specified schema enum is compared
with all nine category constants in `SessionVocabularySchemaIntegrationTest`.
The closed category set is still represented by strings in `Refused` and
`SessionFailure`; typed migration is tracked in #214 and requires C3 review.
`SessionResultMapperIntegrationTest` exercises JSON serialization and parsing
with literal expected codes/categories/messages and an arbitrary future adapter
code, because codes are open strings. This is a projection test of
`SessionFailure`, not a test of runtime exception classification or a domain
decoder. No new category or code acceptance rule is introduced; schema
validation remains independent.

### Implementation-local diagnostic ownership

- `FailClosedProviderUsageGateway` privately owns
  `MODEL_USAGE_UNCONFIRMED`, `MODEL_DEADLINE_EXCEEDED` and
  `MODEL_TOKEN_BUDGET_EXHAUSTED`; these describe provider grant accounting,
  not general model or recursive admission.
- `LinuxStageGitBaselineReconciler` privately owns `UNSAFE_STAGE_FILE`,
  `UNSAFE_STAGE_PATH`, `STAGE_BASELINE_CHANGED` and
  `FOREIGN_WORKTREE_EDIT`; these concern stage handoff/recovery.
- `WorkspaceGitDenialCodes` is internal to infra/workspace and owns
  `GIT_TIMEOUT` and `GIT_STATUS_INVALID`, shared by trusted Git adapters.
  The distinct `GIT_BASELINE_TIMEOUT` remains local to the reconciler.
- `TrustedWriteRuntime` privately owns `APPROVED_ARTIFACT_CHANGED`;
  `WritableWorkflowCli` privately owns `INVALID_EVENT_JOURNAL_PATH`;
  `RecursiveGraphPolicy` privately owns `STAGE_DURATION_LIMIT`.
- `InstalledChildDenialCodes` is internal to app/cli and owns
  `CHILD_USAGE_UNCONFIRMED` and `CHILD_EXECUTION_UNCONFIRMED` shared by the
  installed child meter, bridge and CLI.
- `WorkspaceWriteDenialCodes` is internal to infra/workspace and owns
  `WRITE_SESSION_INVALID`, `UNSAFE_GIT_INDEX`, and `CHECK_UNAVAILABLE`.

The existing literal behavior tests remain independent. These extractions
do not expose new public contracts or change the conditions for denial.

### Reviewer wire vocabulary

`ReviewerReasonCode` owns the exact six allowlisted reason spellings:
`OK`, `IMPLEMENTATION_DEFECT`, `FROZEN_ARTIFACT_CHANGE`,
`EVIDENCE_MISSING`, `CHECK_FAILED` and `OTHER_REQUIRES_HUMAN`.
`ReviewerVerdictPolicy` remains the sole allowlist for combinations; the
fake CLI reuses the reason wire values and the existing
`ReviewerDisposition` / `ReviewerCorrectionScope` enum names (including
`NEEDS_HUMAN` and `IMPLEMENTATION_ONLY`). The decoder retains its explicit
unknown-value rejection. The trusted-write diagnostic `CHECK_FAILED` in
`TrustedWriteRuntime` is a physical check failure, not a Reviewer reason;
its identical spelling does not justify sharing the reason enum.

### Explicit C3 exclusions

Do not modify or refresh pins for the following overlaps. The raw values
remain in the named protected files pending the #207 C3 review process.

| Pinned source | Excluded literals | Reason |
| --- | --- | --- |
| `DurableAcceptedRunningHandoff` | `RESUME_UNSAFE_BOUNDARY`, `RESUME_CHECKPOINT_CHANGED` | Trusted accepted/running handoff pin; unpinned counterparts reuse the journal owner. |
| `DurableSessionEventLedger` | `LEDGER_CHECKPOINT_SIZE_EXCEEDED`, `LEDGER_REOPEN_CHECKPOINT_CAPACITY` | Trusted ledger pin; preserve checkpoint capacity checks. |
| `SessionRuntime` | `RESUME_ADMISSION_UNCONFIRMED`, `RESUME_FILESYSTEM_UNSUPPORTED` | Trusted runtime pin; preserve resume failure mapping. |

### Remaining repeated-in-touched-file candidates (#218)

The #213 scanner review on `2bc43a6` reported 110 repeated rows.
The named shared owners and Reviewer wire migration resolve 11 of those
rows. The following 99 remaining file/value rows are explicitly deferred to
#218 to avoid broadening this slice into a separate review of adapter
and graph failure semantics. Each group identifies the remaining raw
values in its file and the semantic reason for reviewing them together.
This is an inventory, not approval to change their spelling or precedence.

| Production file | Deferred literal values | Reason for separate audit |
| --- | --- | --- |
| `KoogStageRoleExecutor` | `EVENT_SINK_FAILED`, `ROLE_IDENTITY_MISMATCH`, `ROLE_TOOL_SURFACE_UNAVAILABLE`, `INVALID_ROLE_BUDGET_OR_INPUT`, `MODEL_BRIDGE_UNSUPPORTED` | Role bridge event, tool-surface and request validation; preserve the adapter's outcome/event pairing. |
| `InstalledChildRuntimeGrants` | `CHILD_USAGE_INVALID` | Meter's invalid physical usage observation; review with provider usage semantics rather than conflate unknown usage. |
| `InstalledRecursiveWorkflow` | `AUTHENTICATED_CHILD_RECEIPT_MISSING` | Authenticated receipt absence at the installed host boundary; review with other receipt producers. |
| `LinuxFiveRoleWorkflowHost` | `CREATE_PREIMAGE_EXISTS`, `EDIT_PREIMAGE_CHANGED`, `RECOVERY_PREIMAGE_UNAVAILABLE`, `AUTHORITATIVE_ACCEPTANCE_UNCONFIRMED` | Host preimage, recovery and acceptance evidence; review against the separate Git reconciler and stage authority transitions. |
| `RecursiveGraphPolicy` | `INVALID_ROOT`, `GLOBAL_CHILD_LIMIT`, `CHILD_AUTHORITY_ESCALATION`, `INVALID_CHILD_DEPENDENCY`, `CYCLIC_RECURSIVE_GRAPH`, `INVALID_CHILD_RESERVATION`, `NO_PROGRESS_LOOP` | Graph admission, authority, reservation and progress checks; preserve each denial predicate and ordering. |
| `RecursiveChildIntegrationPolicy` | `CHILD_ARTIFACT_UNACCEPTED` | Child artifact acceptance evidence; reconcile with host receipt validation. |
| `StageGraphPolicy` | `SHARED_ROLE_ACTOR`, `INVALID_STAGE_SUPERSESSION`, `INVALID_STAGE_REVIEW`, `UNACCEPTED_STAGE_OUTPUT`, `GRAPH_NOT_RUNNING`, `INVALID_INDEPENDENT_REVIEW`, `GRAPH_NOT_ACCEPTED` | Graph-local state, supersession, actor separation and review validity; review the policy/host boundary before assigning further shared owners. |
| `RecursiveGraphScheduler` | `CHILD_EVIDENCE_UNAVAILABLE`, `INTEGRATION_ATTEMPT_LIMIT`, `STAGE_EXECUTOR_FAILED` | Scheduler execution, integration limits and evidence availability; keep scheduler outcomes distinct from graph admission. |
| `RunDeveloperStageUseCase` | `UNSUPPORTED_DEVELOPER_STAGE`, `TARGET_READ_UNAVAILABLE`, `STALE_TARGET_BASELINE`, `CREATE_TARGET_EXISTS`, `TARGET_ENCODING_INVALID`, `INVALID_DEVELOPER_PROPOSAL` | Developer proposal validation and target baseline/read checks; audit with stage file and artifact producers. |
| `RunFiveRoleWorkflowUseCase` | `HOST_SNAPSHOT_UNAVAILABLE`, `GRAPH_TOKEN_BUDGET_EXHAUSTED`, `WORKFLOW_OUTPUT_MISMATCH`, `MISSING_COMMITTED_GRAPH`, `MISSING_HOST_ACCEPTANCE`, `STAGE_RECONCILIATION_UNCONFIRMED`, `GRAPH_FINISH_UNCONFIRMED` | Orchestrator graph/host confirmation and reconciliation; distinguish uncertain commits from confirmed denial. |
| `RunPreparationStageUseCase` | `UNSUPPORTED_PREPARATION_STAGE`, `STALE_ARTIFACT_APPROVAL`, `HUMAN_APPROVAL_STOPPED`, `HUMAN_APPROVAL_TIMED_OUT`, `NO_PENDING_HUMAN_APPROVAL`, `INVALID_HUMAN_APPROVAL_LEASE`, `HUMAN_APPROVAL_AUTHORITY_UNAVAILABLE`, `STALE_HUMAN_APPROVAL`, `HUMAN_APPROVAL_READ_UNAVAILABLE` | Preparation-specific artifact and human-approval lease/authority outcomes; review together with approval host implementations. |
| `RunTwoOriginRecursiveChildrenUseCase` | `INVALID_ORIGIN_PAIR` | Two-origin pair validation, not individual recursive node admission. |
| `SessionResumeAdmission` | `RESUME_FENCE_LIMIT`, `RESUME_FENCE_CLEANUP_UNCONFIRMED`, `RESUME_FENCE_CONTEXT_CHANGED`, `RESUME_LEDGER_PATH_CHANGED`, `RESUME_LEDGER_IDENTITY_UNCONFIRMED`, `RESUME_EFFECT_RECONCILIATION_INVALID`, `RESUME_LEASE_HELD` | Fence, ledger identity, lease and effect reconciliation checks; preserve storage and replay failure classifications. |
| `ResumeFenceAnchor` | `RESUME_FENCE_HARD_LINK_PROBE_CLEANUP_UNCONFIRMED`, `RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY`, `RESUME_FENCE_AUDIT_CONFLICT` | Physical fence-directory, audit and hard-link probe safety; review publication failure mapping before further extraction. |
| `ModelGateway` | `MODEL_RETRY_EXHAUSTED`, `MODEL_STREAM_INCOMPLETE`, `MODEL_TRANSPORT_FAILED` | Transport/stream and retry failure mapping; keep distinct from general model response and usage budget denials. |
| `ReviewerStageContracts` | `REVIEW_MUTATED_OTHER_STATE`, `UNAUTHORIZED_REPAIR` | Reviewer repair authority and mutation verification, not the reason/scope wire allowlist. |
| `RunReviewerStageUseCase` | `INVALID_REVIEW_RESPONSE`, `REVIEW_FINISH_UNCONFIRMED` | Reviewer decoder and uncertain finish outcomes; distinguish invalid model proposal from authoritative CAS result. |
| `TrustedWriteRuntime` | `PATH_CASE_COLLISION`, `INDEX_MUTATION_DENIED`, `FILE_SIZE_LIMIT`, `WRITE_IO_DENIED`, `MUTATION_DIGEST_MISMATCH`, `WRITE_EXECUTION_DENIED`, `CHECK_OUTPUT_LIMIT`, `CHECK_OUTPUT_UNAVAILABLE` | Trusted write/index and output validation; review physical mutations and check execution together without changing denial order. |
| `LinuxStageGitBaselineReconciler` | `GIT_BASELINE_TIMEOUT`, `INVALID_STAGE_PATH`, `UNSAFE_STAGE_FILE_MODE`, `GIT_COMMAND_INTERRUPTED`, `GIT_BASELINE_INTERRUPTED`, `GIT_COMMAND_UNAVAILABLE`, `UNEXPECTED_GIT_CHANGE`, `STAGE_BRANCH_CHANGED`, `STAGE_WORKSPACE_CHANGED`, `FROZEN_ARTIFACT_CHANGED`, `INVALID_GIT_BASELINE`, `INVALID_STAGE_CHANGESET`, `UNSAFE_GIT_ENTRY`, `UNCERTAIN_GIT_HANDOFF`, `FOREIGN_GIT_OR_INDEX_EDIT`, `POST_RESTORE_IMAGE_UNCONFIRMED` | Git baseline, path and recovery failure classification; review with workspace adapters while retaining recovery-specific uncertainty and timeout semantics. |
| `FailClosedProviderUsageGateway` | `MODEL_REQUEST_TIMEOUT_INVALID` | Provider request timeout validation, not a stage deadline or Git timeout. |
| `WorkspaceManager` | `READ_LIMIT_EXCEEDED`, `INVALID_TASK_PATH`, `RESULT_LIMIT_EXCEEDED`, `GIT_OUTPUT_INVALID`, `GIT_INSPECTION_DENIED` | Read-only workspace path/result limits and Git inspection parsing/denials; review independently of write-capability grants. |

The scanner also matches non-diagnostic literals. In `WorkspaceManager`
and `LinuxStageGitBaselineReconciler`, the Git/locale environment keys
`LC_ALL`, `GIT_CONFIG_NOSYSTEM`, `GIT_CONFIG_GLOBAL`,
`GIT_CONFIG_SYSTEM`, `GIT_ATTR_NOSYSTEM`, `GIT_NO_REPLACE_OBJECTS`,
`GIT_OPTIONAL_LOCKS`, and `GIT_TERMINAL_PROMPT` are external process
environment vocabulary, not denial codes. `GIT_INDEX_FILE` in the
reconciler is also an environment key. `UNCHECKED_CAST` in
`WorkspaceManager` and `TrustedWriteRuntime` is a compiler suppression,
not a runtime diagnostic. Keep these spellings literal at their call sites
in this diagnostic slice; any environment-ownership refactor is separate.

The repeated-row inventory was reconstructed from production source at
`2bc43a6` using the scanner's literal pattern. Strict review of `a391898`
confirmed all 99 deferred rows with the repository scanner and verified
all six C3 source pins (PR #212, review comment 7140593826).

The cross-file literal `DUPLICATE_GRAPH_ID` is intentionally separate:
`StageGraphPolicy` checks uniqueness of stage-run and artifact IDs within
one stage graph, whereas `RecursiveGraphPolicy` checks graph IDs and
stage-run IDs across recursive nodes. The equal diagnostic spelling is
preserved without coupling these distinct graph identity invariants.
Both sites remain documented for the semantic-owner follow-up #218.
