## #104: trusted-host durable `created → accepted` handoff

`DurableCreatedAcceptedHandoff.publish` is an opt-in, host-only primitive in
`:infra:journal`. An explicit, separate `DefaultRunSessionUseCase.publishTrustedCreatedAccepted`
entry point now reaches it through `DurableRuntimeCreatedAcceptedHost`, but only
for an operator-owned **handoff-only** invocation, never through the normal
`invoke()`/CLI/scheduler path. This does not allow runtime execution or
resume. The host supplies an independently verified and already durable
`created` checkpoint (C1), matching trusted `CheckpointHostContext`, a
read-only `ResumeHostAttestation` with expected fence generation, and the
single canonical `created → accepted` event from its trusted event emitter.

### Admission and durable publication

The method rejects an unsafe C1 phase/shape/edge, a noncontiguous event
sequence, inconsistent event/session identity, and a wrong root `graphId`
**before** minting a fence. These are cheap filters, not replacement for
the writer's full frame and ledger verification. Other post-admission
failures can still consume a fence generation (e.g. unsupported frame data,
changed checkpoint/ledger, late lock or publication failure); a caller must
reconcile the current generation instead of recycling the old CAS value.

It then obtains a fresh OS-backed resume lease, **re-reads the admitted C1
from disk** with `admittedCheckpointForReopen`, and requires the entire
`VerifiedSessionCheckpoint` to equal the caller's object, not merely its
caller-supplied SHA label. C2 is derived from **that on-disk C1**, never
from a caller-crafted snapshot. `accepted` is an admitted but still
unexecuted task; its checkpoint `taskState` is `ready`, not `running`.
The host reopens the exact checkpoint-bound durable ledger, calls
`appendFenced` and `saveFromOpenLedgerFenced` on the **same** writer,
and returns only after C2 is durably committed. Writer close precedes
resume lease retirement.

### Uncertain outcome: no automatic retry

The two publications (ledger append and checkpoint C2) are not a single
crash-atomic transaction. **Any exception**, including a failure *after*
C2 was durably written, means the host must stop; a thrown exception does
not prove C2 is absent. In particular, `RESUME_LEASE_RELEASE_UNCONFIRMED`,
`LEDGER_REOPEN_WRITER_POISONED`, `LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED`,
`LEDGER_REOPEN_CHECKPOINT_REQUIRED`, and
`CHECKPOINT_WRITE_UNCONFIRMED` do not imply a safe retry. Before choosing
any next action the operator must check the read-only persisted checkpoint,
ledger and fence/anchor heads, using `SessionCheckpointStore.inspect`,
`SessionResumeAdmission.currentGeneration` and read-only operator
reconciliation where safe. These inspectors can themselves refuse an
uncovered tail or busy/uncertain lock; treat refusal as an unresolved
operator condition, **not** permission to overwrite or repeat publication.

An acknowledged append without C2 leaves a durable uncovered tail. The
next automatic admission fails closed (currently
`CHECKPOINT_JOURNAL_MISMATCH` during evidence verification), even if
the event is otherwise phase-valid; no truncation, tail adoption or blind
path-based checkpoint retry is allowed (tracked in #117).
For the [proposed (not accepted) #117 metadata-tail adoption design](METADATA_TAIL_ADOPTION_DESIGN.md),
see its security prerequisites and operator-only alternative. That proposal
does not change this fail-closed runtime behavior.

### Runtime separation and tests

This primitive does **not** authorize execution after the handoff. The
current runtime emits pre-acceptance model/tool events, and no runtime
or receipt publications are generation-fenced. Allowing runtime work after
C2 would make a previously safe `accepted` checkpoint misleading after
a crash. Keep this helper disconnected from production session execution
until the separately reviewed #104/#105 host integration can atomically
guard entry into `running` and all subsequent side effects.

Offline integration tests cover real C1 → fenced event → durable C2,
writer/lease retirement, next-generation admission/reopen, forged C1
snapshots with authentic hash labels, preflight invalid edges and frames,
uncertain/accepted C1, stale expected generation, and a failed checkpoint
publication after an acknowledged append. Slow-fsync races, actual
runtime-path error redaction, execution/receipt fencing and general
recovery remain outstanding in #104/#105. Separate filesystem allowlist
hardening remains Draft PR #122.

See also [fenced resume](FENCED_RESUME.md),
[checkpoint storage](SESSION_CHECKPOINTS.md) and
[durable event ledger](DURABLE_EVENT_LEDGER.md).

### Opt-in real runtime boundary (handoff only)

The trusted host must independently create and durably verify C1, derive
`CheckpointHostContext` from current Git/policy/workspace evidence and
`ResumeHostAttestation` from host-owned authority. The new
`DurableRuntimeCreatedAcceptedHost` compares the Session Protocol request
identity, task, policy digest, Git HEAD, optional branch, workspace and exact
host-owned checkpoint/ledger paths, then inspects C1 on disk. It emits one
canonical session transition via the shared `SessionEventJournal.frame`
builder and delegates to the existing fenced handoff,
which re-reads the complete authenticated C1 under its lease before C2.
The boundary intentionally NEVER invokes `WorkspaceOpener`,
`ModelGateway`, `EventSinkFactory`, or emits a terminal result.

`DefaultRunSessionUseCase.publishTrustedCreatedAccepted` enforces ordinary
fresh-session admission precedence and passes the same injected clock,
cancellation and deadline to the host. It checks interruption before host
I/O and between current-context verification, C1 inspection and final
fenced publication. This opt-in path never binds the caller thread to
`Cancellation`: a late `cancel()` or `expire()` only updates signals
and cannot itself interrupt the `FileChannel` publication. Pre-existing
thread interrupts are detected and refused at the host gates before C2,
without clearing the caller's interrupt status. A blocked filesystem
sync cannot be forcibly rolled back; an unrelated
external `Thread.interrupt()`, I/O failure or crash can still leave an
uncertain durable tail requiring read-only operator reconciliation.
The entry point is not called by `invoke()`; its receipt is evidence
that C2 was durably observed at return, not an execution grant.
It grants **no** runtime, tool, model, child or effect execution permission.
No automatic
retry is allowed when publication throws: an exception may follow a
committed C2. Normal v1 resume requests remain deny-only and unchanged.

Remaining #104/#105 gates: trustworthy initial C1 producer and current
authority integration in a production host, actual runtime execution/receipt
generation fencing, live lease ownership through terminal publication,
slow/blocked-fsync shutdown and race tests, and crash/kill E2E of that
execution path. Do not enable auto-resume or side-effect replay.
