## #104: trusted-host durable `created → accepted` handoff

`DurableCreatedAcceptedHandoff.publish` is an opt-in, host-only primitive in
`:infra:journal`, deliberately **not** wired into `DefaultRunSessionUseCase`
or the CLI. The host supplies an independently verified and already durable
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
