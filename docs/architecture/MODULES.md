# Gradle multi-module boundaries

Issue #22 introduced six modules; issues #27 and #29 add the task persistence and event journal adapters; the Gradle root remains `recursive-coder` and is not an executable module.

| Module | Ownership | Production project dependencies |
| --- | --- | --- |
| `:core:contracts` | Session Protocol support, request validation, model/workspace contracts, shared value types and pure tool authorization | none |
| `:core:session` | `RunSessionUseCase` + `DefaultRunSessionUseCase`, compatibility Session Controller, deadline/budget enforcement, admission policy and event journal | `:core:contracts` |
| `:core:tasks` | Task command/query and scheduler-attempt Use Case interfaces and implementations, TaskService façade, policy, repository port and scheduler | `:core:contracts` |
| `:infra:model` | Offline FakeModel and optional OpenAI-compatible HTTP adapter | `:core:contracts` |
| `:infra:workspace` | Workspace Manager, restricted Git and trusted write/check runtime | `:core:contracts` |
| `:infra:task-store` | Durable tasks.json snapshot, file locks and atomic replacement | `:core:tasks`, `:core:contracts` |
| `:infra:journal` | JSONL append/flush/close and physical event-log SHA-256 | `:core:session`, `:core:contracts` |
| `:app:cli` | Clikt command entry point and Koin 4.2.2 interface-to-implementation bindings/composition | all seven libraries |

```text
                      :app:cli  (only executable)
                    /     |        |          \
           :core:session :core:tasks :infra:model :infra:workspace :infra:task-store :infra:journal
                    \     |        |          /
                         :core:contracts
```

Production dependencies point from the composition root and adapters toward the core contracts; no core module depends on the CLI, Koog, or the HTTP model implementation. The concrete workspace opener, model provider and JSONL EventSink are injected by `:app:cli` using Koin factories and constructor injection; Koin is not present in any core module. The scheduler accepts a `TaskRunner` instead of constructing a model adapter. Session Protocol JSON Schema and fixtures remain at the repository root under `contracts/v1/` and continue to be validated independently by Node/Python CI.


## Use Case contracts and Koin composition (issues #58 and #59)

The initial vertical slice introduced `EnqueueTaskUseCase` in `:core:tasks` and `RunSessionUseCase` in `:core:session`. Issue #59 adds `TaskQueriesUseCase`, cancel/retry/pause/resume/replace-dependencies/cancel-all contracts and scheduler claim/complete/interrupt/recover/prepare contracts, each with an independent policy-backed `Default*` implementation. The CLI resolves **interfaces** using explicit Koin module definitions in `:app:cli/CompositionRoot.kt`. It does not construct concrete Use Cases or use `get()` from business logic. No new Gradle layer is needed: interfaces, orchestration and owned ports stay inside the bounded contexts.

`TaskService.enqueue` and `SessionController.run` now delegate to the new implementations while retaining constructor/API compatibility for older callers. Session state (`Cancellation`, opened workspace, event journal, watchdog) is created per invocation, not injected as a shared singleton; `RunSessionUseCase` is a factory. Koin is local to `:app:cli` and versioned in the shared catalog (`koin-core`/`koin-test` 4.2.2); core implementations and tests need no Koin dependencies. `TaskComposition` owns one repository per parsed command and closes with it; per-session scopes close after each direct or scheduled execution, including failures. A pure unit Koin graph test verifies both module definitions, command-scope repository identity, interface resolution and closure paths, without a real file store.

All `tasks` CLI commands and the scheduler resolve injected Use Case interfaces rather than calling `TaskService` business methods. `TaskScheduler` receives `TaskRepository` for its scheduler lifetime lease, plus read/claim/complete/interrupt/recover/prepare interfaces. Its legacy `TaskService` constructor, and `TaskService` itself, remain compatibility-only entrypoints. Every mutable Use Case applies `TaskPolicy` within a repository transaction; the scheduler lease still spans recovery, dispatch and worker shutdown. `RunSessionUseCase` returns immutable `SessionExecutionResult` with a typed outcome and the single already-validated Session Protocol v1 JSON projection; callers reuse it without re-encoding. `SessionRunResult`, `SessionArtifact`, `SessionFailure` and `SessionBudgetState` are intentionally `internal`. No wire format, store schema, event or outcome mapping changed.

## Contracts and request admission

`:core:contracts` intentionally owns the shared `SessionRequest` parser and shape validation (approximately 190 lines) as well as `Cancellation`, `Refused` and `Stopped`. Both `:core:session` and the infrastructure adapters consume these stable types; moving them into `:core:session` would force the adapters to depend on the session runtime. Pure read/write/check authorization decisions and the SDD/ATDD/TDD/Developer/Reviewer ownership matrix now live in `:core:contracts` (`ToolAuthorizationPolicy`). The trusted workspace adapter still owns canonical-path/descriptor checks, Git state, sandbox execution, review provenance and mutation receipts. `contracts/v1` remains the authoritative transport schema.

## Protocol-to-domain mapping inventory (issue #31)

Session Protocol v1 is a **transport** contract, not a Task/Session aggregate.
The existing module ownership is preserved; an additional empty domain or protocol module
would introduce extra dependency edges without separating any working implementation.

| Boundary | Transport coupling | Typed domain owner / mapping decision |
| --- | --- | --- |
| Incoming SessionRequest | `:core:contracts/SessionRequest.kt` parses and validates JSON and paths | Validated DTO shared by CLI, Task and workspace ports; `:core:session/SessionAdmissionPolicy` *independently* checks supported version, capabilities, model, budgets and policy |
| Enqueue and scheduled attempt | `:core:tasks/TaskRequestMapper.kt` parses/substitutes v1 requests | `:core:tasks/DefaultEnqueueTaskUseCase` accepts validated submissions via `EnqueueTaskUseCase`; `TaskService` delegates for compatibility and `TaskPolicy` handles Task transitions without creating JSON |
| Session termination | `:core:session/SessionResultMapper.kt` projects v1 result, diagnostics and artifact refs | `SessionLifecyclePolicy` decides typed `SessionOutcome`, budget is `SessionBudgetState`, and coordinator passes `SessionRunResult` |
| Task completion | `DefaultCompleteTaskAttemptUseCase` converts wire status via `SessionOutcome.fromWire` before mutation | `TaskPolicy.finish` receives only a typed outcome; unsupported values fail before transaction with a fixed, non-echoing diagnostic |
| Task CLI output | `:app:cli/TaskJsonMapper.kt` | Typed `TaskState` and `SessionOutcome` stay in the core aggregate |
| Durable tasks.json | `:infra:task-store/TaskStore.kt` persists state and status strings | Decodes them to typed values and rejects unknown statuses as corrupt snapshots |
| Model/tool/event JSON | `:infra:model` handles model wire JSON; `:core:session` encodes journal event envelopes | Not part of Task lifecycle decisions; unrelated event/serialization rewrites remain deferred |

Wire values, field names/order, CLI exit codes, Session Protocol v1 schema/fixtures,
and tasks.json v1 format are not changed. Validation of JSON shape is never a substitute
for Session admission or trusted workspace checks. Mapping exceptions use stable messages
rather than echoing inbound request text.

## Koog dependency

The previously unused `ai.koog:koog-agents:1.3.0` dependency was deliberately dropped from the original root application as part of this structural split. No production source imports it; therefore removing it changes dependency resolution checks, **not current runtime behavior**. The renamed JVM CI job no longer verifies Koog artifact availability. When Koog-backed orchestration is implemented in a future `:agent:runtime` module, restore a tested, module-scoped Koog dependency and CI coverage.

## Build and test

With JDK 21 and Gradle 9.8.1 installed, run from the repository root:

```sh
./gradlew clean test installDist
python -m unittest discover -s tests -p 'test_cli_runtime_v1.py' -v
```

Module-local unit tests can be run separately with `./gradlew :core:session:test :infra:workspace:test`:

- `:core:session` owns the pure `SessionLifecyclePolicy`, `SessionAdmissionPolicy` and `SessionBudgetPolicy` with deterministic value-only tests (`SessionPolicyUnitTest`). `SessionEventJournalUnitTest` validates event envelopes with an in-memory sink. `SessionControllerUnitTest` uses in-module `ModelGateway`, `WorkspaceSession` and `EventSink` fakes instead of physical event files to exercise orchestration, admission, trusted observations, budgets, errors and event ordering without concrete adapters.
- `:infra:workspace` owns write-tool schema validation and direct Workspace Manager policy/adapter tests. They use only `:core:contracts` types; the small Git fixture stays inside the infrastructure module.
- `:app:cli` retains the real Session Controller + FakeModel + Workspace Manager integration tests, CLI scheduler tests, and sandbox E2E. These are intentionally not copied into the unit-test modules.

Both the production and test project dependency graphs stay acyclic: `:core:session` and `:infra:workspace` depend only on `:core:contracts`, while `:app:cli` is the sole cross-module composition root. Core production classpaths remain adapter-free. No live inference service or private hardware is needed. The CLI installation path and executable name remain `build/install/recursive-coder/bin/recursive-coder`, and arguments, exit codes, and on-disk protocol formats are unchanged.

Shared Kotlin/JVM 21 and JUnit settings live in the `build-logic` included build as the `recursivecoder.jvm` convention plugin. The Kotlin plugin and Kotlin JUnit integration versions are both sourced from `build-logic/gradle/libs.versions.toml`, which the root and included build consume. Third-party dependencies remain declared at their owner. `gradlew` is still the repository's pre-release delegation script; replacing it with a self-contained wrapper is tracked separately in issue #12.

## Deliberately deferred

The current code does not justify empty `:agent:runtime` or `:infra:tools` modules. When recursive Koog orchestration and an independently owned tool execution layer exist, those modules can be extracted with focused boundary tests. Until then the trusted write/check implementation remains alongside restricted Git in `:infra:workspace`.


## Verified architecture and test tiers (issue #25)

The root `checkArchitecture` task enforces the project dependency directions in the table above for every Gradle configuration, including test-only edges and future fixtures. It also detects project cycles and rejects direct core dependencies on common CLI/Koog/HTTP frameworks. CI includes deliberately illegal dependency and test-cycle regressions. A new module requires an intentional allowlist update.

The JVM `unitTest` target contains only tagged pure tests; `integrationTest` runs tagged local filesystem, Git and mock-HTTP cases; `e2eTest` selects tagged sandbox checks. The existing `test` target remains **unfiltered**, preserving all existing coverage and the `./gradlew test installDist` workflow. CI retains the sandbox execution assertion, installed CLI E2E and independent protocol tests.

The file-backed TaskStore now lives in `:infra:task-store`; the CLI composes it with injected task and scheduler Use Cases, while `:core:tasks` ships a pure in-memory repository for use-case tests. The physical journal lives in `:infra:journal` (#29); event envelope semantics stay in `:core:session`. See [Test tiers and architecture guardrails](TEST_SUITES.md) for detailed classification, commands and exceptions.

## Task repository (issue #27)

The TaskRepository port owns atomic reads, read-decide-write mutations, the process-wide scheduler lease and run output-root resolution. Task Use Cases and the TaskService compatibility façade depend only on that port; the CLI is the sole owner of TaskStore selection. Both adapters implement the same transactional interface. The CLI composition explicitly owns the constructed `TaskStore` and closes any future resource-owning `AutoCloseable` adapter after Koin shutdown. A borrowed repository is not closed by the composition; the current `TaskStore` uses only operation-scoped resources and needs no close method. The filesystem adapter preserves tasks.json v1 bytes, corruption rejection, POSIX/OS file locks, fsync and atomic rename. An explicit cross-process lock regression and shared transaction contract guard semantics. The core test graph does not depend on an infrastructure project.
