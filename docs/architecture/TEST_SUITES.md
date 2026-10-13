# Test tiers and architecture boundaries

Issue #25 makes the accepted architecture direction executable without modifying the production runtime, CLI or Session Protocol v1.

## Dependency graph verification

Run `./gradlew checkArchitecture`. This inspects every declared Gradle project dependency across production and test configurations, including future test fixtures. It rejects project dependency cycles even when an edge is declared only in `testImplementation`.

| Consumer module | Allowed project dependencies |
| --- | --- |
| `:core:contracts` | None |
| `:core:session` | `:core:contracts` |
| `:core:tasks` | `:core:contracts` |
| `:infra:model` | `:core:contracts` |
| `:infra:workspace` | `:core:contracts` |
| `:app:cli` | All five modules above |

The checker also rejects unclassified new modules and direct core dependencies on Koog, Clikt and common HTTP framework dependency groups. Each forbidden edge diagnostic identifies its module, configuration and target. CI runs two negative regressions using temporary Gradle init scripts: a core-to-adapter test dependency and a test-only edge that creates a cycle.

**Scope and existing exceptions:** The rules cover Gradle project dependencies and selected external framework groups, not imports or the implementation of port interfaces. The included `build-logic` build belongs to Gradle tooling, not product modules. Current `:core:tasks` has a file-backed `TaskStore` and `:core:session` still writes a physical event journal internally. These are *existing extraction debts* tracked by issues #27 and #29, not permissions for core to depend on infrastructure or CLI. This task deliberately does not refactor production code.

## Test taxonomy

| Tier | Target | Characteristics and coverage |
| --- | --- | --- |
| Pure unit | `./gradlew unitTest`, `@Tag("unit")` | In-memory values and deterministic fakes only: no temp filesystem, real Git, HTTP, Koog, sockets or subprocesses |
| Integration | `./gradlew integrationTest`, `@Tag("integration")` | Durable task store, local files/Git, model mock HTTP, workspace safety, locks and adapters |
| JVM sandbox E2E | `./gradlew e2eTest`, `@Tag("e2e")` | Real bubblewrap isolation on a supported Linux host |
| Installed CLI E2E | Python `test_cli_runtime_v1.py` | Installed binary, FakeModel, outputs and Session Protocol v1 conformance |
| Protocol compatibility | Python `test_contract*.py` and Node `contracts/v1/session-v1.test.mjs` | Schema, example fixtures, negative cases, version and keyword guard |
| Future live-model evaluations | Opt-in only | Real model compatibility and quality checks, never required for default CI |

`SessionProtocolTest` and `TrustedWriteToolSchemaTest` are tagged *pure unit*. Despite historical names, `SessionControllerUnitTest` uses temporary files and `WorkspaceBoundaryUnitTest` invokes real Git; these run in the **integration** tier. `TaskManagerTest` also creates a real file-backed store and Git fixture. `ModelGatewayTest` uses a local mock HTTP server but no external inference service.

The sandbox method in `TrustedWriteRuntimeTest` inherits the class-level `integration` tag and adds `e2e`. `integrationTest` excludes `e2e` methods so its ordinary local I/O checks can run without bubblewrap. The dedicated `e2eTest` task selects that sandbox method; installed CLI E2E is instead handled by Python.

## Commands, CI and non-dropping guarantee

~~~sh
./gradlew checkArchitecture unitTest
./gradlew integrationTest
./gradlew e2eTest
./gradlew test installDist
python -m unittest discover -s tests -p 'test_cli_runtime_v1.py' -v
python -m unittest discover -s tests -p 'test_contract*.py' -v
node --test contracts/v1/session-v1.test.mjs
~~~

`test` **still runs all existing tests** regardless of their tags; `./gradlew test installDist` is backward-compatible. An untagged future test is therefore still included in the full JVM CI, not silently dropped. New tests should nonetheless be explicitly tagged to appear in focused targets. Do not classify a test as pure just because it has fake model/workspace interfaces if its fixture writes files or launches a process.

CI has an independent architecture/pure-unit job, verifies the fast suite really executed, and retains the full JVM `test` job, its mandatory sandbox JUnit XML check and the installed CLI E2E. The independent protocol-conformance job is unchanged. All default verification is offline from any inference endpoint and does not require credentials or personal hardware. Sandbox E2E may be skipped on unsupported local hosts, but **must run and pass in Linux CI**.
