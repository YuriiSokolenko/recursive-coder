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
| Build and CI | Build-logic architecture graph, JVM convention, Gradle scripts, CI YAML and Python test-tier/bootstrap/bytecode guards | See the [#205 tooling audit](SEMANTIC_VOCABULARY_TOOLING.md): consolidate local repetitions only; independent guard expectations and external Gradle task names are not production vocabulary. |
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
