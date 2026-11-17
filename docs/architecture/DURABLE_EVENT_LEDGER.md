# Durable Session Event Ledger (issue #102)

This is an opt-in **host-only event sink**, not a replacement for the Session Protocol v1 JSONL export. A narrow pre-execution fenced reopen path exists under #114; it does **not** enable general crash-resume or external-effect replay.

## Binary format

`DurableSessionEventLedger` creates a new ledger under a host-provisioned private directory *outside the Git workspace*, with an adjacent persistent `<name>.lock` file. Version 1 begins with seven ASCII bytes `RCLDG1\n`, followed by zero or more records:

| Offset within record | Representation |
| --- | --- |
| 0–3 | Big-endian signed 32-bit UTF-8 payload length (1..16,384) |
| 4–35 | SHA-256 of `previousHash || payload` (initial previousHash = 32 zero bytes) |
| 36.. | UTF-8 Session Protocol v1 `SessionEvent` JSON payload |

The hash chain covers JSON bytes, not raw model/tool output. Each event is verified against the canonical sequence, causation ID and fixed session/trace identity. The sink permits only known host-origin metadata keys and bounded primitive values; actor/transition/timing/redaction fields are independently validated. Unexpected data **fails closed**. New event metadata must be added to this policy intentionally.

`SessionEventJournal` is the identity/sequence emitter. Its `EventSink` port calls `append()` then `flush()`. This sink acknowledges a record only after a successful `FileChannel.force(true)`. The file and parent directory are synced on initial creation; `close()` refuses to verify any pending or failed append. `sha256()` reopens and verifies the full ledger under an exclusive lease.

## Ownership and recovery limits

Normal creation remains **CREATE_NEW**; only the explicit host-only `reopenForAppend()` path may open an existing ledger while holding a live `SessionResumeLease`, without truncation or implicit resume. A process-wide canonical-path reservation prevents opening a second `.lock` channel in the same JVM, which can otherwise release the live owner's POSIX `fcntl` lease. An OS file lock excludes cooperating *other* processes. The lock file remains in place after close. A hostile same-UID process, hard-link tricks and external filesystem changes are beyond an advisory lock's authority, so this ledger requires a trusted private directory.

`inspect()` is read-only: it opens an **existing** `.lock` file without `CREATE`, refuses a missing lock with `LEDGER_LOCK_MISSING`, and never creates new files. It returns a fully verified prefix plus last sequence, chain digest and file SHA-256, or a typed refusal. A torn record, invalid hash, unsupported metadata, changed identity, or live writer refuses inspection. A **complete record removed at a record boundary is not detectable by the chain alone**. The opt-in checkpoint layer (#103) binds sequence, head digest, file digest and verified byte offset independently; see [host-owned checkpoints](SESSION_CHECKPOINTS.md). It still does not grant restart authority. A complete record that survived a crash might have been written before the writer received its fsync acknowledgement. Therefore an intact prefix **does not prove side effects were accepted** and must never authorize automatic model or tool replay. Uncertain outcomes stay quarantined for #104–#105 reconciliation.

The current CLI uses its original `JsonlEventSink` unless a trusted composition root intentionally supplies `DurableSessionEventLedger.factory(workspaceRoot)`; no Session Protocol v1 files or default read-only behavior change. The workspace root is host-supplied and is not accepted from the model.

The ledger imposes a 16 KiB per-event bound, a 32 MiB file bound and a 100,000-event replay bound. Neither retention nor archival policy is supplied here; operators must keep private logs until the session's checkpoint/receipt reconciliation is complete.

## Verification

`DurableSessionEventLedgerTest` is an `@Tag("integration")` suite exercising fsynced emission through the real `SessionEventJournal`, strict replay, corruption/torn tails, identity/causation/sequence rehashing, unsupported metadata, unsafe file/lock paths, and same-JVM plus cross-process lease exclusion. Full JVM tests, architecture checks and Linux sandbox acceptance remain mandatory CI gates.

## Opt-in fenced reopen-for-append (issue #114; host-only implementation)

The host-only `DurableSessionEventLedger.reopenForAppend(path, workspaceRoot,
checkpointDirectory, context, lease)` checks the current
[fenced resume admission contract (#104)](FENCED_RESUME.md), then reopens
only the admitted, fully verified ledger prefix. The returned writer permits
only `appendFenced(event)`: regular `EventSink.append()` and `flush()` refuse.
`inspect()` remains read-only and `create()` remains CREATE_NEW.
No default CLI behavior, automatic model/tool replay, production receipt
fencing, or general post-execution restart is enabled. This is a **Draft
implementation**, pending full acceptance and independent security review.

### Evidence and inode identity

1. A trusted host first obtains a current `SessionResumeLease` through
   the existing **pre-execution-only** admission. The lease is authoritative
   for the admitted checkpoint SHA-256, generation, tool authority and
   admitted ledger **file key**; it stores the latter privately as
   `admittedLedgerFileKey`. The checkpoint itself deliberately does **not**
   persist an inode or file key. No checkpoint/fence/anchor format change
   is proposed for this slice.
2. Under the existing checkpoint authority, obtain the verified immutable
   checkpoint bound by `lease.admittedCheckpointSha256`. Its persisted
   ledger evidence provides the expected sequence, head digest, full-file
   SHA-256 and exact verified byte offset. Check the checkpoint identity,
   policy, workspace baseline and tool-authority binding against the
   admission; do not accept a separately supplied, unbound snapshot.
   **After acquiring the long-lived ledger OS lock and replaying the
   verified prefix, re-read the latest checkpoint head under a short
   `checkpoint.lock` and require its SHA still matches
   `lease.admittedCheckpointSha256`.** This closes the C1 → C2 gap where
   another same-lease writer publishes new checkpoint metadata without
   adding ledger bytes between initial inspection and lock acquisition.
3. Capture/check the file key at reopen with
   `lease.requireLedgerPath(ledgerPath)` **after** obtaining the ledger
   lock, and re-check the **path's file key** at the open, replay and append
   boundaries. The full prefix is now replayed through **the same
   read/write `FileChannel` later used for fenced append**, so the
   verification and writer cannot refer to different opened descriptors.
   This still does **not** independently compare that descriptor's inode
   to the path file key or detect A→B→A rename swaps between checks;
   hostile same-UID filesystem mutation remains out of scope.
   The path file key is compared against the value captured
   by admission in the **same live lease**, not against a field allegedly
   persisted by the checkpoint. After a process restart, a new admission
   captures a new ephemeral key and independently verifies checkpoint-bound
   ledger bytes. File keys can be reused after unlink/recreation: a hostile
   same-UID filesystem writer remains outside the stated threat model.
4. Under the ledger lock, **compare the physical file length first**
   to the checkpoint's verified byte offset. Greater length is
   `LEDGER_REOPEN_TAIL_UNCONFIRMED` **even if every extra record is complete
   and checksum-valid**; shorter length is a checkpoint mismatch. Refuse
   before opening the writer in either case: do not seek past extra bytes,
   truncate them, infer acknowledgement, or offer a tolerate-valid-tail
   mode. Only at the exact expected length replay the **entire** existing
   ledger through the same open read/write descriptor subsequently used
   for append (format, framing, metadata allowlist, event order, hash chain and
   session/trace identity) and compare its verified sequence, head digest,
   full-file SHA-256 and byte offset against the checkpoint. A changed
   prefix refuses. Uncertain external effects require explicit operator
   reconciliation, not automatic replay.
5. Only after all evidence matches may the host-owned writer resume at the
   verified end with the exact prior sequence, digest, last event ID and
   session/trace identity. Retain the existing metadata/redaction policy,
   event/byte limits and v1 framing.

### Lock order and atomic publication

- **Admission (already implemented):** acquire and retain the OS
  `resume.lock`, then take the short `checkpoint.lock` while verifying
  checkpoint/ledger evidence; ledger inspection obtains an existing ledger
  `.lock` via nonblocking `tryLock` and releases it before admission
  completes. Never wait for a ledger lock while holding
  `checkpoint.lock`.
- **Reopen (implemented):** retain the same `resume.lock` through the live
  `SessionResumeLease`; reserve the canonical ledger-lock path **inside
  the JVM before opening any channel**, then take its existing
  `<ledger>.lock` with nonblocking `tryLock` and retain it for the writer
  lifetime. Full-ledger replay and byte/file-key comparison occur under
  that ledger lock, **without holding `checkpoint.lock` during the scan**.
  Read/verify the checkpoint's immutable expected evidence under a short
  checkpoint lock, releasing it before any full-ledger scan. Then, after
  replay, take a **second short checkpoint lock while still holding the
  ledger OS lock** to confirm the checkpoint head has not changed. This
  matches the existing ledger → checkpoint lock order used by fenced saves.
  Where a later checkpoint must be published, short
  `checkpoint.lock` acquisition follows the lease and live ledger-writer
  monitors. No new channel to a held lock inode is permitted.
- **Commit serialization (implemented):** while the OS resume and ledger locks
  remain held, acquire the Java monitors in the order
  `lease.publishIfCurrent { ledger-writer monitor → [short checkpoint.lock] }`.
  The fenced append API must keep **the complete append, `force(true)`,
  and successful acknowledgement within one
  `lease.publishIfCurrent { ... }` callback**, so the lease cannot be closed
  between a status check and fsync. Do **not** expose an unguarded
  `EventSink.append()` / `flush()` combination for reopened writers.
  `lease.assertCurrent()` by itself is only a status check, not a
  commit guard. Fenced checkpoint publication must retain the existing
  `saveFromOpenLedgerFenced` order:
  `lease.publishIfCurrent → ledger monitor → checkpoint.lock`.
- **Blocking trade-off:** local `FileChannel.force(true)` is unavoidable
  for durable acknowledgement and can incur disk latency while holding
  the lease monitor. No model execution, external tools, network I/O,
  arbitrary host callbacks or full-ledger replay may occur in the callback.
  Do not introduce another path that waits for a ledger lock while holding
  `checkpoint.lock`; the existing nonblocking inspection must fail
  promptly on a live writer rather than deadlock.
- **Admission-to-reopen gap:** admission releases its temporary ledger
  inspection lock before reopen obtains the long-lived one. Reopen must
  check the inode and replay **again under its own exclusive lock** against
  the admitted checkpoint. A competing appender, replacement, added record,
  missing lock or changed hash in that window must produce a typed refusal
  before a new event is acknowledged. No lock-file deletion or implicit
  recovery is permitted.
- **Retirement:** the trusted host **MUST** publish a matching
  `saveFromOpenLedgerFenced` checkpoint while writer and lease are open,
  close the writer, and **only then** retire the lease. A reopened writer
  tracks acknowledgements that are not yet covered by a successfully
  persisted checkpoint. Its `close()` always releases its file and
  OS locks, but refuses with `LEDGER_REOPEN_CHECKPOINT_REQUIRED` if a
  durable append remains uncheckpointed. Merely entering
  `withCommittedCheckpointAnchor` does **not** clear that flag; only a
  successfully published, verified checkpoint returned by
  `saveFromOpenLedgerFenced` does. Before persistence, that path verifies
  the exact **writer/lease reference identity**, journal anchor and
  **pre-execution snapshot shape/phase** against the verified history
  and writer's acknowledged phase. A stale `created` snapshot after a
  `created → accepted` event, or any nonzero attempt, non-ready child
  or pending effect, is rejected **before publishing** the next checkpoint.
  After successful durable persistence it performs only an
  infallible in-memory acknowledgement (no late rejection of a committed
  generation). This is a diagnostic, not a rollback:
  previously acknowledged ledger bytes remain and require operator
  reconciliation if the checkpoint is missing. Reversed cleanup is a
  host contract violation; `close()` deliberately does not acquire
  the lease monitor. An attempted stale append poisons the writer and
  takes precedence as `LEDGER_REOPEN_WRITER_POISONED`. If a prior fsynced
  event still lacks a checkpoint, `LEDGER_REOPEN_CHECKPOINT_REQUIRED`
  is also attached as a **suppressed exception** to the primary close
  failure, including revoked or unacknowledged close. Preserve primary
  failures across cleanup, and never acknowledge post-retirement writes.

### Reopened event scope and denial contract

Until #105 implements the additional side-effect and execution gates,
reopened writers and the admission gate use **one core phase-history
policy**, `PreExecutionPhasePolicy` from `core:session`. Snapshot
shape checks use `PreExecutionSnapshotPolicy` in the I/O adapter;
fenced appends additionally enforce `FencedGraphMetadataPolicy`.
Only `session.transition` and `checkpoint.saved` are permitted, and
**both** types allow at most the `graphId` metadata key. Recursive,
stage, receipt and execution markers on `checkpoint.saved` are refused
before fsync, as are non-root actor identities.
A newly appended `session.transition` MUST contain `transition`
exactly `{from:"created",to:"accepted"}` and its `from` must match
the last confirmed phase restored from the checkpoint and verified
history. No `running`, `succeeded`, terminal or other execution phase
may be published. Once that transition is acknowledged, another
transition is refused; `checkpoint.saved` cannot smuggle a
`transition` field. **The event type `checkpoint.saved` is only a
metadata observation, not proof that any immutable checkpoint generation
was published.** Only a verified checkpoint-store record can discharge
the writer's persistence obligation. The fenced append additionally binds
`metadata.graphId` (when present) to the graph root of the **admitted
checkpoint**, and refuses `parentGraphId` and `childGraphId` before physical
write, so the later checkpoint verifier cannot reject an already fsynced
event for a foreign graph. Such input gives `LEDGER_REOPEN_EVENT_REFUSED`
without poisoning or publishing bytes.
**Every** historical `session.transition` must contain the explicit
`created → accepted` edge, with no duplicate and no runtime/stage metadata.
A checkpoint in `accepted` requires **exactly one** such transition
in the verified ledger; a checkpoint in `created` requires **none**.
A missing `transition` field is **not** a legacy safety proof:
current `CorrelatedRecursiveSessionEvents` emits `CHILD_RESERVED` and
`CorrelatedPreparationSessionEvents` emits `CREATED`/`READY` using
`session.transition` without a phase edge. Those records are denied.
Both admission and reopened writer also require `status=completed`,
reject non-null recursive/child/tool actor identifiers, and reject
stage/child metadata on lifecycle transition events.
Admission is now intentionally stricter for explicitly recorded illegal
or inconsistent historical phase edges. Invalid new events refuse with `LEDGER_REOPEN_EVENT_REFUSED` without
poisoning the writer. Historical mismatch refuses admission or reopen.
**Compatibility change from the previously merged #113 admission API:**
the type-only allowance is replaced by strict pre-execution evidence.
Explicit illegal or duplicate lifecycle edges, transition events
**without** `transition`, recursive/child/tool actor markers, and
`accepted` snapshots without the matching explicit edge now refuse
`RESUME_UNSAFE_BOUNDARY`. Valid `created` snapshots may have only
`checkpoint.saved` metadata events, with no lifecycle edge.
Execution/receipt publication requires future explicit authorization (#105).

The host-only reopen implementation uses the following typed refusal codes.
Existing codes are reused; the new `LEDGER_REOPEN_*` and `LEDGER_NOT_FENCED`
codes distinguish operational refusals from corrupted evidence:

| Condition | Thrown type / code |
| --- | --- |
| Lease retired, stale fence or lost current-generation authority | `SessionResumeUnconfirmed / RESUME_FENCE_STALE_WRITER` (reuse) |
| Ledger inode/path differs from the key pinned by the lease | `SessionResumeUnconfirmed / RESUME_LEDGER_PATH_CHANGED` (reuse) |
| Competing live ledger writer | `SessionLedgerUnconfirmed / LEDGER_LEASE_HELD` (reuse) |
| Short checkpoint lock currently busy during initial or final evidence read | `SessionLedgerUnconfirmed / LEDGER_REOPEN_BUSY` (new), caused by `CHECKPOINT_LEASE_HELD`; retryable contention, **not** a corruption finding |
| Explicit workspace root differs from checkpoint host context | `SessionLedgerUnconfirmed / LEDGER_REOPEN_CONTEXT_MISMATCH` (new), before any write |
| Calling `appendFenced` on an ordinary non-reopened writer | `SessionLedgerUnconfirmed / LEDGER_NOT_FENCED` (new); ordinary writer remains healthy |
| Missing ledger lock file | `SessionLedgerUnconfirmed / LEDGER_LOCK_MISSING` (reuse) |
| Well-formed **or** malformed bytes after checkpoint offset | `SessionLedgerUnconfirmed / LEDGER_REOPEN_TAIL_UNCONFIRMED` (new) |
| Verified prefix shorter than checkpoint, or sequence/head/full-file hash/offset or session/trace mismatch | `SessionLedgerUnconfirmed / LEDGER_REOPEN_CHECKPOINT_MISMATCH` (new); retain underlying parser refusal as cause |
| Corrupt version, framing, hash, event metadata or identity | `LEDGER_REOPEN_CHECKPOINT_MISMATCH` with the underlying `LEDGER_*` parser code as cause |
| Historical ledger contains a missing/illegal phase edge, recursive or stage event markers, or accepted checkpoint unsupported by a real edge | Admission: `SessionResumeUnconfirmed / RESUME_UNSAFE_BOUNDARY`; reopen with a previously trusted lease: `SessionLedgerUnconfirmed / LEDGER_REOPEN_CHECKPOINT_MISMATCH` |
| Historical ledger includes any event other than `session.transition` or `checkpoint.saved` | `SessionLedgerUnconfirmed / LEDGER_REOPEN_CHECKPOINT_MISMATCH` (before reopening) |
| Forbidden new event type, illegal lifecycle edge, missing/wrong `from`, or `checkpoint.saved` carrying a transition (no bytes written; writer remains healthy) | `SessionLedgerUnconfirmed / LEDGER_REOPEN_EVENT_REFUSED` (new; distinct from admission denial) |
| Calling ordinary `append` or `flush` on a reopened writer | `SessionLedgerUnconfirmed / LEDGER_FENCED_API_REQUIRED` (use `appendFenced`) |
| `saveFromOpenLedgerFenced` refuses a stale lease, fence, or mismatched session/trace/policy before publication | `SessionResumeUnconfirmed` (for example `RESUME_FENCE_CONTEXT_CHANGED`); no checkpoint publication ambiguity is latched before temp-file writes |
| `saveFromOpenLedgerFenced` fails checkpoint-store validation or publication | `CheckpointUnconfirmed` (for example `CHECKPOINT_WRITE_UNCONFIRMED` when physical publication may have begun); preserve the precise code and inspect/reconcile before retrying an ambiguous write |
| 100,000-event cumulative limit for both ordinary and reopened writers | `SessionLedgerUnconfirmed / LEDGER_EVENT_LIMIT_EXCEEDED` (new) |
| 32 MiB whole-file or 16 KiB per-event byte limit for either writer | `SessionLedgerUnconfirmed / LEDGER_RECORD_SIZE_INVALID` (reuse) |
| Reopen prefix already exceeds the 4 MiB live-checkpoint cap, or next fenced frame would cross it | `SessionLedgerUnconfirmed / LEDGER_REOPEN_CHECKPOINT_CAPACITY` (new); refuse before any write, never poison |
| Acknowledged fenced append not covered by a successfully persisted matching checkpoint at writer close | `SessionLedgerUnconfirmed / LEDGER_REOPEN_CHECKPOINT_REQUIRED` (new); close releases locks but does not truncate |
| Physical append/fdatasync uncertainty | `LEDGER_WRITE_UNCONFIRMED` / `LEDGER_SYNC_UNCONFIRMED`; close reports `LEDGER_UNACKNOWLEDGED_APPEND` |
| Stale/revoked writer with no pending physical write | Further append and close report `SessionLedgerUnconfirmed / LEDGER_REOPEN_WRITER_POISONED`; no unacknowledged-byte claim |
| Non-cooperating foreign append changes file length while reopened writer is live | `SessionLedgerUnconfirmed / LEDGER_REOPEN_CHECKPOINT_MISMATCH` on append; subsequent `close()` reports `LEDGER_REOPEN_WRITER_POISONED` without acknowledging the foreign bytes |

The foreign-append check compares the channel's actual file size to its
committed write cursor before appending. It detects a non-cooperating append,
but does **not** make advisory locks secure against a hostile same-UID actor
who can rewrite bytes without changing file length. That attacker remains
outside the trusted-private-directory threat model.

No failure can lead to truncation, replay of external effects or false
successful acknowledgement. Tests must assert the exact code **and**
that no additional trusted event was published.

### Concrete writer API and cumulative limits

`SessionCheckpointStore.admittedCheckpointForReopen()` reads the pinned
checkpoint chain under a short checkpoint lock **without** reacquiring
the ledger lock. The ledger itself is then replayed under the reopened
writer's long-lived OS lock, restoring the exact sequence, previous event
ID, session/trace identity, SHA-256 chain digest and write offset.
For fenced saves, `withCommittedCheckpointAnchor` also replays through
this **same open writer descriptor**, restoring its append position in
a `finally` block; ordinary CREATE_NEW checkpointing retains the
separate path-based inspection.

`appendFenced(event)` enforces allowed event types before writing bytes,
then performs frame write and `force(true)` inside a single
`lease.publishIfCurrent { synchronized(writer) { ... } }` callback.
Plain `EventSink.append()` and `flush()` are disabled on this writer.
`appendFenced` must be followed by a matching
`saveFromOpenLedgerFenced` before releasing writer/lease authority.
A forced and acknowledged ledger event with no subsequent checkpoint
leaves an extra tail relative to the checkpoint. The reopened writer
tracks this pending checkpoint obligation from each successful fsync
acknowledgement; `saveFromOpenLedgerFenced` clears it **only after
successful durable checkpoint publication and verification of the
matching anchor**. `withCommittedCheckpointAnchor` alone cannot clear
it, nor can a failed save. `close()` releases all resources but reports
`LEDGER_REOPEN_CHECKPOINT_REQUIRED` immediately when an acknowledged
tail is still uncovered. Restart then **refuses**
`CHECKPOINT_JOURNAL_MISMATCH` and requires operator reconciliation;
**no automatic truncate, replay or promotion** is supported. This is a
deliberate current **recovery-policy** limitation, not an assertion that
metadata-only tail adoption is physically impossible. Verified hashes alone
cannot establish acknowledgement or side-effect absence, and
`checkpoint.saved` is not authoritative publication evidence. A separate,
generation-fenced, independently reviewed adoption protocol is proposed
in [#117](https://github.com/YuriiSokolenko/recursive-coder/issues/117);
#114 intentionally refuses to infer safety from a well-formed tail.
After a checkpoint is published, its new
SHA differs from the one pinned by the current
`SessionResumeLease`; another `reopenForAppend` under that same lease
refuses with `LEDGER_REOPEN_CHECKPOINT_MISMATCH` (cause
`CHECKPOINT_REOPEN_UNCONFIRMED`). A new admission, with fresh fence
generation CAS, is required to reopen again.
A local policy refusal is represented by a **private typed sentinel**
that becomes `SessionLedgerUnconfirmed / LEDGER_REOPEN_EVENT_REFUSED` at the public API boundary.
It does not poison the writer, so `close()` remains clean and a later
valid pre-execution event can still commit. Pure frame validation errors
before I/O are separately recognized as non-poisoning input refusals.
All other exceptions from lease/authority checks and unexpected callbacks
conservatively revoke the writer **regardless of their public error code**. A lost/stale lease poisons
further publication, but `close()` reports `LEDGER_REOPEN_WRITER_POISONED`
rather than pretending bytes were unacknowledged when none were written.
Uncertain writes/forces are different: they poison the writer **and**
make `close()` report `LEDGER_UNACKNOWLEDGED_APPEND`. The internal
`appendFencedWithFault` seam exists only for deterministic crash-boundary
integration tests and must not be wired to an application/model/tool caller.
Both test-only fault seams require explicit Kotlin
`@OptIn(LedgerTestFaultInjection::class)`; that compile-time marker is
not a JVM security boundary. Production code and model tools must never
expose them.

The **cumulative**, not per-reopen, limits are 100,000 records and 32 MiB
(including all previously replayed frames), enforced for **both** ordinary
`CREATE_NEW` and reopened writers. **Additionally, fenced reopened
writers must remain checkpointable under the 4 MiB live-checkpoint cap.**
Reopen refuses an already larger verified checkpoint-bound prefix, even
though the original closed-ledger writer was allowed to create it. Each
fenced append computes its entire new frame size before any bytes are
written and refuses crossing the 4 MiB cap without poisoning the
writer; equality at exactly 4 MiB is permitted. The previously permissive ordinary
writer now refuses a 100,001st event as `LEDGER_EVENT_LIMIT_EXCEEDED`;
this is intentional so its output can never exceed the reader's replay cap.
The count and byte boundary are tested through the shared deterministic
capacity guard without 100,000 individual fsync calls. The separate 4 MiB
live-checkpoint limit still applies to `withCommittedCheckpointAnchor`.

### Acceptance and documentation gate

Offline Linux `@Tag("integration")` tests must cover successful
create/append/force/close → pre-execution checkpoint → lease admission →
reopen/append/force → exact continuation of sequence and hash chain;
independent-JVM same-generation **admission** races (the loser is denied
before reopen), plus separately tested independent-JVM reopened ledger-lock
contention; stale callbacks and lease retirement;
inode replacement; wrong policy, identity, offset or digest; competing
writers; complete unacknowledged tails; and injected faults before/after
acquire, verification, append, fsync, acknowledgement and close.

A Linux-only test also runs a **separate JVM** that acquires the resume
lease and reopened ledger, then is actually terminated via
`Process.destroyForcibly()` (SIGKILL) at three named boundaries:
`record-written` (before force), `forced-before-ack`, and
`after-close` (writer closed, checkpoint not published).
A **new JVM** must verify the full but uncheckpointed tail and refuse
a fresh generation-2 admission with `CHECKPOINT_JOURNAL_MISMATCH`,
leaving the checkpoint/fence and ledger bytes unchanged. These tests are
not power-loss or disk-controller durability tests; they establish
process-death lease release and fail-closed admission after JVM termination.

The opening paragraph here and the descriptions in `FENCED_RESUME.md`
and `SESSION_CHECKPOINTS.md` must now describe this opt-in capability;
none should imply general recovery or post-execution restart.
CI, multi-JVM race/fault tests and independent security review remain
merge gates. Full production runtime/receipt fencing and execution after
external effects remain separate work in #104/#105.
