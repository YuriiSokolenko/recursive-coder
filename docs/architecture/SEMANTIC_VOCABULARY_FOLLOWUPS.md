# Semantic vocabulary follow-up implementation (#197–#205)

Parent: #194. Initial invocation slice: PR #196. This document records the
cumulative follow-up branch's actual migration scope and limitations, independently
of the inventory introduced by PR #196. The original base was dev at `9c2ad3e`. PR #196 was squash-merged into dev at `d439306`; the follow-up commits are rebased on that exact tree, retaining the resolved invocation changes.
All nine work items have a focused implementation commit. Do not mark the parent
or any child complete solely because this branch exists.

## Ownership and migration inventory

| Issue | Authoritative owner and changed consumers | Intentional exclusions / remaining audit |
| --- | --- | --- |
| #197 | `ModelProviderIds`, `ModelFeatureIds` in model contracts; `SessionCapabilityIds` in contracts. Admission, both model gateways, CLI fake-model gates, Koog role adapter. | External HTTP fields and finish reasons are separate from configured features; prompt prose and literal independent tests remain unchanged. |
| #198 | `SessionWireFields.kt` groups envelope kinds and identity fields, invocation, workspace, task input, model, tool policy, budget, output, resume, artifact-reference and event fields by contract. Request parser, model config mapper, task mapper, result/event emitters, pre-execution consumers, workspace admission and durable request binding reuse these definitions. | Result-only/diagnostic-only fields with a single producer remain local; durable format fields and unrelated metadata are separate. Further inspection of remaining protocol consumers is required before claiming exhaustive migration. |
| #199 | `SessionEventKinds` and `SessionEventStatuses` in contracts; correlated event adapters, checkpoint and effect readers. CLI exit status uses `SessionOutcome` directly. | Keep emitted phases, schema-reserved phases, event statuses and terminal outcomes distinct. Ledger's broader explicit phase allowlist and existing session phase vocabulary remain unchanged; do not derive authorization sets from enum entries automatically. |
| #200 | `WorkspaceToolNames`, `ToolPolicyModes`, `StageCheckIds`; workspace contracts, authorization and admission, stage use cases, installed root and child hosts. Trusted write/check audit names and HTTP model tool name are private to their adapters. | Git command arguments, write operation schema and model-facing read_task are separate from workspace read policy. Do not widen tool authority. |
| #201 | `SessionDenialCodes`, `ModelDenialCodes`, `ToolDenialCodes`, `WorkspaceDenialCodes`, `StageDenialCodes` in shared contracts. Reuse common code strings across admission, adapters and stage coordinators. | Local-only codes and human-readable messages stay at the producer. Same-spelled errors across unrelated contexts require separate semantic review; not all repository diagnostics have been migrated. |
| #202 | `TaskPriority`, `TaskSchedulerConcurrency`, `TaskWireFields`, `TaskTransitionWireFields` in tasks; task policy, use case, facade and CLI. All durable snapshot keys/version are private to TaskStore and independent of CLI fields. Existing TaskState and SessionOutcome codecs are retained. | Public taskId and private snapshot id differ by design. Task counter zero, invocation minimum and overflow protection are different invariants; #193 behavior remains unchanged. |
| #203 | `StageEventMetadataFields` in contracts; correlated stage/recursive event emitters and pre-execution phase policy. Installed offline check identity is local to CLI; child-origin validation reuses StageKind. | StageRoleLimits, graph limits and typed decisions are already owned. Do not unify stage/role or graph/session states by spelling. See the targeted #203 audit below for proposal, event and installed configuration ownership and C3 exclusions. |
| #204 | `JournalFormatVocabulary` in journal; effect metadata and state keys reused by physical effect journal, effect envelope policy and evidence verifier. Fence/anchor/audit markers remain private to their codecs. Anchor bound reuses existing ResumeFenceStorage.MAX_FENCES. Lock names are internal to journal. | Do not merge unrelated ledger and effect record capacities, magic headers, cryptographic domains, HMAC inputs or wire versions merely because values coincide. Checkpoint task-state allowlist stays local: no journal dependency on tasks. See the targeted completion audit below. |
| #205 | Gradle JVM convention uses local source-set name and e2e tag; Python test-tier, JUnit evidence and CI bootstrap guards reuse their own local constants. | CI YAML and verification metadata, independent Python tests and standalone JavaScript schema checks remain literal or independently specified. Do not import product constants into external verifiers. |

## #200 targeted completion audit

Reviewed `WorkspaceContracts.kt`, `ToolAuthorizationPolicy.kt`,
`SessionRequest.kt`, `SessionPolicy.kt`, `WorkspaceManager.kt`,
`TrustedWriteToolSchema.kt`, `TrustedWriteRuntime.kt`,
`StageToolExecutionContracts.kt`, `LinuxStageToolSessionOpener.kt`,
`RunPreparationStageUseCase.kt`, `RunDeveloperStageUseCase.kt`,
`RunFiveRoleWorkflowUseCase.kt`, `TaskRequestMapper.kt`, `TaskCli.kt`,
`Main.kt`, `InstalledRecursiveWorkflow.kt`, `LinuxFiveRoleWorkflowHost.kt`,
`WritableWorkflowCli.kt`, `KoogStageRoleExecutor.kt`, `ModelGateway.kt`
and the C3-pinned `SessionRuntime.kt`.

- PR #206 already centralized all six read-tool identities in
  `WorkspaceToolNames`, both request modes in `ToolPolicyModes`, and the
  fixed host check ID in `StageCheckIds`. The sealed workspace tool
  variants, explicit read allowlist, request parser, session admission,
  workspace opening/dispatch and stage check consumers reuse those owners.
  The fixed check ID is not the workspace Git diff tool. The installed
  offline check ID remains CLI-local (see #203).
- `SessionRequest` validates both mode spellings but preserves opaque tool
  names; session admission separately requires read-only mode, a read
  grant, no read denial and no writable/immutable inputs. Workspace opening
  additionally rejects unknown allowed tools. Per-call denial wins over
  allowance; the model-facing HTTP tool `read_task` is never a workspace
  grant. The HTTP declaration, response check and model instruction now
  share the existing private adapter constant without changing prompt bytes.
- Trusted write schema operation discriminators and fields are local to
  the decoder; `WriteOperationKind` is a typed authorization fact, not a
  wire codec. The stage adapter maps typed `StageWrite` variants into
  typed runtime operations without granting authority. The developer
  proposal prompt's create/edit labels describe a different, host-selected
  target format. The runtime's write/run_check audit names are private;
  Git command verbs, CLI list subcommand, and prose are distinct.
  No new constants module, schema, dependency, permission or C3 change.
- Independent literal checks retain the six workspace names, two modes,
  and fixed check ID. The schema integration test compares the mode enum
  against the checked-in v1 schema, round-trips independently authored
  request JSON, and rejects invalid mode spellings while retaining opaque
  tool strings. Pure admission tests pin the narrower workspace opening
  gate and deny precedence. The existing write authorization matrices
  remain literal. New Linux integration coverage decodes all three write
  operations, verifies their actual effects and protected/role refusals,
  and checks literal audit identities and fixed-check versus workspace
  tool separation. Unknown operation strings remain rejected by the
  decoder; model/schema acceptance alone never grants runtime permission.

Validation of this completion slice requires all six CI jobs on the exact
candidate head and independent review; no local Gradle pass is claimed.

## #197 targeted completion audit

Reviewed the current dev versions of `ModelContracts.kt` (including
`ModelConfig`), `SessionCapabilityIds.kt`, `SessionRequest.kt`,
`SessionRuntime.kt` (including C3-pinned `admissionFacts()`),
`SessionPolicy.kt` (including `SessionAdmissionPolicy`),
`ModelGateway.kt` (factory, fake and OpenAI-compatible gateways),
`FailClosedProviderUsageGateway.kt`, `Main.kt`,
`InstalledRecursiveWorkflow.kt`, `WritableWorkflowCli.kt` and
`KoogStageRoleExecutor.kt`. Also inspected
`RunSessionUseCase.kt`, `TaskRequestMapper.kt`, `CompositionRoot.kt`,
`InstalledChildRuntimeGrants.kt`, `LinuxFiveRoleWorkflowHost.kt` and
`TaskCli.kt` for additional consumers.

- PR #206 already centralized the configured provider IDs and fake model ID
  in `ModelProviderIds`, the six configured feature IDs in
  `ModelFeatureIds`, and the four runtime capability IDs in
  `SessionCapabilityIds`. The reviewed production consumers use those
  definitions; no additional production substitution is necessary.
- The admission capability allowlist, provider-specific fake feature set,
  remote usage requirement, gateway feature allowlist and metered gateway
  restrictions are separate policy decisions. They remain explicit and
  unchanged; declaring an identifier does not grant support or authority.
- Model discovery's advertised feature names correspond to configured
  features. Chat Completions `tool_calls` response fields, the
  `tool_calls` finish reason and the `tool_choice` request key instead
  belong to the HTTP adapter. They are not configuration feature constants.
  Other HTTP fields, credential references, prompt text, diagnostic prose,
  Koog-local model identifiers and independent fixture literals remain
  separate. The v1 schema deliberately permits opaque identifiers; reserved
  or unknown values are not evidence of runtime support.
- Two independent model extraction paths use `ModelConfigFields`:
  C3-pinned `SessionRuntime.admissionFacts()` reads provider, model ID and
  features directly from the raw request for `SessionAdmissionPolicy`;
  it also reads required capabilities through `SessionRequestFields`.
  `ModelConfig.from` decodes gateway configuration and preserves opaque
  identifiers without normalization. Literal JSON-to-`ModelConfig`
  mapping tests cover the latter path. Round-trip coverage is not
  applicable: `ModelConfig` is decode-only and has no production encoder.
- Literal unit tests pin provider dispatch, fake identity and exact feature
  selection, fallback gateway negotiation refusal, and pre-HTTP rejection
  of wrong providers, missing tool support and unknown remote features.
  A separate integration-tier test pins fallback gateway `next()` refusal
  without prior negotiation. It constructs a `Path` as required by the
  gateway API; the tier guard forbids explicit `Path` references in unit
  fixtures even without real filesystem access.
  The remote refusal test uses a failing credential lookup to guard against
  reaching HTTP request construction. Existing literal admission tests pin
  unknown capability/provider denial, fake restrictions, usage requirements
  and denial precedence. Existing HTTP integration tests pin remote feature
  rejection and response behavior; provider-usage and Koog tests retain
  their separate restrictions. The literal identifier drift test and
  schema/examples are intact.

No schema, wire, durable format, authorization, production code or dependency
changes are needed for this completion slice. Full CI is required before
merge; this audit does not close #194.

## #202 targeted completion audit

Reviewed `TaskPolicy.kt` (including TaskState, Task, TaskQueue and transition policy),
`TaskVocabulary.kt`, `TaskUseCases.kt`, `TaskOperationsUseCases.kt`,
`TaskManager.kt`, `TaskRepository.kt`, `TaskRequestMapper.kt`,
`TaskStore.kt`, `TaskJsonMapper.kt` and `TaskCli.kt`.

- Task-owned unclaimed attempt, priority validation and scheduler concurrency
  defaults/bounds have explicit owners in tasks. `TaskSchedulerConcurrency` is
  public only to share the scheduler limits/default with CLI; it is independent
  of model, stage and recursive-graph parallelism. Diagnostics and CLI help
  derive their numeric values from the respective owners. The zero sentinel is not
  SessionProtocol's positive invocation minimum. Int.MAX_VALUE and the
  checked increment remain the authoritative overflow boundary (#193).
- Dependency-attention reason prefix and cancellation eligibility are private
  TaskPolicy definitions. Other state sets serve distinct decisions and stay
  explicit; no automatic derivation from enum membership.
- Scheduler outcomes remain typed until the existing string-facing completion
  boundary; TaskRunner and TaskService compatibility APIs and pre-transaction
  validation in DefaultCompleteTaskAttemptUseCase are unchanged. The repeated
  scheduler-stop reason has one local owner.
- CLI queue-report paused key is local, independent of task state and store
  paused flag. TaskJsonMapper already uses typed codecs and task projection
  keys. Its single taskId field is distinct from protocol task input and
  private snapshot id. TaskStore already owns all v1 keys, and no
  persistence, lock, validation, field order or optional-field default changes
  are needed. TaskRepository has no duplicated wire vocabulary.
- TaskRequestMapper uses protocol-owned fields; its request byte-size limit is
  a cross-cutting request constraint rather than task vocabulary. Leave it to
  the protocol/request audit instead of creating a competing task constant.
  File names, human help and diagnostics, poll intervals, graph traversal
  markers and unrelated zero-valued counters remain independent.
- Independent literal tests pin the existing task state and CLI mapping,
  snapshot field order, all state/outcome codecs and unknown state/outcome rejection;
  literal 0/33 rejection and 1/32 acceptance exercise the scheduler entry point.
  The existing #193 counter, priority, fairness and scheduler tests are retained.

Verification requires the full CI matrix; this audit does not close #194.

## #203 targeted completion audit

Reviewed against dev at `373a7c826950a8b40c3604f218d5c8e7f75e79ff`.
This slice retains the existing stage and recursive domain contracts and
consolidates the remaining shared proposal, event and installed offline
configuration vocabulary identified in the following targeted source audit.

| Reviewed files | Ownership and disposition |
| --- | --- |
| `StageGraphContracts`, `StageGraphPolicy`, `StageRoleExecutionContracts`, `StageRoleLimits`, `StageToolExecutionContracts`, `ToolAuthorizationPolicy` | Stage/role/artifact/state enums and typed decisions remain distinct. Explicit five-role order is shared by the coordinator and installed CLI; it is not derived from future enum entries. Existing role limits, graph validation, tool authority and explicit policy sets remain unchanged. |
| `PreparationStageFinishPolicy`, `PreparationStageHostContracts`, `RunPreparationStageUseCase`, `RunDeveloperStageUseCase`, `RunFiveRoleWorkflowUseCase` | Preparation approval and stage state are separate typed decisions; existing instruction/proposal limits remain authoritative. Developer proposal fields and operation prose are local to the decoder/prompt, not workspace tool or protocol fields. |
| `ReviewerStageContracts`, `RunReviewerStageUseCase` | Reviewer proposal keys and trusted prompt evidence tags are shared with the fake installed producer; existing evidence prefixes, disposition/scope and reason codes are reused. Explicit decode and CAS allowlists remain independent of enum membership. The evidence count, displayed history count, configured reviewer-repair retry ceiling and host failed-write retry ceiling are separate limits, not one generic 32. |
| `RecursiveGraphPolicy`, `RecursiveChildIntegrationPolicy`, `RecursiveGraphScheduler`, `RecursiveChildIntegrationHost`, `RunTwoOriginRecursiveChildrenUseCase` | Existing graph/child identity constructors, global child cap, node states, typed decisions and per-origin grant accounting remain authoritative. Hash inputs, ID prefixes, budget arithmetic, dependency rules and scheduling/authorization checks are unchanged. |
| `CorrelatedSessionEvents`, `PreExecutionPhasePolicy`, `InstalledRecursiveWorkflow`, `InstalledChildRuntimeGrants`, `SessionCheckpointStore`, `DurableSessionEventLedger`, `SessionRuntime`, `SessionResultMapper` | Stage event metadata owns origin, request ID, code, artifact identity/digest, preparation and Reviewer fields, and child audit fields. Session usage event metadata has its own owner. These keys are admitted to durable event storage by the pinned ledger; the checkpoint evidence reader shares the artifact metadata owner. See pinned exclusions below. Envelope, proposal, CLI result and result usage fields remain separate. The BOTH origin sentinel is local to multi-origin reporting. Child identity derivations, attested bytes, grants and audit meanings are retained. |
| `WritableWorkflowCli`, `LinuxFiveRoleWorkflowHost`, `CompositionRoot` | Installed plan and approval field groups are private to the CLI. Root and child checkout fields share their local owner; artifact and Developer target paths, root and child token/tool limits, and plan/approval versions are distinct. Child token overrides and default child grant use the same existing range. Local isolation marker and repeated CLI outcome labels are shared. The explicit installed origin and host admission sets are unchanged. |
| `KoogStageRoleExecutor` | Uses EngineeringRole and StageRoleLimits directly. Koog strategy/prompt IDs, provider usage and role prompt prose are adapter-local, not shared stage ID constructors. No new execution or model authority. |

### #203 event metadata ownership and C3 exclusions

The unpinned producers and consumers share the following exact metadata keys.
Every corresponding raw allowlist entry in the C3-pinned
`DurableSessionEventLedger` remains intentionally unchanged:

| Owner and unpinned sites | Exact durable metadata keys excluded from the pinned ledger migration |
| --- | --- |
| `StageEventMetadataFields`: recursive/role events, installed audit | `origin`, `requestId`, `code` |
| `StageEventMetadataFields`: preparation events and checkpoint evidence reader | `artifactId`, `artifactSha256` (both); `kind`, `approval` (preparation only) |
| `StageEventMetadataFields`: Reviewer events | `verdict`, `reasonCode`, `scope`, `reviewedSha256`, `evidenceCount` |
| `StageEventMetadataFields`: installed child audit and recursive stop | `childResourceAudit`, `observedTokens`, `observedToolCalls`, `accountedTokens`, `accountedToolCalls`, `usageUncertain`, `physicalCompleted`, `schedulerChargeRecorded`, `recursiveStopCode`, `requiresReconciliation` |
| `SessionUsageEventMetadataFields`: role events | `modelRequests`, `inputTokens`, `outputTokens` |

The pinned `SessionRuntime` also retains its raw event metadata
`inputTokens` and `outputTokens`. These are additional C3 exclusions;
the same spellings in `SessionResultMapper` are independent result usage
fields, not event metadata, and belong to the result-envelope audit.
Reviewer event `verdict` and `scope` are independent of the model-facing
`ReviewerProposalFields`. No other raw metadata keys remain in the two
touched emitters; the pinned ledger's explicit key categories, membership,
types and validation are unchanged. Migrate the listed pinned references
only under the independent C3 review tracked by [#207](https://github.com/YuriiSokolenko/recursive-coder/issues/207).
Do not refresh approved hashes as part of #203.

The journal diagnostic and denial codes in `SessionCheckpointStore` are outside
the #203 scope: the 23 repeated codes and the three codes shared with the
C3-pinned `DurableSessionEventLedger` remain unchanged. The shared codes are
`CHECKPOINT_LEASE_HELD`, `LEDGER_NOT_FENCED` and
`LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED`. Their journal-domain audit was
recorded in #204 (merged); consolidation is now tracked by
[#249](https://github.com/YuriiSokolenko/recursive-coder/issues/249).
Migration of pinned ledger references also requires independent C3 review
under #207. The checkpoint artifact metadata owner and its use in the
checkpoint reader are already integrated by #204 and remain unchanged.

Existing independent literal stage/role, Reviewer decision matrix, scheduler,
correlated events, and installed workflow tests remain intact. New literal
tests pin the shared order, stage bounds, metadata, proposal keys and evidence
grammar. Reviewer integration exercises unknown verdict, scope, reason and
field spellings, extra and missing fields. Installed CLI E2E rejects unknown
fields at plan, approval, workspace, check, artifact, Developer target, limits
and both child decode sites. Literal event assertions and existing checkpoint
positive/negative evidence fixtures independently pin artifact identity/digest.
The artifact key unit oracle introduced by #204 is retained without duplication.
The existing installed success path exercises the real fake proposal producer
and Reviewer decoder with literal expected outcomes. There is no general
stage-graph wire codec to round-trip. Plan/approval are decode-only;
the independent Python producer remains literal. No schema, durable format,
dependency or authority changes are intended. Full CI is required; #194
remains open.

## #204 targeted completion audit

Reviewed the journal production sources on dev at
`313ba87454de8a9ada747ff7a9b3eb05c3ec82a4` and the related effect
contracts. The following inventory separates physical formats, evidence
projections and protocol events.

| Reviewed files | Ownership and disposition |
| --- | --- |
| `DurableEffectRecordJournal`, `JournalFormatVocabulary`, `EffectJournalContracts` | The physical codec owns its JSON keys and genesis discriminator. The journal and checkpoint codec share the internal `EffectJournalLimits.MAX_RECORDS` (100,000), outside the opt-in-protected writer; the session ledger's equal capacity is independent. Effect kinds and records remain typed; effect state wire names are already shared with evidence projections. |
| `SessionCheckpointStore` | The checkpoint codec owns v1/v2/v3 payload versions and grouped JSON field sets. Physical RCCHK1 framing and Session Protocol v1 are independent. The two embedded effect heads share the same head schema. Session phase values reuse the existing lifecycle vocabulary where exposed. |
| `SessionResumeAdmission`, `DurableCreatedAcceptedHandoff` | Share complete checkpoint-local task, child and usage vocabulary with the codec. Child readiness is distinct from task readiness; no dependency on tasks or automatic expansion of accepted states. Child drift is checked against RecursiveNodeState; task cross-module oracle is tracked by #246. |
| `DurableRuntimeCreatedAcceptedHost`, `JsonlEventSink` | C1 event emission and validation reuse Session Protocol request/envelope/event fields and statuses. JSONL remains a projection, not a physical ledger format. |
| `CheckpointBoundHostEffectEvidenceVerifier`, `EffectEvidenceEnvelopePolicy` | Existing effect metadata keys, effect record states, typed effect kinds and session event pairs are already reused. The reserved effect prefix, diagnostic codes, exact key allowlists and event/record order remain unchanged. |
| `ResumeFenceAnchor`, `ResumeRuntimeFilesystemPreflight`, `ResumeDenialCodes` | Fence, anchor and audit markers and storage paths remain format-local. The existing fence maximum and lock names already have shared owners. Filesystem type names, probe prefixes and refusal diagnostics are distinct from persisted record vocabulary. |
| `EffectGenesisReconciliation`, `EffectAuthorityContracts` | Keep separate HMAC/digest domains, authority root versions, reconciliation marker and typed authority states. These are not the physical journal record states or Session Protocol versions. |
| `DurableSessionEventLedger`, `DurableAcceptedRunningHandoff`, `DurableRuntimeAcceptedRunningHost` | C3-pinned sources stay byte-identical, including remaining protocol fields, task readiness literals, ledger phase allowlists and fault hooks. No pin refresh. |

Other equal values are not automatically shared: physical file size and frame
limits, checkpoint collection bounds, digest widths and domain-specific
state names remain local. The checkpoint success phase reuses
`SessionPhaseVocabulary.succeededWire` with a literal drift assertion;
a terminal outcome is not a substitute. The checkpoint usage checks retain
literal `llm.` and `tool.` namespace prefixes: these are family predicates,
not individual event identifiers or checkpoint usage-kind names.
The producer and verifier share stage artifact metadata keys through
`StageEventMetadataFields`; C3 ledger allowlist entries remain pinned.
Independent fixtures, literal negative inputs, authorization checks and
rejection order remain intact.

New tests pin checkpoint version discrimination, embedded head field order
and the physical record capacity boundary. MAC-valid forged records with
unknown state or effect kind must still be rejected. Existing independent
HMAC, ledger, fence, checkpoint and projection tests are retained.

No local Gradle execution is claimed. Full CI is required before merge.
This audit does not close #194.

### Diagnostic inventory and exclusions

The following repeated diagnostic literals are existing format-local
refusals, not new protocol vocabulary. They remain literal in this
format-field refactoring: their storage, authentication, validation and
recovery decisions are not being consolidated as part of #204. The deferred
journal and checkpoint diagnostic consolidation is tracked by #249.
Counts below are source occurrences, excluding constant definitions, using
the trusted base scanner's UPPER_SNAKE literal pattern.

| File | Diagnostic | Count |
| --- | --- | ---: |
| `SessionCheckpointStore` | `CHECKPOINT_WORKSPACE_INVALID` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_PAYLOAD_INVALID` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_VERSION_UNSUPPORTED` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_UNSAFE_DIRECTORY` | 4 |
| `SessionCheckpointStore` | `CHECKPOINT_LEASE_HELD` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_UNSAFE_LOCK` | 3 |
| `SessionCheckpointStore` | `CHECKPOINT_LEASE_RELEASE_UNCONFIRMED` | 3 |
| `SessionCheckpointStore` | `CHECKPOINT_SIZE_INVALID` | 4 |
| `SessionCheckpointStore` | `CHECKPOINT_TRUNCATED` | 3 |
| `SessionCheckpointStore` | `CHECKPOINT_UNRECOGNIZED_ENTRY` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_CHAIN_MISMATCH` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH` | 9 |
| `SessionCheckpointStore` | `CHECKPOINT_EFFECT_RECONCILIATION_SOURCE_MISMATCH` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_EFFECT_HEAD_FENCE_MISMATCH` | 4 |
| `SessionCheckpointStore` | `CHECKPOINT_JOURNAL_UNCONFIRMED` | 4 |
| `SessionCheckpointStore` | `CHECKPOINT_LIVE_LEDGER_TOO_LARGE` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_WRITE_UNCONFIRMED` | 4 |
| `SessionCheckpointStore` | `CHECKPOINT_EFFECT_RECONCILIATION_UNVERIFIED` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_STALE_GENERATION` | 3 |
| `SessionCheckpointStore` | `CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_RUNNING_BOUNDARY_CHANGED` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_FENCED_LEDGER_ADVANCED` | 2 |
| `SessionCheckpointStore` | `CHECKPOINT_MISSING` | 5 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_PIN_PUBLICATION_UNCONFIRMED` | 2 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_UNSAFE_PATH` | 5 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_BUSY` | 2 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_UNSAFE_LOCK` | 2 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_INVALID_RECORD` | 3 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_GENESIS_INVALID` | 9 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_RECORD_CORRUPT` | 10 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_APPEND_UNCONFIRMED` | 3 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_CONTEXT_MISMATCH` | 4 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_SIZE_INVALID` | 4 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_TRUNCATED` | 2 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_AUTH_FAILED` | 2 |
| `DurableEffectRecordJournal` | `EFFECT_JOURNAL_PATH_CHANGED` | 2 |

Three cross-file codes are shared with the C3-pinned ledger:
`CHECKPOINT_LEASE_HELD` (checkpoint lock contention),
`LEDGER_NOT_FENCED` (absence of a fenced ledger writer), and
`LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED` (reopened ledger/checkpoint
binding cannot be confirmed). Keep the existing literal spellings and
their same-meaning sites until a separately authorized C3 migration (#249);
do not create competing owners or refresh pins in this slice.

Owned-but-raw C3 exclusions reported by the base scanner:
`LEDGER_CHECKPOINT_SIZE_EXCEEDED`, `LEDGER_REOPEN_CHECKPOINT_CAPACITY`,
`RESUME_ADMISSION_UNCONFIRMED`, `RESUME_CHECKPOINT_CHANGED`,
`RESUME_FILESYSTEM_UNSUPPORTED`, and `RESUME_UNSAFE_BOUNDARY`.
These remain excluded pinned literals, not independent new diagnostics.
The stage artifact metadata allowlist in the pinned ledger likewise retains
`artifactId` and `artifactSha256`.

## Trusted C3 boundary exclusion

The architecture gate rejected changes to three SHA-256-pinned sources:
`SessionRuntime.kt`, `TrustedRunningBoundaryPolicy.kt` and
`DurableSessionEventLedger.kt`. PR #206 restored their exact bytes and retained the original pins. The separate
#208 reintroduces the literal-only substitutions in these files,
`DurableRuntimeAcceptedRunningHost.kt` and `DurableAcceptedRunningHandoff.kt`.
The ledger's explicit authorization allowlists remain explicit and unchanged in
membership. The six source pins are retained and updated only for the exact
reviewed bytes; the new JVM verifier is an additional defense. See the [Trusted C3 review ledger](TRUSTED_C3_REVIEW.md).
Pin retirement remains open under #207 and requires independent human review
and enforced approvals. PR #208 requires fresh CI and owner acceptance.

## Changed-file inventory

The following files were changed relative to the stated dev base at the end of
the implementation commits; this is not a claim that every repository file has
been scanned or that each issue's complete acceptance criteria are satisfied.

- `agent/runtime`: `KoogStageRoleExecutor.kt`.
- `app/cli`: `InstalledRecursiveWorkflow.kt`, `LinuxFiveRoleWorkflowHost.kt`, `Main.kt`, `TaskCli.kt`, `TaskJsonMapper.kt`, `WritableWorkflowCli.kt`.
- `build-logic/src`: `recursivecoder.jvm.gradle.kts`.
- `core/contracts`: `ModelContracts.kt`, `ModelDenialCodes.kt`, `PreparationStageFinishPolicy.kt`, `SessionCapabilityIds.kt`, `SessionDenialCodes.kt`, `SessionEventVocabulary.kt`, `SessionRequest.kt`, `SessionWireFields.kt`, `StageDenialCodes.kt`, `StageEventMetadataFields.kt`, `StageToolExecutionContracts.kt`, `ToolAuthorizationPolicy.kt`, `ToolDenialCodes.kt`, `WorkspaceContracts.kt`, `WorkspaceDenialCodes.kt`, `ModelVocabularyUnitTest.kt`, `SessionEventFieldsUnitTest.kt`, `SessionEventVocabularyUnitTest.kt`, `SessionWireFieldsUnitTest.kt`, `SharedDenialVocabularyUnitTest.kt`, `StageVocabularyUnitTest.kt`, `ToolVocabularyUnitTest.kt`.
- `core/session`: `CorrelatedSessionEvents.kt`, `PreExecutionPhasePolicy.kt`, `RunDeveloperStageUseCase.kt`, `RunPreparationStageUseCase.kt`, `RunReviewerStageUseCase.kt`, `SessionEventJournal.kt`, `SessionPolicy.kt`, `SessionResultMapper.kt`.
- `core/tasks`: `TaskManager.kt`, `TaskPolicy.kt`, `TaskRequestMapper.kt`, `TaskUseCases.kt`, `TaskVocabulary.kt`, `TaskVocabularyUnitTest.kt`.
- `infra/journal`: `CheckpointBoundHostEffectEvidenceVerifier.kt`, `DurableEffectRecordJournal.kt`, `DurableRuntimeCreatedAcceptedHost.kt`, `EffectEvidenceEnvelopePolicy.kt`, `JournalFormatVocabulary.kt`, `ResumeFenceAnchor.kt`, `SessionCheckpointStore.kt`, `SessionResumeAdmission.kt`, `JournalFormatVocabularyUnitTest.kt`.
- `infra/model`: `FailClosedProviderUsageGateway.kt`, `ModelGateway.kt`.
- `infra/task-store`: `TaskStore.kt`.
- `infra/workspace`: `LinuxStageToolSessionOpener.kt`, `TrustedWriteRuntime.kt`, `WorkspaceManager.kt`.
- `tools`: `check_ci_bootstrap.py`, `check_junit_evidence.py`, `check_test_tiers.py`.

Additional targeted inspection covered the existing Gradle architecture convention,
architecture graph/build-script checks, dependency-verification guard, JavaScript
protocol validator, StageGraphContracts, StageGraphPolicy, StageRoleLimits,
RecursiveGraphPolicy, and task/journal contracts and adapters. Their existing
typed values, external identifiers and independent oracle literals were retained
where no shared semantic replacement was justified. A later targeted audit
of all 84 production Kotlin sources found two remaining CLI event emissions
and the C3 literals addressed by #208. The checkpoint binding's graphId key
remains codec-local, distinct from event metadata. This is not a claim that
all unrelated diagnostics, numbers, or cross-language tooling have been audited.

## Review follow-up

The shared stage event metadata keys in `SessionCheckpointStore`, C1 event emission
in `DurableRuntimeCreatedAcceptedHost`, stage readiness in `StageGraphPolicy`
and `RecursiveGraphScheduler`, and sandbox denials in
`LinuxStageGitBaselineReconciler` use their vocabulary owners. Checkpoint
binding fields are distinct from event metadata and remain local. The C3 host emitter is migrated in the separate PR #208 with source pins retained. Tool invocation denials `FORGED_TOOL_ID` and
`TOOL_QUOTA_EXCEEDED` belong to `ToolDenialCodes`.

## Compatibility and verification

Independent literal tests were added for model and capability values, request
and event fields, event kinds/statuses, workspace tool names/modes, diagnostic
codes, task state codec/priority, stage vocabulary and journal evidence/fence
values. A schema-backed integration test compares actual vocabulary constants with
canonical v1 properties and enums; literal unit tests remain independent change
detectors. Existing parser, admission, authorization, lifecycle, E2E and persistence
tests are unchanged. The changes intend to preserve serialized values, field
order, digest inputs, denial order, unknown-value rejection and module dependencies.

No local full Gradle/CI execution is claimed. Require all architecture, pure-unit,
integration, installed CLI E2E and protocol CI jobs before merge. Do not close
#194 or #197–#205 until the remaining audit and their acceptance criteria have
been verified. PR #196 is merged and the follow-up is rebased on dev. The combined files use
`InvocationFields` for JSON keys and `SessionProtocol` for supported invocation
values and the attempt bound. The review fixes
are folded into their thematic commits; path-based invocation mapper tests
remain integration-tier, with malformed-envelope tests in the pure unit tier.
