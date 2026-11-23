# Metadata-only crash-tail adoption — proposed protocol (issue #117)

**Status: DESIGN ONLY; NOT ACCEPTED; NO PRODUCTION RECOVERY AUTHORITY.**
This is the **single canonical #117 proposal** for a *new*, opt-in,
versioned protocol covering one pre-execution metadata tail. An earlier
competing proposal (separate permit plus RCMTA1 CLAIM/COMMITTED; see
"Alternatives considered") is not selected: do not implement both
protocols or treat them as simultaneously valid recovery authorities.
This document deliberately does **not** change `SessionResumeAdmission`, `DurableSessionEventLedger`, the checkpoint store,
the CLI, or the currently mandatory operator-reconciliation behavior. An
independently reviewed implementation, host evidence producer and real crash
tests are prerequisites to enabling any part of the proposal.

## Glossary

| Term | Meaning |
| --- | --- |
| C1 / C2 | Last independently verified checkpoint / successor checkpoint, not fence generations |
| T | Exactly one frozen, canonical `created → accepted` ledger frame beyond C1 |
| N / N+1 / N+2 | Original writer's fenced generation / single-use adoption generation / later ordinary admission generation |
| RCLDG1 | Existing hash-chained session event ledger; a valid frame alone does not prove acknowledgement |
| RCFEN1 / RCFEN2 | Existing fence format (never adoption-eligible) / proposed **new** version committing T before append |
| Epoch | Host-controlled pre-execution interval bound to an exact C1, fence N, and event hash |
| Barrier | Host-authenticated successor-generation boundary excluding all old and in-flight execution |
| Witness | Independently checked, authenticated evidence proving the epoch was execution-free; not a journal event or operator assertion |

## 1. Decision and existing security boundary

The crash window in #114 is **not** claimed to be physically unavoidable.
A hash-valid RCLDG1 suffix may be eligible for a future recovery protocol,
but its hash proves only the physical bytes. It does not prove that the
original `force(true)` returned, that an append was acknowledged, that
checkpoint generation C2 was published, or that no model/tool/effect work
started. No current API establishes all of these facts.

**Decision on #117's `checkpoint.saved` acceptance item: remove these
appends from all future `meta-tail/v1` writer paths; do not bind them to
a new host-authenticated receipt.** There is currently **no production
producer** of `checkpoint.saved`: it remains on the ledger type allowlist
and `PreExecutionPhasePolicy.step` accepts it in verified, historical,
checkpoint-covered prefixes. That historical read compatibility is kept;
removing the type globally from `PreExecutionPhasePolicy` would invalidate
previously verified histories. In the proposed new, **version-scoped**
RCFEN2 epoch, both the producer and append verifier MUST reject
`checkpoint.saved` as an emitted event; no future implementation may
re-enable it without a separately authenticated, reviewed publication
receipt design. This is a **design decision**, not an assertion that the
current implementation already enforces the new-epoch prohibition.

An authoritative checkpoint is proved only by independently verified
checkpoint-store generation evidence. A journal `checkpoint.saved`
event is neither a C2 publication receipt nor an acknowledgement of
`force(true)`. Existing ledgers are not rewritten and the legacy
phase-history predicate is not relaxed.

The proposed `meta-tail/v1` admission policy is a **closed list** of one
additional exact frame: a root-only, `status="completed"`,
`session.transition {from:"created",to:"accepted"}` with at most the
root-matching `metadata.graphId`, and no execution actors, optional
`timing`, tool, child, stage, effect, receipt or custom fields. Require
the exact Session Protocol v1 event shape, including frozen `timestamp`,
sequence, deterministic event ID, causation ID and actor identity;
unknown fields and any taxonomy expansion fail closed.
The accepted source is an independently verified `created` checkpoint C1 satisfying
`PreExecutionSnapshotPolicy.safe`, with `taskState` either `"created"`
or `"ready"`, zero task attempts, every `hostAssertedUsage` entry
having `certainty="known"` and `units=0` (unknown usage refuses),
no accepted artifacts or pending effects and only ready children. An adopted C2 must
set `taskState="ready"`, as the existing durable handoff does; accepting
a C1 with `"created"` therefore requires the unique `created → ready`
task-state change described in section 3, step 7.
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

### Alternatives considered and practicality gate

**Use the existing #104 `accepted → running` order as proof.** The
intended invariant says execution starts only *after* a durably
checkpointed `accepted → running` boundary. If that were a
mandatory, exclusively mediated host gate for **every** runtime/model,
tool, child, receipt and effect path (including crash, cancellation and
stale-lease callbacks), a clean history stopping at `created → accepted`
would be strong negative evidence. **It is not yet that gate.** The
current `publishTrustedAcceptedRunning` is a separate opt-in
metadata-only marker, intentionally disconnected from normal
`DefaultRunSessionUseCase.invoke()` and from side-effect authority.
Normal runtime paths and independent host callbacks are not proven to
require it before dispatch. `C2` absence therefore does not rule out
unrecorded or unacknowledged work. To revisit this alternative,
[#104](https://github.com/YuriiSokolenko/recursive-coder/issues/104)
must make the running transition an unavoidable, generation-fenced
execution gate; [#105](https://github.com/YuriiSokolenko/recursive-coder/issues/105)
must independently authenticate effect intents/receipts and stale
callback exclusion. Neither a valid event type nor the current v1
phase-policy scan establishes these properties today.

**Two-phase event intent with operator-confirmed adoption.** A simpler
design could publish a new immutable fence N with the exact T hash,
then append T, then try C2, while the operator independently investigates
the interrupted run and explicitly approves an exact, one-time
C1/T/N reconciliation under new-lease CAS. This could be a useful
**operator-only** recovery path and does not require unattended
no-work classification. However, the precommitted hash proves intent
to write T, not absence of unrecorded model/tool/effect execution.
An operator approval is an external trust decision, not a reusable
cryptographic no-work witness; this must be documented and audited
as manual reconciliation, never silently upgraded to automatic
adoption. It still needs a new, separately reviewed crash-safe
publication API and tests. It cannot retrofit RCFEN1.

**Separate `MetadataPermitV1` + `RCMTA1 CLAIM/COMMITTED`.** A competing
draft would publish a separate MACed permit, then an immutable CLAIM,
N+1 and only afterward the recovery `force(true)`, followed by a separate
COMMITTED authority. **Not selected for meta-tail/v1**: this adds a second
precommit authority beyond the source N fence, changes force/fence ordering,
and creates extra persistent ambiguity states. This version selects one
precommitted RCFEN2 source fence, authenticated independent no-work evidence,
force-before-N+1, CAS-once publication, and C2 provenance/receipt verification.
Do not create RCMTA1 records or accept them as proof. A future change to
these choices requires a distinct version and independent security review.

**Leave operator reconciliation mandatory.** This costs no new
trusted control plane and is the valid default. Given the complexity
of proving a no-work epoch for a single metadata transition, the
maintainer may choose this outcome and close #117 as
**not planned / operator reconciliation by design** after independent
review, without implementing RCFEN2 adoption. This document
does **not** make that product/security decision for the maintainer.

The automatic option below is deliberately a conditional future
design, **not** a commitment to build a general host controller just
to eliminate this one crash gap. If its proof cannot be produced,
no safe automatic adoption is enabled.

## 2. Authenticated evidence required *before* the old writer appends

This design is **not retroactive**. A bare existing #114 C1/N fence plus a
complete valid RCLDG1 tail has no independent no-execution evidence and
continues to require an operator. The new host must provision all of the
following **before** attempting the candidate append under original lease N.

1. **A precommitted, immutable generation-N `RCFEN2` fence**, not a
   post-fence extension. The trusted host freezes the **entire canonical
   payload for T before admission to N**. The N fence record itself
   commits the exact payload SHA-256, session/trace/policy and authority
   digests, C1 SHA-256, ledger path identity, old ledger offset/head and
   the next sequence, event ID and causation identity under the closed
   `meta-tail/v1` policy. The independent ledger-parent N anchor
   cryptographically binds that exact RCFEN2 fence and is published and
   durably synced **before** the N fence is committed; both must verify
   before an append is eligible. There is **no separate immutable
   post-N extension** and no new fence generation just to precommit T.
   A crash between the N anchor and N fence publications is an
   *incomplete admission*, handled by the existing prepared-admission
   reconciliation model (extended and independently reviewed for
   RCFEN2), not by tail adoption. An already committed fence N
   cannot be retrofitted with T. The older `RCFEN1` record, including
   any legacy `checkpoint.saved` event, remains ineligible.

   `SessionEventJournal.frame` always emits a `timestamp` and
   correlation/causation/actor/redaction fields. These are **frozen
   before the N fence is published** and covered by the payload
   commitment, even though the timestamp will precede the physical
   append; it is **event-creation time, not fsync or acknowledgement
   time**. Reject attempts to refresh it after admission.
   The disallowed execution `timing` object is a different,
   optional field; the required `timestamp` is not forbidden.
2. **An independently verifiable execution-free epoch.** A trusted host
   admission controller, outside model-provided state and outside the
   RCLDG1 event stream, must record that this exact (session, generation,
   C1, policy, event hash) epoch was *metadata-only* with model, tools,
   workspace writes, child execution, Git CAS, effect-intent publication
   and result receipts **unavailable by construction**. This is a
   mediated capability boundary, not an application boolean, event type,
   "no entries found" scan or assertion from the dead JVM. The controller
   must durably issue an authenticated epoch identity before append.
   A proposed `NoStartWindowV1` must positively attest prior work is
   drained, no model/tool/child/receipt/result operation is in flight and
   no execution capability can be issued until the gate is retired.
   Every external entry point, including callbacks, must first durably
   publish a domain-separated authenticated `STARTED` record under the
   monotonic gate *before* crossing the external boundary. The verifier
   must validate its complete continuous history, generation/high-water
   mark, revocation state and crash-window coverage. An absent
   `STARTED` entry in an incomplete log is never no-start proof.
3. **A new-generation no-start/barrier witness.** After the old owner
   has terminated or irrevocably relinquished its lease, the independent
   controller must authenticate the **complete** effect/work admission
   history for that epoch, exclude every in-flight/unfenced work request,
   attest there was *no dispatched or unacknowledged model/tool/child/
   side-effect work*, and bind its monotonic barrier to the proposed
   successor generation N+1, exact old fence/anchor and candidate event
   hash. A higher numeric fence, absent `STARTED` record, zero usage,
   checkpoint phase, MAC by itself or a human yes/no checkbox is **not**
   this witness. The controller must reject rollback and seal/verify this
   proof independently of the session journal and checkpoint files.

The last two artifacts require a **real, reviewed host-side trusted execution
controller with mandatory mediation and a retained signing/MAC key or
equivalent root of trust**. The independent no-start key/MAC domain
and rollback-resistant head must not be substitutable with public
RCLDG1/checkpoint hashes. A MAC attests issuance, not truth: every adapter
must be demonstrably mediated, and a complete authenticated RCEFX1
history plus host non-start witness must be checked where applicable.
An empty effect journal alone is insufficient. These safeguards do
not exist in the current runtime.
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
   the ledger sibling `<ledger-name>.lock` (for example
   `events.ledger.lock`, as used by `DurableSessionEventLedger`)
   must already exist. If an RCEFX1 effect sidecar is present or
   provisioned, its separate sibling `<effects-name>.lock` must
   also be inspected during the no-work/effect drift check; a missing,
   busy, changed or inaccessible effect lock is **not** absence-of-work
   proof. Determine whether an effect sidecar was ever provisioned
   from independent host authority, not from absence of files.
   Snapshot and recheck identity (path, file key/inode, no symlink)
   of the ledger data and pre-existing ledger/resume/checkpoint/effect
   lock files, plus all provisioned effect data files and heads.
   Acquire relevant OS locks nonblockingly and verify effect-head/
   no-start high-water continuity both before and after replay.
   Missing, busy, swapped or changed identities refuse. The authority
   directories must have trusted ownership/permissions on approved
   persistent local filesystems. Never create a lock during read-only
   eligibility; missing or unsafe lock/data-file identity refuses
   without any file modifications.
2. Take the pre-existing exclusive `resume.lock` with nonblocking
   `tryLock` and the existing ledger OS lock, also nonblocking; keep both
   through any later commit. Acquire `checkpoint.lock` **briefly** to
   verify C1, the whole checkpoint/fence/independent-anchor chain, and the
   original versioned commitment, then release it. Never block for the
   ledger lock while holding `checkpoint.lock`. Do not create N+1.
3. While holding the ledger's **separate OS lock-file channel**,
   use one continuously open RCLDG1 **data** `FileChannel` for replay,
   later force, and post-force verification:
   - Replay the complete C1 prefix and exactly one following complete
     candidate frame T. Validate framing, UTF-8, canonical JSON,
     sequence, causation/event identities, session/trace/root graph,
     full hash chain and the precommitted exact event digest.
   - Compare the prefix's offset/head/file digest independently with
     C1; then calculate T's complete head/digest/length. The payload
     hash is fixed before N, including `timestamp`; do not manufacture
     or re-emit any field on restart.
   - Recheck file key/path/length and immutable C1/N/anchor heads under
     a **second short** `checkpoint.lock` while the ledger OS lock is
     retained. The data file has its **own** channel; do not confuse
     that with the separate `<ledger-name>.lock` channel.
4. Authenticate the independent no-work proof and exact successor
   barrier against the bytes and generation of physical T:
   - Validate the witness's epoch, original N fence/anchor, T digest,
     host signing/key provenance and N+1 successor target against the
     host's complete execution-admission history; reject stale,
     incomplete, rolled-back or caller-invented evidence.
   - Verify the effect sidecar/head has not drifted, including its
     `<effects-name>.lock` when applicable. Confirm original N
     ownership has ended and no unacknowledged work or stale callback
     retains execution/publication permission.
   - Check `N+2 <= MAX_FENCES (256)` **before any mutation**:
     one adoption needs N+1 and a meaningful later normal admission
     needs N+2. A session with fewer than two remaining fence slots
     is not eligible, even if N+1 alone would fit.
   - Refuse any uncertainty: missing proof, wrong signature,
     changed path or head, unsafe/missing/busy lock, truncated or
     extra frame, noncommitted but hash-valid frame, unsupported
     version or insufficient fence capacity.
     **Rejection is a read-only observation:** checkpoint,
     ledger bytes, lock files, anchors, checksums and fence generation
     remain byte-for-byte unchanged. No truncate, cleanup, force,
     repair, delete or implicit retry is allowed.

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
7. Derive C2 solely from the verified on-disk C1: allow exactly
   `phase="created" → "accepted"` and a task-state transition from
   `"created"` to `"ready"` or from `"ready"` to `"ready"`, matching
   `DurableCreatedAcceptedHandoff.publish`. Only the next checkpoint
   sequence/previous hash and exact physical journal sequence/head/
   file SHA/offset for T may otherwise change, **plus authenticated
   adoption provenance**. This requires a *new, canonical checkpoint
   schema version* binding C1 SHA, original RCFEN2 N fence/anchor SHA,
   exact T frame digest/offset, no-start barrier/epoch digest,
   successor N+1 generation and its fence SHA. Neither ordinary
   `saveFenced` nor existing v1/v2/v3 checkpoint codecs may invent
   or silently reinterpret this provenance. **Conversely, a pre-#117
   reader encountering the new C2 schema version must fail closed with
   `CHECKPOINT_VERSION_UNSUPPORTED` (or an equivalent typed
   unsupported-version refusal) before admission, rather than parse
   it as an ordinary accepted checkpoint, ignore the adoption fields,
   or downgrade it to an older version.** The current
   `SessionCheckpointCodec.decode` accepts only versions 1–3 and
   already refuses unknown versions; the future schema must preserve
   that fail-closed compatibility boundary. All future read-only
   admissions must verify provenance against independently observed
   physical C2, ledger, source and recovery fence heads; until the
   new schema and reader are implemented, C2 adoption is unsupported.
   Prove the complete post-tail history is accepted by the **closed v1
   adoption policy**, and that all attempts/children/artifacts/usage/
   pending effects and all non-journal bindings are unchanged. Publish C2
   with `expectedPreviousSha256=C1.sha256`, the current N+1 generation
   and same live ledger descriptor; use normal immutable checkpoint temp
   force → publication CAS → directory force. No intermediate
   `checkpoint.saved` append is permitted. Read back C2's exact digest,
   file/ledger evidence and fence/anchor head *before* reporting success.
8. Acknowledge **only** `AdoptedMetadataCheckpoint(C2, N+1)` to the trusted
   host after independent verification of durable C2, its adoption
   provenance and N+1 fence. This is a *new recovery receipt*,
   never the original append/force ACK or an external-effect receipt.
   Subsequent readers may recognize the already committed C2 only;
   prepared N+1 or ambiguous C2 never permits a retry.
   Release ledger lock/writer before retiring the N+1 resume lease.
   The separately reviewed normal pre-execution admission still needs a
   **fresh** generation N+2 before any later host work, and must not
   treat this metadata checkpoint as permission for execution. Keep the
   no-start gate closed until a separate, explicitly authorized, freshly
   fenced host boundary retires it; C2 alone does not activate execution.

Use existing nonblocking OS lock acquisition and its short lock durations.
The proposal uses **one ledger data `FileChannel`** for read/force and
**one distinct sibling `<ledger-name>.lock` channel** to own its OS
file lock; this is intentional. Never open a second channel to an
**already held lock file inode** (notably `resume.lock` or
`<ledger-name>.lock`) because JVM/POSIX lock ownership can be
released unexpectedly. No external callbacks, model, tool or network
calls are allowed inside publication monitors. A blocked `force(true)`
may delay lease retirement;
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

**Fence budget:** `MAX_FENCES = 256` covers all admitted
generations. The original RCFEN2 admission N consumes one ordinary
generation (also needed without recovery). If adoption is required,
it consumes **one additional N+1**; a future regular admission
consumes **one more N+2**. Thus an adopted session needs **two
free generations after N**. If `N >= 255` (or a gap/unknown
fence version makes the capacity unverifiable), the read-only
eligibility gate refuses with `RESUME_FENCE_LIMIT` before N+1,
without altering any ledger, fence, anchor or checkpoint bytes.
Use a **fresh session identity**, new private store and independently
reverified inputs/workspace/authority; do not reset or prune
existing generations. No in-place fence rotation is proposed.

This protocol needs explicit compatibility of its authenticated
version-2 fence/anchor with legacy RCFEN1 history. Unknown,
mismatched or downgraded versions refuse.
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
| Same-UID path swap, unsupported filesystem, missing ledger/effect lock | Refuse before modifying bytes or publishing a generation |
| Fence budget N=254 vs N=255/256 | N=254 may adopt at N+1=255 and subsequently admit at N+2=256; N>=255 must refuse unchanged before publication with `RESUME_FENCE_LIMIT`; never rotate/evict old fences |
| Canonical event payload with altered precommitted timestamp or optional `timing` object | Refuse unchanged; timestamp is frozen before admission, not a durability clock |
| C1 taskState `created` or `ready` | Both adopt to C2 `ready` with no other task-state mutations; C1 `running` refuses before force |
| C1 `hostAssertedUsage` contains `certainty="unknown"` (even with other known-zero entries) | Reject before force, fence or C2; no ledger or authority mutation |
| Pre-#117 reader opens a valid new-version adopted C2 | `CHECKPOINT_VERSION_UNSUPPORTED` (or equivalent typed unknown-version refusal) before admission; no provenance stripping or downgrade |
| Missing/busy/swapped ledger/effect lock; missing provisioned effect data; changed inode/head | Read-only eligibility refuses without creating files, force, fence or checkpoint |
| Incomplete/revoked no-start gate; missing `STARTED` record due to history gap; active late callback | Refuse before force; zero journal entries are not positive non-start proof |
| Forged/missing C2 provenance, mixed fence versions or changed source digest | Future reader refuses; no silent v1/v2/v3 adoption |
| Successful isolated recovery, fresh JVM read and re-admission | C2 contains exactly one adopted edge and verified new-schema provenance; C1/C2 chain, physical ledger and N+1 anchor verify; no double adoption, no permission to execute; later read-only admission requires N+2 |

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
- A new versioned, closed RCFEN2 fence/anchor commitment (not a parallel
  `MetadataPermitV1` / `RCMTA1` record); incompatibility with old
  uncommitted v1 tails; read-only `inspectCandidate` that creates no
  evidence; and single-use guarded `publishAdoptedCheckpoint` distinct
  from ordinary saves.
- A new canonical C2 checkpoint schema with authenticated adoption
  provenance and explicit fail-closed read-side verification.
- Deterministic real-JVM kill/race/force/CAS acceptance above, independent
  review of exact-head code and CI on the actual integration suite.

The future `meta-tail/v1` producer and append verifier must also
enforce the explicit `checkpoint.saved` **no-producer** decision for
new epochs, while the old `PreExecutionPhasePolicy` remains
compatible with fully checkpoint-covered legacy histories. Do not
implement a second host-authenticated `checkpoint.saved` receipt
without a separate reviewed design.

Only **after** that independent review and all tests pass may the
maintainer update `DURABLE_EVENT_LEDGER.md` and `FENCED_RESUME.md`
to describe an implemented opt-in path. Until then, both documents'
current operator-reconciliation rule is authoritative. Keep #117 open;
do not expose new runtime execution, effect replay, receipt recovery,
legacy-tail migration or automatic restart. #104/#105 continue to own
the separate execution and effect-authority gates.
