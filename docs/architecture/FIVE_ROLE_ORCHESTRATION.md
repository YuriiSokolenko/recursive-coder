# Host-owned five-role orchestration (issue #79)

**Status: partial implementation.** `RunFiveRoleWorkflowUseCase` provides a
bounded, in-process coordinator for `SDD → ATDD → TDD → Developer → Reviewer`.
An in-memory fake-host integration test executes all five existing role use
cases. Additional pure scripted coordinator tests check fail-closed responses.
**Neither fake host authenticates actual write/check receipts or fences Git
mutations.** These tests do not prove the eventual production host's guarantees.
This is **not** an installed writable CLI or a completed issue #79.

## Execution and authority

The coordinator never executes a model tool directly and never grants physical
workspace authority. It delegates role proposals to the already existing
`RunPreparationStageUseCase`, `RunDeveloperStageUseCase`, and
`RunReviewerStageUseCase`. Those use cases retain their independent
`PreparationStageHost`, `StageToolSessionOpener`, and `ReviewerStageHost`
permissions and checks.

The host constructs the `FiveRoleWorkflowPlan` (output IDs/paths, source file
targets, immutable instructions, model and budgets). A model response is not a
trusted plan, artifact approval, tool receipt, or Reviewer decision.

At each stage boundary the coordinator:

1. Reads an authoritative host graph/accepted-ID snapshot and rejects drift.
2. Requires the stage to be CREATED or READY; the corresponding use case
   performs its own policy admission and atomic stage lease.
3. Accepts only the concrete use case's committed ACCEPTED result; a denial,
   failure, human-approval requirement or uncertain CAS stops execution.
4. Re-reads the authoritative snapshot to confirm the committed stage result.
   On any early stop, a reported graph is also re-read; `Stopped.authoritative`
   is only populated after matching live host state. Unknown stop state requires
   `ReconciliationRequired` and must never be used for resume.
5. Calls `FiveRoleWorkflowHost.advance` before admitting the next role and
   verifies the returned stage graph against a second live host read.

`advance` is a **trusted host CAS boundary**, not a request to automatically
commit any dirty workspace. A production implementation must verify the
trusted write/check receipts, current accepted/frozen artifact digests and
source postimages, then reconcile that exact accepted change set into a clean
Git baseline suitable for the next `TrustedWriteRuntime.open`. It must fence
concurrent workspace mutations throughout the read/commit interval. A policy
`Permit` does not substitute for this evidence.

Developer failures retain the trusted stage's `workspaceMayBeDirty` signal:
a `DeveloperStageOutcome.Failed` with this flag **never** becomes a resumable
`Stopped`, even when the host graph snapshot looks valid. It becomes
`ReconciliationRequired(code, DEVELOP, workspaceMayBeDirty = true)`. The flag
is also forwarded from Developer `ReconciliationRequired` outcomes; `false`
only means no positive dirty-workspace signal was supplied, **not** verified
cleanliness. Only a trusted host can inspect and reconcile physical changes.
A clean `Denied` that nevertheless changes the host snapshot is likewise
converted into `UNCONFIRMED_STOP_STATE`.

For stage attribution, an exception thrown *inside* `advance` is reported for
the finishing stage (the reconciliation operation is unconfirmed). Once
`advance` has returned, an invalid/unconfirmed next-stage snapshot is
reported for the **next** stage, which must not be admitted.

A Developer handoff includes its output manifest, changed-file pre/postimages,
write tool ID, checked evidence and usage. The host must independently verify
these against retained authenticated receipts before constructing the Reviewer
lease. The Reviewer has no write tools; its acceptance remains distinct from
the Developer role and is rechecked at `finishSuccess`.

`finishSuccess` must atomically validate all accepted frozen bytes, the
independent Reviewer acceptance attestation and legal
`RUNNING → SUCCEEDED` transition. The coordinator checks the returned graph
shape and reads it back from the host but cannot make a host's unverified
claims authoritative. Every configured Developer target is separately validated
against Developer write scope before starting any stage; this early check is not
a replacement for the trusted runtime's physical path/receipt enforcement.

The plan has **one overall deadline and cancellation token for all five
stages**, not a fresh allowance per stage. A slow early stage reduces the
remaining time available to later stages. The coordinator currently **stops**
on Reviewer rejection or NEEDS_HUMAN. It does not yet drive the separately
documented bounded `ReviewerRepairPlanner` retry loop; repair requires a
distinct host-verified operation and is not an implicit continuation.

## Linux Git baseline reconciliation — issue #79 next slice

The host-only `LinuxStageGitBaselineReconciler` in `:infra:workspace`
implements **physical clean-Git handoff**, not an authenticated workflow host.
It is opt-in and not selected by the installed CLI or Koin composition.

After a trusted stage CAS has independently accepted a proposal, the future
production host must revalidate its HMAC-backed write/check receipts, manifest
identity and approved output digest *before* invoking this reconciler. The
reconciler itself does not establish receipt authenticity, Reviewer approval,
or an authoritative accepted-ID set.

`StageGitReconciliationRequest` pins the old 40-hex Git HEAD, named branch,
role, every changed path and its expected SHA-256, plus unchanged frozen
artifact paths and digests. The reconciler rejects any other Git status,
modified index entry, symlinked path, unexpected ignored/untracked file,
role-forbidden path or changed frozen byte. It uses descriptor-relative,
no-follow file reads and the same external workspace lock file as
`TrustedWriteRuntime`.

**Git hooks are disabled for every invocation** through an overriding
`core.hooksPath=/dev/null` configuration. This includes
`reference-transaction` and `post-index-change`, which ordinary Git
plumbing can run even without invoking `git commit`. Only a conservative
allowlist of repository-local Git configuration keys is accepted;
`core.filemode=false`, filters, attribute overrides, aliases and hooks
configuration are rejected. For the *currently accepted stage writes*,
the Linux reconciler checks exact POSIX permissions corresponding to `100644`
or `100755` and requires new files to be `100644` and tracked writes to
preserve their HEAD mode **before** updating the branch reference. Existing
frozen inputs are different: Git only tracks the executable bit, so frozen
regular files at `0600` (umask 077) or `0664` (umask 002) are permitted
as long as their SHA-256 and no-follow file access are valid and Git status
remains clean. The same distinction applies to preflight and post-commit
verification. Mode-only changes, deletions, renames, pre-staged index changes
and unexpected ignored or untracked files are not supported and are denied.
Linux repositories copied from another OS may also be rejected if their Git
local configuration does not satisfy the strict Linux-only allowlist.

Under that lock it builds a private temporary index, hashes accepted bytes
without filters, creates a Git tree/commit using plumbing (no shell, Git
hooks or model-supplied commands), and performs an old-SHA `update-ref` CAS.
The real index is aligned with the new commit without replacing worktree
bytes. It reads back the new HEAD, clean Git status and frozen/accepted
digests before reporting `Committed`.

Before `update-ref` is attempted, validation failure is `Denied`. Once a
ref update is attempted, any error becomes `ReconciliationRequired` because
the branch or index may already have changed. **Never retry blindly**; reload
the host graph, workspace status and HEAD and reconcile under a new exclusive
fence. Git object writes may leave unreachable objects on denial, which are
not accepted graph state.

The lock prevents concurrent *trusted* writes using the same lock directory.
It does not prevent arbitrary external processes from modifying the workspace;
a production host must control that access and quarantine uncertain state.
The stage host must atomically persist the new HEAD and next-stage policy
before allowing a new trusted write session. This integration, authenticated
receipt storage, the Reviewer commit gate and the writable CLI are **not yet
implemented**.

`LinuxStageGitBaselineReconcilerTest` tests the Git operations on Linux in
the integration tier, including successful successive role baselines and
adversarial status/digest/locking cases. These tests are not a substitute for
an installed five-role CLI E2E or Linux receipt-provenance validation.

## Compatibility

The existing `recursive-coder run` command remains read-only and unchanged.
No `FiveRoleWorkflowHost` binding is provided by default session composition.
The native TaskScheduler and Session Protocol v1 schema are unchanged.

## Outstanding issue #79 acceptance

- Implement a production Linux host with real trusted receipt retention,
  isolation and fenced per-stage Git reconciliation.
- Wire an explicit opt-in scoped-write CLI route and stage-specific deterministic
  FakeModel fixtures. An executable CLI E2E must cover all five roles.
- Wire bounded `RecursiveGraphScheduler` child graphs with **two distinct
  nested stage origins**, accepted child manifests and parent integration.
- Exercise negative CLI acceptance: dependency cycle, budget limit, Reviewer
  rejection, protected specification/test mutation and artifact conflict.
- Wire correlated role, child, transition, request, tool and budget event metadata
  into the installed CLI's durable journal without representing unknown usage
  as zero or disclosing prompts/credentials.

No checkpoint/crash recovery (#10), performance benchmark (#11), live model
provider or external task coordinator is required for #79.
