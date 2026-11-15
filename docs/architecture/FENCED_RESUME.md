# Exclusive, generation-fenced resume admission (issue #104)

This is an opt-in host-only API in `:infra:journal`. It does **not** wire
automatic restart into the CLI, enable replay of tool/model calls, or grant
permissions by trusting a checkpoint. Issue #105 must reconcile uncertain
external operations before broader resume boundaries become available.

## Boundary and trusted inputs

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
   CREATE_NEW temp, file force(true), ATOMIC_MOVE, parent directory force(true).
   All published fences and their chain are verified on every checkpoint read.
   Before fence publication, an immutable SHA-256 chained
   `<ledger-name>.resume-anchor-NNNNNNNNNNNN.rca` is published and fsynced in
   the ledger's separate directory. A shortened fence chain with a surviving
   external anchor fails closed; if a crash leaves an anchor without its fence,
   no admission occurs before operator reconciliation. This detects partial
   checkpoint-directory rollback, not a same-UID rollback of BOTH directories.
   Orphan `.resume-pending-*.tmp` files are deleted under both admission locks.
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
   Do not run model requests or blocking network/tool
   operations inside the publication callback. Keep the OS lease until all
   authorized publication is complete. `close()` invalidates the token.
   Closing without completing terminal publication is not a successful commit.

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
Existing `CheckpointUnconfirmed` codes still report baseline/policy, journal,
snapshot and path failures. Neither class is a successful resume token.

The existing ledger is CREATE_NEW-only and cannot reopen its closed file for
append. `saveFromOpenLedgerFenced` currently guards the call but does not
create a resumed ledger writer. End-to-end recovery requires an explicitly
fenced ledger-reopen path and production runtime/receipt integrations.

Legacy `inspect`/save paths adapt fence storage errors to the existing
`CheckpointUnconfirmed` contract. A live stale callback remains a typed
`SessionResumeUnconfirmed`. A crash after publishing a sidecar anchor but
before its fence is deliberately unrecoverable without operator action.

This implementation cannot fence **unrelated existing** workspace writes,
receipts or model/tool operations that do not accept `SessionResumeLease`.
Those host integration points must explicitly guard their own commits before
claiming end-to-end generation fencing. The checkpoint API alone guarantees
fenced checkpoint publication and prevents a stale checkpoint writer from
overwriting a newer generation. No default CLI behavior changes.

Tests under the integration tier cover pre-execution admission, fenced/unfenced
save, stale callbacks, generation CAS, changes to HEAD/policy/frozen inputs,
unsafe execution boundaries, corrupt fence files, independent process lock
races, external anchor rollback, stale live-writer callbacks, orphan cleanup
and OS lease release after force-kill. All tests run offline.
