# Durable Session Event Ledger (issue #102)

This is an opt-in **host-only event sink**, not a replacement for the Session Protocol v1 JSONL export and not a checkpoint/resume implementation.

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

Creation is **CREATE_NEW**; no existing file is automatically truncated or reopened for append. A process-wide canonical-path reservation prevents opening a second `.lock` channel in the same JVM, which can otherwise release the live owner's POSIX `fcntl` lease. An OS file lock excludes cooperating *other* processes. The lock file remains in place after close. A hostile same-UID process, hard-link tricks and external filesystem changes are beyond an advisory lock's authority, so this ledger requires a trusted private directory.

`inspect()` is read-only: it opens an **existing** `.lock` file without `CREATE`, refuses a missing lock with `LEDGER_LOCK_MISSING`, and never creates new files. It returns a fully verified prefix plus last sequence, chain digest and file SHA-256, or a typed refusal. A torn record, invalid hash, unsupported metadata, changed identity, or live writer refuses inspection. A **complete record removed at a record boundary is not detectable by the chain alone**. The opt-in checkpoint layer (#103) binds sequence, head digest, file digest and verified byte offset independently; see [host-owned checkpoints](SESSION_CHECKPOINTS.md). It still does not grant restart authority. A complete record that survived a crash might have been written before the writer received its fsync acknowledgement. Therefore an intact prefix **does not prove side effects were accepted** and must never authorize automatic model or tool replay. Uncertain outcomes stay quarantined for #104–#105 reconciliation.

The current CLI uses its original `JsonlEventSink` unless a trusted composition root intentionally supplies `DurableSessionEventLedger.factory(workspaceRoot)`; no Session Protocol v1 files or default read-only behavior change. The workspace root is host-supplied and is not accepted from the model.

The ledger imposes a 16 KiB per-event bound, a 32 MiB file bound and a 100,000-event replay bound. Neither retention nor archival policy is supplied here; operators must keep private logs until the session's checkpoint/receipt reconciliation is complete.

## Verification

`DurableSessionEventLedgerTest` is an `@Tag("integration")` suite exercising fsynced emission through the real `SessionEventJournal`, strict replay, corruption/torn tails, identity/causation/sequence rehashing, unsupported metadata, unsafe file/lock paths, and same-JVM plus cross-process lease exclusion. Full JVM tests, architecture checks and Linux sandbox acceptance remain mandatory CI gates.
