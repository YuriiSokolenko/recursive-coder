# Exclusive, generation-fenced resume admission (issue #104)

This is an opt-in host-only API in `:infra:journal`. It does **not** wire
automatic restart into the CLI, enable replay of tool/model calls, or grant
permissions by trusting a checkpoint. Issue #105 must reconcile uncertain
external operations before broader resume boundaries become available.

## Boundary and trusted inputs

### Required host filesystem setup

Before enabling fenced admission, provision a **Linux/POSIX checkpoint
directory and the parent directory of the durable ledger**, each outside
the workspace, as real directories owned by the effective numeric UID with
exact mode **0700**. The ledger is a separate regular file in that private
parent. A public or group-readable ledger parent is not accepted for fencing;
the ordinary non-fencing checkpoint interface can continue to operate
without creating anchors. All operators and reconciliation callers must
use the same trusted host identity and current Git/policy inspection.

**Local Linux filesystem only:** keep both directories on local filesystems
with reliable advisory locks, hard links, stable inode/file keys and directory
fsync. NFS, SMB, FUSE and other network or userspace-backed mounts are
unsupported even when the hard-link capability preflight succeeds. That
preflight does not validate lock reliability or inode stability.

`SessionResumeAdmission.admit(directory, context, ledgerPath, attestation)`
requires the same private, Linux/POSIX, effective-UID-owned 0700 checkpoint
directory and out-of-workspace durable event ledger as issue #103. The host,
not an LLM, supplies a current `CheckpointHostContext` from a fresh Git,
frozen-input and policy inspection. Admission replays and verifies the complete
checkpoint chain, ledger sequence/head/full-file hash/offset, artifact receipts
and graph before changing anything. If another live durable-ledger writer
exists, read-only verified replay is refused.

The host attestation supplies an expected fence generation for CAS, current
host-owned `ReadPolicyFacts`, and explicit external-action reconciliation.
The module validates the read-only capabilities with
`ToolAuthorizationPolicy.admitReadOnlyPolicy` and computes a canonical `RCREAD1`
SHA-256 from these facts and the verified host policy hash. Callers no longer
provide two self-matching authority digests. The facts MUST still be
authentic observations from a trusted host (not a model or checkpoint), and
a boolean external-action flag is not independent proof of side effects.
Changing allowed read tools across admissions refuses with
`RESUME_FENCE_CONTEXT_CHANGED`.

Only a **pre-execution** snapshot is supported: phase created/accepted,
task state created/ready, task attempt zero, no non-ready children,
no accepted artifacts, no pending effects, no unknown or nonzero
host-asserted consumption, and no agent/model/tool/event types other than
session transitions and checkpoint.saved. These are deliberately narrower
than the currently emitted "running" checkpoints. Any later state or uncertain
effect returns `RESUME_UNSAFE_BOUNDARY` and needs operator reconciliation;
there is no opportunistic retry or optimistic tool replay. Prior bytes,
including "known" usage, are not independent usage/accounting receipts.

## Lock order, generations and publication

1. Acquire `resume.lock` exclusively, reserving its canonical path in the
   JVM **before** opening another file channel to the same lock inode. This OS
   lock remains held across inspection, admission, resumed execution and
   terminal publication. A concurrent process gets `RESUME_LEASE_HELD`.
2. Acquire `checkpoint.lock` briefly to inspect the immutable chain and
   closed verified ledger, compare the caller's `expectedFenceGeneration`
   with the latest published generation, and atomically publish the new fence.
   Only the successful publisher gets a `SessionResumeLease`.
3. The fence is an immutable `resume-NNNNNNNNNNNN.rcf` file, with a
   `RCFEN1` header, monotonic generation, preceding fence digest, exact
   admitted checkpoint digest, session/trace identity, policy digest, and
   tool-authority digest, plus a SHA-256 payload checksum. The write uses
   CREATE_NEW temp, file force(true), atomic hard-link publication
   (`Files.createLink`) that fails if the destination already exists, and
   parent directory force(true). **Before** any anchor is committed,
   admission probes hard-link creation and directory fsync separately in both
   the checkpoint directory and the ledger parent. The directories need not
   share a filesystem. Unsupported links abort with
   `RESUME_FENCE_HARD_LINK_UNAVAILABLE` before changing the generation.
   This is an availability preflight, not a guarantee against later I/O errors.
   If the process crashes mid-probe, checkpoint probe filenames matching
   `.resume-hardlink-probe-<UUID>.tmp/.link` are tolerated by read-only
   inspection. Ledger-parent probes instead use
   `.resume-hardlink-probe-ledger-<SHA256(ledger basename)>-<UUID>.tmp/.link`.
   Probe files are not committed evidence; inspection never deletes them.
   A subsequent admission removes checkpoint probes and **only its own
   ledger's** orphan probes in the shared parent, fsyncing changed directories.
   Nonmatching checkpoint files and unsafe probe types refuse.
   Hard-link publication is
   limited to Linux filesystems supporting same-directory hard links.
   Unsupported filesystems fail closed. Temp names are cleaned without
   unlinking committed data.
   All published fences and their chain are verified on every checkpoint read.
   Before fence publication, an immutable SHA-256 chained
   `<ledger-name>.resume-anchor-NNNNNNNNNNNN.rca` is published and fsynced in
   the ledger's separate directory. A shortened fence chain with a surviving
   external anchor fails closed; if a crash leaves an anchor without its fence,
   normal admission refuses. The explicit host/operator-approved
   `reconcilePreparedAdmission` can complete exactly one verified prepared
   fence under both OS locks, without deleting an anchor or rolling back the
   generation. This is never automatic. This detects partial
   checkpoint-directory rollback, not a same-UID rollback of BOTH directories.
   Orphan `.resume-pending-*.tmp` fence temps and
   `.<ledger-name>.resume-anchor-pending-*.tmp` ledger-sibling temps
   are cleaned only for their own ledger during
   admission under both OS locks. The ledger's parent must also be
   effective-UID-owned and mode 0700 for anchored operation. Unfenced legacy
   checkpoint operations check only the exact first anchor filename without
   scanning the ledger directory; anchored reads verify bounded exact
   generation filenames, ignoring unrelated sibling files.
   There are at most 256 generations per store.
4. Release the short checkpoint lock after admission, **not** the long
   `resume.lock`. During execution, only `SessionCheckpointStore.saveFenced`
   or `saveFromOpenLedgerFenced` can publish checkpoints. They validate the
   live lease and current fence under the checkpoint lock. Once the first fence
   exists, the legacy unfenced save APIs fail closed with
   `RESUME_FENCE_REQUIRED` (wrapped as `CheckpointUnconfirmed` by legacy save). A closed/stale lease fails with
   `RESUME_FENCE_STALE_WRITER`.
5. The host must wrap each short trusted state/receipt commit in
   `lease.publishIfCurrent { ... }`, which serializes validation and the
   publication with lease retirement, preventing a check-to-commit race.
   `lease.assertCurrent()` is a non-publishing status guard, **not** an
   atomic commit guard. Callback checks re-read only the fence and anchor head
   files; admission and snapshot inspection still verify the complete chain.
   Every committed operator audit is also checked against its own published
   fence's checkpoint and policy SHA-256, not just its anchor and fence hash.
   Do not run model requests or blocking network/tool
   operations inside the publication callback. Keep the OS lease until all
   authorized publication is complete. `close()` invalidates the token.
   Closing without completing terminal publication is not a successful commit.

Do not open another channel to `resume.lock` within the JVM while its lease
is held. Some JVM/OS combinations may drop process-level advisory locks when
any channel to the same file is closed; the lease is the only channel owner.

The locking order for admission is resume.lock -> checkpoint.lock ->
closed-ledger inspect (its independent tryLock). The live-ledger checkpoint
path uses its existing writer monitor -> checkpoint.lock; it never takes
resume.lock. Admission never blocks waiting for an active ledger writer.
The checkpoint lock is **not** held over model requests, long tool runs,
or future host filesystem operations. Within the checkpoint lock, bounded
verification/serialization and fsync still have no real-time latency guarantee.

Only cooperating processes using these APIs are fenced. The private
directory and current OS effective UID must be trusted: advisory OS locks
and hash chains cannot prevent a malicious same-UID owner from rewriting
records. Generation state is independent of the checkpoint sequence; a
generation is never reset to zero after a completed restart. Never prune a
subset of fence or anchor records to evade the 256-generation limit.
Exhaustion requires stopping writers, operator reconciliation and provisioning
a NEW session identity with fresh ledger/store and verified authority.
There is no in-place rotation or safe implicit truncation. A crash releases
the OS lease but not its durable generation, so the next process must provide
the observed current generation as its CAS precondition.

## Refusals and limits

Typed `SessionResumeUnconfirmed` codes distinguish live lease, invalid
authority, uncertain external effects, unsupported safe boundary, forced CAS
failure, changed context, stale writer and corrupt/unsupported fence versions.
On the operator repair path, a mismatch between an existing anchor and
its published fence is reported as `RESUME_FENCE_RECONCILIATION_UNCONFIRMED`
with the original `RESUME_FENCE_ANCHOR_MISMATCH` retained as its cause.
Existing `CheckpointUnconfirmed` codes still report baseline/policy, journal,
snapshot and path failures. Neither class is a successful resume token.

The existing ledger is CREATE_NEW-only and cannot reopen its closed file for
append. `saveFromOpenLedgerFenced` currently guards the call but does not
create a resumed ledger writer. End-to-end recovery requires an explicitly
fenced ledger-reopen path and production runtime/receipt integrations.

Legacy `inspect`/save paths adapt fence storage errors to the existing
`CheckpointUnconfirmed` contract. A live stale callback remains a typed
`SessionResumeUnconfirmed`. A crash after publishing a sidecar anchor but
before its fence requires explicit operator reconciliation.

### Read-only recovery diagnostics

`SessionResumeAdmission.reconciliationStatus(directory, context, ledgerPath)`
validates the checkpoint, closed ledger and committed anchor chain under the
short checkpoint lock, then checks **at most one** prepared generation and
its audit. The result is informational, not authorization:

- `PREPARED_UNAPPROVED`: one extra durable anchor; no operator audit yet
  (`operatorId=null`)
- `APPROVED_PENDING`: a valid audit commits the operator's approval, but
  the corresponding fence is **not published**; retry only with that same
  operator and exactly the approved digest
- `COMPLETED`: a valid audit is bound to the latest published fence;
  a normal admission without a recovery audit returns `null`

It reports the generation, prepared/published fence SHA-256, and recorded
operator ID where available. Inconsistent evidence refuses; it never repairs,
cleans temps, opens the ledger for append, or grants a session lease.
**Never automatically copy the digest returned here into
`PreparedFenceReconciliation.expectedPreparedFenceSha256`.** The trusted
operator must compare it against **independently obtained** host/incident
evidence before approval; otherwise the approval is circular.

This is not a liveness indicator: it does **not** acquire or test
`resume.lock`. Even `PREPARED_UNAPPROVED` can represent an in-flight
admission. Stop prior processes and independently confirm no writer holds
either lock before making an operator decision. `COMPLETED` describes only
the latest generation; it disappears from this accessor after a later normal
admission. The accessor deliberately refuses on checkpoint/policy drift or
inconsistent audit digests (`RESUME_FENCE_AUDIT_UNCONFIRMED` or a baseline
verification failure) rather than treating different host context as verified
recovery. Consult archived audit/ledger evidence manually if this blocks
diagnostics; never turn the diagnostic refusal into permission to resume.
A missing audit removed by the trusted same-UID owner remains
outside the threat model.

### Interrupted anchor prepare — operator procedure

1. Stop the prior process and verify no one holds `resume.lock` or
   `checkpoint.lock`. Use the [read-only recovery diagnostics](#read-only-recovery-diagnostics)
   above to identify a validated prepared head and any committed operator
   approval; the accessor does not verify liveness. Confirm that the closed
   event ledger replays successfully, inspect the previous verified fence/anchor
   generations, the current policy, Git baseline, frozen inputs and all
   uncertain external effects.
2. Confirm that **exactly one** anchor at generation N+1 is committed and its
   matching fence is absent; no later generations are present. Independently
   record the expected prepared fence SHA-256, generation and operator identity
   using independent trusted evidence, not a copied diagnostics field.
   If effects, authority, ledger identity or external facts are uncertain,
   **do not approve**. Archive evidence and create a new session with a new
   ledger/store after full operator reconciliation; never delete an anchor.
3. Call `SessionResumeAdmission.reconcilePreparedAdmission` with a fresh
   trusted `ResumeHostAttestation` (expectedFenceGeneration=N) and
   `PreparedFenceReconciliation(N+1, preparedFenceSha256, operatorId, true)`.
   The operator-only function and `PreparedFenceReconciliation` require an
   explicit Kotlin `@OptIn(ResumeOperatorOnlyApi::class)` at trusted host
   call sites. This only acknowledges the safety boundary at compile time;
   the host must still authenticate and authorize the operator.
   The implementation acquires the exclusive OS lease, validates the complete
   checkpoint/ledger and pre-execution boundary, recomputes the proposed fence,
   verifies its SHA-256 against exactly one authenticated trailing anchor,
   then writes a **write-once, fsynced**
   `<ledger-name>.resume-audit-NNNNNNNNNNNN.rcaudit` record containing the
   approved host operator ID, `operatorApproved=true`, generation, exact
   fence/anchor/checkpoint/policy SHA-256, and a host timestamp. The record is
   published before completing the fence and its checksum is verified on read.
   A retry after a crash reuses the matching audit bytes, never changes the
   operator or time, and refuses a conflicting identity or digest.
   Only then does it durably complete the fence without replaying any model/tool action.
   The returned lease owns N+1, not a silently new generation.
   **The approval audit is an immutable intent record, not proof of completed
   recovery.** It can be durable while fence N+1 is still absent. The state is
   `approved/pending` while the audit and prepared anchor exist without that
   fence; it becomes `completed` only when the matching, verified fence N+1
   is durably published. Do not infer completion from `operatorApproved=true`.
4. Validate the returned lease and inspect the full fence/anchor chains.
   If publication remains uncertain or operator approval is denied, stop;
   **do not truncate or retry blindly**. If the approval audit was already
   committed, any retry MUST use the same `operatorId` and exact digests.
   A different operator cannot take over this old store: the deliberate
   `RESUME_FENCE_AUDIT_CONFLICT` refuses even a new trusted approval.
   If the recorded operator cannot complete it, preserve the pending audit,
   archive the old store/ledger/anchors and start a **new session with a fresh
   store** after manual reconciliation. Do not modify or delete the existing
   operator record. Completing this prepare is not
   full production crash-resume: the existing ledger still cannot safely
   reopen for append and existing runtime receipts are not generation-fenced.

The operator ID and `operatorApproved` are **host-supplied assertions, not
user authentication, proof of a human decision, or independently verified
authorization**. The host must authenticate and authorize the operator
before constructing `PreparedFenceReconciliation`. The immutable audit is
checksum-validated and durable but is not tamper-proof against the trusted
same-UID filesystem owner, who can delete files. It is stored outside the
closed ledger because that ledger has no safe append/reopen API. Published
audit, anchor and fence filenames are created through atomic no-replace hard
links, not `exists` plus `ATOMIC_MOVE`; missing/deleted audit evidence is
still not independently detectable. An unknown, missing or conflicting audit
must never be silently overwritten.
Never expose
this method to model-authored commands or unauthenticated host services.
A different prepared hash, invalid operator ID or unapproved recovery
fails closed with `RESUME_FENCE_RECONCILIATION_UNCONFIRMED`.
A negative expected generation has its own
`RESUME_FENCE_EXPECTED_GENERATION_INVALID` denial. An admitted lease
also compares the ledger's Linux file key **before and after verified
ledger inspection** and rechecks it for fenced publications so replacing the
inode at the same path invalidates the lease. File keys can be recycled by a
filesystem after deletion/recreation; protection against a malicious same-UID
writer remains out of scope.

The `admitWithFault` (including its `beforeInspection` and
`hardLinkProbe` callbacks) and `reconcilePreparedWithFault` entry points
are `internal` deterministic
integration-test seams compiled in production sources. They are **not
supported application APIs** and must not be wired to external callers,
model tools, or operator endpoints.

**If recovery cannot reproduce the prepared fence digest** because Git,
frozen inputs, policy facts, read-tool grants, checkpoint contents or ledger
evidence changed, the operator API deliberately refuses and the old store
remains blocked. Never edit, delete, roll back, or force-advance its anchor
and fence records. The last-resort manual workflow is to stop all related
writers, archive the immutable original checkpoint directory, anchors,
audit and ledger for investigation, independently reconcile any uncertain
external effects, then provision a **new session/trace identity and fresh
ledger/checkpoint store** with a newly authenticated baseline and policy.
This is an explicit new session, not a continuation of the old one.

This implementation cannot fence **unrelated existing** workspace writes,
receipts or model/tool operations that do not accept `SessionResumeLease`.
Those host integration points must explicitly guard their own commits before
claiming end-to-end generation fencing. The checkpoint API alone guarantees
fenced checkpoint publication and prevents a stale checkpoint writer from
overwriting a newer generation. No default CLI behavior changes.

### Verification coverage

The Linux integration tier tests these failure boundaries offline:

- Read-only pre-execution admission, read-tool authority, generation CAS,
  and changes to HEAD, policy, frozen inputs and unsafe execution state
- Live ledger and stale callbacks; independent process lock and forced release
- Anchor rollback, interrupted prepares, audit/fsync recovery and idempotent
  operator retries, including conflicting operator rejection
- `reconciliationStatus` transitions from unaudited prepare to approved
  pending to latest completed, plus malformed/stray and mismatched audit cases
- Hard-link publication without overwrite, wired two-directory preflight,
  injected ledger-side refusal before durable prepare, and scoped cleanup of
  orphan probe files without writes during inspection
- Two ledger stores sharing a parent: each admission removes only its own
  SHA-256-scoped probes and leaves foreign probe files untouched
- Ledger inode replacement both before inspection and after anchor publication
- Corrupt or semantically mismatched audits with valid checksums, including
  committed audit checkpoint/policy mismatches against their own fence, plus audit
  and anchor tampering in generation 1 when generation 2 is committed;
  operator recovery keeps the originating anchor mismatch as a typed cause
  records beyond the current fence head

These tests run offline. They verify cooperating-process behavior, not
protection from a malicious same-UID filesystem owner.
