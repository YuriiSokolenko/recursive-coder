# Gradle multi-module boundaries

Issue #22 introduces six implemented modules; the Gradle root remains `recursive-coder` and is not an executable module.

| Module | Ownership | Production project dependencies |
| --- | --- | --- |
| `:core:contracts` | Session Protocol support, request validation, model/workspace contracts and cross-module value types | none |
| `:core:session` | Session Controller, deadline/budget enforcement, admission policy and event journal | `:core:contracts` |
| `:core:tasks` | Durable Task Manager, dependency DAG, scheduler and task execution interface | `:core:contracts` |
| `:infra:model` | Offline FakeModel and optional OpenAI-compatible HTTP adapter | `:core:contracts` |
| `:infra:workspace` | Workspace Manager, restricted Git and trusted write/check runtime | `:core:contracts` |
| `:app:cli` | Clikt command entry point, configuration and concrete dependency composition | all five libraries |

```text
                      :app:cli  (only executable)
                    /     |        |          \
           :core:session :core:tasks :infra:model :infra:workspace
                    \     |        |          /
                         :core:contracts
```

Production dependencies point from the composition root and adapters toward the core contracts; no core module depends on the CLI, Koog, or the HTTP model implementation. The concrete workspace opener and model provider are injected by `:app:cli`. The scheduler accepts a `TaskRunner` instead of constructing a model adapter. Session Protocol JSON Schema and fixtures remain at the repository root under `contracts/v1/` and continue to be validated independently by Node/Python CI.

## Contracts and request admission

`:core:contracts` intentionally owns the shared `SessionRequest` parser and shape validation (approximately 190 lines) as well as `Cancellation`, `Refused` and `Stopped`. Both `:core:session` and the infrastructure adapters consume these stable types; moving them into `:core:session` would force the adapters to depend on the session runtime. Workspace authorization and execution checks **remain** in their existing core/session and infrastructure modules; `contracts/v1` is still the authoritative transport schema.

## Koog dependency

The previously unused `ai.koog:koog-agents:1.3.0` dependency was deliberately dropped from the original root application as part of this structural split. No production source imports it; therefore removing it changes dependency resolution checks, **not current runtime behavior**. The renamed JVM CI job no longer verifies Koog artifact availability. When Koog-backed orchestration is implemented in a future `:agent:runtime` module, restore a tested, module-scoped Koog dependency and CI coverage.

## Build and test

With JDK 21 and Gradle 9.8.1 installed, run from the repository root:

```sh
./gradlew clean test installDist
python -m unittest discover -s tests -p 'test_cli_runtime_v1.py' -v
```

Each core/infra library tests its own contract or implementation without depending on other concrete modules; all cross-module integration tests (Session Controller + FakeModel + Workspace Manager and CLI scheduler) run under `:app:cli`. This eliminates cycles in both production and test project dependencies. Core production classpaths remain adapter-free. No live inference service or private hardware is needed. The CLI installation path and executable name remain `build/install/recursive-coder/bin/recursive-coder`, and arguments, exit codes, and on-disk protocol formats are unchanged.

Shared Kotlin/JVM 21 and JUnit settings live in the `build-logic` included build as the `recursivecoder.jvm` convention plugin. The Kotlin plugin and Kotlin JUnit integration versions are both sourced from `build-logic/gradle/libs.versions.toml`, which the root and included build consume. Third-party dependencies remain declared at their owner. `gradlew` is still the repository's pre-release delegation script; replacing it with a self-contained wrapper is tracked separately in issue #12.

## Deliberately deferred

The current code does not justify empty `:agent:runtime` or `:infra:tools` modules. When recursive Koog orchestration and an independently owned tool execution layer exist, those modules can be extracted with focused boundary tests. Until then the trusted write/check implementation remains alongside restricted Git in `:infra:workspace`.
