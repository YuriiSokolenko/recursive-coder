# Recursive Coder — Standalone Architecture

> **Status:** target architecture, RFC 0.3  
> **Technology:** Kotlin/JVM, JetBrains Koog, configurable local or compatible LLM APIs  
> **Core principle:** Recursive Coder must own the complete coding-agent lifecycle. No external workflow, repository, or service may be a mandatory coordinator.

## 1. Mission

Build a **fully independent software engineering agent** that can accept complex development tasks, recursively refine and implement them, validate the results, and preserve outcomes safely.

Koog provides agent-building primitives, while Recursive Coder retains ownership of task queues, session lifecycles, nested agents, tools, budgets, recovery, event history, and terminal results.

This project is not a plugin for another orchestration platform. Cloud providers, source-control hosting, IDEs, and task trackers are optional integrations built on public interfaces.

## 2. Components

```text
  CLI / Public API / Future UI
               |
          Task Manager  ← Scheduler / Dependency Graph / Retries
               |
         Session Controller
               |
    Recursive Agent Graph (Koog)
       SDD → ATDD → TDD → Develop → Review
        |       |     |        |       |
        +-------+-----+--------+-------+
               |
          Trusted Tool Runtime
          /       |       \
    Workspace  Checks   Model Gateway
               |            |
       Event Journal / Checkpoints
```

### Task Manager

Owns the local task queue, dependencies, priorities, readiness, pause/resume, cancellation, and concurrency. Each task has a first-party `taskId`. Neither creating child tasks nor scheduling their execution requires externally hosted issues or CI jobs.

### Session Controller

A task can have one or more session attempts. The controller owns `sessionId`, deadlines, token and tool budgets, capabilities, child-agent state, terminal outcomes, and checkpoint lifecycle.

The canonical protocol represents transitions such as `created → accepted → running`, `running → checkpointing → running|paused`, and `paused → resuming → running`. Success, failure, cancellation, rejection, and timeout are terminal conditions. Status must be derived from verified runtime state, **not** from a model's final words.

The bootstrap implementation in issue #4 handles one fake-model read-only session; the fuller state machine, nested agents, and durable recovery are future milestones.

### Recursive Agent Graph

Each stage is an independent agent role with separately enforced authority:

1. **SDD** produces the task specification and testable requirements.
2. **ATDD** converts acceptance criteria into independent acceptance tests.
3. **TDD** designs focused unit/integration tests and contracts.
4. **Developer** implements code against the previously approved specifications and tests.
5. **Reviewer** independently evaluates the implementation, requests repair, or recommends decomposition.

Recursion applies to **every stage**, not only Developer. A decomposed subtask receives its own bounded workflow and shares parent budgets. A nested reviewer may not rewrite the source requirements to conceal a failure.

### Trusted Tool Runtime

Tool permission is not inferred from prompt text. Every invocation must be checked against the active role, allow/deny policy (denials take precedence), file scope, canonical real paths and symlinks, sandbox rules, and remaining budgets. The Developer **must not modify** specifications and tests owned by SDD/ATDD/TDD. Any such change requires a distinct trusted and independently approved workflow.

### Workspace Manager

Creates and protects an execution workspace, records the original Git baseline, validates the allowed mutation scope, and supports safe recovery after interruptions. Before accepting a result, the engine verifies the changed-file manifest and trusted check receipts. Supporting arbitrary directories without Git is a separate explicit future mode; no baseline SHA may be fabricated.

### Model Gateway

Model selection is configured by Recursive Coder rather than by a hardcoded home network, GPU, server port, model, or cloud credential. Adapters may implement testable OpenAI-compatible endpoints and other providers. Before execution, the engine negotiates tool calling, structured output, streaming, token budgets, context limits, and provider-specific incompatibilities. The CI path uses an offline deterministic fake model.

### Event Journal and durable recovery

Model, tool, policy, resource-usage, recursion, and state events have structured identifiers and parent/child correlations. Events are append-only and versioned; secret or private content is redacted before persistence.

Checkpoints contain hashed state snapshots and a journal of side effects. Resuming requires a new monotonic `fenceGeneration`, an exclusive lease, and checks of the protocol version, policy, workspace baseline and digest, and unresolved mutations. Uncertain side effects must **not** be repeated without reconciliation.

**Diagnostic replay** can reconstruct recorded inputs and observed outputs in a controlled environment. It does not claim deterministic access to a model's hidden internal reasoning.

## 3. Public session protocol

`contracts/v1/session-v1.schema.json` is the canonical transport schema of the project:

- **SessionRequest:** invocation source (`cli|api|scheduler`), workspace, task, model, policy, budgets, outputs, and checkpoint/resume request.
- **SessionResult:** terminal status, diagnostics, usage completeness, hashed artifact references, and provenance.
- **SessionEvent:** correlation, agent/tool identity, action type, timing, redaction, and available observed metrics.
- **SessionCheckpoint:** persisted state, generation/fencing, and side-effect journal.

Session Protocol v1.0 is **pre-release**. Incompatible versions and unsupported required capabilities are rejected explicitly rather than silently downgraded. All compatibility changes require tests and review.

The engine creates and owns `invocationId` and `sessionId`. These identifiers are independent of any GitHub workflow run, IDE session, or third-party task tracker.

## 4. Execution interfaces

**CLI first.** The user supplies a task and a local session configuration. The agent owns the session, inspects the project, executes authorized tools, and returns a machine-readable result plus diagnostics. When the configured model and required tools are local, no Internet connection should be necessary.

**API later.** The same task and session services may be exposed through a versioned HTTP/gRPC interface. Remote user interfaces and integrations use this API; they cannot directly invoke privileged tools.

**Scheduler/daemon later.** Internal scheduling can handle many tasks subject to local model capacity, bounded concurrency, and fairness. This responsibility stays inside the product, not in external workflows.

Git-host integrations (PR creation, reviews, webhooks), IDE extensions, and task-system adapters are independent opt-in plugins with narrowly scoped permissions. They do not belong in the trusted session core.

## 5. Security invariants

- Denials override grants. Unknown protocol versions and capabilities fail closed.
- Agent control files, policies, secrets, `.git`, specifications, and role-protected tests must be protected regardless of task instructions.
- Prompts, model output, and retrieved context are untrusted data.
- Every shell command, path, and network destination requires independent authorization and appropriate sandboxing.
- A model-supplied tool result is **not** equivalent to a trusted workspace mutation receipt.
- Accepting a coding result requires independently verified checks and a permissible final diff.
- Checkpoints and terminal receipts are tied to the exact session, policy, baseline, and fence generation.
- Hardware or provider failures must produce an explicit terminal/error state with available diagnostic evidence; crashes may require safe later reconciliation.

## 6. Independent CI and quality

GitHub Actions is used **solely as CI for this repository**, never as the owner of an external engineering workflow. GitHub-hosted runners execute tests without private inference hardware:

- Kotlin/JVM compilation and unit tests;
- JSON Schema Draft 2020-12 conformance and negative protocol cases;
- deterministic fake-model CLI/integration tests;
- planned recursive-agent, tool-policy, sandbox, and recovery tests;
- separately gated optional live-model and performance evaluations;
- reproducible packaged CLI release artifacts and checksums.

Every new engine module must ship with relevant tests. Live model behavior does not replace security, sandbox, or recovery proofs; model benchmark scores do not demonstrate authorization correctness.

## 7. Delivery roadmap

| Milestone | Deliverable |
| --- | --- |
| Foundation | Repository structure, independent CI, versioned Session Protocol v1, standalone architecture |
| Session Engine | CLI, `SessionController`, deterministic FakeModel, observable read-only session and validated transitions |
| Agent Graph | Recursive SDD/ATDD/TDD/Develop/Review roles, bounded recursion, and immutable approved artifacts |
| Tooling | Workspace protection, safe read/write/check tools, policy enforcement, and adversarial tests |
| Durability | Event Journal, checkpoints, fenced resume, and fail-closed recovery |
| Product | First-party task scheduling, optional daemon/API and integrations, diagnostics UI, and releases |

**Implementation status:** the foundation, first offline fake-model session, trusted read-only workspace, and strict injectable OpenAI-compatible Model Gateway are present. Production Koog agent orchestration, recursive multi-agent workflows, writable tools, and crash recovery are not yet available. This architecture describes requirements rather than claiming those future capabilities are implemented.
