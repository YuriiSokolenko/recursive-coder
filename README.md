# Recursive Coder

**Recursive Coder** is an in-development, standalone open-source software engineering agent built with **Kotlin/JVM**. **JetBrains Koog** is integrated as an opt-in, fake-model-only single-role adapter; recursive production orchestration remains planned.

> **Implementation snapshot (2026-11-13, PR #90 / issue #79):** The CLI has native task scheduling, a read-only session engine, FakeModel, an optional strict OpenAI-compatible Model Gateway and a trusted workspace. A Linux-only trusted write/check runtime has an **opt-in host-controlled stage port (#74)**, but remains disabled for CLI sessions and Koog. A minimal isolated Koog stage runner is implemented in `:agent:runtime` but is **not selected by CLI sessions**. An **in-process, five-role workflow coordinator** and bounded recursive child-result integration are implemented separately; they are **not wired into the installed CLI**. A host-only Linux Git baseline reconciliation helper is implemented in draft PR #91, but **authenticated production stage-to-stage integration is not implemented**. Recursive five-role CLI execution, checkpoints and crash-resume remain unimplemented.

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

The Gradle build has **nine product modules**: `:agent:runtime`, `:core:contracts`, `:core:session`, `:core:tasks`, `:infra:model`, `:infra:workspace`, `:infra:task-store`, `:infra:journal`, and the only executable module, `:app:cli`. `build-logic` is an included build, not a product module. See [Gradle module ownership](docs/architecture/MODULES.md).

The installed distribution remains at `build/install/recursive-coder/bin/recursive-coder`, preserving the existing CLI smoke-test path.

The project includes an offline deterministic fake-model CLI, durable task queue/scheduler, trusted read-only workspace, optional configured HTTP model adapter, and physical JSONL event sink. The Linux trusted write/check runtime also has a host-only stage tool port. A **separate, explicitly gated fake-only `workflow` CLI** can now orchestrate five trusted roles in a dedicated checkout; default SessionRequest v1 and native task commands remain read-only. See [First executable session](docs/architecture/FIRST_SESSION.md), [Trusted writes](docs/architecture/TRUSTED_WRITES.md) and [Writable CLI](docs/architecture/WRITABLE_CLI.md).

With Gradle 9.8.1 and JDK 21 installed:

```bash
./gradlew test installDist
```

[GitHub Actions CI](.github/workflows/ci.yml) runs on **GitHub-hosted `ubuntu-24.04` runners** after merged [PR #71](https://github.com/YuriiSokolenko/recursive-coder/pull/71). The three jobs use fresh VMs, including for fork PRs, with JDK 21, Gradle 9.8.1, Python 3.12 and Node.js 22 provisioned as required. They cover architecture/pure unit tests, full JVM/integration/mandatory sandbox E2E plus installed CLI, and versioned contracts. Restricted AppArmor integration enables bubblewrap's private network namespace without disabling system protection; sandbox E2E and the no-skip assertion remain mandatory. The [CI migration run](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/43120760211) passed all three jobs on 2026-11-11, including the sandbox E2E. Tests require no live model provider. See [Testing architecture](docs/architecture/TEST_SUITES.md).

## Documentation

- [Architecture and engineering roadmap](docs/architecture/ARCHITECTURE.md)
- [Stage graph contracts and policy gates (#72)](docs/architecture/STAGE_GRAPH.md)
- [Verified recursive child artifact integration (#79, partial)](docs/architecture/CHILD_INTEGRATION.md)
- [Host-owned five-role orchestration (#79, in progress)](docs/architecture/FIVE_ROLE_ORCHESTRATION.md)
- [Explicitly opted-in offline writable CLI (#79, partial)](docs/architecture/WRITABLE_CLI.md)
- [Koog single-role execution boundary (#73)](docs/architecture/KOOG_STAGE_EXECUTION.md)
- [Host-controlled stage tool authority and isolated checks (#74)](docs/architecture/STAGE_TOOL_AUTHORITY.md)
- [Host-approved SDD, ATDD and TDD preparation stages (#75)](docs/architecture/PREPARATION_STAGES.md)
- [Host-fenced Developer stage and verification (#76)](docs/architecture/DEVELOPER_STAGE.md)
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
| CLI, read-only Session Runtime, Model Gateway, native Task Manager | Implemented baseline | [#4](https://github.com/YuriiSokolenko/recursive-coder/issues/4), [#5](https://github.com/YuriiSokolenko/recursive-coder/issues/5), [#6](https://github.com/YuriiSokolenko/recursive-coder/issues/6), [#7](https://github.com/YuriiSokolenko/recursive-coder/issues/7) |
| Standalone Linux trusted write/check security boundary | Implemented, not CLI-integrated | [#8](https://github.com/YuriiSokolenko/recursive-coder/issues/8) |
| Minimal Koog single-role adapter (fake only) | Implemented, opt-in | [#73](https://github.com/YuriiSokolenko/recursive-coder/issues/73) |
| Recursive Koog SDD → ATDD → TDD → Developer → Reviewer graph | Partially implemented: five role stages and bounded in-memory recursive subtree; integrated CLI workflow planned | [#9](https://github.com/YuriiSokolenko/recursive-coder/issues/9), [#78](https://github.com/YuriiSokolenko/recursive-coder/issues/78) |
| Durable checkpoints, fencing and crash recovery | Planned | [#10](https://github.com/YuriiSokolenko/recursive-coder/issues/10) |
| Independent coding evaluation harness | Planned | [#11](https://github.com/YuriiSokolenko/recursive-coder/issues/11) |
| Release packaging and optional daemon/API | Planned | [#12](https://github.com/YuriiSokolenko/recursive-coder/issues/12), [#13](https://github.com/YuriiSokolenko/recursive-coder/issues/13) |

See [Target architecture](docs/architecture/TARGET_ARCHITECTURE.md) for the engineering decisions and [the project epic](https://github.com/YuriiSokolenko/recursive-coder/issues/3) for detailed scope.

**Core principle:** Recursive Coder is designed, built, and tested as a fully independent product. External services are optional integrations, not owners of its execution lifecycle.

- [Independent Reviewer stage and bounded repair](docs/architecture/REVIEWER_STAGE.md)
- [Bounded recursive stage requests and subtree budgets](docs/architecture/RECURSIVE_GRAPH.md)
