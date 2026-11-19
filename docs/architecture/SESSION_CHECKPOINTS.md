# Host-owned atomic session checkpoints (issue #103)

This is a metadata-only, opt-in persistence adapter in :infra:journal.
A verified snapshot alone cannot authorize a model request, tool invocation,
write, retry or replay. Issue #104 adds a separate pre-execution,
generation-fenced admission API (see [FENCED_RESUME.md](FENCED_RESUME.md)),
but automatic restart and external-effect reconciliation remain out of scope
(#105). The [effect intent and receipt contracts](EFFECT_INTENTS.md) define
pure classifications. A bounded host-only checkpoint v2 extension now stores an
exact RCEFX1 effect-head pin; this is physical evidence only, not independently
verified external effect completion. The default CLI and Session Protocol v1 do not consume these files.

## Effect journal head checkpoints (opt-in v2)

Legacy checkpoints remain canonical v1 with exactly the original field set.
Only the dedicated `saveEffectHeadFenced` API may publish a v2 checkpoint,
which adds `effectJournalHead`: session ID, issuing fence generation,
admitted checkpoint SHA, tool authority SHA, normalized absolute sidecar
path SHA, last effect sequence and the host MAC head. This single atomic,
SHA-chained checkpoint generation binds all fields together; arbitrary
callers cannot provide one of these pins through ordinary `saveFenced`.
Both v1 and v2 decoders reject unknown fields and noncanonical payloads.

The head is obtained from the exact live RCEFX1 journal under the existing
lease monitor. The journal's genesis is pin-able at sequence zero. Every
v2 checkpoint, including an empty genesis pin, requires operator
reconciliation and is **not eligible for another resume admission**.
`inspectEffectHeadBound` checks the latest verified checkpoint, physical
path binding, and complete journal HMAC prefix against the recorded pin.

**Durability is not the same as an independent rollback-resistant head.**
The current fence anchors the previously admitted checkpoint, not each
later checkpoint version. A coherent rollback of newer checkpoint versions
and matching RCEFX1 tail still needs an independent monotonic authority
outside that rollback domain. No external effects may be enabled until
that guard and the pre-execution Started pin barrier are implemented.
See [EFFECT_INTENTS.md](EFFECT_INTENTS.md).

## Trusted inputs and snapshot format

The host supplies a private checkpoint directory **outside the Git checkout**,
the closed durable session ledger from #102, and a CheckpointHostContext
constructed from trusted workspace / Git / policy inspection. The checkpoint
does not accept host facts from the model or derive authority from its own bytes.

The stable v1 schema records the session ID/trace, phase, task and attempt,
child graph parent relationships/states, accepted artifact IDs and SHA-256
digests, **host-asserted** known or explicitly **unknown** model/tool usage,
outstanding side-effect IDs/correlation/state, and a checkpoint generation with the previous
snapshot digest. It binds the policy hash, SHA-256 of the canonical real workspace
path (the raw absolute path is not written), pinned Git HEAD/branch/baseline hash,
root graph ID/generation, immutable frozen-input digest, and the ledger's verified
sequence, head digest, full-file digest and exact verified byte offset.

All keys and types are fixed. Codec output is canonical, byte-for-byte verified
on reopen, bounded at 512 KiB, and disallows text/prompt/tool-output maps.
IDs are limited to host identifiers; accepted artifacts must be matched to
completed, hash-matching events in the durable ledger. Child parents must form a
rooted acyclic graph at the trusted root GraphId, independently from Task.id.
Correlated pending side effects must reference actual ledger events.
The wire field `hostAssertedUsage` and Kotlin `HostAssertedCheckpointUsage`
make provenance explicit. **"Known" means a value asserted by a trusted host,
not independently verified against the ledger**: the ledger does not currently
provide reliable scope-by-scope consumption receipts. No future reconciliation,
quota or resume policy may treat this number as ledger-confirmed without new
trusted independent evidence. A known claim requires a nonnegative value;
**unknown usage has a null value, never an inferred zero**. Model/tool events
require corresponding explicit host claims, but this verifies existence only,
not the numerical units or scope attribution.
Unmatched tool.start events, processed in journal order and matched by execution
identity (or correlation fallback), require outstanding side-effect records
instead of being silently treated as completed. A `policy.denied`
or `session.error` event does not automatically settle a `tool.started`
record: it remains pending until independently reconciled. Terminal
session phases are exactly `SessionPhase` wire values, and terminal success
cannot coexist with pending effects or unknown consumption.

`SessionPhaseVocabulary` is the intentionally supported, read-only public
adapter surface for the **currently emitted** v1 session phase wire spellings:
`wires`, `terminalWires`, `createdWire` and `acceptedWire`. The sets and
both individual pre-execution spellings derive from the internal
`SessionPhase` enum; callers cannot mutate the exported sets. Any spelling or membership change
requires a compatibility review and an explicit update to drift-guard tests.
The internal enum, transition policy, and future checkpoint/resume phases are
**not** part of this adapter contract; no new protocol states are implied.

Each immutable file is named with a 12-digit checkpoint sequence and the
suffix .rcp. Its binary frame is:

| Field | Representation |
| --- | --- |
| Magic | 7 ASCII bytes RCCHK1 followed by newline |
| Length | Big-endian signed 32-bit canonical JSON byte count (1..524288) |
| Checksum | SHA-256 of canonical UTF-8 JSON payload, 32 bytes |
| Payload | Canonical UTF-8 JSON, strictly schema-checked |

The JSON also includes the previous checkpoint's checksum. Generations are
contiguous from one. Reopening verifies **every** published snapshot,
the cross-snapshot chain, session and binding continuity, journal offsets and
ordering, before comparing the latest snapshot against the full trusted ledger.

## Restricted fenced ledger reopen (#114)

A current `SessionResumeLease` issued by #104 is required before reopening
a closed durable ledger for append. The trusted-host-only
`SessionCheckpointStore.admittedCheckpointForReopen()` reads the **latest
checkpoint chain** under the short checkpoint lock without taking a live
ledger lock. It compares that verified checkpoint's SHA-256 against the
digest pinned by the live lease and independently checks the supplied
host context and absence of unresolved side effects. After taking the
ledger OS lock and verifying the full file, reopen takes a **second short
checkpoint lock** to reread the latest checkpoint SHA. An intervening
C1 → C2 checkpoint publication (even with identical ledger bytes) refuses
with `LEDGER_REOPEN_CHECKPOINT_MISMATCH` and retains the underlying
`CHECKPOINT_REOPEN_UNCONFIRMED` cause. This is consistent with the
ledger → checkpoint lock order of live fenced checkpoint publication.

`DurableSessionEventLedger.reopenForAppend()` then takes the exclusive
ledger OS lock and verifies the **full existing ledger through the same
open read/write file channel later used by its writer** against the
checkpoint's persisted event sequence, head digest, whole-file digest and
exact byte offset, and the session/trace identity. The expected inode key
comes from the **same live lease**, not the checkpoint wire format; no
checkpoint or fence format change is required. Any extra bytes, even a
complete valid event beyond the checkpoint offset, refuse reopen instead
of being treated as acknowledged.

A reopened writer and the admission gate use the same
`PreExecutionLedgerPolicy`: only `session.transition` for the
`created → accepted` edge with matching prior phase, plus
`checkpoint.saved` without a transition field. Every historical
`session.transition` must carry that explicit phase edge; an
`accepted` checkpoint without it is denied. Current recursive
`CHILD_RESERVED` and preparation `CREATED`/`READY` events use
the same event type **without** the explicit edge, so are not admitted. A
`checkpoint.saved` ledger event is not evidence of successful checkpoint
publication; only the independently verified immutable checkpoint-store
generation confirms it. New `running`,
terminal, repeated or inconsistent transitions refuse
`SessionLedgerUnconfirmed / LEDGER_REOPEN_EVENT_REFUSED` without writing bytes
(the `RESUME_UNSAFE_BOUNDARY` code belongs to resume admission). Publication through
`appendFenced(event)` performs append, local fsync and acknowledgement
atomically under `lease.publishIfCurrent`. It cannot be attached as an unrestricted
`EventSink`. The older path-based `saveFenced` API is also constrained to
the shared pre-execution snapshot and phase-history policy. It can
publish metadata for an **unchanged checkpoint-covered ledger** only:
the full verified sequence, digest, file hash and offset must match the
preceding checkpoint. An uncovered tail is
`CHECKPOINT_FENCED_LEDGER_ADVANCED`; an unsafe phase/snapshot is
`CHECKPOINT_RESUME_UNSAFE_BOUNDARY`. Neither may publish a new
generation. **Compatibility change since #113:** path-based fenced
saves previously had a broader admissible scope; a live lease is now
insufficient without independently verified pre-execution history and
already-checkpoint-covered bytes. Path-based saving cannot promote an
uncertain tail left by `LEDGER_REOPEN_CHECKPOINT_REQUIRED` or
`LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED`; reconcile explicitly or start
a new session rather than retrying publication after close.

The live fenced checkpoint path remains
`saveFromOpenLedgerFenced` **for an already reopened lease-bound writer
only** (`CHECKPOINT_WRITER_NOT_FENCED` for a CREATE_NEW writer), preserving
`lease → writer monitor → checkpoint.lock` order and the existing
4 MiB live-checkpoint bound. A reopened writer must **also** refuse
any pre-existing prefix larger than 4 MiB, or any additional fenced
frame that would cross the cap, before new bytes are written
(`LEDGER_REOPEN_CHECKPOINT_CAPACITY`). The general
CREATE_NEW ledger's 100,000-event and 32 MiB limits remain unchanged.

**Publication order matters:** after each acknowledged fenced append, the
host must save a matching checkpoint using `saveFromOpenLedgerFenced`
while both the writer and lease are still open, close the writer,
then retire the lease. Only a **successfully persisted and
checkpoint-bound** `saveFromOpenLedgerFenced` clears the reopened
writer's `acknowledgedSinceCheckpoint` state; its writer/lease
identity, snapshot/anchor correspondence and the **same pre-execution
snapshot/phase rules used at admission** are checked **before**
durable `persist`. A saved `accepted` phase must match the verified
`created → accepted` event and the writer's committed phase; an old
`created` snapshot or execution-shaped snapshot cannot discharge
checkpoint coverage. The writer's open channel is reused for the
save-time committed-anchor replay. After successful persistence the in-memory
acknowledgement cannot reject a generation that is already committed. `withCommittedCheckpointAnchor`
alone and failed saves leave it set. If a durable append remains uncovered,
writer `close()` **releases resources but throws**
`LEDGER_REOPEN_CHECKPOINT_REQUIRED`. If fenced checkpoint publication
failed at a possibly durable point, the writer instead reports
`LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED`; neither state is automatically
retryable. If closure also fails from a stale lease, poisoned writer or
uncertain append, that primary error is retained and the outstanding
checkpoint diagnostic is **suppressed**. A later admission fails closed
with `CHECKPOINT_JOURNAL_MISMATCH` and operator reconciliation
or a **new session** is required, not truncation.
Once a newer checkpoint exists, the same lease's admitted checkpoint
SHA is stale; reopening again requires a new generation-fenced admission.
Calling `lease.close()` before `writer.close()` violates the host
cleanup contract and can alter the latter's reported status.

The production `SessionRuntime` currently emits workspace audit
`tool.started` / `tool.completed` and `llm.capabilities` before its
first `accepted` transition. A physical ledger made from that real
stream is therefore **not admissible** to this pre-execution-only
policy. An opt-in host producer and a checkpoint before RUNNING belong
to #104/#105, not automatic model/tool event whitelist expansion.

This feature allows **pre-execution event continuation only**, not
automatic model/tool execution, receipt re-publication or arbitrary
crash-resume. Those paths remain gated by #104/#105.

## Durability and crash behavior

A closed durable ledger is inspected before saving. While the ledger writer is
still open, the host can instead call saveFromOpenLedger: it verifies the durable
prefix under the writer's synchronized append/flush monitor and holds that
monitor until the checkpoint is published. **All appends/flushes stall during
this operation**, including replay, validation and file/directory fsync. To
limit growth, the ledger checks a single 4 MiB live-checkpoint limit
**before replay**, under the append monitor. A larger prefix is refused as
`CHECKPOINT_LIVE_LEDGER_TOO_LARGE` (ledger cause
`LEDGER_CHECKPOINT_SIZE_EXCEEDED`) without publishing a generation or
poisoning the writer. This limit is distinct from the ledger's normal 32 MiB
capacity. A store refuses more than 256 published generations; fsync latency
has no strict real-time bound. Any pending or poisoned ledger append is refused.
External read-only inspect still requires the writer to close.

The writer takes a persistent adjacent directory lock (process-local reservation
plus exclusive OS file lock), validates the existing chain and expected previous
digest, writes a unique .pending-UUID.tmp file with CREATE_NEW, calls
FileChannel.force(true), atomically renames to the never-before-used generation
path, then syncs the **parent directory**. Both the published checksum and
confirmed directory sync are needed to acknowledge the save. No published
generation is truncated, replaced, repaired or opportunistically skipped.
A write that may have passed rename but not directory sync is unconfirmed; a
caller must inspect instead of retrying the old generation blindly.
For a **reopened fenced writer**, a failed publication at or after
temporary-file write that might have published the generation latches
`checkpointPublicationUnconfirmed`. `close()` reports
`LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED`; by contrast
`LEDGER_REOPEN_CHECKPOINT_REQUIRED` means no fenced save was
acknowledged and no ambiguous save result was recorded. Neither code
proves a checkpoint generation is physically absent. Preserve the
original `CHECKPOINT_WRITE_UNCONFIRMED`, then halt. An operator may
inspect the immutable generation chain, journal anchor, exact ledger
sequence/head/file hash/offset, and fence before manual reconciliation.
An existing C2 is not evidence that the interrupted directory fsync
was acknowledged. No blind retry or automatic resume is permitted.

Read-only inspect opens only a pre-existing checkpoint.lock and creates nothing.
It ignores orphan .pending-UUID.tmp files and rejects corrupt, truncated,
noncanonical, unknown-format, missing-generation, unexpected, symlinked and
incompatible published files. It also refuses a changed policy, workspace,
Git baseline, graph generation, frozen inputs, ledger head/sequence/size and
unmatched artifact ownership or correlation evidence. A removed complete ledger tail can
be detected only if it crosses the independently persisted snapshot anchor.

VerifiedSessionCheckpoint.reconciliationRequired reports unknown usage or
unconfirmed/pending side effects, even though inspecting those records is
permitted. resumeAllowed is always false; the legacy requireResumeAdmission
still denies. The separate SessionResumeAdmission API from #104 acquires an
OS lease and increments an immutable fence only for a verified pre-execution
boundary. Once fenced, checkpoint publication requires the current lease, with a [separately anchored fence head](FENCED_RESUME.md) beside the ledger. Legacy checkpoint API refusals retain the typed `CheckpointUnconfirmed` contract.
An unconfirmed ledger tail or an unacknowledged tool result is **not** promoted
to accepted history. These APIs are not wired into a production resume command.

The checkpoint directory must be Linux/POSIX, with numeric `unix:uid`
matching the JVM **effective UID** (the second Uid field of Linux
`/proc/self/status`), and mode `0700` (owner read/write/execute only).
The mutable `user.name` JVM property and the real UID under `sudo`/`su`
are not authorization inputs. The effective UID is the checkpoint owner's
required policy identity; Linux actually performs filesystem access checks
using the filesystem UID (fourth procfs field), normally the same but potentially
different after `setfsuid`. If procfs or Unix owner attributes are unavailable,
ambiguous or malformed, save and inspect fail closed with
`CHECKPOINT_UNSAFE_DIRECTORY`. This is a fail-closed deployment
precondition, not an attempt to chmod or repair unsafe storage.
An exclusive advisory lock and NOFOLLOW checks protect against
cooperating processes and accidental path substitution, not a complete defense
against a hostile same-UID actor controlling the private directory. The host
must provision that directory securely and retain the ledger and snapshots
together until manual reconciliation/retention policy is established.

## Verification

SessionCheckpointCodecUnitTest (@Tag("unit")) exercises strict canonical
codec, redaction, digest/graph/usage policy. SessionCheckpointStoreIntegrationTest
(@Tag("integration")) uses a real ledger and filesystem to exercise durable
reopen, simulated interruptions after temp sync and after rename, stale
generations, corruption, baseline and journal drift, hostile paths,
effective-UID impersonation via closed/live save and read-only inspect, exact
live-ledger size boundaries (4 MiB - 1, 4 MiB and above), verified post-refusal
ledger replay, and graph/correlation/artifact mismatch. The integration suite is
Linux/procfs-only and skips on unsupported developer platforms. Full Gradle/CI,
including the Linux sandbox and Session Protocol compatibility jobs, must pass
before merge.
