# Recursive Coder

**Recursive Coder** is a standalone open-source software engineering agent built with **Kotlin/JVM and JetBrains Koog**.

> **Current status:** The first executable command-line session engine includes `SessionController`, a trusted read-only workspace, a deterministic FakeModel, and an injectable OpenAI-compatible Model Gateway with explicit capability checks. Full Koog agent execution, task scheduling, recursive development, and trusted code changes are **not yet implemented**.

## What we are building

Recursive Coder is a full coding agent, **not** a wrapper around another project's CI system. It owns the task lifecycle: accepting requests, inspecting a project, breaking work into smaller tasks, producing specifications and tests, implementing code, independently validating changes, and recording results.

Users should be able to run it locally or connect it to other systems without depending on an external GitHub workflow, dispatcher, or controlling service. External integrations are always optional adapters; they never own the agent's execution authority.

## Recursive engineering process

`SDD → ATDD → TDD → Develop → Independent Review`

Each stage runs in a separately authorized agent role. Any stage can recursively decompose a difficult task until bounded atomic subtasks can be evaluated and integrated. Specifications and tests created by SDD, ATDD, and TDD are protected from unilateral edits by the Developer. Changes require a distinct, independently approved process.

## Product architecture

- **Task Manager / Scheduler:** first-party tasks, priorities, dependencies, concurrency limits, pause, retry, and cancellation.
- **Session Controller:** root sessions, nested agents, explicit state transitions, budgets, and deadlines.
- **Agent Graph:** recursive orchestration of SDD, ATDD, TDD, Developer, and Reviewer roles.
- **Trusted Tool Runtime:** enforced authorization, workspace isolation, read/write/check tools, and verifiable results.
- **Model Gateway:** configurable local or remote compatible LLM endpoints with explicit protocol capability negotiation.
- **Event Journal / Checkpoints:** correlated events, diagnostics, safe recovery, and controlled handling of uncertain side effects.
- **Interfaces:** CLI first, followed by optional API and integrations.

Recursive Coder itself owns its tasks and sessions. Git can manage source workspaces; a particular git host is neither a required runtime component nor a privileged controller.

## Build and test

The current foundation uses Kotlin 2.4.21, JDK 21, Gradle 9.8.1, Koog 1.3.0, and Session Protocol `1.0`.

The project includes an offline deterministic fake-model CLI, a trusted read-only workspace boundary, and an optional user-configured HTTP model adapter. CI uses only the fake model and deterministic mock HTTP servers. For setup, supported features, sample execution, and known limitations, see **[First executable session](docs/architecture/FIRST_SESSION.md)**.

With Gradle 9.8.1 and JDK 21 installed:

```bash
./gradlew test installDist
```

The independent [GitHub Actions CI](.github/workflows/ci.yml) runs JVM and protocol checks on GitHub-hosted runners. The fake-model integration tests do not require a network model endpoint, personal hardware, secrets, or an external runner. Optional live-model checks will be separately gated.

## Documentation

- [Architecture and engineering roadmap](docs/architecture/ARCHITECTURE.md)
- [First executable CLI session](docs/architecture/FIRST_SESSION.md)
- [Workspace security and read-only tools](docs/architecture/WORKSPACE.md)
- [Model Gateway configuration and capability discovery](docs/architecture/MODEL_GATEWAY.md)
- [Session Protocol v1](contracts/README.md)
- [Canonical JSON Schema](contracts/v1/session-v1.schema.json)
- [Versioned protocol examples](contracts/v1/session-v1.examples.json)
- [Contributing and documentation language](CONTRIBUTING.md)

**All repository documentation, comments intended for publication, PR descriptions, and issue specifications must be written in English.** This includes future RFCs and design notes.

## Implementation roadmap

1. **Session Engine:** executable CLI, `SessionController`, deterministic FakeModel, observed read-only session.
2. **Workspace and Model Gateway:** trusted workspace boundaries and a capability-negotiated real model adapter.
3. **Task Manager and recursive Agent Graph:** first-party scheduling and SDD → ATDD → TDD → Develop → Independent Review.
4. **Trusted Tooling:** immutable specs/tests, sandboxed writes and validation, independent evidence, and policy tests.
5. **Durability:** checkpoints, crash recovery, idempotency, fencing, and fault injection.
6. **Product interfaces and quality:** optional API, integrations, releases, and independently reproducible coding benchmarks.

**Core principle:** Recursive Coder is designed, built, and tested as a fully independent product. External services are optional integrations, not owners of its execution lifecycle.
