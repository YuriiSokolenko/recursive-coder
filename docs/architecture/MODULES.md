# Gradle multi-module boundaries

Issue #22 introduced six modules; issue #27 adds the task persistence adapter; the Gradle root remains `recursive-coder` and is not an executable module.

| Module | Ownership | Production project dependencies |
| --- | --- | --- |
| `:core:contracts` | Session Protocol support, request validation, model/workspace contracts, shared value types and pure tool authorization | none |
| `:core:session` | Session Controller, deadline/budget enforcement, admission policy and event journal | `:core:contracts` |
| `:core:tasks` | Task policy, repository port, scheduler and task execution interface | `:core:contracts` |
| `:infra:model` | Offline FakeModel and optional OpenAI-compatible HTTP adapter | `:core:contracts` |
| `:infra:workspace` | Workspace Manager, restricted Git and trusted write/check runtime | `:core:contracts` |
| `:infra:task-store` | Durable tasks.json snapshot, file locks and atomic replacement | `:core:tasks`, `:core:contracts` |
| `:app:cli` | Clikt command entry point, configuration and concrete dependency composition | all six libraries |

```text
                      :app:cli  (only executable)
                    /     |        |          \
           :core:session :core:tasks :infra:model :infra:workspace :infra:task-store
                    \     |        |          /
                         :core:contracts
```

Production dependencies point from the composition root and adapters toward the core contracts; no core module depends on the CLI, Koog, or the HTTP model implementation. The concrete workspace opener and model provider are injected by `:app:cli`. The scheduler accepts a `TaskRunner` instead of constructing a model adapter. Session Protocol JSON Schema and fixtures remain at the repository root under `contracts/v1/` and continue to be validated independently by Node/Python CI.

## Contracts and request admission

`:core:contracts` intentionally owns the shared `SessionRequest` parser and shape validation (approximately 190 lines) as well as `Cancellation`, `Refused` and `Stopped`. Both `:core:session` and the infrastructure adapters consume these stable types; moving them into `:core:session` would force the adapters to depend on the session runtime. Pure read/write/check authorization decisions and the SDD/ATDD/TDD/Developer/Reviewer ownership matrix now live in `:core:contracts` (`ToolAuthorizationPolicy`). The trusted workspace adapter still owns canonical-path/descriptor checks, Git state, sandbox execution, review provenance and mutation receipts. `contracts/v1` remains the authoritative transport schema.

## Koog dependency

The previously unused `ai.koog:koog-agents:1.3.0` dependency was deliberately dropped from the original root application as part of this structural split. No production source imports it; therefore removing it changes dependency resolution checks, **not current runtime behavior**. The renamed JVM CI job no longer verifies Koog artifact availability. When Koog-backed orchestration is implemented in a future `:agent:runtime` module, restore a tested, module-scoped Koog dependency and CI coverage.

## Build and test

With JDK 21 and Gradle 9.8.1 installed, run from the repository root:

```sh
./gradlew clean test installDist
python -m unittest discover -s tests -p 'test_cli_runtime_v1.py' -v
```

Module-local unit tests can be run separately with `./gradlew :core:session:test :infra:workspace:test`:

- `:core:session` owns the pure `SessionLifecyclePolicy`, `SessionAdmissionPolicy` and `SessionBudgetPolicy` with deterministic value-only tests (`SessionPolicyUnitTest`). `SessionControllerUnitTest` uses in-module `ModelGateway` and `WorkspaceSession` fakes plus temporary event files to exercise orchestration, admission, trusted observations, budgets, errors and event ordering without concrete adapters.
- `:infra:workspace` owns write-tool schema validation and direct Workspace Manager policy/adapter tests. They use only `:core:contracts` types; the small Git fixture stays inside the infrastructure module.
- `:app:cli` retains the real Session Controller + FakeModel + Workspace Manager integration tests, CLI scheduler tests, and sandbox E2E. These are intentionally not copied into the unit-test modules.

Both the production and test project dependency graphs stay acyclic: `:core:session` and `:infra:workspace` depend only on `:core:contracts`, while `:app:cli` is the sole cross-module composition root. Core production classpaths remain adapter-free. No live inference service or private hardware is needed. The CLI installation path and executable name remain `build/install/recursive-coder/bin/recursive-coder`, and arguments, exit codes, and on-disk protocol formats are unchanged.

Shared Kotlin/JVM 21 and JUnit settings live in the `build-logic` included build as the `recursivecoder.jvm` convention plugin. The Kotlin plugin and Kotlin JUnit integration versions are both sourced from `build-logic/gradle/libs.versions.toml`, which the root and included build consume. Third-party dependencies remain declared at their owner. `gradlew` is still the repository's pre-release delegation script; replacing it with a self-contained wrapper is tracked separately in issue #12.

## Deliberately deferred

The current code does not justify empty `:agent:runtime` or `:infra:tools` modules. When recursive Koog orchestration and an independently owned tool execution layer exist, those modules can be extracted with focused boundary tests. Until then the trusted write/check implementation remains alongside restricted Git in `:infra:workspace`.


## Verified architecture and test tiers (issue #25)

The root `checkArchitecture` task enforces the project dependency directions in the table above for every Gradle configuration, including test-only edges and future fixtures. It also detects project cycles and rejects direct core dependencies on common CLI/Koog/HTTP frameworks. CI includes deliberately illegal dependency and test-cycle regressions. A new module requires an intentional allowlist update.

The JVM `unitTest` target contains only tagged pure tests; `integrationTest` runs tagged local filesystem, Git and mock-HTTP cases; `e2eTest` selects tagged sandbox checks. The existing `test` target remains **unfiltered**, preserving all existing coverage and the `./gradlew test installDist` workflow. CI retains the sandbox execution assertion, installed CLI E2E and independent protocol tests.

The file-backed TaskStore now lives in `:infra:task-store`; the CLI composes it with `TaskService`, while `:core:tasks` ships a pure in-memory repository for use-case tests. The physical journal in `:core:session` remains deferred work (#29). See [Test tiers and architecture guardrails](TEST_SUITES.md) for detailed classification, commands and exceptions.

## Task repository (issue #27)

The TaskRepository port owns atomic reads, read-decide-write mutations, the process-wide scheduler lease and run output-root resolution. TaskService depends only on that port; the CLI is the sole owner of the TaskStore selection. Both adapters implement the same transactional interface. The filesystem adapter preserves tasks.json v1 bytes, corruption rejection, POSIX/OS file locks, fsync and atomic rename. An explicit cross-process lock regression and shared transaction contract guard semantics. The core test graph does not depend on an infrastructure project.
