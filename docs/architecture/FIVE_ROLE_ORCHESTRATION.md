# Host-owned five-role orchestration (issue #79)

**Status: host-only production implementation under validation (Draft PR #92).** `RunFiveRoleWorkflowUseCase` provides a
bounded, in-process coordinator for `SDD → ATDD → TDD → Developer → Reviewer`.
An in-memory fake-host integration test executes all five existing role use
cases. Additional pure scripted coordinator tests check fail-closed responses.
The in-memory fake-host tests alone cannot authenticate write/check receipts
or fence Git mutations. A separate Linux production-host integration suite now
exercises the real trusted write/check port, authenticated stage handoffs,
real Git reconciliation and independent Reviewer completion. This remains
**not** an installed writable CLI or a completed issue #79.

## Two-origin recursive child coordination — PR #94

`RunTwoOriginRecursiveChildrenUseCase` in `:core:session` coordinates
two distinct authorized stage origins (covered by nested SDD and Developer
fixtures). Each origin owns a separate `RecursiveGraphScheduler`, trusted
`RecursiveChildIntegrationHost`, child budget, dependency graph and
authorization scope. The host, not an agent, provides accepted/frozen
manifest evidence. Stage-scoped `RecursiveStageContext.executeChild`
checks direct parent ownership before dispatch; the executor never receives
a mutable scheduler.

The coordinator orders origins deterministically, requires explicit
child-result integration and rejects failed children, duplicate origins,
conflicting artifact identities and paths. Conflict keys use NFC Unicode
normalization and locale-invariant case folding so path aliases cannot
silently survive on a case-insensitive checkout. After both trees finish,
both hosts' accepted IDs, manifests and file digests are re-read. Failure
of the correlated recursive event sink is observable and blocks success;
the session's journal owner must also verify `finish()`.

**Trust-boundary limitation:** the two-origin scenario is currently
demonstrated using independent host-owned fake receipt sources, not
authenticated child write/check receipts in the installed Linux five-role
workflow. The installed CLI can independently persist metadata-only
correlated SDD–Reviewer events when opted into `eventsPath`, but this is
not installed **two-origin** acceptance. See [Writable CLI](WRITABLE_CLI.md).
Issue #79 remains open until those production host gates and full
negative acceptance cases pass.

## Authenticated child write/check evidence — issue #79 follow-up

The opt-in `LinuxFiveRoleWorkflowHost.authenticatedAcceptedStage(stageRunId)`
now exposes a **read-only** host-attested accepted-stage receipt. It
revalidates the actual TrustedWriteRuntime HMAC, generation-bound completed
write/check records, required checks, accepted frozen manifest and **all**
written postimages from the current workspace. Missing receipts, active
writers, quarantine or byte drift yield no evidence. It never exports the HMAC
key, grants workspace writes or upgrades a model-provided manifest.

`LinuxAuthenticatedRecursiveChildIntegrationHost` adapts **real child-stage
write/check evidence** to `RecursiveChildIntegrationHost` only when the
trusted caller has bound the exact child stageRunId. The output's producer must
be the child stage, and the authenticated stage graph, parent graph/stage IDs,
request ID, derived graph/stage identity, origin, writable scope, and frozen
scope must all agree. The adapter checks **every** host-attested
`writtenPostimages` path (not just `manifest.declaredPath`) against the
child's writable and frozen scopes. A single host-authorized multi-file write
can still be denied when the child grant covers fewer files. A parent's
accepted stage output **cannot** be relabelled as a child's result.
`manifestsFor` remains nullable and fail-closed; `verifyChild` exposes
a typed, call-local denial code for host diagnosis and regression tests,
without treating a denial as accepted evidence. `acceptedArtifactIds`
is a provisional list of authenticated stage IDs, not a substitute for
per-child scope checks at manifest integration.

The Linux integration suite exercises **two distinct actual host-generated
child-stage outputs**, one SDD and one Developer. For Developer it runs the
trusted SDD/ATDD/TDD predecessors and independent Reviewer in a second
isolated checkout, then verifies the Developer's actual write/check receipt.
The same recursive coordinator re-verifies both origins at parent integration
and emits correlated recursive SessionEvents without synthetic receipt stores.
FakeModel's token counts are fixture data, **not** a trusted token meter;
the two-origin integration test conservatively charges each child its
**full token grant** while counting genuine verified write/check calls.
The host retains nullable stage usage for reporting but it is not assumed
to be independently measured.
The host can pre-bind the future Developer child stage identity only when it
matches `RecursiveGraphPolicy.childId/childStageId` and the trusted
parent/request correlation; this host-only input is not exposed to a model.
The constructor also rejects duplicate preauthorized stage-run IDs (including
ATDD/TDD/Developer aliases), so conflicts fail before later handoffs.
Host receipts snapshot written-path/check collections defensively; nullable
stage usage is retained without interpreting missing tokens as zero. The
Linux regression suite covers real multi-file writes, parent-result
impersonation, constructor denials and run-time collision checks.

This prepares the trust boundary but **does not yet dispatch these
child-stage writers from the installed five-role parent workflow** or merge
their outputs into its single physical checkout. Therefore these Linux
integration tests are **not installed two-origin recursive CLI E2E**.
The installed CLI's five-role parent execution and journal remain separate
until those child stages are scheduled, written, checked and committed under
the parent host fence. Issue #79 remains open.

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

## Linux production host and Git baseline reconciliation — PR #92

The host-only `LinuxStageGitBaselineReconciler` in `:infra:workspace`
implements **physical clean-Git handoff**, not an authenticated workflow host.
It is opt-in and not selected by the installed CLI or Koin composition.

The opt-in `LinuxFiveRoleWorkflowHost` in `:app:cli` now retains
generation-fenced, host-HMAC-attested write/check evidence obtained only from
the genuine `TrustedWriteRuntime.finish()` path. Each accepted stage checks
the independent host decision, exact output bytes, verified changed-file hashes
and stage/accepted-ID CAS before invoking the reconciler. The Developer handoff
must match host-retained usage, write tool ID, check records and changed files;
the separate Reviewer adapter cannot open a write tool. The reconciler itself
does not establish receipt authenticity or independent Reviewer approval.

For clean-tree admission, the host calls
`LinuxStageGitBaselineReconciler.inspectCleanBaseline` rather than a second,
insecure Git invocation. This returns typed `Clean` or `Denied(code)`; operators
can retrieve the last denial via `LinuxFiveRoleWorkflowHost.lastBaselineDenialCode()`
without exposing Git output or secret paths. Admission and reconciliation
share environment-scrubbed Git subprocesses, strict repository-local config
and index allowlists, exact HEAD/branch checks, and ignored-file-aware status.
**One five-second monotonic deadline spans all Git subprocesses of each
read-only baseline inspection.** Expiry stops and destroys the active child,
rather than holding the host monitor for a succession of 15-second waits.
This budget applies to the read-only Git subprocesses, **not** to
`canonicalRoot()`/`toRealPath()`, host `readBytes()` or `frozenIntact()`.
A stage transition may run more than one clean-baseline inspection under the
host monitor, so five seconds is **not** a total transition deadline.
Interrupts during Git `future.get` or `waitFor` preserve the thread interrupt
flag and return a stable fail-closed code. Mutable stage reconciliation
explicitly rejects all `!!` ignored status entries with
`UNEXPECTED_GIT_CHANGE` even if an authenticated accepted path is ignored.

External approval runs outside the host monitor; late decisions are discarded
after lease revocation. A failed post-gateway fence or byte check permanently
sets `REJECT` on the current lease, preventing repeated human approval prompts.
Interrupted callbacks preserve the thread's interrupt flag. Host HMAC payloads
are canonical, versioned, length-prefixed bytes rather than ambiguous
separators/toString.
Descriptor-relative file access refuses protected paths, links and non-regular
files including FIFOs. Arbitrary same-UID writers remain outside this in-process
security boundary and require an isolated checkout.

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
configuration are rejected. For *currently accepted stage writes*,
the reconciler uses the POSIX **owner execute bit** (not the
group/other execute bits) to determine Git mode (`100644`/`100755`). Accepted
files with group/other execute but no owner execute are rejected before Git
ref CAS; new files must be non-executable and tracked
writes to preserve their HEAD Git mode **before** updating the branch ref,
and rejects world-writable or owner-unreadable/unwritable files. Non-executable
files created under `umask 077` (`0600`) or `umask 002` (`0664`)
are valid: `TrustedWriteRuntime` honors the process umask when creating a
file, and Git does not record those read/write permission differences.

Already frozen regular inputs only require SHA-256, no-follow descriptor
safety and a clean Git status; they do not need the accepted-write permission
policy. The same distinction applies to preflight and post-commit
verification. Executable-bit changes, deletions, renames, pre-staged index
changes and unexpected ignored or untracked files are not supported and are
denied.
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

The Linux integration test suite now exercises two genuine Git-ref mutation
boundaries through an **internal test-only observer**, which is not exposed
to serialized requests, the agent, or the installed CLI. After a real
successful `update-ref`, a deterministic injected failure proves the result
is `ReconciliationRequired` with the moved HEAD and still-dirty index.
A separate test lets an external Git process advance the branch after
preflight but before this reconciler's `update-ref`; the stale-value CAS
cannot overwrite the competing HEAD and still returns conservative
`ReconciliationRequired` because an update was attempted. These tests do
not establish crash recovery or verified cleanup of an uncertain workspace.

The lock prevents concurrent *trusted* writes using the same lock directory.
A denied write request still marks an attempted write: even if the write policy
rejected it before changing disk, abort deliberately quarantines the host.
This is intentionally conservative until a trusted recovery mechanism exists.
It does not prevent arbitrary external processes from modifying the workspace;
a production host must control that access and quarantine uncertain state.
The in-process host now publishes the committed HEAD together with the next
graph stage before admitting further work. It quarantines uncertain state
instead of silently retrying. **An explicit fake-only writable CLI is now available;
durable recovery and two-origin recursive installed E2E are not yet implemented.**

`LinuxStageGitBaselineReconcilerTest` tests Git handoffs and now the direct
typed read-only API on Linux, including malformed head/branch, HEAD/branch
drift, unsafe config and index flags, ignored files, ref changes during final
inspection and expiry of the total baseline deadline. The
`LinuxFiveRoleWorkflowHostTest` exercises authenticated Linux receipts,
a complete five-role real-Git success through `finishSuccess`, Developer
handoff tampering, diagnostic codes, role-only/protected reads, blocking
approval and terminal rejection after byte drift or duplicate gateway calls. Neither suite is an installed writable CLI E2E
or a crash-recovery guarantee.

## Compatibility

The existing `recursive-coder run` command remains read-only and unchanged.
The separate, explicitly opted-in `recursive-coder workflow` command composes
this Linux host with offline proposals and exact plan-bound approvals; see
[Writable CLI](WRITABLE_CLI.md). No `FiveRoleWorkflowHost` binding is provided
by default session composition.
The native TaskScheduler and Session Protocol v1 schema are unchanged.

## Outstanding issue #79 acceptance

- Production deployment still requires an isolated workspace and lock scope;
  PR #92 delivered the host and PR #93 adds a separate fake-only installed CLI.
- Review/extend the installed CLI E2E beyond the five-role sequential fixture.
- Wire bounded `RecursiveGraphScheduler` child graphs with **two distinct
  nested stage origins**, accepted child manifests and parent integration.
- Exercise negative CLI acceptance: dependency cycle, budget limit, Reviewer
  rejection, protected specification/test mutation and artifact conflict.
- Wire correlated role, child, transition, request, tool and budget event metadata
  into the installed CLI's durable journal without representing unknown usage
  as zero or disclosing prompts/credentials.

No checkpoint/crash recovery (#10), performance benchmark (#11), live model
provider or external task coordinator is required for #79.
