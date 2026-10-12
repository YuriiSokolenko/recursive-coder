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
bytes, content size and multi-file collisions before any write. A preflight
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
output returns CHECK_OUTPUT_LIMIT rather than CHECK_TIMEOUT. The GitHub-hosted
Linux CI enables user namespaces on its disposable runner, probes bubblewrap,
and requires the real sandboxed test to execute. On independently managed
Linux systems where bubblewrap or namespaces are missing, check failure is
non-success; the runtime never falls back to unsandboxed execution.

For **Developer**, the built-in git_diff_check is necessary but **never**
sufficient to finish: the host must configure and require at least one
independently executed, sandboxed test. This is enforced even when the host
uses the default check policy (git_diff_check only), in which case finish
returns TEST_CHECK_REQUIRED. SDD/ATDD/TDD may finish with only Git validation.

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
- The current bootstrap SessionController remains read-only; wiring write
  sessions into the Koog agent graph is future work.

Run ./gradlew test on Linux; negative tests include role boundaries,
immutable tests/specs, schema rejection, a tracked symlink directory that
reaches the NOFOLLOW guard, ignored Git paths, case-insensitive aliases,
NUL input, reversible edits and no-ops, Git staging/renames, external
untracked changes, stale/forged receipts and isolated external checks.
