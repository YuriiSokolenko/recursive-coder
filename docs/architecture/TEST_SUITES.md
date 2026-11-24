# Test tiers and architecture boundaries

Issue #25 makes the accepted architecture direction executable without modifying the production runtime, CLI or Session Protocol v1.

## Dependency graph verification

Run `./gradlew checkArchitecture` or `./gradlew checkArchitecture --configuration-cache` (Gradle 9.8.2). The first configuration-cache run stores the task graph; the second reuses it. CI verifies both messages explicitly. This inspects every declared Gradle project dependency across production and test configurations, including future test fixtures. It rejects project dependency cycles even when an edge is declared only in `testImplementation`.

| Consumer module | Allowed project dependencies |
| --- | --- |
| `:core:contracts` | None |
| `:core:session` | `:core:contracts` |
| `:core:tasks` | `:core:contracts` |
| `:infra:model` | `:core:contracts` |
| `:infra:workspace` | `:core:contracts` |
| `:infra:task-store` | `:core:tasks`, `:core:contracts` |
| `:infra:journal` | `:core:session`, `:core:contracts` |
| `:app:cli` | All seven modules above |

The checker also rejects unclassified new modules and direct core
dependencies on Koog, Clikt and common HTTP framework dependency groups.
Each forbidden edge diagnostic identifies its module, configuration and target.
A standalone pure Kotlin graph verifier tests an all-allowed synthetic cycle
independently of the allowlist. CI also covers illegal production and test
dependencies, a test-only cycle, unknown modules and forbidden core frameworks.

### Architecture snapshot lifecycle (issue #42)

The root plugin registers `checkArchitecture` with only task metadata. Its
`gradle.projectsEvaluated { taskProvider.configure { ... } }` callback fills
the dependency and policy inputs **after all projects in this build finish
evaluation**, including their `afterEvaluate` handlers. This is Gradle's public
lifecycle API, independent of task realization: even when the root build calls
`tasks.named("checkArchitecture").get()` before subprojects configure, the
already-realized task gets the declared module/configuration/target graph before
execution. No dependencies are resolved; the snapshot includes production,
test and test-fixture configurations and checks inherited file dependencies.
The task action reads only serializable properties and declared file inputs,
never mutable `Project` or `Configuration` objects.

**Callback-order limitation:** `projectsEvaluated` listeners execute in
registration order. This plugin's listener is registered during root plugin
application; root build-script listeners run later. For an **eagerly realized**
task, `configure { ... }` inside this listener runs immediately, before later
`projectsEvaluated` listeners. For a **lazy** task, that configuration can
instead run after those listeners. Therefore a dependency inserted from a later
`projectsEvaluated` callback could be missed only by the eager path. The
guaranteed common snapshot boundary is the end of normal project evaluation,
not the end of every `projectsEvaluated` listener. This build's reviewed
callbacks do not add dependencies; adding such a late dependency mutation
requires separate policy enforcement or lifecycle redesign.

The snapshot sets `snapshotComplete=true` only after filling all task inputs.
The task action explicitly rejects an unset seal rather than evaluating an
empty graph. The regression fixture verifies normal and eager realization on
cold/reused configuration caches, rejects an edge declared during a later
subproject's evaluation after cache invalidation and reuse, and separately
checks the missing-snapshot diagnostic. Issue #42 refers to Gradle 9.8.1;
this repository currently pins and verifies Gradle 9.8.2, without a wrapper
version change in this PR.

### Architecture suite Gradle home and shared-cache boundary (issues #167, #169)

Run `python3 -I -m unittest discover -s tests -p 'test_architecture_gradle.py' -v`
with **Python 3.10 or newer** (CI uses Python 3.12) and Gradle 9.8.2.
The suite creates a private, mode-0700 `GRADLE_USER_HOME` with its own
daemon registry, `init.d` and minimal `gradle.properties`; developer daemons,
developer init scripts and developer Gradle properties are **not** imported.
A 60-second idle timeout and a suite-scoped `--quiet --stop` on exit/timeout
target only suite daemons. The isolated properties suppress Gradle's welcome
banner without changing the repository configuration.

By default the home lives below `<resolved GRADLE_USER_HOME parent>/.architecture-gradle-homes/`
on the **same filesystem** as the shared dependency cache, not directly in
`$HOME`. The hidden parent is private (mode 0700 on POSIX) and rejects
symlinks, junctions and unsafe ownership or permissions. On startup the suite
removes only positively marked directories older than 72 hours whose owning
POSIX process is no longer running on the same reported hostname. Other files,
unknown directories and live processes are never pruned. Hostnames are not
unique machine identities: containers sharing a home and hostname but using
different PID namespaces can mistake each other's homes for stale ones.
Windows skips automatic stale-home removal without reliable liveness proof;
an interrupted run may require manual cleanup of this hidden parent.

Set `ARCHITECTURE_GRADLE_HOME_PARENT=/existing/writable/path` to try an
explicit base directory **first**, creating its own private
`.architecture-gradle-homes/` underneath; use a filesystem holding
`caches/modules-2/files-2.1` if the artifact cache is mounted separately.
If an attempted location raises `OSError` (for example, permissions), the
suite tries the sibling location, then a private child of the shared home,
then a private subdirectory under the system temp location. The last fallback
will normally fail the artifact-device check if `/tmp` is on tmpfs and the
cache contains artifacts. A cross-device cache always **fails explicitly**
rather than silently copying multi-GB artifacts into RAM. Other hardlink
failures may copy up to a total of 128 MiB; exceeding the cap fails explicitly.
The `[architecture-suite-home]` log reports placement, attempted-location
fallbacks, setup elapsed time and hardlinked/copied file counts and byte totals.
Failures to allocate any home also log their attempted fallbacks. Timings are
informational only.

**This is not full cache isolation.** The content-addressed
`caches/modules-2/files-2.1` artifacts are hardlinked (or explicitly copied)
so the architecture security guard still validates canonical paths and hashes.
Other selected cache paths, including dependency resolution metadata and the
local build cache, intentionally point to the **developer's shared cache**.
The suite's own Gradle 9 `init.d` script sets
`settings.caches { cleanup = Cleanup.DISABLED }` in a `beforeSettings` hook,
preventing **suite-initiated Gradle User Home cache cleanup** from pruning
shared cache entries. A real-Gradle probe asserts that the effective
`settings.caches.cleanup.get()` value is the `Cleanup.DISABLED` object.
Ordinary Gradle access to shared cache metadata is still possible, as is
cleanup initiated independently by the developer's other Gradle processes.
The suite does not promise read-only shared caches.

Fixture build outputs and configuration-cache entries remain private per fixture.
All Gradle regressions still use `--offline`, strict dependency verification
and canonical-path/SHA enforcement; no security negative check is relaxed.
Windows symlink fallback uses directory junctions. Cleanup is performed by
`TemporaryDirectory`/`shutil.rmtree` on supported CPython 3.10+, where
Windows junction removal unlinks the junction itself rather than traversing
and deleting its target (the behavior was fixed in Python 3.8). Do not replace
this with recursive removal that follows junctions or symlinks.

### Architecture CI timing and execution evidence (issue #36)

The architecture regression suite runs real injected Gradle dependency-edge,
cycle, and configuration-cache negative cases. The reused, suite-private Gradle
daemon introduced in [PR #168](https://github.com/YuriiSokolenko/recursive-coder/pull/168)
replaced repeated **cold** Gradle starts without removing any of the 93 actual
Gradle invocations measured at PR #168 head `b15e47b`. Scenario-specific subprocess deadlines are bounded (normally
180 seconds, or 240 seconds for more expensive compile/configuration cases),
and the runner streams combined stdout/stderr with scenario, phase and timeout
markers; a timeout stops only its private daemon registry. These are ceilings,
not performance assertions.

Comparable GitHub-hosted architecture runs recorded for PR #168 (minutes):

| Sample | Architecture job | Negative regression step |
| --- | ---: | ---: |
| Before: [45085104240](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45085104240) | 22.3 | 17.2 |
| Before: [45097927895](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45097927895) | 15.7 | 11.7 |
| Before: [45134262419](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45134262419) | 22.0 | 16.9 |
| **Before median** | **22.0** | **16.9** |
| After: [45138287471, attempt 1](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45138287471/attempts/1) | 6.6 | 2.52 |
| After: [45138287471, attempt 2](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45138287471/attempts/2) | 6.6 | 2.57 |
| After: [45138287471, attempt 3](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45138287471/attempts/3) | 6.6 | 2.62 |
| **After median** | **6.6** | **2.57** |

All three *after* architecture attempts ran the same 43-test/93-Gradle-invocation
suite on PR #168 head `b15e47b`; attempts 2 and 3 reran the architecture job only,
not the full workflow. For whole-workflow context, the three before runs took
26.1, 20.2 and 26.1 minutes; after-change successful full runs
[45138287471](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45138287471)
and [45145310763](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45145310763)
took 10.4 and 10.5 minutes respectively. These are observational samples on
PR #168 commits, **not** a measured speedup for later changes or a CI threshold.

CI uses `tools/check_junit_evidence.py` to require passing unfiltered
`test` evidence in every product module and passing focused evidence for
modules with compiled integration or E2E methods. The existing bytecode tier
classifier supplies expected module/tier membership; a module with no methods
in a focused tier need not produce XML for that tier. Focused case identities
must also occur in that module's unfiltered reports. The gate checks execution
presence, not complete method-level coverage; failure/error XML rejection is
defence in depth after Gradle succeeds. Existing mandatory sandbox markers and
pure-unit evidence remain separate. The contracts job tests the evidence checker
and checks lexicographic contiguous imports in the eight issue #36 classes;
this deliberately does not impose a new repository-wide formatting policy.
The independently installed CLI and protocol suites remain unchanged. The unfiltered `test` task remains intact. The production
`installDist` build runs in the isolated bytecode-build job and is verified
before CLI E2E; the full JVM tasks run on their own clean runner after that
verifier succeeds. Compilation artifacts are intentionally **not shared** across
these independent trust boundaries merely to improve cache hit rates. The
standalone developer command `./gradlew test installDist` remains supported.

### Configuration-cache invalidation regression (issue #41)

A cache hit is not a permanent architecture approval. The Python regression in
`tests/test_architecture_gradle.py` copies only Gradle build inputs and the local
`build-logic` into an isolated temporary project. On Gradle 9.8.2 it first runs
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
using the pinned Gradle 9.8.2 installation.

### Non-module configuration-cache inputs (issue #45)

The same isolated Gradle 9.8.2 regression suite additionally proves invalidation
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
  Gradle 9.8.2 reports the invalidation as a changed input to
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

**Scope and existing exceptions:** The rules cover Gradle project dependencies and selected external framework groups, not imports or the implementation of port interfaces. The included `build-logic` build belongs to Gradle tooling, not product modules. `TaskStore` was extracted into `:infra:task-store` in issue #27, with its `TaskRepository` contract in `:core:tasks`; only the CLI composition root selects the filesystem adapter. The physical JSONL event writer is in `:infra:journal` (#29), selected by the CLI EventSinkFactory. Neither case permits core to depend on infrastructure or CLI. The original issue #25 architecture guardrails remain enforced across all modules.

## Deny-only fenced resume boundary (PR #135)

`SessionPolicyUnitTest` (`unit`) pins the original v1 denial precedence for
resume requests even with later-invalid capability/model/tool/budget fields,
and the strict safe-detail grammar. `SessionControllerUnitTest`
(`integration`, historical name) uses in-memory model/workspace ports with
temporary output files to verify sanitized nonfatal host errors,
pre-cancelled requests, protocol-before-probe ordering, configured bounded
handling of an uninterruptible filesystem lookup, restoration of the caller
interrupt flag, independent diagnostic workers for distinct runtime
instances, and unaffected default refusal even while another runtime's
opt-in diagnostic worker is occupied.

`RuntimePreExecutionBoundaryIntegrationTest` (`integration`) tests the real
`DefaultRunSessionUseCase` and durable session ledger. It verifies an
injected `FileStore.type()` failure carrying private cause/suppressed
paths. The regression scans binary ledger bytes via ISO-8859-1 **and**
authenticated events decoded by `inspect`, along with result and diagnostics;
it verifies no model/workspace activity and no `resume.lock` or fence.
An additional durable-ledger regression interrupts the runtime caller while
`Future.get` is waiting on the opt-in probe, and confirms the later NIO
journal failure propagates without publishing a typed result or diagnostics. Another test verifies
that two valid local filesystem types still cannot authorize resume.
Neither test provides production runtime admission, effect reconciliation,
or permission to resume writes (#104/#105).

## Trusted runtime C1 -> C2 handoff-only integration (#104)

`RuntimeTrustedCreatedAcceptedHandoffIntegrationTest` exercises the
`DefaultRunSessionUseCase.publishTrustedCreatedAccepted` entry with a real
host-verified C1, physical durable ledger, OS-fenced transition and C2.
The model, workspace and normal runtime event sink are deliberately
explosive fakes; the tests assert that none is reached and no Session
Protocol result is written. Identity/CAS/protocol denial cases are checked
before execution. This is a host-only metadata boundary, not a test of
runtime execution fencing, stalled-fsync shutdown or crash-resume.

## Trusted runtime C2 -> C3 running-only boundary (#104, no effects)

`TrustedRunningBoundaryPolicyUnitTest` (`@Tag("unit")`) proves that only
one final, exact-envelope root actor `accepted -> running` event follows
an authenticated pre-execution prefix. Unknown-null actor keys, missing
actor keys, execution-shaped metadata, repeated edges and pre-existing
tool events fail closed without a physical writer.

`RuntimeTrustedCreatedAcceptedHandoffIntegrationTest`
(`@Tag("integration")`) exercises the distinct, explicitly opted-in
`DurableRuntimeAcceptedRunningHost` through
`DefaultRunSessionUseCase.publishTrustedAcceptedRunning`. It proves
durable C3 with no model/workspace/event-sink activity, incorrect CAS,
created -> running shortcut and repeated C3 denial without new generation,
checkpoint or journal mutation.

`DurableCreatedAcceptedHandoffIntegrationTest` (`@Tag("integration")`)
provides physical-fs coverage for C3 without a checkpoint (unrecoverable
journal tail), C3 attempts while generation-one lease is held, retired
generation-two writer append/save refusal, mismatched CAS after generation
two, and byte-identical journal/no-C3 guarantees. C3 is intentionally
**not an admissible resume boundary**.

The dedicated `:infra:journal:verifyRunningHostOptIn` Gradle task executes
the project's Kotlin compiler against **positive and negative** out-of-source
fixtures in `infra/journal/src/optInFixtures/`. CI must observe that a
caller opted into `TrustedPreExecutionHandoff` alone cannot call
`publishAcceptedRunning` on the C2 class *and* cannot instantiate the
`@TrustedRunningBoundaryApi` C3 host, whereas a caller explicitly opted
into both compiles. This is compiler-enforced API isolation, not a source
grep or runtime/JUnit-only assertion. CI invokes the task in its architecture
job. See [fenced resume and crash-tail runbook](FENCED_RESUME.md). No test
here authorizes actual effects, general runtime resume or #105 recovery.

## Five-role coordinator tests (issue #79)

`FiveRoleWorkflowCoordinatorUnitTest` (`@Tag("unit")`) runs scripted in-memory `FiveRoleStageActions` and `FiveRoleWorkflowHost` ports. It checks invalid plans including forbidden Developer targets, mismatched host snapshots, tentative vs confirmed commits, reconciliation denials, Reviewer stops and final graph attestation failures with exact codes/stages. `FiveRoleFakeModelWorkflowIntegrationTest` (`@Tag("integration")`) invokes the five real stage use cases through an in-memory fake host and fake tool sessions. **These fake-host tests alone do not establish authenticated receipts or Git transition security.** Direct `:infra:workspace`
`LinuxStageGitBaselineReconcilerTest` cases separately exercise the new typed
baseline API: malformed heads/branches, Git ref drift (including during final
inspection), unsafe local config/index flags, ignored files and total deadline
expiry. This validates the shared read-only and mutable preflight contract at
its owning module rather than only through CLI integration. The new `LinuxFiveRoleWorkflowHostTest` (`:app:cli`, `@Tag("integration")`) exercises the real Linux TrustedWriteRuntime session, host HMAC/typed evidence, a complete five-role run through Git CAS and independent Reviewer `finishSuccess`, tampered Developer usage/write ID/changed-file digests, forged acceptance, quarantine, unsafe Git configuration, ignored files, FIFO reads, and an external approval callback without holding the host monitor. The installed writable CLI with two distinct nested stage origins is still outstanding under #79. See [five-role orchestration](FIVE_ROLE_ORCHESTRATION.md).

## Portable task-store lock and atomic replacement regressions (issue #52)

`:infra:task-store:integrationTest` executes `TaskRepositoryContractTest`
using Java 21 and a standalone JVM fixture. It launches the current JDK's
`bin/java` (or `bin/java.exe`) with a Java launcher argument file containing
the Gradle test runtime classpath, avoiding Windows command-line length limits.
Child stderr is captured separately from the stdout handshake and reported on failure.
**No Python, `fcntl`, shell utility, POSIX-only API or external service**
is required for these lock tests. Local filesystems must support Java NIO
exclusive file locks; Linux CI exercises both `.store.lock` (blocking
transaction) and `.scheduler.lock` (fail-fast lease/recovery denial).
The child prints `LOCKED` only after acquiring the physical file lock;
the parent observes an in-transaction hook immediately before the lock call.
Blocking is then observed over a bounded 300 ms `Future.get` window, and
completion is required after the child releases its lease.
A separate child probes that a failed same-JVM acquisition did not release
the original lease.

Windows and macOS are supported by the JVM fixture design, but this
repository's mandatory CI currently runs these regressions on Linux; do not
treat unexecuted platforms as validated. Only alias-symlink cases explicitly
assume/skip on Windows when symlinks require privileges or developer mode,
with a diagnostic. Missing Java subprocess execution or broken local NIO
locking is a failing environment rather than a silent success.

The atomic replacement regression uses a test-only fail-only hook *after*
`FileChannel.force(true)` and close of `tasks.json.tmp`, at the
`ATOMIC_MOVE` boundary. This avoids assuming that replacing an existing
directory with a regular file fails on all filesystems. It verifies byte-
identical previous authority, ignored orphan temp, and successful later
replacement. No production move options, snapshot encoding, corruption
checks, locking, or fsync behavior are substituted.

## Test taxonomy

| Tier | Target | Characteristics and coverage |
| --- | --- | --- |
| Pure unit | `./gradlew unitTest`, `@Tag("unit")` | In-memory values and deterministic fakes only: no temp filesystem, real Git, HTTP, Koog, sockets or subprocesses |
| Integration | `./gradlew integrationTest`, `@Tag("integration")` | Durable task store, local files/Git, model mock HTTP, workspace safety, locks and adapters |
| JVM sandbox E2E | `./gradlew e2eTest`, `@Tag("e2e")` | Real bubblewrap isolation on a supported Linux host |
| Installed CLI E2E | Python `test_cli_runtime_v1.py` | Installed binary, FakeModel, outputs and Session Protocol v1 conformance |
| Protocol compatibility | Python `test_contract*.py` and Node `contracts/v1/session-v1.test.mjs` | Schema, example fixtures, negative cases, version and keyword guard |
| Future live-model evaluations | Opt-in only | Real model compatibility and quality checks, never required for default CI |

`SessionProtocolTest`, `SessionPolicyUnitTest`, `ToolAuthorizationPolicyUnitTest` and `TrustedWriteToolSchemaTest` are tagged *pure unit*. `ToolAuthorizationPolicyUnitTest` uses a literal role/path expected matrix, frozen before/after authorization outcomes transcribed from the pre-extraction `TrustedWriteRuntime.authorized()` at `dev@04018999`, denied read capabilities, immutable artifact/review conflicts, check rules, and adversarial precedence cases; adapter enforcement is covered independently by `TrustedWriteRuntimeTest` and `WorkspaceBoundaryUnitTest`. `SessionEventJournalUnitTest` checks event ordering, correlation, metadata-only redaction and sink failure in memory. `JsonlEventSinkTest` exercises the physical JSONL adapter, checksum, partial-write, flush and close failures in the integration tier. `DurableSessionEventLedgerTest` covers the separate opt-in fsynced binary ledger, strict read-only replay, hash-valid identity/causation/sequence refusals, metadata redaction policy, and an inter-process lease regression; see [durable event ledger](DURABLE_EVENT_LEDGER.md). `DurableSessionEventLedgerCapacityUnitTest` contains pure, I/O-free 4 MiB / 32 MiB / 100k guard boundary cases. `SessionCheckpointCodecUnitTest` validates the canonical versioned metadata-only snapshot format and structural policy without I/O; `SessionCheckpointStoreIntegrationTest` exercises real ledger binding, immutable generations, injected crash points, and corruption/drift refusals; see [host-owned checkpoints](SESSION_CHECKPOINTS.md) and [fenced admission](FENCED_RESUME.md), covering Linux lease races, authority drift, durable anchor rollback and stale writer refusals. Issue #114 checks are split by responsibility:

- `PreExecutionPhasePolicyUnitTest` in `core:session` (`@Tag("unit")`)
  proves the single explicit `created → accepted` edge, real recursive/
  preparation-emitter denial, strict checkpoint metadata and actor allowlists.
- `PreExecutionLedgerPolicyUnitTest` in `infra:journal` (`@Tag("unit")`)
  validates the shared core phase decisions, snapshot/usage boundaries and
  fenced root-graph metadata policy without physical I/O.
- `DurableSessionEventLedgerCapacityUnitTest` (`@Tag("unit")`) fixes
  precise 4 MiB live-checkpoint, 32 MiB ledger and 100k event boundaries.
- `SessionCheckpointStoreIntegrationTest` (`@Tag("integration")`) checks
  checkpoint store generations, path-based `saveFenced` boundaries, unsafe
  snapshot/phase denial, fence admission, durable audit and anchor recovery,
  preflight failures and reconciliation.
- `FencedLedgerReopenIntegrationTest` (`@Tag("integration")`) checks the
  reopened writer's verified prefix, phase-bound fenced appends and saves,
  valid two-generation continuation, 4 MiB limits, writer/lease revocation,
  ambiguous publication diagnostics, multi-JVM lock and fence races, inode
  replacement, physical write/force fault seams, SIGKILL/restart refusal
  and no-mutation prepublication validation. Subprocess coverage has bounded
  waits and `@Timeout`.
- `RuntimePreExecutionBoundaryIntegrationTest` (`@Tag("integration")`)
  exercises the real `DefaultRunSessionUseCase` event journal and pins the
  pre-execution admission refusal `RESUME_UNSAFE_BOUNDARY`: the production
  runtime is not an authorized #114 fenced-ledger producer.

`SessionPolicyUnitTest` exhaustively checks legal/illegal lifecycle edges, terminal immutability, cancellation/deadline ordering, admission refusals, request reservation and token/tool quota decisions with fixed values and no clocks or I/O. Despite historical names, `SessionControllerUnitTest` uses temporary result/diagnostics files and an in-memory event sink and `WorkspaceBoundaryUnitTest` invokes real Git; these run in the **integration** tier. `TaskManagerTest` also creates a real file-backed store and Git fixture. `ModelGatewayTest` uses a local mock HTTP server but no external inference service.

`TaskPolicyUnitTest` and `TaskRepositoryUnitTest` (`:core:tasks`) are **pure unit** suites for immutable transitions, DAG readiness, fairness, cancellation/retry decisions and in-memory transaction semantics. `TaskManagerTest` and `TaskRepositoryContractTest` (`:infra:task-store`) are **integration** suites for file-backed snapshots, locks, Git fixtures, concurrent claims, adapter parity, restart/races and atomic rename failure. Neither tier substitutes for the other.

The `DeveloperLinuxStageIntegrationTest` is tagged `e2e` and exercises
real Developer-to-Linux sandbox success and failed-check cases. Neither is
selected by the focused `integrationTest` tier. CI asserts both appear
exactly once, without skips, in the unfiltered `test` and focused
`e2eTest` XML outputs.

The sandbox method in `TrustedWriteRuntimeTest` inherits the class-level `integration` tag and adds `e2e`. `integrationTest` excludes `e2e` methods so its ordinary local I/O checks can run without bubblewrap. The dedicated `e2eTest` task selects that sandbox method; installed CLI E2E is instead handled by Python.

## Enforced classification and purity checks

`./gradlew check` runs the single root `checkTestTiers` guard after compiling every JVM module's test source set; `./gradlew checkTestTiers` can also run it independently. The guard inspects the resulting `.class` files with `tools/check_test_tiers.py` (Python 3.10+ standard library only). This identifies executable Jupiter methods through bytecode annotations instead of names or Kotlin source grep. Supported markers include `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestTemplate` and `@TestFactory`, plus composed annotations compiled in a project test source set. Dynamic test children inherit the factory's classification; the guard validates the factory, not every generated runtime child.

**Choose a tier for every new JUnit test:**

1. Use class-level `@Tag("unit")` for an entirely in-memory suite, or put a tag on each test method. Valid unit fixtures use values, fakes and other deterministic in-memory collaborators.
2. Use `@Tag("integration")` when an adapter, temporary file, Git repository, subprocess or local mock HTTP server is involved.
3. Use `@Tag("e2e")` when exercising real sandbox or cross-component execution. The supported combination is class `integration` plus method `e2e`: `integrationTest` excludes that method and `e2eTest` includes it. Repeated tags of the same tier are harmless; `unit` mixed with `integration` or `e2e` is rejected. Class, method, superclass/interface and JUnit `@Nested` enclosing-class tags all count.

A test with no effective tier, including a typo such as `@Tag("unti")`, fails the validator with source location, class and method. It **still runs under the ordinary unfiltered `test` task**. Never add tag filters to that legacy task; validation is a separate verification dependency, not a filter. The root `check` wires the guard **once**, even though it compiles test classes from every JVM module. CI checks the root lifecycle for a single guard and separately compares the guard-only dry-run `testClasses` tasks against the independently derived Gradle project inventory. The guard independently discovers conventional `src/test` Kotlin/Java sources and fails with a module-specific diagnostic when a newly added JVM module is absent from Gradle's supplied compiled-class directories. This fallback cannot discover modules that use only custom test source locations; those must be registered in Gradle with their test output directories. `build-logic` is an independent included build and has its own Gradle tests.

**Purity guard and limits:** For `unit` tests the bytecode validator rejects references to selected filesystem (including NIO channels), socket/TLS (`java.net.SocketAddress`, `javax.net`), Git/JGit, HTTP, subprocess and Koog APIs, plus JUnit `@TempDir` fixtures. Kotlin I/O facades are denied by default, except the reviewed Closeable, Constants, Exceptions and encoding helpers. In-memory `Closeable.use` and JDK value types such as `URI` and `ByteBuffer` are allowed. The conservative NIO channel and InetAddress restrictions also reject in-memory channel adapters and pure address construction. JVM object types in field, method, array and constant-pool descriptors are checked by the unit-tier guard even without a separate class reference; production and preparation guards retain their original class-reference semantics. A descriptor-only `Path` is permitted as a value in a contract, but explicit `Path` class references and calls remain restricted. Generic type arguments appearing only in the optional JVM Signature attribute (for example `List<Path>`) are not checked. It scans the test class, Kotlin-style `$Companion`, lambda and `$DefaultImpls` helpers, locally compiled base types, and enclosing fixtures of JUnit `@Nested` test classes, including outer `@BeforeEach` methods and `@TempDir` fields even when the outer class has no tests of its own. Descendant lookup and purity results are cached per module to avoid rescanning the same compiled helper graph for every method. This intentionally conservative static boundary can flag an unused I/O helper in a mixed class; separate pure and integration suites if that happens. It allows in-memory fakes, but does **not** prove freedom from all side effects: reflective calls, third-party helpers outside the compiled test classes, dynamic code loading and runtime delegates can evade inspection. Review remains required. Run `python3 -m unittest discover -s tests -p 'test_test_tiers.py' -v` for independent compiled positive and negative fixtures; these do not download JUnit or run a model. The separately compiled Kotlin fixtures use the ordinary project compiler defaults (including indy lambdas); the Java fixtures with Kotlin-style class names additionally exercise legacy class-based lambdas and isolated DefaultImpls scanning.

## Trusted C3 compile-input guard (#140)

`checkArchitecture` validates compiled production Kotlin and Java sources,
including extra and generated roots. Production Java is denied until a separate
C3 Java policy has been reviewed. The effective Kotlin compiler options and
source-set language settings are checked for compiler-wide C3 opt-ins;
`@argfile` entries are denied to prevent flags being read from untracked files.
In production, `@Suppress` accepts only literal, unescaped `UNCHECKED_CAST`
and `UNUSED_PARAMETER` diagnostics; other strings, concatenation, constants,
aliases and unrecognized suppression syntax fail closed even if the trusted
marker is never mentioned. The guard also refuses `SuppressWarnings`, which
could name an alias of the Kotlin suppression annotation. Tests and test fixtures remain separate.

Additional production-bytecode controls reject non-Kotlin/Java JVM compiler
tasks, any compiler-plugin classpath outside the exact seven built-in KGP 2.4.21
Maven module coordinates/versions and option IDs, and `-Xplugin`/`-P` flags,
file-based dependencies on production configurations, and explicitly registered
`SourceSetOutput.output.dir` directories. Dynamic reflection/class-loading
entry points are conservatively denied in production Kotlin source; JNI and
other dynamic or generated execution paths still require separate review.

All six trusted C3 implementation/source files have exact SHA-256 content pins
in `TrustedRunningOptInPolicy`. Any change, even without a changed annotation,
fails `checkArchitecture` until reviewed and the pinned hash is explicitly
updated. `.github/CODEOWNERS` documents required owners; mandatory approvals
require repository branch-protection/ruleset configuration and must be
verified separately.

**Limit:** this verifier checks the compile configuration as seen by
`checkArchitecture`. Mutations made in later `KotlinCompile.doFirst`
actions or dynamically executed plugins are *not proven safe* by this gate.
The removed text-only Gradle scan was not a complete defence against such
mutations either. Review all additions to compiler task actions and convention
plugins before granting C3 authority. It is not a bytecode verifier:
transformations after `checkArchitecture`, custom non-compiler bytecode
generators, reflection through undiscovered indirection, JNI, and other
non-Gradle bytecode paths remain outside the guarantee.
A suppression annotation renamed
through a typealias supplied by a precompiled external dependency (and not
spelled as `Suppress`/`SuppressWarnings` in scanned source) is outside this
text-based guard; all dependency additions require independent security
review. The KGP built-in compiler plugin artifacts are checked by resolved
cache origin and module coordinates, not by artifact SHA-256; a compromised
artifact cache remains outside this guard's guarantee. The guard does not authorize effects, resume, model execution or C3
publication.

## Commands, CI and non-dropping guarantee

~~~sh
./gradlew check                      # runs checkTestTiers once
./gradlew checkArchitecture unitTest
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

CI has six independent jobs with explicit dependencies: architecture/tier-validation/pure-unit with cold/reused Gradle 9.8.2 configuration-cache checks; production-only `installDist` compilation and artifact upload; a **separate clean checkout and isolated Python verifier** that checks compiled outputs and standard Gradle launchers and publishes the approved artifact; full JVM `test`, `integrationTest`, `e2eTest`, mandatory sandbox JUnit XML/no-skip assertion and published JUnit results (blocked until the verifier passes); **separately isolated installed CLI E2E** on a fresh VM (only after verification, with no preceding Gradle/Kotlin test execution, SHA-256 verification and `python3 -I`); and protocol conformance with the English-only documentation guard. Since merged [PR #71](https://github.com/YuriiSokolenko/recursive-coder/pull/71), these jobs use GitHub-hosted `ubuntu-24.04` VMs with JDK 21, Gradle 9.8.2 where needed, Python 3.12 and Node.js 22 as required. Bubblewrap and the restricted `bwrap-userns-restrict` AppArmor profile are installed independently in both the JVM job and the isolated CLI E2E job before their real isolation probes, without disabling system-wide AppArmor or sharing the host network. Fork PRs run on disposable GitHub VMs, not N150. Tests need neither live inference nor credentials. The [CI migration run](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/43120760211) passed the three original jobs on 2026-11-11, including mandatory sandbox E2E (0 skips). Sandbox E2E may be skipped on unsupported local hosts, but **must run and pass on Linux CI**.

## Mandatory preparation host conformance (#119)

Run `./gradlew --no-build-cache clean checkPreparationHostConformance` on a supported Linux host with the restricted bubblewrap profile. The task requires every concrete production preparation host and each mandatory fake to execute all shared contract methods exactly once in both `test` and `integrationTest`; missing, excluded, skipped or unregistered suites fail. The architecture job independently executes the negative bytecode/report fixtures. See [preparation stages](PREPARATION_STAGES.md) for the adapter authoring procedure and the explicit Linux pending-refusal profile.


### Production JVM bytecode and package verification (#140)

The CI **Independent production distribution verifier** job runs
`tools/check_production_bytecode.py` in a fresh checkout after the separate
production build has published its `installDist` and compiled classes as a strictly
extracted artifact, **before** any JVM test job is allowed to run. It reuses the JVM class-file parser behind
`checkTestTiers` to inspect constant-pool class, method and MethodHandle
references from **production** classes. A fail-closed JDK package allowlist
rejects unreviewed JDK API owners, including `URLClassLoader`, `ServiceLoader`,
`java.beans`, `java.lang.foreign`, `java.io.ObjectInputStream`,
`java.util.logging`, `javax.script`, `javax.management`, `Unsafe` and module
reconfiguration. Loader method names are blocked independent of the static
owner, and Kotlin callable-reflection methods are denied. This also rejects
reflection APIs including
Kotlin `::class.java.methods`, `Class.getDeclaredMethods`,
`ClassLoader.loadClass` and `Thread.getContextClassLoader`.
The class-file `BootstrapMethods` attribute is also checked: only compiler-
generated `LambdaMetafactory`, `StringConcatFactory` and JDK 21
`SwitchBootstraps.typeSwitch` bootstrap calls are allowed. Any other
invokedynamic bootstrap (including `ObjectMethods.bootstrap`) is rejected. The Kotlin
`kotlin.jvm.internal.Reflection` class-literal helper remains allowed.

Packaging is checked against compiler output by exact bytes: extra or
replaced `*.class` entries in production JARs are rejected. Production JAR
manifests must not add a `Class-Path` or agent entry (including CR-only line
endings). The **entire installed tree** is restricted to two launchers and flat
`lib/*.jar` files; extra files, symlinks and directories fail closed. Both
launchers are byte-compared, apart from validated CLASSPATH order, to trusted
`startScripts` outputs generated by Gradle 9.8.2 from a minimal application
project **in the separate verification job**. All shipped JARs must match
compiler-produced module JARs or the exact resolved application
`:app:cli:runtimeClasspath` SHA-256 inventory. This manifest constrains
what is packaged to resolved runtime dependencies, but does not independently
authenticate the external dependency cache; dependency SHA-256 pinning is tracked
in #142. Production
`*.class` and `*.jar` resource inputs are rejected separately by
`checkArchitecture` before compilation.

Both the root and included build-logic settings permit only the reviewed
HTTPS Maven Central and Plugin Portal repositories. Project repositories
are forbidden through `RepositoriesMode.FAIL_ON_PROJECT_REPOS`. Local
`mavenLocal`, `flatDir`, `file:` repositories, `artifactUrls` fallback
repositories, and declared legacy project/settings `buildscript` repositories
or classpath dependencies are refused. Ordinary Gradle `apply(from = ...)`
and `apply { from(...) }` script-plugin syntax is rejected by additional
textual guards in project build files **and both root/included-build settings
scripts**; negative fixtures cover the settings cases. Dynamically generated
or indirectly applied scripts are outside this lexical check. Effective
`project.buildscript.repositories` is also checked, regardless of the DSL
spelling. Arbitrary build logic, user-supplied
Gradle init scripts and execution-time code mutations are **not sandboxed**
by these checks.

Pinned external dependency checksums in `gradle/verification-metadata.xml`
are **not yet configured** (#142). The Gradle-emitted runtimeClasspath inventory
is a build observation, not independently authenticated provenance: malicious
build logic could forge it. Dynamic calls hidden in precompiled dependencies,
unsupported transformations after verification, and build-time Gradle/init-script
execution remain outside the guard. Legitimate Git/sandbox adapters still call
`ProcessBuilder.start`, whose authority is enforced by runtime policy.
The standalone verifier uses `python3 -I` and checks its clean `tools/` and
`tests/` checkout before inspecting the untrusted artifact. Environment
mutations made in the build job do not cross this job boundary. Full JVM tests
start only after a successful independent verification. After verification, the
clean job publishes the approved archive and exports its SHA-256 as a job output.
The JVM job still recompiles and runs Gradle/JUnit suites separately; it
does not provide the CLI E2E execution environment. A distinct `cli-e2e`
GitHub-hosted job starts from a fresh credential-free checkout after the
verifier succeeds. It downloads the approved archive, compares its SHA-256
with the independent job output, restores only allowed installed files, then
installs and probes the restricted bubblewrap/AppArmor sandbox, and runs
**both installed CLI Python suites with `python3 -I`** (no Gradle or
Kotlin test bytecode previously executed on that runner). This establishes a
clean-job execution boundary for the verified CLI files and limits Python
startup/module injection from the checkout and the JVM-test environment. It
does **not** prove that independently compiled JUnit classes match the
approved distribution, nor does it make the test checkout, host runner or
system tooling cryptographically authenticated. This is still a CI boundary,
not a sandbox, mandatory CODEOWNERS approval, or authenticated base commit
policy; run the final review independently before merging.

Six trusted C3 source paths have `.gitattributes` `eol=lf` entries
to make raw SHA-256 stable across Windows, Linux and macOS. The compiler
classpath pins exactly KGP **2.4.21**; updating Kotlin requires updating
the reviewed compiler-artifact baseline and re-running the complete guard.
Compiler option `-P` is rejected intentionally and broadly.

CODEOWNERS covers C3 files, build logic, Gradle settings and wrappers,
workflow definitions, compiler fixtures, architecture verification scripts
and its own policy. **CODEOWNERS is advisory in this solo repository**:
`dev` has no branch protection or rulesets, and PR authors cannot
approve their own changes. Independent security review and content pins
remain required before manually merging.
