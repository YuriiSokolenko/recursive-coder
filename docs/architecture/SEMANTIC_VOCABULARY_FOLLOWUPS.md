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
| #202 | `TaskPriority`, `TaskWireFields`, `TaskTransitionWireFields` in tasks; task policy, use case, facade and CLI. All durable snapshot keys/version are private to TaskStore and independent of CLI fields. Existing TaskState and SessionOutcome codecs are retained. | Public taskId and private snapshot id differ by design. Task counter zero, invocation minimum and overflow protection are different invariants; #193 behavior remains unchanged. |
| #203 | `StageEventMetadataFields` in contracts; correlated stage/recursive event emitters and pre-execution phase policy. Installed offline check identity is local to CLI; child-origin validation reuses StageKind. | StageRoleLimits, graph limits and typed decisions are already owned. Do not unify stage/role or graph/session states by spelling. Additional stage diagnostics and adapter-local plan fields still need a targeted audit. |
| #204 | `JournalFormatVocabulary` in journal; effect metadata and state keys reused by physical effect journal, effect envelope policy and evidence verifier. Fence/anchor/audit markers remain private to their codecs. Anchor bound reuses existing ResumeFenceStorage.MAX_FENCES. Lock names are internal to journal. | Do not merge unrelated ledger and effect record capacities, magic headers, cryptographic domains, HMAC inputs or wire versions merely because values coincide. Checkpoint task-state allowlist stays local: no journal dependency on tasks. More codec-local field candidates remain for review. |
| #205 | Gradle JVM convention uses local source-set name and e2e tag; Python test-tier, JUnit evidence and CI bootstrap guards reuse their own local constants. | CI YAML and verification metadata, independent Python tests and standalone JavaScript schema checks remain literal or independently specified. Do not import product constants into external verifiers. |

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

The explicit event metadata keys in `SessionCheckpointStore`, C1 event emission
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
