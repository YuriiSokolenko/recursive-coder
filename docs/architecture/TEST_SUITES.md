# Test tiers and architecture boundaries

Issue #25 makes the accepted architecture direction executable without modifying the production runtime, CLI or Session Protocol v1.

## Dependency graph verification

Run `./gradlew checkArchitecture` or `./gradlew checkArchitecture --configuration-cache` (Gradle 9.8.1). The first configuration-cache run stores the task graph; the second reuses it. CI verifies both messages explicitly. This inspects every declared Gradle project dependency across production and test configurations, including future test fixtures. It rejects project dependency cycles even when an edge is declared only in `testImplementation`.

| Consumer module | Allowed project dependencies |
| --- | --- |
| `:core:contracts` | None |
| `:core:session` | `:core:contracts` |
| `:core:tasks` | `:core:contracts` |
| `:infra:model` | `:core:contracts` |
| `:infra:workspace` | `:core:contracts` |
| `:infra:task-store` | `:core:tasks`, `:core:contracts` |
| `:app:cli` | All six modules above |

The checker also rejects unclassified new modules and direct core dependencies on Koog, Clikt and common HTTP framework dependency groups. Each forbidden edge diagnostic identifies its module, configuration and target. The Gradle task snapshots declared module/configuration/target coordinates during configuration and consumes only serializable inputs during execution; it never resolves dependencies. A standalone pure Kotlin graph verifier also tests an all-allowed synthetic cycle independently of the allowlist. CI covers illegal production and test dependencies, a test-only cycle, unknown modules, a forbidden core framework group, and valid graphs.

### Configuration-cache invalidation regression (issue #41)

A cache hit is not a permanent architecture approval. The Python regression in
`tests/test_architecture_gradle.py` copies only Gradle build inputs and the local
`build-logic` into an isolated temporary project. On Gradle 9.8.1 it first runs
`checkArchitecture --configuration-cache` on a valid graph, then proves that
the second run **reuses** the cache. After the cache hit, it changes a copied
module `build.gradle.kts` to add an illegal `implementation` edge and,
separately, an illegal `testImplementation` edge. Each run must fail with
module/configuration/target diagnostics and show that Gradle **invalidated the
cache because the build file changed** and recalculated the task graph.

After each negative case it restores the original input and requires the
valid graph to pass again. The restored run may either reuse a matching valid
entry or reconfigure; an invalidated entry is **not** expected to be reused.
The fixture never modifies the checkout, resolves dependencies, or calls a
model service. CI runs it together with the other architecture regressions
using the pinned Gradle 9.8.1 installation.

### Non-module configuration-cache inputs (issue #45)

The same isolated Gradle 9.8.1 regression suite additionally proves invalidation
after three changes **outside product module build scripts**. It starts with a
valid graph and an explicitly reused configuration-cache entry. Each mutation
follows a confirmed cache hit and is followed by restoring the changed input
and verifying the valid graph succeeds again. Before the second and third
mutations, a cache hit is verified using the restored run itself if it reused
a valid entry, or one additional warm-up run if restoration reconfigured.
After the final properties restoration, no extra cache hit is required.
The three input changes are:

- **Build logic:** removing `:core:contracts` from the `:core:tasks` allowlist
  in the copied `ArchitectureGraph.kt` causes the existing
  `:core:tasks:implementation -> :core:contracts` edge to be rejected.
  Gradle 9.8.1 reports the invalidation as a changed input to
  `:build-logic:compileKotlin`, not as an `ArchitectureGraph.kt` path;
  the fixture separately confirms the source edit.
- **Settings:** including a temporary `:architectureUnclassified` project
  through the copied `settings.gradle.kts` triggers an
  `Unclassified module` diagnostic; the directory is entirely fixture-local.
- **Properties:** a fixture-only `architectureFixtureIllegalEdge` property
  is read during module configuration. Changing only that property from
  `false` to `true` introduces a forbidden declared edge from
  `:core:tasks` to `:infra:workspace`. No JVM or daemon property is changed.

Each negative run requires a configuration-cache miss, an input-specific
indicator in Gradle's cache invalidation reason, and an actionable architecture
diagnostic. The
restored run needs to **pass**, not necessarily report an invalidation:
Gradle is free to reuse an earlier valid entry. No production build logic,
repository input or remote endpoint is modified by these tests.

**Scope and existing exceptions:** The rules cover Gradle project dependencies and selected external framework groups, not imports or the implementation of port interfaces. The included `build-logic` build belongs to Gradle tooling, not product modules. `TaskStore` was extracted into `:infra:task-store` in issue #27, with its `TaskRepository` contract in `:core:tasks`; only the CLI composition root selects the filesystem adapter. `:core:session` still writes a physical event journal internally (existing extraction debt #29). Neither case permits core to depend on infrastructure or CLI. The original issue #25 architecture guardrails remain enforced across all modules.

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

`TaskPolicyUnitTest` and `TaskRepositoryUnitTest` (`:core:tasks`) are **pure unit** suites for immutable transitions, DAG readiness, fairness, cancellation/retry decisions and in-memory transaction semantics. `TaskManagerTest` and `TaskRepositoryContractTest` (`:infra:task-store`) are **integration** suites for file-backed snapshots, locks, Git fixtures, concurrent claims, adapter parity, restart/races and atomic rename failure. Neither tier substitutes for the other.

The sandbox method in `TrustedWriteRuntimeTest` inherits the class-level `integration` tag and adds `e2e`. `integrationTest` excludes `e2e` methods so its ordinary local I/O checks can run without bubblewrap. The dedicated `e2eTest` task selects that sandbox method; installed CLI E2E is instead handled by Python.

## Enforced classification and purity checks

`./gradlew checkTestTiers` compiles each JVM module's test source set, then inspects the resulting `.class` files with `tools/check_test_tiers.py` (Python 3.10+ standard library only). This identifies executable Jupiter methods through bytecode annotations instead of names or Kotlin source grep. Supported markers include `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestTemplate` and `@TestFactory`, plus composed annotations compiled in a project test source set. Dynamic test children inherit the factory's classification; the guard validates the factory, not every generated runtime child.

**Choose a tier for every new JUnit test:**

1. Use class-level `@Tag("unit")` for an entirely in-memory suite, or put a tag on each test method. Valid unit fixtures use values, fakes and other deterministic in-memory collaborators.
2. Use `@Tag("integration")` when an adapter, temporary file, Git repository, subprocess or local mock HTTP server is involved.
3. Use `@Tag("e2e")` when exercising real sandbox or cross-component execution. The supported combination is class `integration` plus method `e2e`: `integrationTest` excludes that method and `e2eTest` includes it. Repeated tags of the same tier are harmless; `unit` mixed with `integration` or `e2e` is rejected. Class, method, superclass/interface and JUnit `@Nested` enclosing-class tags all count.

A test with no effective tier, including a typo such as `@Tag("unti")`, fails the validator with source location, class and method. It **still runs under the ordinary unfiltered `test` task**. Never add tag filters to that legacy task; validation is a separate CI gate.

**Purity guard and limits:** For `unit` tests the bytecode validator rejects references to selected filesystem, Git/JGit, socket, HTTP, subprocess and Koog APIs, plus JUnit `@TempDir` fixtures. It scans the test class, nested helper classes, locally compiled base types, and enclosing fixtures of JUnit `@Nested` test classes, including outer `@BeforeEach` methods and `@TempDir` fields even when the outer class has no tests of its own. This intentionally conservative static boundary can flag an unused I/O helper in a mixed class; separate pure and integration suites if that happens. It allows in-memory fakes, but does **not** prove freedom from all side effects: reflective calls, third-party helpers outside the compiled test classes, dynamic code loading and runtime delegates can evade inspection. Review remains required. Run `python3 -m unittest discover -s tests -p 'test_test_tiers.py' -v` for independent compiled positive and negative fixtures; these do not download JUnit or run a model.

## Commands, CI and non-dropping guarantee

~~~sh
./gradlew checkArchitecture checkTestTiers unitTest
./gradlew checkArchitecture --configuration-cache
./gradlew -p build-logic test
./gradlew integrationTest
./gradlew e2eTest
./gradlew test installDist
python -m unittest discover -s tests -p 'test_cli_runtime_v1.py' -v
python -m unittest discover -s tests -p 'test_contract*.py' -v
node --test contracts/v1/session-v1.test.mjs
~~~

`test` **still runs all existing tests** regardless of their tags; `./gradlew test installDist` is backward-compatible. An untagged future test is therefore still included in the full JVM CI, not silently dropped. New tests **must** have an effective tier tag or `checkTestTiers` fails, even though they still execute under `test`. Do not classify a test as pure just because it has fake model/workspace interfaces if its fixture writes files or launches a process.

CI has an independent architecture/tier-validation/pure-unit job with cold/reused Gradle 9.8.1 configuration-cache verification and graph-algorithm unit tests, verifies the fast suite really executed, and retains the full JVM `test` job, its mandatory sandbox JUnit XML check, focused `integrationTest`/`e2eTest` routing assertion and the installed CLI E2E. The independent protocol-conformance job is unchanged. All default verification is offline from any inference endpoint and does not require credentials or personal hardware. Sandbox E2E may be skipped on unsupported local hosts, but **must run and pass in Linux CI**.
