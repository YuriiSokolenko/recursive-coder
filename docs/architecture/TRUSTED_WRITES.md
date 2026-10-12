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
SecureDirectoryStream must be available. All path components are traversed
descriptor-relative with NOFOLLOW_LINKS; missing prerequisites fail closed.
Parent directories must already exist. Each batch performs a complete
preflight before changing files; an I/O failure during mutation poisons the
session rather than claiming transactional rollback.

## Validation and evidence

Each attempted write or check receives a strictly increasing host-side tool ID.
The journal callback records only tool name, receipt ID and denial code. A
successful write updates the accepted path/digest manifest and invalidates
all earlier check receipts.

The built-in git_diff_check executes a fixed, bounded Git whitespace check
plus scrutiny of new files. Additional checks are selected by immutable
host-side IDs, never shell text from the model. They run under a dedicated
bubblewrap process with no network, a read-only project mount, private scratch,
no inherited environment, bounded output and deadline. If bubblewrap or kernel
isolation is unavailable, the check fails; it never runs unsandboxed instead.
Tests can conditionally exercise this backend on hosts where namespaces work.

A passed check returns an HMAC-SHA256 receipt bound to the session, policy,
baseline HEAD, check ID and accepted manifest. The signing key lives only
inside the active runtime. The finish method independently checks all changes
against accepted paths and actual bytes, checks all approved artifact
digests, refuses staged changes, rename paths, untracked changes outside the
manifest, changed HEAD/configuration and missing/stale/forged receipts. All
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

Run ./gradlew test; negative tests include role boundaries, immutable
tests/specs, schema rejection, symlink traversal, Git staging/renames, external
untracked changes, stale/forged receipts and separate sandboxed checks.
