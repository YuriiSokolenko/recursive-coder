# Recursive Coder — Target Architecture

> **Status:** Accepted architectural direction; incremental migration, not a claim that all components exist.  
> **Decision date:** 2026-10-13; **implementation status verified:** 2026-11-11 (`dev@4ed9fe8`)  
> **Target:** Kotlin/JVM + JetBrains Koog, independently runnable with a local or configured remote LLM.  
> **Related:** [Current architecture](ARCHITECTURE.md) · [Gradle modules](MODULES.md) · [Session Protocol v1](../../contracts/README.md)

## 1. Decision

Recursive Coder will combine **Clean Architecture**, **Hexagonal Architecture (Ports & Adapters)**, pragmatic **Domain-Driven Design (DDD-lite)**, and **Functional Core / Imperative Shell**.

- **Clean Architecture** governs dependency direction: stable domain and application decisions are independent of infrastructure, frameworks, transports and user interfaces.
- **Ports & Adapters** isolate model providers, trusted workspaces, task storage, event journals, clocks and future integrations. The core owns the contracts it needs; external implementations satisfy them.
- **DDD-lite** establishes clear Task, Session and Agent Execution boundaries, invariants and vocabulary without imposing a large tactical-DDD framework.
- **Functional Core / Imperative Shell** keeps security, scheduling, resource-budget and state-transition *decisions* deterministic, while a thin coordinating shell invokes I/O and commits effects.
- **Event-driven observability** records correlated, versioned facts and verified outcomes. Event sourcing, CQRS and distributed services are **not** required. Koin is mandatory only at the CLI composition root; core decisions and contracts remain framework-free.

This is a direction for controlled evolution of the existing product, **not** a greenfield rewrite or a reason to introduce empty Gradle modules.

## 2. Dependency rule

**Source dependencies point inward; runtime control stays with Recursive Coder.** The agent must not delegate task/session authority to Koog, an LLM, CI, a git host or an external workflow.

```text
CLI / future API (composition root)
                |
      Application use case contracts
 EnqueueTaskUseCase | RunSessionUseCase
       (all task operations injected through #59)
                |
      Concrete implementations
   DefaultEnqueueTaskUseCase
   DefaultRunSessionUseCase
                |
          Domain decisions
 Task | Session | Policy | Budgets | DAG
                ^
                | owned ports
                |
Adapters: Task Store | Workspace/Git | Model | Event Journal | Clock
                         |
             Koog orchestration adapter (planned)
        (runs roles; not trusted policy authority)
```

The diagram expresses *ownership and communication*, not a claim that every adapter is a separate Gradle module. The actual runtime path can have application services calling ports implemented by adapters. No domain decision directly constructs concrete adapters.

**Non-negotiable constraints**

1. No domain policy depends on Clikt, Koog, HTTP clients, Git subprocesses, file systems, JSON persistence, or a specific model/provider.
2. Inbound data (CLI, API, protocol, LLM output, tool arguments) is validated at boundaries. Validation at the transport level never replaces domain/security validation.
3. Decisions may use explicit facts such as current time and observed usage; they do not read wall-clock time, mutate global state or invoke I/O themselves.
4. Infrastructure performs **trusted revalidation** of file paths, symlinks, actual workspace state and authorization before side effects. A pure allow/deny result alone is not a security sandbox.
5. Model output is untrusted. Only independently verified tool/check receipts and engine state may authorize a successful result.
6. There must be no cycles between production *or test* Gradle project dependencies.

## 3. Bounded contexts and responsibilities

| Context | Owns | Does not own |
| --- | --- | --- |
| **Task** | Task identity, states, dependencies/DAG, priority, fairness, retry/cancel policies, repository port and scheduling decisions | JSON snapshot writer, OS locks, model calls, CLI |
| **Session** | Session identity, lifecycle state machine, admission, deadlines, budgets, cancellation, terminal result rules, event semantics | HTTP/Koog, physical journal writer, filesystem trust |
| **Agent Execution** | SDD → ATDD → TDD → Developer → Independent Review; stage/child orchestration, recursion and artifact gates | Bypassing core permission/budget decisions; arbitrary direct tool execution |
| **Tool Policy** | Role/capability matrix, deny-overrides-allow, immutable artifact ownership, pure authorization decisions | Trusting model claims about paths or receipts; sandbox implementation |
| **Protocol** | Session Protocol v1 external schema, validated DTOs, explicit conversions and compatibility | Core business decisions encoded as ad hoc JSON strings |
| **Infrastructure** | Trusted workspace/Git/files, model gateways, durable storage, event writer, sandbox/process integration | Deciding policy based on prompt text or taking ownership of task/session state |

**Task and Session are separate aggregates.** One Task may produce multiple Session attempts. Task completion consumes a verified Session outcome; it must not infer success from model prose. The scheduler invokes a `TaskRunner` abstraction, not `SessionController` coupled to concrete adapters.

The recursive agent graph coordinates the five roles but does not become a second session engine. Child budgets are bounded by parent budgets; role-owned specifications and approved tests remain protected. The Developer cannot approve itself or mutate frozen SDD/ATDD/TDD artifacts without a separately authorized workflow.

## 4. Ports, decisions and effects

Ports should be **narrow, task-specific interfaces** defined at the core boundary that needs them; do not build a generic service locator or a catch-all `Infrastructure` interface.

Typical ports and their ownership:

| Port / seam | Consumer | Concrete owner |
| --- | --- | --- |
| `TaskRepository` (transactions/claims) | Task use cases | `:infra:task-store` owns the file-backed `TaskStore`; a pure in-memory implementation exists in `:core:tasks` |
| `TaskRunner` | Task scheduler | CLI/composition binding of Session execution |
| `ModelGateway` | Session/agent runtime | `:infra:model` FakeModel / compatible HTTP client |
| `WorkspaceOpener` / trusted workspace operations | Session/tool coordinator | `:infra:workspace` |
| `EventSink` (append/flush/failure) | Session coordinator | `:infra:journal` implements physical JSONL storage and verified checksums |
| Time provider / explicit observed timestamps | Task and Session use cases | System clock in production; fixed clock in tests |

A command to a use case may produce a **pure decision** (new state, rejection reason, authorized intended actions). The imperative shell validates and performs effects through trusted ports, records their observed outcomes, and applies subsequent transitions. This is not a promise of exactly-once side effects; recovery must reconcile uncertainty as specified in [issue #10](https://github.com/YuriiSokolenko/recursive-coder/issues/10).

Use typed domain states/outcomes where meaningful. Treat serialized wire DTOs as transport types; changes to Session Protocol v1 require a separate compatibility decision.

## 5. Modules: actual vs. conditional target

### Implemented modules (eight)

- `:core:contracts` — shared protocol/request types, model/workspace contracts and pure authorization policy.
- `:core:tasks` — Task state/DAG policy, Use Cases, scheduler and repository ports.
- `:core:session` — Session Use Cases, lifecycle/admission/budget policies and event envelopes.
- `:infra:model` — FakeModel and strict OpenAI-compatible HTTP adapter.
- `:infra:workspace` — descriptor-checked Git reads and the **separate** Linux trusted write/check runtime.
- `:infra:task-store` — durable `tasks.json`, file locking and atomic snapshots.
- `:infra:journal` — physical JSONL sink with flush/close/checksum handling.
- `:app:cli` — Clikt CLI and command/session-scoped Koin composition; only executable module.

The included `build-logic` Gradle build is not a ninth product module. Core policy remains free of Koin, Koog and HTTP adapter dependencies. **Current CLI sessions are read-only**, even though the standalone write/check boundary is implemented.

### Conditional future modules — add only with working implementations

- `:agent:runtime` when Koog-backed recursive role execution is implemented ([#9](https://github.com/YuriiSokolenko/recursive-coder/issues/9)).
- `:infra:tools` only if independently owned sandbox/write/check operations justify extraction from `:infra:workspace`.

Do not create empty speculative modules. Task Store and Journal adapters are already extracted.

### Explicit Use Case interfaces and CLI DI (issues #58 and #59)

The application boundary is represented by interface + implementation pairs within their owning bounded contexts, **not** by a new empty Gradle module:

| Contract | Implementation | Owns | Consumer |
| --- | --- | --- | --- |
| `EnqueueTaskUseCase.invoke(requestJson, priority, dependencies): Task` | `DefaultEnqueueTaskUseCase` in `:core:tasks` | Shape/priority admission, then atomic repository update applying `TaskPolicy.enqueue` | `tasks enqueue` via Koin; existing `TaskService.enqueue` delegates for compatibility |
| `RunSessionUseCase.invoke(request, cancellation): SessionExecutionResult` | `DefaultRunSessionUseCase` in `:core:session` | Workspace and protocol admission, trusted model/tool execution, budgets, terminal events and verified result publication | `recursive-coder run` and scheduler's `TaskRunner` via Koin; existing `SessionController` delegates for compatibility |
| `TaskQueriesUseCase`, `CancelTaskUseCase`, `RetryTaskUseCase`, `PauseTaskQueueUseCase`, `ResumeTaskQueueUseCase`, `ReplaceTaskDependenciesUseCase`, `CancelAllTasksUseCase` | Separate `Default*` implementations in `:core:tasks` | Read-only task inspection or one policy-backed atomic command per business intent | Task CLI through command-scoped Koin; `TaskService` delegates for compatibility |
| `ClaimNextTaskUseCase`, `CompleteTaskAttemptUseCase`, `InterruptTaskAttemptUseCase`, `RecoverInterruptedAttemptsUseCase`, `PrepareTaskAttemptUseCase` | Separate `Default*` implementations in `:core:tasks` | Atomic claim, stale-session fencing, interruption, recovery and attempt projection | `TaskScheduler` via injected interfaces; the compatibility constructor bridges old callers |

The input reuses the existing validated `SessionRequest`; the public Use Case returns an immutable `SessionExecutionResult(outcome, protocol)` containing the typed terminal `SessionOutcome` and the **already-validated, already-published** Session Protocol v1 `JsonObject`. The Use Case serializes and validates exactly once before publishing `result.json`; the CLI and compatibility façade reuse that same value without encoding again. Detailed `SessionRunResult`, `SessionArtifact`, `SessionFailure` and `SessionBudgetState` stay `internal` to `:core:session` and are **not** supported public contracts.

`:app:cli/CompositionRoot.kt` owns Koin definitions. `io.insert-koin:koin-core:4.2.2` and `koin-test:4.2.2` share the `koin` version in the Gradle catalog. Koin 4.2.2 is a stable Kotlin 2.x-compatible line; no annotations or code generation are used. Factories explicitly bind Task and Session Use Case interfaces to concrete implementations, while `TaskRepository`, `ModelGateway`, `WorkspaceOpener`, `EventSinkFactory` and `Clock` are injected through constructors. `koinApplication { modules(...) }` builds **local** containers, always closed in `finally` or `AutoCloseable.close()`; there is no process-global `startKoin` or service-locator call inside the core.

Lifetime rules: one `TaskComposition` and `TaskRepository` are shared across operations of a parsed task CLI command and closed in `executeCli`'s `finally`. Session compositions are created for individual direct or scheduled session invocations and closed on normal or exceptional exit; no Koin container survives its work. Use Cases are factories. The model provider is selected per invocation. `Cancellation`, the admitted workspace, journal, watchdog and all session execution state belong to each individual `RunSessionUseCase.invoke`, **not** singleton Koin definitions. Unit tests instantiate implementations directly with in-memory repositories/fakes and fixed clocks; the `KoinBindingsTest` pure unit suite verifies graph definitions and task composition identity/closure using in-memory repositories only, with no global Koin state.

Issue [#59](https://github.com/YuriiSokolenko/recursive-coder/issues/59) completes task-command and scheduler injection. `TaskScheduler` accepts the repository lease port plus task query, claim, completion, interruption, recovery and attempt-projection Use Case interfaces; the CLI constructs it through Koin without calling `TaskService`. `TaskService` remains a documented compatibility façade whose methods delegate to independent implementations; its legacy scheduler constructor remains available only for existing callers/tests. Every mutation runs `TaskPolicy` inside one `TaskRepository.update`, and completion validates wire outcome before transaction entry. The scheduler retains the exclusive lease through recovery, dispatch and worker shutdown. On-disk task snapshots and Session Protocol v1 are unchanged.

## 6. Testing architecture

Use **JUnit 5 + kotlin.test** as the baseline. Prefer handwritten deterministic fakes for narrow ports; introduce mocking or property-based test tools only where a concrete case merits them.

| Tier | What it proves | Allowed dependencies | Default expectation |
| --- | --- | --- | --- |
| **Pure unit** | State machines, DAG, fairness, budget arithmetic, role authorization, protocol/domain mappings | In-memory values, fakes, fixed time; no real Git/files/network/Koog | Very fast; run every PR |
| **Integration** | Real TaskStore snapshots/locks, workspace/Git containment, model HTTP mock protocol, event JSONL, sandbox effects | Disposable local filesystem, Git process, local mock HTTP/sandbox | Run in repository CI |
| **Installed CLI E2E** | CLI → Task/Session → FakeModel → trusted workspace → schema-valid results | Packaged executable and disposable fixture | Run in repository CI |
| **Fault / recovery** | Crashes, stale claims, partial writes, uncertain effects, checkpoint fencing | Controlled failures and disposable adapters | Mandatory when implementing #10 |
| **Optional live-model evaluation** | Real model compatibility and coding quality | Explicitly configured local/remote model | Separate opt-in, never a default CI gate |

A test that starts `git`, creates a persistent file-backed store, or exercises real locks is an **integration test**, even if its class currently includes `Test` or resides in `src/test`. Separate ownership/CI tiers without deleting existing coverage.

Test design rules:

- Use table-driven legal and illegal transition cases and deterministic logical clocks.
- Test policy denial and unknown capabilities **before** effects.
- Keep independent contract/schema fixtures for transport compatibility.
- Assert no duplicate tool effects, no false successful terminal states and no unauthorised changes in relevant integration/fault cases.
- Preserve sandbox tests as genuinely executed CI coverage, not silently skipped green results.
- Any new domain rule arrives with a pure unit test; any new adapter or security boundary arrives with integration tests.

Test suites must remain offline/deterministic by default. Do not use flaky live-model tests as substitutes for invariants.

## 7. Security, observability and durability

- Permission checks are first-party and fail closed. Denials override grants.
- Trust boundaries distinguish **declared** paths/properties from **observed** canonical filesystem and tool state.
- Approved specs/tests are immutable to the Developer; independent Review cannot be replaced by self-evaluation.
- State transitions are valid only through explicit rules. Terminal states and result publication are monotonic and verified.
- Journal events use correlated IDs, sequence, timestamps and sanitised metadata; I/O failures must not silently become success.
- Budget decisions use observed token/tool/time consumption, controlling child and global limits.
- Durable snapshots, fencing and uncertain-side-effect recovery belong to the existing [durability milestone #10](https://github.com/YuriiSokolenko/recursive-coder/issues/10); extracting a journal port does **not** by itself implement crash-safe recovery.

## 8. Incremental roadmap and issue tracker

Architecture refactors [#24](https://github.com/YuriiSokolenko/recursive-coder/issues/24)–[#31](https://github.com/YuriiSokolenko/recursive-coder/issues/31), plus Use Case/Koin work [#58](https://github.com/YuriiSokolenko/recursive-coder/issues/58) and [#59](https://github.com/YuriiSokolenko/recursive-coder/issues/59), have been integrated into `dev`. The following issues remain open; dependency and priority are reflected explicitly rather than implied by row order.

| State | Issue | Deliverable |
| --- | --- | --- |
| Next product milestone (P1) | [#9](https://github.com/YuriiSokolenko/recursive-coder/issues/9) | Koog-backed recursive five-stage graph; integrate existing trusted tools |
| Depends on #9 (P1) | [#10](https://github.com/YuriiSokolenko/recursive-coder/issues/10) | Durable checkpoints, lease fencing, side-effect reconciliation and recovery |
| Depends on #9/#10 (P1) | [#11](https://github.com/YuriiSokolenko/recursive-coder/issues/11) | Reproducible coding evaluation harness |
| Later (P2) | [#12](https://github.com/YuriiSokolenko/recursive-coder/issues/12), [#13](https://github.com/YuriiSokolenko/recursive-coder/issues/13) | Release packaging, optional daemon/API and integrations |
| Architecture and test follow-ups (P2/P3) | [#36](https://github.com/YuriiSokolenko/recursive-coder/issues/36), [#37](https://github.com/YuriiSokolenko/recursive-coder/issues/37), [#40](https://github.com/YuriiSokolenko/recursive-coder/issues/40), [#42](https://github.com/YuriiSokolenko/recursive-coder/issues/42), [#43](https://github.com/YuriiSokolenko/recursive-coder/issues/43), [#51](https://github.com/YuriiSokolenko/recursive-coder/issues/51), [#52](https://github.com/YuriiSokolenko/recursive-coder/issues/52), [#54](https://github.com/YuriiSokolenko/recursive-coder/issues/54), [#63](https://github.com/YuriiSokolenko/recursive-coder/issues/63) | Deterministic coverage, repository contracts and Gradle guardrail refinements |

A green CI job establishes that current checks passed, **not** that planned recursion or crash recovery already works.

## 9. Definition of done for an architecture-refactor PR

1. Current externally observable CLI/protocol behavior remains compatible unless the PR explicitly documents and tests a separately authorized change.
2. New logic has meaningful deterministic tests at the right tier; previous integration/security coverage remains intact.
3. Production and test dependencies are acyclic and observe the inward dependency rule.
4. Adapter failures and security denials are not swallowed or recast as successful results.
5. The repo's independent CI passes: JVM checks, canonical protocol checks, installed fake-model CLI, and relevant sandbox/adapter tests.
6. Documentation, issue descriptions, PR text and published code comments are in English.
7. The PR has a narrow scope and a clear statement of what remains deferred.

## 10. Explicit non-goals

No microservices, no Koin dependency inside core, no mandatory CQRS/Event Sourcing, no full framework rewrite, no empty speculative Gradle modules, no live LLM dependency for CI, no change to the product's independent execution authority, and no broad unreviewed JSON/protocol migration.

**Architecture priority:** correct and testable decisions first; stable ports second; physical module moves only when they enforce a real boundary.
