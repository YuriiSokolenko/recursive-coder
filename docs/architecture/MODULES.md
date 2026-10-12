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

Module-local unit tests can be run separately with `./gradlew :core:session:test :infra:workspace:test`:

- `:core:session` owns fast Session Controller tests with in-module `ModelGateway` and `WorkspaceSession` fakes. These cover admission, trusted observations, token/request budgets, errors, and event ordering without loading concrete adapters.
- `:infra:workspace` owns write-tool schema validation and direct Workspace Manager policy/adapter tests. They use only `:core:contracts` types; the small Git fixture stays inside the infrastructure module.
- `:app:cli` retains the real Session Controller + FakeModel + Workspace Manager integration tests, CLI scheduler tests, and sandbox E2E. These are intentionally not copied into the unit-test modules.

Both the production and test project dependency graphs stay acyclic: `:core:session` and `:infra:workspace` depend only on `:core:contracts`, while `:app:cli` is the sole cross-module composition root. Core production classpaths remain adapter-free. No live inference service or private hardware is needed. The CLI installation path and executable name remain `build/install/recursive-coder/bin/recursive-coder`, and arguments, exit codes, and on-disk protocol formats are unchanged.

Shared Kotlin/JVM 21 and JUnit settings live in the `build-logic` included build as the `recursivecoder.jvm` convention plugin. The Kotlin plugin and Kotlin JUnit integration versions are both sourced from `build-logic/gradle/libs.versions.toml`, which the root and included build consume. Third-party dependencies remain declared at their owner. `gradlew` is still the repository's pre-release delegation script; replacing it with a self-contained wrapper is tracked separately in issue #12.

## Deliberately deferred

The current code does not justify empty `:agent:runtime` or `:infra:tools` modules. When recursive Koog orchestration and an independently owned tool execution layer exist, those modules can be extracted with focused boundary tests. Until then the trusted write/check implementation remains alongside restricted Git in `:infra:workspace`.


## Verified architecture and test tiers (issue #25)

The root `checkArchitecture` task enforces the project dependency directions in the table above for every Gradle configuration, including test-only edges and future fixtures. It also detects project cycles and rejects direct core dependencies on common CLI/Koog/HTTP frameworks. CI includes deliberately illegal dependency and test-cycle regressions. A new module requires an intentional allowlist update.

The JVM `unitTest` target contains only tagged pure tests; `integrationTest` runs tagged local filesystem, Git and mock-HTTP cases; `e2eTest` selects tagged sandbox checks. The existing `test` target remains **unfiltered**, preserving all existing coverage and the `./gradlew test installDist` workflow. CI retains the sandbox execution assertion, installed CLI E2E and independent protocol tests.

The current file-backed TaskStore in `:core:tasks` and physical journal in `:core:session` are explicitly deferred internal extraction work (#27 and #29), **not** cross-module dependency exemptions. See [Test tiers and architecture guardrails](TEST_SUITES.md) for detailed classification, commands and exceptions.
