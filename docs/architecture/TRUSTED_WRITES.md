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
  Checks run with a read-only workspace bind, no network, an empty tmpfs `HOME`
  and only `/usr`, `/bin` and `/lib*`. Gradle, Maven, npm and most test runners
  write build output into the workspace and need dependency caches, so they do
  not work here; current fixtures use small read-only commands. A disposable
  writable overlay of the checkout plus a read-only, digest-pinned dependency
  cache and toolchain are a planned design; the post-check `verifyState()` fence
  must stay unchanged.
- **Receipt HMACs are in-process integrity checks.** The runtime and the Linux
  workflow host keep their HMAC keys in the same JVM. The MACs detect forged or
  stale values passed between components; they are not a trust boundary against
  code running in that process.
