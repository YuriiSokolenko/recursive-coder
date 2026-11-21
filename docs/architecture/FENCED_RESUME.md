# Exclusive, generation-fenced resume admission (issue #104)

This is an opt-in host-only API in `:infra:journal`. It does **not** wire
automatic restart into the CLI, enable replay of tool/model calls, or grant
permissions by trusting a checkpoint. Issue #105 must reconcile uncertain
external operations before broader resume boundaries become available.
See the [effect intent and recovery contracts](EFFECT_INTENTS.md) for the
pure policy, including the successor-generation non-start barrier requirement;
no live effect reconciliation adapter is enabled here.

## Trusted accepted-to-running boundary (partial #104)

The trusted `DefaultRunSessionUseCase.publishTrustedAcceptedRunning` entry
point is **separate from normal `invoke()`**. It requires an already durable,
host-authenticated accepted checkpoint C2 and a fresh independently provided
host Git/policy/authority context. It uses a new exclusive resume generation,
reopens the exact checkpoint-bound ledger, appends one canonical
`accepted -> running` transition under `appendTrustedAcceptedRunning`, and
persists C3 with `saveFromOpenLedgerFenced` before the writer and lease close.

This is a **metadata-only, one-way marker, not permission to execute**.
It preserves task state `ready`, attempt zero, unchanged children/artifacts,
known-zero usage and zero pending effects; the checkpoint store compares the
entire C3 candidate against C2 under `checkpoint.lock`. The independent
`TrustedRunningBoundaryPolicy` permits only the final, root-actor transition
after the old `PreExecutionPhasePolicy`-authenticated prefix. Ordinary
`appendFenced` remains pre-execution-only.

**C3 is deliberately not admissible for resume.** The unmodified
`SessionResumeAdmission` pre-execution policy rejects `running`, even if
C3 is complete and the ledger is clean. A crash after the running append but
before C3 leaves an uncovered tail; no automatic repair or retry is allowed.
No model, workspace tool, child, effect journal, receipt, terminal output or
ordinary session event sink is opened by either trusted handoff. The CLI
continues to reject `resume.mode=resume` and normal execution is unchanged.

### Crash-tail operator runbook (current contract)

A crash after `accepted -> running` was durably appended but before C3 was
published creates a **permanently blocked session under the current APIs**.
Neither automatic resume, the genesis-only operator reconciliation path, nor
`SessionCheckpointStore.inspect` can recover or bless the uncovered tail.
The failure is fail-closed, and there is **no manual repair endpoint** for that
same session in #104. Do not truncate, edit, delete, or replay journal frames,
checkpoints, lease generations, anchors or signed effect evidence to work
around the refusal.

The generation-2 resume fence and the immutable anchor pinned to C2 were
**already durably published before the C3 ledger append began**. Their presence,
absence or apparent duplication is not evidence that repairing a checkpoint or
minting another fence is safe. Preserve both as immutable audit evidence.

A complete but uncovered running tail normally surfaces as
`CHECKPOINT_JOURNAL_MISMATCH` (the last accepted C2 does not cover the
physical RCLDG1 tail). A torn or otherwise unacknowledged physical tail can
instead surface as `CHECKPOINT_JOURNAL_UNCONFIRMED`; these are different
diagnostics, and neither authorizes repair. **Neither code alone proves this
specific crash window**; both can represent corruption or unexpected drift. For triage,
preserve a read-only copy of the entire store/ledger, independently inspect
the physical journal, and verify that a trusted C2 is the last checkpoint
while the ledger has exactly one trailing canonical
`accepted -> running` event with no C3. If any additional or unrecognized
frames are present, treat evidence as unconfirmed, not a recoverable case.
Escalate both outcomes to the operator; do not attempt to mutate or resume
the old session. Create a new session from freshly verified inputs and
workspace/policy authority; any external effects must be assessed under #105
before starting new work. A future *separately reviewed operator reconciliation
design* must explicitly authenticate this tail if in-place recovery is ever
proposed. Neither #104 nor #105 currently implements that path.

If the crash occurred **after C3's atomic rename**, a completely valid C3
may already exist, even if its publisher did not return an acknowledgement.
Read-only `SessionCheckpointStore.inspect` may accept that exact C3 and its
fully authenticated RCLDG1 history. This is still not execution authority:
`SessionResumeAdmission.admit` refuses `RESUME_UNSAFE_BOUNDARY` for C3.
Never blindly retry publication or treat a successful `inspect` as approval
to resume model/tool work.

This milestone does not solve #105 effect authority/reconciliation, long-held
fsync/shutdown watchdog semantics, kill-boundary subprocess evidence, or the
true live-fenced runtime `FileStore.type()` sanitized-denial E2E. Those remain
gates for connecting real execution and for closing #104. Independently
review the authenticated operator call boundary before production use.

## Deny-only runtime filesystem preflight (Draft PR #135)

`ResumeRuntimeFilesystemPreflight` is an optional, trusted diagnostic adapter
for `DefaultRunSessionUseCase`. It is **not** `SessionResumeAdmission.admit`
and never acquires `resume.lock`, mints a fence, starts a model/tool, or
permits execution or effect replay. The production CLI still uses the default
`UNSUPPORTED_RESUME` behavior. The default bypasses the diagnostic worker
entirely, so even a stuck opt-in lookup cannot change this code.
A successful probe also returns `UNSUPPORTED_RESUME`: no success case
creates resume authority.

The Session Protocol v1 rejection order remains **protocol -> invocation ->
resume**, before capabilities/model/tool policy/budget. A resume request with
invalid later fields still returns `UNSUPPORTED_RESUME` on the default
path. With the opt-in diagnostic enabled, filesystem lookup occurs at the
original RESUME decision point, **before** validation of later capability,
model, tool-policy or budget fields. The opt-in diagnostic must not alter
the preceding protocol/invocation denial order. Host failures cross the
infra-to-core boundary solely as stable denial codes plus a syntax-constrained
`checkpoint=... ledger=...` detail. Never expose the raw exception, cause,
suppressed exceptions or private host paths in a result, event or diagnostics.
Volume type and lookup success can reveal host configuration, so this optional
preflight is restricted to operator-trusted callers, not untrusted model input.

The session checks cancellation/deadline immediately before and after probe
execution. `FileStore.type()` on a hung mount may not be interruptible, so
the probe is isolated in a `ResumePreflightRunner` provided to the
`DefaultRunSessionUseCase` constructor. **Each runtime instance owns its
own bounded daemon worker** (one active task, no queue); test instances
cannot starve one another. Invocations intentionally sharing the same
runtime instance share only that instance's diagnostic worker. Its
configurable `maxProbeMillis` defaults to **500 ms**, and the remaining
session deadline can shorten the wait. An overrun returns the typed
`RESUME_ADMISSION_UNCONFIRMED` denial unless cancellation/deadline wins.
The kernel operation may continue past the wait in that daemon; subsequent
opt-in requests on the **same runtime** fail closed while its worker is
occupied. A late result cannot create an event, fence or execution permission.
`InterruptedException` restores the caller interrupt flag. If that flag then
causes an interruptible NIO `FileChannel` ledger append/flush to fail, the
runtime propagates `EventSinkFailure` **without publishing a typed session
result or diagnostics**; the unconfirmed journal must not be treated as a
successful denial receipt. Fatal VM errors still propagate through the
trusted runtime rather than being suppressed.

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

Admission, generation inspection and operator reconciliation additionally
read `FileStore.type()` for **both** the checkpoint directory and the ledger
parent before taking a checkpoint or resume lock. Only explicitly reviewed
Linux filesystem types (`ext2`, `ext3`, `ext4`, `xfs`, `btrfs`, `f2fs`,
`zfs`) pass the conservative type gate. NFS, SMB/CIFS, FUSE, overlay,
`tmpfs`, unknown types and lookup failures fail closed with
`RESUME_FILESYSTEM_UNSUPPORTED`; no new fence is published. The exception
retains this stable code and includes non-secret diagnostics such as
`checkpoint=overlay ledger=ext4`. A failed lookup reports
`lookup-failed` for its respective volume and preserves the original cause.

Mount type classification is only a necessary preflight, **not** proof of
fsync or lock correctness. For example, `ext4` with disabled write barriers
(`nobarrier`) or `zfs` with `sync=disabled` may not honor the required
durability contract. Operators must provision and verify trusted, persistent
local filesystems and their mount options. In Docker/Podman, an `overlay`
container root is rejected; put **both** the checkpoint directory and ledger
parent on appropriate persistent bind-mounted or volume-backed local storage.
The initial unfenced checkpoint creation may still succeed on `tmpfs`, but
subsequent fenced admission will refuse; validation of the initial write
is separate from the filesystem admission gate tracked by issue #104
and should not be treated as evidence of durability.

**Untrusted-boundary rule for the remaining #104 runtime integration:**
only the structured `SessionResumeUnconfirmed.code` and sanitized
`detail` may reach model-visible or protocol-visible outputs. Do not
serialize, stringify, or forward the exception, `cause`, suppressed
exceptions, or stack trace; filesystem lookup causes may contain host
paths. Keep full exceptions in host/operator-only diagnostics. This rule
must be verified when resume is actually wired into runtime; it is
**not** enforced by the currently unconnected runtime path.

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
no accepted artifacts, no pending effects, and no unknown or nonzero
host-asserted consumption. The verified event history may contain only
completed `checkpoint.saved` metadata or a single explicit
`session.transition` with a legal `created → accepted` edge. **Both**
event types allow only the optional `graphId` metadata key: stage/recursive
markers, reservation IDs and receipt-like metadata are denied even if hidden
inside `checkpoint.saved`. Only the root agent actor identity may be
non-null. A created checkpoint has no phase edge; accepted requires exactly
one. Type-only legacy transition records (`CHILD_RESERVED`, preparation
`CREATED`/`READY`) refuse with `RESUME_UNSAFE_BOUNDARY`, pinned by
tests against the real event emitters. This intentionally tightens #113
admission and may reject older hand-built pre-execution journals; real
runtime journals with `tool.*` or `llm.*` events were **already** refused.
`ready` children in a host snapshot are unexecuted planning descriptions,
not evidence of reservation, grant or child execution; the positive
explicit-edge admission test uses no children. The policy never replays
side effects. Any later state or uncertain effect returns
`RESUME_UNSAFE_BOUNDARY` and needs operator reconciliation;
there is no opportunistic retry or optimistic tool replay. Prior bytes,
including "known" usage, are not independent usage/accounting receipts.

**Compatibility with merged #113:** `saveFenced` is now narrower: unlike
the original #113 path-based fenced writer, it refuses a snapshot outside
the safe pre-execution phase/history policy and refuses any journal prefix
not already covered by the previous checkpoint (`CHECKPOINT_FENCED_LEDGER_ADVANCED`).
Applications using #113's broader call shape must switch to the current
open-writer `saveFromOpenLedgerFenced` protocol or remain stopped; merely
holding a lease is not authority to confirm additional ledger bytes.

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
   There are at most 256 generations per store: first admission is
generation 1 and each subsequent complete reopen/checkpoint cycle requires
a fresh generation, allowing at most **255** further admissions/reopens.
Generation 257 refuses `RESUME_FENCE_LIMIT`; there is no automatic rotation.
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
resume.lock. Reopen takes the **ledger OS lock**, then re-enters the
**lease monitor** via `assertCurrent`/`requireLedgerPath`, and finally
takes a short `checkpoint.lock` to verify the latest head; the full
save path has lease monitor → writer monitor → checkpoint lock.
No claim of globally consistent lock order is made: correctness currently
also relies on nonblocking file-lock acquisition. Admission never blocks
waiting for an active ledger writer.
All OS file-lock acquisitions in the current implementation are
**non-blocking `tryLock`**, so contention yields a fail-closed
`*_LEASE_HELD` refusal rather than an OS-lock deadlock. Reopen
reports transient checkpoint lock contention as `LEDGER_REOPEN_BUSY`
(cause `CHECKPOINT_LEASE_HELD`), not an evidence mismatch. A subsequent
retry may succeed after the competing short operation finishes. Any future
change to blocking `lock()` must re-audit monitor ordering; the only
nested monitor order is lease → writer. The checkpoint lock is **not**
held over model requests, long tool runs,
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

The opt-in `DurableSessionEventLedger.reopenForAppend(..., lease)` path (#114)
can reopen an **exactly checkpoint-bound pre-execution ledger** only while
a current generation lease is held. `appendFenced(event)` atomically wraps
the append, local fsync and acknowledgement inside `lease.publishIfCurrent`.
Plain `EventSink.append/flush` refuse for a reopened writer.
`saveFromOpenLedgerFenced` is for an **already reopened, lease-bound
writer only**. Passing an ordinary CREATE_NEW writer refuses with
`CHECKPOINT_WRITER_NOT_FENCED` (cause `LEDGER_NOT_FENCED`);
use `saveFromOpenLedger` on the ordinary unqualified path, subject to
the normal resume-fence admission rules.

**No current production `SessionRuntime` journal is admissible under
#114.** Its workspace admission produces `tool.started` /
`tool.completed` audit events and `llm.capabilities` **before**
`created → accepted`; the shared policy deliberately rejects these types.
The #114 two-generation continuation tests use host-controlled synthetic
metadata. A separate opt-in [durable `created → accepted` handoff](DURABLE_CREATED_ACCEPTED_HANDOFF.md)
now publishes C2 from a verified on-disk C1 under a trusted host lease;
it is **not** connected to runtime execution. Production integration still
needs a separately authorized pre-execution producer and #105 runtime/receipt
fencing (or a separately reviewed policy change). Do not attach this
writer directly to the existing runtime event stream.

The older path-based `saveFenced` now enforces the **same**
`PreExecutionSnapshotPolicy.safe` and verified phase-history rules.
It may publish a new checkpoint generation only when the ledger's exact
sequence, head SHA, full-file SHA and offset are **already covered by the
previous persisted checkpoint**. It must reject an uncovered tail with
`CHECKPOINT_FENCED_LEDGER_ADVANCED` and an inadmissible snapshot with
`CHECKPOINT_RESUME_UNSAFE_BOUNDARY`, before any generation is published.
`saveFenced` is **not** an escape hatch for a failed
`LEDGER_REOPEN_CHECKPOINT_REQUIRED` close: even a checksum-valid tail
may lack confirmed fsync acknowledgement. That state requires explicit
operator reconciliation outside the automatic #114 API (or a new session).
Only `saveFromOpenLedgerFenced` while the same writer remains open can
confirm and cover freshly acknowledged fenced appends.
`PreExecutionPhasePolicy` is shared with the admission gate: the only
new phase transition is `created → accepted`, with exact `from` continuity;
`checkpoint.saved` may not carry a transition. No model/tool execution
or terminal phase can be emitted through this path. Compared with the
merged #113 admission gate, **every** historical
`session.transition` must have a valid explicit `created → accepted`
edge, and `accepted` must be justified by that edge. The current
recursive child-reservation and preparation-stage emitters produce
`session.transition` **without** a phase edge; these are refused.
A `checkpoint.saved` event is not itself evidence that the checkpoint
was actually published. Before a fenced checkpoint save, the host must
supply a snapshot whose phase and full pre-execution shape match the
verified ledger history and writer's committed phase. A stale
`created` snapshot after the `created → accepted` event is denied
**before persistence**, leaving the writer's checkpoint obligation intact.

**The host must publish a matching `saveFromOpenLedgerFenced` checkpoint
while writer and lease are open, close the writer, then retire the lease.**
A reopened writer refuses an admitted prefix already over **4 MiB**, or
any additional fenced frame that would cross that same live-checkpoint
limit, with `LEDGER_REOPEN_CHECKPOINT_CAPACITY` before writing bytes.
Normal CREATE_NEW writers retain their separate 32 MiB bound.
Closing the writer after retiring its lease violates the host contract;
the writer's `close()` avoids consulting the lease to prevent monitor
inversion. If writer closure is already revoked or uncertain, its
original failure remains primary; an outstanding
`LEDGER_REOPEN_CHECKPOINT_REQUIRED` (or
`LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED` following an ambiguous publication)
is retained as a suppressed cause. Following a successfully acknowledged
fenced append, only
a **successfully persisted and verified** `saveFromOpenLedgerFenced`
clears the checkpoint-required state; reading an anchor or a failed
save never does. If the host closes without the matching checkpoint,
`close()` releases locks but immediately throws
`LEDGER_REOPEN_CHECKPOINT_REQUIRED`, while leaving durable bytes
untouched. An attempted fenced save that throws
`CHECKPOINT_WRITE_UNCONFIRMED` (for example, after atomic rename but
before confirmed directory fsync) instead latches an **unknown checkpoint
publication** state. On close, the writer reports
`LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED`, not the definitely uncovered
`LEDGER_REOPEN_CHECKPOINT_REQUIRED`. A readback might find C2 already
present or still absent; neither outcome independently proves publication
durability or authorizes retry. Keep the original exception, stop automated
publication, and have a trusted operator inspect the checkpoint chain,
fence head, exact journal digest/sequence/offset and durable generation
before deciding on manual reconciliation or a new session. The live
writer never clears this flag through read-only inspection. The stale C1
expected hash must not be retried. Future admission with an uncovered tail
refuses `CHECKPOINT_JOURNAL_MISMATCH`; a published C2 with the old
generation lease also demands fresh admission. There is no implicit
truncation/replay. A successful checkpoint advances
the latest checkpoint SHA, so a **second reopen with the same lease**
refuses; a new admission and generation CAS is required. A complete
supported cycle is generation 1 admission → fenced append with
`created → accepted` → checkpoint C2 in phase `accepted` → clean
writer/lease close → generation 2 admission from C2 → another permitted
pre-execution append → checkpoint C3. It is pinned by an integration test.

**Required trusted-host sequence (not an atomic two-file transaction):**
obtain admission/lease; reopen the writer; append only authorized
pre-execution metadata with `appendFenced`; **immediately** publish
a matching generation through `saveFromOpenLedgerFenced` while writer
and lease remain open; close the writer; retire the lease. If the process
dies between acknowledged append and checkpoint publication, a durable
tail survives without checkpoint coverage. The next admission refuses
`CHECKPOINT_JOURNAL_MISMATCH`, and the host must halt for explicit
operator reconciliation (or create a new session). No method in #114
makes the two physical publications crash-atomic; do not assume recovery
is available merely because `appendFenced` returned successfully.
The current operator-reconciliation requirement is intentional rather than
a claim that policy-valid metadata-only tail adoption can never be designed.
It remains out of #114 until [#117](https://github.com/YuriiSokolenko/recursive-coder/issues/117)
proves a safe, generation-fenced adoption protocol. Integrity of the tail
does not itself attest its fsync acknowledgement or absence of external effects.
Each local `FileChannel.force(true)` executes while the resume lease
monitor is held. This has **no guaranteed latency bound**: a slow disk
can delay `lease.close()`, competing fenced publications and shutdown.
Host shutdown must not rely on a short deadline or attempt to retire the
lease while an in-flight fenced fsync is still completing. This feature
still does **not** enable arbitrary model/tool execution, general
crash-resume or runtime/receipt publication without the remaining
production gates.

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
   full production crash-resume: #114 offers only fenced **pre-execution**
   event continuation, while existing runtime receipts and model/tool
   execution remain outside the generation fence.

The operator ID and `operatorApproved` are **host-supplied assertions, not
user authentication, proof of a human decision, or independently verified
authorization**. The host must authenticate and authorize the operator
before constructing `PreparedFenceReconciliation`. The immutable audit is
checksum-validated and durable but is not tamper-proof against the trusted
same-UID filesystem owner, who can delete files. It is stored outside the
closed ledger because audit publication must precede the missing fence,
not depend on reopening the ledger. The restricted #114 append path does
not modify this operator-audit ordering. Published
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
- Corrupt or semantically mismatched audits with valid checksums, and audit
  records beyond the current fence head
- Historical two-generation regression: checksum-valid checkpoint/policy drift
  in the first audit, plus a self-consistent forged chain of both anchors and
  the first audit that differs from the published first-generation fence
- Operator recovery rejects historical anchor/fence divergence with a typed
  reconciliation error and retains the original anchor mismatch as its cause

These tests run offline. They verify cooperating-process behavior, not
protection from a malicious same-UID filesystem owner.
