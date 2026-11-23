# Metadata-only crash-tail adoption — proposed protocol (issue #117)

**Status: DESIGN ONLY; NOT ACCEPTED; NO PRODUCTION RECOVERY AUTHORITY.**
This document specifies a possible *new*, opt-in, versioned protocol for a
single pre-execution metadata tail. It deliberately does **not** change
`SessionResumeAdmission`, `DurableSessionEventLedger`, the checkpoint store,
the CLI, or the currently mandatory operator-reconciliation behavior. An
independently reviewed implementation, host evidence producer and real crash
tests are prerequisites to enabling any part of the proposal.

## 1. Decision and existing security boundary

The crash window in #114 is **not** claimed to be physically unavoidable.
A hash-valid RCLDG1 suffix may be eligible for a future recovery protocol,
but its hash proves only the physical bytes. It does not prove that the
original `force(true)` returned, that an append was acknowledged, that
checkpoint generation C2 was published, or that no model/tool/effect work
started. No current API establishes all of these facts.

**Decision: stop emitting `checkpoint.saved` as a journal record for new
metadata-adoptable epochs.** An authoritative checkpoint generation must be
verified from the checkpoint store, never inferred from a Session Protocol
event. Previously written `checkpoint.saved` events remain valid only
*inside an already verified checkpoint-bound historical prefix* where the
current policy permits them. They are **never eligible for tail adoption**.
Do not rewrite older ledgers or change `PreExecutionPhasePolicy` to make
legacy tails recoverable.

The proposed `meta-tail/v1` admission policy is a **closed list** of one
additional exact frame: a root-only, completed
`session.transition {from:"created",to:"accepted"}` with at most the
existing `graphId` metadata and no execution actors, timing, tool, child,
stage, effect, receipt or custom fields. The accepted source is an
independently verified `created` checkpoint C1 with zero task attempts,
zero usage, no accepted artifacts or pending effects and only ready children.
The complete history through C1 must pass the existing
`PreExecutionPhasePolicy`; its physical ledger offset and both digests must
match C1. No other transition, duplicate event, multi-record suffix,
`checkpoint.saved`, `accepted → running`, effect sidecar mutation,
unrecognized version, or future event taxonomy can become eligible by
default. This is deliberately narrower than general metadata recovery.

The newer trusted `accepted → running` boundary is **explicitly excluded**.
Even if it contains no actual effects, it is a one-way execution-denied
marker under the current #104 contract, not permission for a new lease to
resume execution. The #105 effect contracts, including a MACed record or
a checkpoint effect-head pin, do **not** themselves prove absence of work
and cannot be used as a substitute for the evidence below.

## 2. Authenticated evidence required *before* the old writer appends

This design is **not retroactive**. A bare existing #114 C1/N fence plus a
complete valid RCLDG1 tail has no independent no-execution evidence and
continues to require an operator. The new host must provision all of the
following **before** attempting the candidate append under original lease N.

1. **A committed immutable `meta-tail/v1` fence extension** (a distinct,
   versioned `RCFEN2`/anchor design, not a permissive parser change to
   `RCFEN1`) binds C1 SHA-256, original resume generation N, ledger path
   identity, last trusted byte offset, old chain head, session/trace/policy
   and authority digests, the exact next canonical event *payload hash*,
   the exact event sequence/causation identity and the closed policy ID.
   The original generation fence and independent ledger-parent anchor
   must durably commit this extension **before** the append. The old
   `RCFEN1` format is ineligible; no post-crash self-attested upgrade.
   The record and its anchor must be independently authenticated and
   chained to the already verified C1, not supplied by the candidate
   writer or inferred from its tail.
2. **An independently verifiable execution-free epoch.** A trusted host
   admission controller, outside model-provided state and outside the
   RCLDG1 event stream, must record that this exact (session, generation,
   C1, policy, event hash) epoch was *metadata-only* with model, tools,
   workspace writes, child execution, Git CAS, effect-intent publication
   and result receipts **unavailable by construction**. This is a
   mediated capability boundary, not an application boolean, event type,
   "no entries found" scan or assertion from the dead JVM. The controller
   must durably issue an authenticated epoch identity before append.
3. **A new-generation no-start/barrier witness.** After the old owner
   has terminated or irrevocably relinquished its lease, the independent
   controller must authenticate the **complete** effect/work admission
   history for that epoch, exclude every in-flight/unfenced work request,
   attest there was *no dispatched or unacknowledged model/tool/child/
   side-effect work*, and bind its monotonic barrier to the proposed
   successor generation N+1, exact old fence/anchor and candidate event
   hash. A higher numeric fence, absent `Started` record, zero usage,
   checkpoint phase, MAC by itself or a human yes/no checkbox is **not**
   this witness. The controller must reject rollback and seal/verify this
   proof independently of the session journal and checkpoint files.

The last two artifacts require a **real, reviewed host-side trusted execution
controller with mandatory mediation and a retained signing/MAC key or
equivalent root of trust**. They do not exist in the current runtime.
Until the controller and its evidence verification are implemented and
independently assessed, the policy's only correct outcome is
`NOT_ELIGIBLE / RECONCILIATION_REQUIRED`.

A trusted host verifier must receive evidence from its own authoritative
store, validate the entire epoch history and publication ordering, and
return a nonforgeable capability scoped to this exact candidate. Do not
offer a public API accepting `safe=true`, arbitrary caller MAC bytes or
an unverified ledger-derived "proof". The witness is a *no-work* proof,
not proof that the old append returned to its caller. The new owner may
persist the previously unacknowledged bytes **only because they represent
the exact precommitted, execution-free event**; it may never replay a
host action or mint a historical receipt to fill the gap.

## 3. Preflight, publication order and crash states

Terminology: C1 = last verified checkpoint, original lease/fence = N,
exact suffix = T, candidate successor lease/fence = N+1, proposed next
checkpoint = C2 (`C1.sequence + 1`). Fence generation and checkpoint
sequence are **different** counters.

The future implementation must be an explicit host-only entry point,
separate from `admit`, `reopenForAppend`, `saveFenced` and
`saveFromOpenLedgerFenced`. The existing path-based save intentionally
refuses to bless bytes that lack a live acknowledged writer. Do not relax it
to implement recovery.

**Read-only eligibility gate (no mutation on rejection):**

1. Validate paths and supported local filesystem types, expected host
   context and immutable controller evidence *without creating a lock*.
   Existing `resume.lock`, `checkpoint.lock` and
   `<ledger>.lock` must already exist. If they are absent, unsafe, busy
   or their inode/path identities differ, refuse with no modifications.
2. Take the pre-existing exclusive `resume.lock` with nonblocking
   `tryLock` and the existing ledger OS lock, also nonblocking; keep both
   through any later commit. Acquire `checkpoint.lock` **briefly** to
   verify C1, the whole checkpoint/fence/independent-anchor chain, and the
   original versioned commitment, then release it. Never block for the
   ledger lock while holding `checkpoint.lock`. Do not create N+1.
3. Under the **same open ledger file descriptor** subsequently used for
   physical force, verify the complete RCLDG1 prefix and *exactly one*
   complete following frame, canonical serialized payload bytes,
   framing/UTF-8, sequence, causal/event identities, session/trace/root
   graph, full hash chain and exact precommitted event digest. Compare C1
   offset/head/file digest against the prefix independently from the
   complete T head/digest/length. Recheck file key/path, size and the
   immutable C1/fence/anchor heads under another short
   `checkpoint.lock` while retaining the ledger lock.
4. Verify the independent execution-free witness and its successor
   barrier *against this exact physical T*. Verify no effect sidecar/head
   drift and that no other process still owns generation N. Reject every
   uncertainty (missing proof, bad signature, stale epoch, invalid shape,
   short/torn/overlong tail, complete extra tail, valid but nonprecommitted
   frame, MAC mismatch, changed file key, busy lock, no free fence slot).
   **A rejected candidate is only an observation**, not a failed recovery
   transaction: checkpoint, ledger bytes, lock files, anchors, checksum and
   fence generation must be byte-for-byte unchanged. No truncate, repair,
   deletion, implicit retry or acceptance on error.

**Commit path (only after every gate succeeded):**

5. With the original ledger descriptor and exclusive locks still held,
   call `FileChannel.force(true)` on the physical ledger. A return from
   **this** force is the new owner's durability barrier; do **not** claim
   the original writer's force or acknowledgement happened. After force,
   verify the exact C1-prefix/T-byte boundaries and original heads again.
   Any error is `UNCONFIRMED` and grants no authority.
6. Serialize under the existing resume OS lock and future equivalent of
   `lease.publishIfCurrent { writer monitor → short checkpoint.lock }`.
   Under `checkpoint.lock`, compare latest checkpoint == C1, latest
   fence == N and independent anchor/head == the preflight values; require
   the authenticated barrier target == N+1. Publish an **immutable,
   generation-CAS** successor anchor then N+1 fence via the existing
   create-new temp → file force → hard-link publication → parent directory
   force model, extended to bind the tail hash, witness hash and
   `meta-tail/v1` purpose. Never overwrite N or repair a prepared record
   without a distinct operator-reviewed prepared-admission protocol.
   Keep the OS locks and the new token current through C2.
7. Derive C2 solely from the verified on-disk C1: the only logical change
   is `phase=accepted`, the next checkpoint sequence/previous hash, and
   physical journal sequence/head/file hash/offset extended by exactly T.
   Prove the complete post-tail history is accepted by the **closed v1
   adoption policy**, and that all attempts/children/artifacts/usage/
   pending effects and all non-journal bindings are unchanged. Publish C2
   with `expectedPreviousSha256=C1.sha256`, the current N+1 generation
   and same live ledger descriptor; use normal immutable checkpoint temp
   force → publication CAS → directory force. No intermediate
   `checkpoint.saved` append is permitted. Read back C2's exact digest,
   file/ledger evidence and fence/anchor head *before* reporting success.
8. Acknowledge **only** `AdoptedMetadataCheckpoint(C2, N+1)` to the trusted
   host, never a receipt for the old append or any external action.
   Release ledger lock/writer before retiring the N+1 resume lease.
   The separately reviewed normal pre-execution admission still needs a
   **fresh** generation N+2 before any later host work, and must not
   treat this metadata checkpoint as permission for execution.

Use existing nonblocking OS lock acquisition and its short lock durations.
No external callbacks, model, tool or network calls are allowed inside
publication monitors. A blocked `force(true)` may delay lease retirement;
it must never be moved outside the current-lease guard merely to avoid
latency. Re-audit JVM monitor and OS lock order before implementation:
the current adapter has additional same-process reservations and nested
ledger/lease checks; the proposed outline is **not** permission to
introduce an inverse locking edge or a second channel to a held lock inode.

**Crash outcome rules:** A crash before step 6 can leave an untouched
or newly forced T but **no adoption**: the existing #114 policy still
rejects it. Once publication of the N+1 anchor/fence or the C2 temporary
file begins, outcomes are potentially ambiguous even if a call throws.
A crash after N+1 fence but before C2 is **reconciliation-required**,
not a reason for an N+2 process to blindly readopt T. A crash after C2
rename may already have committed C2 despite no caller acknowledgement;
inspect the immutable on-disk heads under operator control. Do not
infer absence of publication from an exception, reuse N+1, skip a
generation or issue a stale receipt. Keep immutable prepared artifacts
for operator diagnosis. No automatic retry of an uncertain publication.

This protocol also needs explicit compatibility of its authenticated
version-2 fence/anchor with legacy RCFEN1 history and the current
256-fence bound. Unknown, mismatched or downgraded versions refuse.
Checkpoint publication is **not** a substitute for external-work
reconciliation and cannot authorize runtime replay.

## 4. Deterministic acceptance matrix (future implementation)

The acceptance suite must drive the *real* host verifier and physical
store, not just a pure boolean policy. Use separate JVM processes,
a cooperating verifier/controller, bounded handshakes at exact fault
boundaries, and no sleeps-as-synchronization. For each negative
read-only gate compare complete checkpoint/ledger/fence/anchor/lock
byte snapshots and file names (including digest and generation) before
and after; prove nothing was silently truncated or repaired.

| Fault / race | Required result |
| --- | --- |
| Kill old JVM **before record write** | No physical suffix; existing C1 remains; no adoption or N+1 |
| Kill after complete or torn write **before force** | Torn/partial refuses unchanged; complete exact precommitted T may qualify only with independent no-work barrier; no claim of old acknowledgement |
| Kill after `force(true)` **before ack** | Same decision as byte-identical complete T; old acknowledgement never inferred |
| Kill after writer close **before C2** | Exact precommitted T plus authenticated no-work barrier can adopt once; no source-writer token is required |
| Complete **hash-valid but noncommitted/extra** frame | Refuse before any new force, fence or checkpoint; bytes and heads unchanged |
| Corrupt hash, wrong identity, torn/overlong tail | Refuse unchanged; do not guess crash vs tampering |
| Old `checkpoint.saved` or `accepted → running` suffix | Always refuse automatic adoption even with a valid hash |
| Valid T but absent/forged/stale/controller-self-supplied witness | Refuse unchanged; event type, no-sidecar state or hash never substitute |
| N-lease still live / two independent new JVMs racing | Neither can bypass the live lease; with N retired at most one N+1 CAS and one C2; loser leaves all committed artifacts untouched |
| Old/N callback blocked until after N+1 CAS or retirement | No callback may append or publish a stale receipt/checkpoint; no check-then-publish window |
| Failure before successor anchor / after anchor / after fence / after C2 rename | Pre-publication denial unchanged; every ambiguous publication quarantined; immutable records remain available |
| Same-UID path swap, unsupported filesystem, missing lock | Refuse before modifying bytes or publishing a generation |
| Successful isolated recovery, fresh JVM read and re-admission | C2 contains exactly one adopted edge; C1/C2 chain, physical ledger and N+1 anchor verify; no double adoption, no permission to execute; later read-only admission requires a new generation |

Tests must distinguish **SIGKILL / process crash** from power-loss
durability. The latter requires separate filesystem/storage acceptance
and cannot be claimed from kill tests or `force(true)` alone. Re-run
the standard fenced-reopen and admission failure tests to confirm old
#114/RCFEN1 behavior stays fail-closed and source event policies have
not been weakened.

## 5. Implementation gates and non-goals

This is a proposal **requiring independent security design review**.
To implement safely, the project must add, in separately reviewable PRs:

- Mandatory-mediation host controller, authenticated no-work epoch and
  successor barrier, anti-rollback root and fault-injected conformance.
  The current #104/#105 artifacts do **not** provide this proof.
- A new versioned, closed persisted fence/anchor commitment; explicit
  incompatibility with existing uncommitted v1 tails; read-only
  `inspectCandidate` that never creates evidence; a single-use,
  lease-guarded `publishAdoptedCheckpoint` distinct from ordinary saves.
- Deterministic real-JVM kill/race/force/CAS acceptance above, independent
  review of exact-head code and CI on the actual integration suite.

Only **after** that independent review and all tests pass may the
maintainer update `DURABLE_EVENT_LEDGER.md` and `FENCED_RESUME.md`
to describe an implemented opt-in path. Until then, both documents'
current operator-reconciliation rule is authoritative. Keep #117 open;
do not expose new runtime execution, effect replay, receipt recovery,
legacy-tail migration or automatic restart. #104/#105 continue to own
the separate execution and effect-authority gates.
