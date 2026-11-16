# Host-owned atomic session checkpoints (issue #103)

This is a metadata-only, opt-in persistence adapter in :infra:journal.
A verified snapshot alone cannot authorize a model request, tool invocation,
write, retry or replay. Issue #104 adds a separate pre-execution,
generation-fenced admission API (see [FENCED_RESUME.md](FENCED_RESUME.md)),
but automatic restart and external-effect reconciliation remain out of scope
(#105). The default CLI and Session Protocol v1 do not consume these files.

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
`wires` and `terminalWires`. Both sets derive from the internal `SessionPhase`
enum and cannot be mutated by consumers. Any spelling or membership change
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
