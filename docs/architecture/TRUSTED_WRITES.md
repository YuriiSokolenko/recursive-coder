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
  empty private tmpfs root (including writable `/tmp`) and writable host-
  backed `/scratch` (`HOME=/scratch`), no network, and only the
  host-mounted `/usr`, `/bin`, `/lib` and `/lib64` trees when present.
  Host `/etc` is not mounted.
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

**Not implemented.** This is a follow-up design contract, not a current
`TrustedCheck` option, new CLI flag, or expanded stage-tool permission.
Today `runIsolated()` invokes a host-allowlisted binary and fixed arguments
inside bubblewrap (`--unshare-all --tmpfs /`, no inherited environment).
It mounts the real checkout read-only at `/workspace`, an external
per-check writable `/scratch` with `HOME=/scratch`, and existing `/usr`,
`/bin`, `/lib` and `/lib64` trees read-only; the private tmpfs root
(including `/tmp`) is *also writable*. No host `/etc` is mounted. There is
no dependency-cache provisioning or pinned build-tool distribution.
`git_diff_check` executes separately. Current small-command E2E only checks
isolation, and ordinary Gradle-based CI is **not** an independent stage check.

### Proposed host-provisioned execution

1. **Freeze an independently materialized source snapshot.** With the
   trusted host's workspace lease held, call the existing `verifyState()`
   and record the accepted manifest. Materialize a **separate complete
   source-tree snapshot** from the pinned Git HEAD tree plus individually
   verified accepted postimages/deletions; check all file contents, modes
   and path spellings against that state. Use independently allocated file
   bytes (no hardlinks to the live workspace). Reject unsupported symlinks,
   special files or Git tree entries rather than silently following them.
   Re-run `verifyState()` against the *real* checkout and require the same
   manifest **after** creating the snapshot, before launching the check.
   Freeze the snapshot read-only for the entire check. Do **not** use the
   mutable live checkout as an overlay lower layer: a lease is not proof
   against hostile or accidental same-UID writes.
2. **Give each check a fresh, size-bounded writable view.** Overlay the
   immutable snapshot read-only with **private upper/work directories**;
   present the resulting filesystem at sandbox `/workspace`. Build outputs,
   lockfiles, generated files and ephemeral edits remain within the
   disposable layer. The actual checkout, external lock/approval files and
   Git index must never be bound writable. No files from the check view
   can be copied back to host source. Protect `/workspace/.git` by
   **omitting Git metadata entirely** from the snapshot/view; Git-dependent
   build plugins must be rejected as unsupported or supplied narrowly
   scoped, host-authenticated read-only metadata by a separately reviewed
   design. Tests must not mistake a synthetic Git repository for the real
   HEAD. This boundary **does not promise frozen tests are immutable within
   the disposable view**: a test process can edit its own copy, so test
   integrity still requires external review (#152).
3. **Constrain all writable storage and processes.** Before launch, the
   host applies enforced byte/inode quotas or a size-limited dedicated
   filesystem for each upper/work pair, cache writable view, `/scratch`,
   and other private writable paths such as `/tmp`. Use hard limits,
   accounting all writes (including tmpfs), rather than output-size or
   time limits alone. Configure independently managed **cgroup v2**
   memory and process-count limits, plus an appropriate CPU budget; bubblewrap
   does *not* establish cgroup limits itself. The host must supervise
   child processes and teardown, refuse unavailable quota/cgroup controls,
   and distinguish exceeded resource limits from a normal failed test.
   Failure, timeout, cancellation or exhaustion must not saturate shared
   host disk/RAM or leave orphan processes or writable layers.
4. **Provision deterministic offline dependency caches.** A trusted host
   resolves and verifies all dependency artifacts/plugins *before* test
   admission and supplies an immutable, content-digest-checked, read-only
   lower cache. Individual tools may use per-check **quota-limited
   copy-on-write** cache views for lockfiles and metadata; never write
   into the shared lower cache. Select explicit offline modes, so missing
   dependencies fail promptly instead of causing network retries:
   - **Gradle:** pinned installed distribution, `--offline --no-daemon`,
     `GRADLE_RO_DEP_CACHE` for the read-only dependency cache and fresh
     writable `GRADLE_USER_HOME` for local metadata, locks and plugin
     caches. The host validates all plugins, wrapper/distribution dependencies
     and buildscript inputs are already available offline.
   - **Maven:** `-o`, a fresh `-Dmaven.repo.local=...` location
     backed by the pinned read-only artifact cache plus a disposable
     writable layer. All build plugins and their transitive dependencies
     must be present before execution.
   - **npm:** `npm ci --offline` with the host-selected cache directory
     (`--cache` or `npm_config_cache`) over a pinned read-only cache
     and writable per-check layer. Lockfile/package content is validated;
     install scripts cannot access a network (network namespace still off).
   All tool configuration and environment variables are **host-selected**;
   the model cannot choose dependency sources or change offline policy.
5. **Pin the toolchain and required system data.** The host verifies a
   versioned SHA-256 content manifest for executables, runtimes, launchers,
   libraries, plugins and any required non-library files. Provide
   **minimal, explicitly allowlisted**, verified read-only files normally
   found in `/etc` (for example passwd/group data, CA trust, timezone,
   name-service configuration) *only when needed*; do not mount host
   `/etc` wholesale. Offline JVM/Node/Gradle E2E must demonstrate that
   the selected toolchain works with this allowlist. Never fall back to
   unpinned host `PATH`, shell commands or network access.
6. **Bind and invalidate check evidence.** Extend the future host-authenticated
   check policy and receipt identity with the complete execution profile:
   check ID/argv, source snapshot and accepted manifest, toolchain digest,
   dependency-cache digest, filesystem/mount plan and resource policy.
   A change in **either toolchain or cache digest**, or any of those
   security inputs, must revoke all previously issued check receipts,
   including receipts produced before a stage authority update. Fail closed
   rather than allowing an old receipt to satisfy a new policy; the host
   must require a new admitted check against the current inputs.
   Trusted writes continue to invalidate earlier check receipts as today.
7. **Keep the original Git fence and discard outputs.** The current
   `runCheck()` still calls `verifyState()` on the **real** workspace
   before launching and after the check, requiring
   `verifyState() == preCheckManifest` unchanged. `finish()` must still
   independently verify approved/frozen artifacts, actual postimages,
   Git HEAD/config/index, current receipts and stage authority.
   Build/test output is an observation, **not** an approved workspace
   mutation. Destroy all disposable upper/work/cache layers on every exit
   path, without copying files back. A failure to verify or safely clean
   the sandbox denies success; it must never trigger unsandboxed execution.

### Required future acceptance cases

- An actual **offline Gradle/JUnit** check writes `build/` outputs in
  the disposable view, uses the pinned toolchain/cache, and leaves the host
  checkout, Git metadata, immutable artifacts and shared dependency cache
  byte-identical. Missing dependencies, a changed pinned toolchain,
  disallowed system-data requirements and overlay provisioning failures
  deny admission rather than falling back to the host.
- A sandbox attempt exceeding its **upper-layer byte/inode quota** is
  stopped with an isolation/resource failure; host checkout, shared
  cache, host filesystem free-space safety margin and neighboring check
  runs remain unaffected. Also test cache-view/`/scratch`/`/tmp` limits
  and process/memory exhaustion, with no orphan processes.
- An **external actor changes the live host checkout while the check runs**,
  despite the host lease: the check continues to see its *unchanged
  independently materialized snapshot*, and the unchanged post-check
  `verifyState()` fence detects real-workspace drift, denying the receipt.
  This scenario is **not** a test of live-checkout overlay lower-layer
  behavior, because live checkout is never the lower layer.
- Network remains unavailable; timeout and output quotas still apply;
  `.git` is hidden, E2E verifies fail-closed Git-dependent tasks;
  changing **cache or toolchain digest after one passed check**
  invalidates its outstanding receipt and requires a new check. CI must
  fail, not skip, the real-sandbox acceptance cases.

Provisioning/isolation failures must be distinguishable from genuine test
assertion failures. The currently implemented
`CHECK_FAILED_OR_SANDBOX_UNAVAILABLE` deliberately conflates these; it
would need revision as part of a future runtime implementation, **not in
this documentation-only issue**.

This design makes build-tool checks *possible*, not automatically trustworthy:
relevant assertions, protections against a Developer influencing test outcomes
(#152), and proof of red-to-green behavior (#153) remain separate. Existing
same-UID concurrency limits and host authority checks still apply.

**References:** [Gradle dependency caching](https://docs.gradle.org/current/userguide/dependency_caching.html),
[Maven repositories](https://maven.apache.org/guides/introduction/introduction-to-repositories.html),
[npm offline/cache options](https://docs.npmjs.com/using-npm/config/).
