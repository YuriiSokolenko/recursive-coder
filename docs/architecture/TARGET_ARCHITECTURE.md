# Recursive Coder — Target Architecture

> **Status:** Accepted architectural direction; incremental migration, not a claim that all components exist.  
> **Date:** 2026-10-13  
> **Target:** Kotlin/JVM + JetBrains Koog, independently runnable with a local or configured remote LLM.  
> **Related:** [Current architecture](ARCHITECTURE.md) · [Gradle modules](MODULES.md) · [Session Protocol v1](../../contracts/README.md)

## 1. Decision

Recursive Coder will combine **Clean Architecture**, **Hexagonal Architecture (Ports & Adapters)**, pragmatic **Domain-Driven Design (DDD-lite)**, and **Functional Core / Imperative Shell**.

- **Clean Architecture** governs dependency direction: stable domain and application decisions are independent of infrastructure, frameworks, transports and user interfaces.
- **Ports & Adapters** isolate model providers, trusted workspaces, task storage, event journals, clocks and future integrations. The core owns the contracts it needs; external implementations satisfy them.
- **DDD-lite** establishes clear Task, Session and Agent Execution boundaries, invariants and vocabulary without imposing a large tactical-DDD framework.
- **Functional Core / Imperative Shell** keeps security, scheduling, resource-budget and state-transition *decisions* deterministic, while a thin coordinating shell invokes I/O and commits effects.
- **Event-driven observability** records correlated, versioned facts and verified outcomes. Event sourcing, CQRS, distributed services and a dependency-injection framework are **not** required for the initial design.

This is a direction for controlled evolution of the existing product, **not** a greenfield rewrite or a reason to introduce empty Gradle modules.

## 2. Dependency rule

**Source dependencies point inward; runtime control stays with Recursive Coder.** The agent must not delegate task/session authority to Koog, an LLM, CI, a git host or an external workflow.

```text
CLI / future API (composition root)
                |
      Application use cases
   TaskService / SessionController
                |
          Domain decisions
 Task | Session | Policy | Budgets | DAG
                ^
                | owned ports
                |
Adapters: Task Store | Workspace/Git | Model | Event Journal | Clock
                         |
             Koog orchestration adapter
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
| `TaskRepository` (transactions/claims) | Task use cases | Current file-backed `TaskStore`; optional `:infra:task-store` after extraction |
| `TaskRunner` | Task scheduler | CLI/composition binding of Session execution |
| `ModelGateway` | Session/agent runtime | `:infra:model` FakeModel / compatible HTTP client |
| `WorkspaceOpener` / trusted workspace operations | Session/tool coordinator | `:infra:workspace` |
| `EventSink` (append/flush/failure) | Session coordinator | JSONL journal adapter; optional `:infra:journal` |
| Time provider / explicit observed timestamps | Task and Session use cases | System clock in production; fixed clock in tests |

A command to a use case may produce a **pure decision** (new state, rejection reason, authorized intended actions). The imperative shell validates and performs effects through trusted ports, records their observed outcomes, and applies subsequent transitions. This is not a promise of exactly-once side effects; recovery must reconcile uncertainty as specified in [issue #10](https://github.com/YuriiSokolenko/recursive-coder/issues/10).

Use typed domain states/outcomes where meaningful. Treat serialized wire DTOs as transport types; changes to Session Protocol v1 require a separate compatibility decision.

## 5. Modules: actual vs. conditional target

### Implemented modules

- `:core:contracts` — currently shared Kotlin contracts, request validation and cross-module types. Keep small; it must not become a repository of all business logic.
- `:core:tasks` — Task use cases, domain transitions, scheduler decisions, and owned repository/runner ports.
- `:core:session` — Session use cases, lifecycle and budget/admission decisions, owned event/workspace seams.
- `:infra:model` — fake and HTTP-compatible model adapters; no core runtime ownership.
- `:infra:workspace` — trusted filesystem/Git and currently trusted tool execution.
- `:app:cli` — Clikt UI, configuration, concrete dependency assembly; the executable application.

### Conditional modules — add only with working implementations

- `:infra:task-store` once concrete file snapshot/locking code is extracted from the Task core.
- `:infra:journal` once physical JSONL journal I/O is extracted from the Session core.
- `:agent:runtime` when Koog-backed recursive role execution is implemented (see [#9](https://github.com/YuriiSokolenko/recursive-coder/issues/9)).
- `:infra:tools` when standalone sandbox/write/check execution warrants its own owned boundary.

`:core:contracts` can continue hosting common stable ports while the first refactors are underway. Move contracts only when clear ownership and an acyclic dependency path are established. Do **not** create `:core:domain`, `:protocol` or empty layer-per-package modules for appearance.

A future Koog module may depend on core contracts and application services, while the core must never depend on Koog. Infrastructure adapter selection happens at an application composition root.

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

Each row is intended as a **separate focused PR into `dev`**, unless a prerequisite is already completed.

| Sequence / parallelism | Issue | Deliverable |
| --- | --- | --- |
| Existing, start first | [#24](https://github.com/YuriiSokolenko/recursive-coder/issues/24) | Module-local Session and Workspace unit tests before Session refactor |
| Foundation; parallel with #24 | [#25](https://github.com/YuriiSokolenko/recursive-coder/issues/25) | Enforce module boundaries and classify unit/integration/E2E suites |
| Independent core workstream | [#26](https://github.com/YuriiSokolenko/recursive-coder/issues/26) | Pure Task state machine, DAG, fairness and retry/cancel policies |
| After #26 | [#27](https://github.com/YuriiSokolenko/recursive-coder/issues/27) | TaskRepository port and concrete persistence adapter |
| After #24 | [#28](https://github.com/YuriiSokolenko/recursive-coder/issues/28) | Pure Session state machine, admission and budget policies |
| After #28 | [#29](https://github.com/YuriiSokolenko/recursive-coder/issues/29) | Session EventSink port and JSONL journal adapter |
| Independent security workstream | [#30](https://github.com/YuriiSokolenko/recursive-coder/issues/30) | Pure Tool Authorization role/policy decisions |
| After #26 and #28 | [#31](https://github.com/YuriiSokolenko/recursive-coder/issues/31) | Typed domain values and Session Protocol JSON mapping boundary |
| Following stable boundaries | [#9](https://github.com/YuriiSokolenko/recursive-coder/issues/9) | Koog recursive five-stage agent graph (already tracked) |
| After agent/workspace fundamentals | [#10](https://github.com/YuriiSokolenko/recursive-coder/issues/10) | Checkpoints, fencing, fault-tolerant recovery (already tracked) |

This plan deliberately **does not recreate** the finished multi-module refactor [#22](https://github.com/YuriiSokolenko/recursive-coder/issues/22), existing trusted-write task [#8](https://github.com/YuriiSokolenko/recursive-coder/issues/8), or the open unit-test task #24.

## 9. Definition of done for an architecture-refactor PR

1. Current externally observable CLI/protocol behavior remains compatible unless the PR explicitly documents and tests a separately authorized change.
2. New logic has meaningful deterministic tests at the right tier; previous integration/security coverage remains intact.
3. Production and test dependencies are acyclic and observe the inward dependency rule.
4. Adapter failures and security denials are not swallowed or recast as successful results.
5. The repo's independent CI passes: JVM checks, canonical protocol checks, installed fake-model CLI, and relevant sandbox/adapter tests.
6. Documentation, issue descriptions, PR text and published code comments are in English.
7. The PR has a narrow scope and a clear statement of what remains deferred.

## 10. Explicit non-goals

No microservices, no dependency injection framework requirement, no mandatory CQRS/Event Sourcing, no full framework rewrite, no empty speculative Gradle modules, no live LLM dependency for CI, no change to the product's independent execution authority, and no broad unreviewed JSON/protocol migration.

**Architecture priority:** correct and testable decisions first; stable ports second; physical module moves only when they enforce a real boundary.
