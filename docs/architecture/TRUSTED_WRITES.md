# Trusted write and validation boundary (issue #8)

The standalone trusted API is implemented by TrustedWriteRuntime and
TrustedWriteToolSchema. This is a host-side security boundary, **not** an
extension of the first-session fake-model CLI: the bootstrap CLI remains
read-only. Production Koog role orchestration and durable recovery are separate
milestones.

## Authority and operations

Only the trusted host may construct TrustedWritePolicy, including its role,
baseline HEAD, approved artifact digests, independently reviewed change request,
check command definitions and required checks. The model-facing schema accepts
only a batch of up to eight create, edit or delete operations. It contains
no role, policy, shell command, executable, network authority or receipt fields.
Edit and delete require an exact SHA-256 precondition.

| Role | Writable tree | Restrictions |
| --- | --- | --- |
| SDD | specs/ | Approved specs require external review |
| ATDD | tests/acceptance/ | Approved acceptance artifacts require external review |
| TDD | src/test/, tests/unit/ | Approved tests require external review |
| Developer | src/main/ | Test/spec-like paths and control files are denied |
| Reviewer | None | Read-only |

All roles are additionally denied Git internals, workflow/configuration,
credentials, protected file types and submodules. There is no role escalation
via natural-language instructions, model results or a claimed tool ID. Approved
artifacts are pinned by a trusted content digest at admission. Only a
host-provided ReviewedChange scoped to approved paths, matching the active
role, with distinct requester/reviewer identities may authorize their
modification. The review object is not accepted in the model tool schema.

A workspace must be an ordinary local Git tree on the declared named branch at
the declared HEAD, with an initially clean index/worktree and exclusive lease.
**Trusted writing is Linux-only in this milestone.** macOS's standard JDK 21
filesystem provider lacks SecureDirectoryStream; admission explicitly returns
SANDBOX_UNAVAILABLE. There is no unsafe macOS fallback. Linux hosts also
must provide descriptor-based SecureDirectoryStream access. The new trusted
writing filesystem tests are Linux-only; schema validation tests are
platform-independent. Read-only CLI support is unchanged.

All path components are traversed descriptor-relative with NOFOLLOW_LINKS;
parent directories must already exist. Preflight checks optimistic hashes,
ignored Git paths, case-folded path aliases (including existing names), NUL
bytes, content size and multi-file collisions before any write. Git trees
containing paths that differ only by case or Unicode normalization are rejected
at admission with PATH_CASE_COLLISION, even on case-sensitive Linux. A preflight
denial does **not** poison the session. An I/O failure after mutation begins
does poison it, rather than claiming transactional rollback. Protected Git
metadata and policy files are denied, but ordinary Kotlin package directories
named build, gradle or test are permitted inside src/main/.

## Validation and evidence

Each attempted write or check receives a strictly increasing host-side tool ID.
The journal callback records only tool name, receipt ID and denial code. A
successful write updates the **effective** path/digest manifest and invalidates
all earlier check receipts, even for no-op edits. The runtime preserves
first-mutation HEAD digests internally: an edit that restores original HEAD
content disappears from the effective diff without poisoning the session.
A fully reverted session has no accepted diff and cannot finish successfully.

The built-in git_diff_check executes a fixed, bounded Git whitespace check
plus scrutiny of new files. Additional checks are selected by immutable
host-side IDs, never shell text from the model. They run under a dedicated
bubblewrap process with no network, a read-only project mount, private scratch,
no inherited environment, bounded output and deadline. If bubblewrap or kernel
isolation is unavailable, the check fails; it never runs unsandboxed instead.
The sandboxed check test also verifies that the checkout is read-only, the
scratch directory is writable, external network send is denied, and large
output returns CHECK_OUTPUT_LIMIT rather than CHECK_TIMEOUT. Since merged [PR #71](https://github.com/YuriiSokolenko/recursive-coder/pull/71), GitHub-hosted ephemeral Ubuntu 24.04 VMs install bubblewrap and its restricted `bwrap-userns-restrict` AppArmor profile and probe real namespace isolation,
and **fails** when the real sandboxed test cannot run. The test treats missing
bubblewrap or namespaces as a failure when CI=true (or GITHUB_ACTIONS=true),
but may skip on local Linux systems without these capabilities. The CI job
also independently checks the JUnit XML to require exactly one executed,
non-skipped successful sandbox integration case, and publishes the XML for
review. On independently managed Linux systems where bubblewrap or
namespaces are missing, check failure is non-success; the runtime never
falls back to unsandboxed execution.

For **Developer**, the built-in git_diff_check is necessary but **never**
sufficient to finish: the trusted host must configure and require at least
one additional sandboxed check. A Developer policy that does not require both
git_diff_check and an additional allowlisted check is rejected by open()
with TEST_CHECK_REQUIRED, before a workspace lease or any write. The default
required-check policy (git_diff_check only) is therefore intentionally
invalid for Developer. SDD/ATDD/TDD may finish with only Git validation.

**Important trust boundary:** the runtime guarantees that a host-allowlisted
command ran in isolation and exited successfully; it does **not** determine
whether the command tests relevant behavior. In particular, a host-configured
command such as /usr/bin/true satisfies the structural requirement while
providing no evidence of software correctness. The trusted caller must select
meaningful checks with assertions, and protect their definitions and inputs
from model modification.

A passed check returns an HMAC-SHA256 receipt bound to the session, policy,
baseline HEAD, check ID and accepted manifest. The signing key lives only
inside the active runtime. The finish method independently checks all changes
against accepted paths and actual bytes, checks all approved artifact
digests, refuses staged changes, rename paths, untracked changes outside the
manifest, changed HEAD/configuration/index flags and missing/stale/forged receipts. All
required checks must have run **after** the most recent accepted write.
Neither a model-declared success nor a plausible JSON receipt is sufficient.

## Deliberate limits

- No model-exposed arbitrary shell, Git staging, commits, pushes or policy edits.
- No implicit directory creation, binary/oversize mutation or per-batch rollback.
- No remote approval workflow in the trusted runtime; the caller must establish
  authorization for the externally reviewed change request.
- No claim that this prevents hostile same-UID processes from racing concurrent
  filesystem changes; production deployment must isolate the host and guard
  the workspace against concurrent non-agent writers.
- The current CLI `RunSessionUseCase`/`SessionController` path remains read-only. Issue #74 provides a separately admitted host-only stage tool port over this runtime (see `STAGE_TOOL_AUTHORITY.md`); Koog still has no model-facing write tools, and automatic graph execution remains future work (#75–#79).

Run `./gradlew test` on Linux; negative tests include role boundaries,
immutable tests/specs, schema rejection, a tracked symlink directory that
reaches the NOFOLLOW guard, ignored Git paths, case-insensitive aliases,
NUL input, reversible edits and no-ops, Git staging/renames, external
untracked changes, stale/forged receipts and isolated external checks.

## What checks do and do not prove

The runtime proves that the *selected* checks ran in its sandbox against the
exact accepted bytes. It does not prove that those checks are meaningful. The
following limits are known and tracked:

- **Path ownership is not test integrity** ([#152](https://github.com/YuriiSokolenko/recursive-coder/issues/152)).
  Main resources are on the test classpath of common JVM builds, so a Developer
  could change test *outcomes* without editing a frozen test (for example
  `junit.platform.execution.dryRun.enabled=true`). `ToolAuthorizationPolicy`
  therefore denies Developer writes to known test-framework configuration files
  (`junit-platform.properties`, `testng.xml`, `kotest.properties`, `pytest.ini`,
  `tox.ini`, `setup.cfg`, `pyproject.toml`, `sitecustomize.py`, `phpunit.xml`,
  `jest.config.*`, `vitest.config.*`, `.mocharc*`, `karma.conf.*`, ...) and to
  every `META-INF/services/` registration, and protects `build.gradle`,
  `settings.gradle`, `pom.xml` and `package.json` for every role. This denylist
  is defense in depth; any code under `src/main` can still observe that it runs
  under test. Independent review remains necessary.
- **No red-to-green evidence** ([#153](https://github.com/YuriiSokolenko/recursive-coder/issues/153)).
  The installed workflow gates SDD, ATDD, TDD and Developer with the same
  required check set, so tests produced before the implementation must already
  pass. Preparation-stage checks are validators of the produced artifact; they
  are not evidence that a test failed on the pre-implementation baseline, and a
  passing Developer check does not prove that the implementation caused it.
  Nothing binds the operator's check to the ATDD/TDD artifact paths. Per-role
  check sets and an authenticated failing baseline receipt (distinguishable
  from sandbox unavailability) are a planned design.
- **The sandbox cannot run most build tools** ([#154](https://github.com/YuriiSokolenko/recursive-coder/issues/154)).
  External checks see the checkout read-only at `/workspace`, an initially
  empty private `/scratch` (`HOME=/scratch`), no network, and only the
  host-mounted `/usr`, `/bin`, `/lib` and `/lib64` trees when present.
  There is no provisioned dependency cache or pinned build toolchain.
  Gradle, Maven, npm and most test runners need to write outputs under the
  checkout and access offline dependencies; the current checks are limited
  to suitable read-only commands (installed E2E uses `grep`). A
  [disposable writable check sandbox](#proposed-disposable-writable-check-sandbox-design-only-154)
  is designed below, **not implemented**.
- **Receipt HMACs are in-process integrity checks.** The runtime and the Linux
  workflow host keep their HMAC keys in the same JVM. The MACs detect forged or
  stale values passed between components; they are not a trust boundary against
  code running in that process.


## Proposed disposable writable check sandbox (design only, #154)

**Not implemented.** This is a follow-up security/design contract, not an
available `TrustedCheck` option, a new CLI flag, or a relaxation of any
stage-tool permission. Today `runIsolated()` runs an explicitly host-allowlisted
executable with arguments, without a shell or inherited environment, inside
bubblewrap (`--unshare-all`, `--tmpfs /`). It binds the real checkout
**read-only** to `/workspace`, temporary host scratch read-write to
`/scratch`, sets `HOME=/scratch` and `PATH=/usr/bin:/bin`, and binds
available system `/usr`, `/bin`, `/lib`, and `/lib64` read-only. The
built-in `git_diff_check` is separate. A successful tiny sandbox E2E proves
isolation behavior, **not** that a Gradle/Maven/npm test suite can run there.
The ordinary Gradle-based project CI is not an independent stage check.

### Proposed host-provisioned execution

1. **Freeze inputs before launch.** With the exclusive workspace lease held,
   call the existing `verifyState()` and capture its accepted manifest. Only
   the trusted host resolves the exact checkout, approved/frozen artifacts,
   check ID, allowed executable/arguments, timeout and output quota. A model
   cannot request a different sandbox, cache, toolchain or command.
2. **Give each check a fresh disposable writable view.** Construct a
   copy-on-write checkout view (read-only lower snapshot, private upper/work
   directories) at sandbox `/workspace`. Build/test output, temporary files,
   package-manager lockfiles and generated sources stay in that upper layer.
   Neither the lower snapshot nor the real checkout, Git index, external
   approval files or lock directory may be writable from the sandbox.
   Do **not** substitute hardlinked writable copies or bind the real checkout
   read-write. Preserve the frozen test/spec and policy inputs; protect them
   from writes inside the test view where practical. Because arbitrary test
   code can still manipulate its own environment, this is isolation rather
   than proof of test integrity (see #152). If safe unprivileged copy-on-write
   mounts are unavailable, deny the check; no host-execution fallback.
3. **Provision dependencies outside the test.** A trusted preparation step
   obtains and verifies required dependencies before admission, records an
   immutable content manifest/digest, and exposes the cache read-only inside
   the namespace. Enforce fully offline resolution: no network, no
   implicit downloading, no mutable shared cache. For tools requiring cache
   locks or metadata writes, use a separate per-check disposable copy-on-write
   cache view, never writes to the shared lower cache. Missing artifacts or
   unexpected resolution attempts fail closed rather than fetching.
4. **Pin the toolchain and invocation.** The host provisions a versioned,
   SHA-256-verified executable/runtime manifest (including transitive
   launchers, runtimes and required libraries/plugins), and mounts that
   toolchain read-only. The host chooses an absolute executable and fixed
   arguments; no agent-provided shell, `PATH` lookup, network or unpinned
   host-tool fallback. Include the complete execution environment identity
   (toolchain, cache content digest, check definition and sandbox profile)
   in a future check-policy/receipt binding, in addition to the current
   accepted-manifest and HEAD binding.
5. **Keep the actual Git fence and discard outputs.** The existing
   `runCheck()` flow must still call `verifyState()` *before* launching the
   check and *after* it exits, with the unchanged
   `verifyState() == preCheckManifest` requirement. Final `finish()`
   revalidates the real checkout, immutable artifacts, current receipts,
   HEAD/config/index and approved bytes exactly as today. Check outputs are
   bounded observations, **not** new authorized writes; destroy the private
   upper layers after every attempt, including timeout, cancellation and
   failure. Never copy generated artifacts or modified tests back to the
   actual checkout. A subsequent trusted write must still invalidate
   prior check receipts.

The initial implementation should support at least one genuinely offline
Gradle/JUnit check that writes into its disposable `build/` directory without
changing the host checkout. Real sandbox E2E must demonstrate read-only host
source, read-only shared cache, pinned toolchain rejection on mismatch, absent
network, bounded runtime/output, isolation of generated files, missing-cache
and unavailable-overlay denial, and detection of external checkout drift by
the unchanged post-check `verifyState()` fence. CI must fail rather than
silently skip the required real-sandbox case. Test-process failure must be
distinguishable from provisioning/isolation failure; current
`CHECK_FAILED_OR_SANDBOX_UNAVAILABLE` conflates them and should be refined
when implementing, **without changing it in this documentation-only issue**.

This would make meaningful build-tool checks *possible*, not automatically
trustworthy: selecting relevant assertions and proving red-to-green behavior
remain independent concerns (#152 and #153). Existing same-UID concurrency
limits and host authority checks still apply.
