# Recursive Coder

**Recursive Coder** is an in-development, standalone open-source software engineering agent built with **Kotlin/JVM**. **JetBrains Koog** is the planned orchestration framework, not part of the current production runtime.

> **Implementation snapshot (2026-11-11, `dev@4ed9fe8`):** The CLI has native task scheduling, a read-only session engine, FakeModel, an optional strict OpenAI-compatible Model Gateway and a trusted workspace. A separate Linux-only trusted write/check runtime exists, but **is not integrated into CLI sessions**. Koog orchestration, recursive coding, checkpoints and crash-resume are **not implemented**.

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

The current foundation uses Kotlin 2.4.21, JDK 21, Gradle 9.8.1, Koin 4.2.2 (CLI composition root only), and Session Protocol `1.0`. Core modules do not depend on Koog, HTTP adapters or Koin.

The Gradle build has **eight product modules**: `:core:contracts`, `:core:session`, `:core:tasks`, `:infra:model`, `:infra:workspace`, `:infra:task-store`, `:infra:journal`, and the only executable module, `:app:cli`. `build-logic` is an included build, not a ninth product module. See [Gradle module ownership](docs/architecture/MODULES.md).

The installed distribution remains at `build/install/recursive-coder/bin/recursive-coder`, preserving the existing CLI smoke-test path.

The project includes an offline deterministic fake-model CLI, durable task queue/scheduler, trusted read-only workspace, optional configured HTTP model adapter, and physical JSONL event sink. The separately callable Linux trusted write/check runtime is **not** an enabled write-capable CLI mode. See [First executable session](docs/architecture/FIRST_SESSION.md) and [Trusted writes](docs/architecture/TRUSTED_WRITES.md).

With Gradle 9.8.1 and JDK 21 installed:

```bash
./gradlew test installDist
```

[GitHub Actions CI](.github/workflows/ci.yml) runs **only on the dedicated self-hosted N150 KVM runner** labeled `self-hosted, linux, x64, n150, recursive-coder-ci`, using the prewarmed Golden Image v2. There is **no GitHub-hosted fallback**, and fork PRs are excluded from these jobs. The three jobs cover architecture/pure unit tests, full JVM/integration/mandatory sandbox E2E and installed CLI, plus versioned contracts. The last `dev` run on 2026-10-14 passed all three jobs. Tests need no live inference provider, but the GitHub CI workflow requires the configured self-hosted runner. See [Testing architecture](docs/architecture/TEST_SUITES.md).

## Documentation

- [Architecture and engineering roadmap](docs/architecture/ARCHITECTURE.md)
- [Gradle modules and dependency rules](docs/architecture/MODULES.md)
- [First executable CLI session](docs/architecture/FIRST_SESSION.md)
- [Native Task Manager and local scheduling](docs/architecture/TASK_MANAGER.md)
- [Workspace security and read-only tools](docs/architecture/WORKSPACE.md)
- [Model Gateway configuration and capability discovery](docs/architecture/MODEL_GATEWAY.md)
- [Session Protocol v1](contracts/README.md)
- [Canonical JSON Schema](contracts/v1/session-v1.schema.json)
- [Versioned protocol examples](contracts/v1/session-v1.examples.json)
- [Contributing and documentation language](CONTRIBUTING.md)

**All repository documentation, comments intended for publication, PR descriptions, and issue specifications must be written in English.** This includes future RFCs and design notes.

## Implementation roadmap

| Milestone | Status | Issue |
| --- | --- | --- |
| CLI, read-only Session Runtime, Model Gateway, native Task Manager | Implemented baseline | [#4–#7](https://github.com/YuriiSokolenko/recursive-coder/issues/3) |
| Standalone Linux trusted write/check security boundary | Implemented, not CLI-integrated | [#8](https://github.com/YuriiSokolenko/recursive-coder/issues/8) |
| Recursive Koog SDD → ATDD → TDD → Developer → Reviewer graph | Planned | [#9](https://github.com/YuriiSokolenko/recursive-coder/issues/9) |
| Durable checkpoints, fencing and crash recovery | Planned | [#10](https://github.com/YuriiSokolenko/recursive-coder/issues/10) |
| Independent coding evaluation harness | Planned | [#11](https://github.com/YuriiSokolenko/recursive-coder/issues/11) |
| Release packaging and optional daemon/API | Planned | [#12](https://github.com/YuriiSokolenko/recursive-coder/issues/12), [#13](https://github.com/YuriiSokolenko/recursive-coder/issues/13) |

See [Target architecture](docs/architecture/TARGET_ARCHITECTURE.md) for the engineering decisions and [the project epic](https://github.com/YuriiSokolenko/recursive-coder/issues/3) for detailed scope.

**Core principle:** Recursive Coder is designed, built, and tested as a fully independent product. External services are optional integrations, not owners of its execution lifecycle.
