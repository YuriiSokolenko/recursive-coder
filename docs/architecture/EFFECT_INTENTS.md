# Host-authenticated effect intent and receipt contracts (#105, first slice)

**Scope:** The merged first slice is a pure `:core:contracts` state machine.
The next isolated host-only slice supplies a physically durable, HMAC-authenticated
effect record file under `:infra:journal`. Neither slice executes external
tools or Git CAS, produces usable runtime resume permission, supplies an
independent external-state verifier, or enables CLI/replay wiring.

## Contract and strict publication order

A host-generated `EffectIdentity` pins session ID, operation ID, fence generation,
effect kind and digest of the exact frozen proposed input. Operation IDs must be
unique for a session across *all* generations and effect kinds; a retry must get
a new ID and pass a new independent admission decision.

**Generation roles are different:** the `fenceGeneration` inside `EffectIdentity`
is the *historical issuing generation* N, while `currentFenceGeneration`
is the currently held trusted recovery lease (possibly N+1 or later).
`reconcile` permits classification when `0 < N <= currentFenceGeneration`;
it refuses records issued in the future. The verifier MUST authenticate the
historical issuing fence against the immutable fence chain and verify current
lease ownership. Passing a guessed current generation is not proof of holding
a lease, and no classification ever grants authority to act. Reusing the
same operation ID under N+1 remains a conflicting identity and is quarantined.

A future trusted adapter must force and authenticate records in order:
`Intent` (before any physical start) -> `Started` (before the external call)
-> `Committed` (only after confirmed outcome), or `Unconfirmed` upon
ambiguous/failed observation. For uncertain Git/CAS responses, timeouts,
exceptions or crash tails, the host must not guess a commit from a local diff
or infer non-execution from a missing `Started` record.

`EffectJournalEntry` carries the original session-event sequence and an
opaque host MAC. `EffectJournalView.entries` is an **ordered projection of
ALL effect records**, not the complete session journal: non-effect records
can create legal gaps in the projected sequence and the final effect may
precede `lastSequence`. Its `headSha256` and `lastSequence` pin the
complete authoritative session-journal prefix. The pure policy checks
positive strictly increasing sequence numbers within that range.

**The verifier must independently fetch the complete durable journal prefix**
using the trusted host's ledger context, authenticate every event and fence,
check full-prefix continuity and exact head/sequence, and reconstruct an
**exact, complete** effect projection to compare with `view.entries`.
An arbitrary filtered subset is invalid, even if each included record has
a plausible MAC. Matching SHA-256 strings without actual authenticated
prefix and projection verification are not sufficient. The physical host
journal below is a **separate, provisional record stream**; it does not yet
construct an `EffectJournalView` from the checkpoint-bound Session Protocol
event ledger or implement `HostEffectEvidenceVerifier`.

## Recovery classification

- **ConfirmedCommitted**: exactly one `Intent -> Started -> Committed`,
  authenticated and ordered under the exact generation and operation ID,
  with the observed external result independently checked.
- **ProvenNotStarted**: exactly one `Intent`, with an authenticated head-bound
  `EffectNotStartedEvidence` proving **both** a successor execution barrier
  and actual non-execution. The policy requires `N < barrierFenceGeneration <=
  currentFenceGeneration` for issuing generation N. The verifier receives
  the current generation and MUST authenticate the barrier's effective fence,
  its binding to the full host-MACed proof and the absence of any in-flight
  or unfenced execution. Neither a missing `Started` nor a higher numeric
  generation alone proves non-execution. Same-generation no-start proofs are
  conservatively quarantined until a separate safe protocol is established.
- **Quarantined**: everything else, including a future or invalid recovery
  generation (`EFFECT_FUTURE_OR_INVALID_GENERATION`), a non-successor/
  future no-start barrier (`EFFECT_NONSTART_BARRIER_INVALID`),
  reused operation ID, duplicate entries, a recorded
  `EffectRecord.Unconfirmed`, conflicting proofs, missing/unverifiable state,
  invalid sequence or verifier failure.

All outcomes are **informational**; none is permission to call a tool, repeat
a write, accept a parent stage/checkpoint, advance a lease or publish a result.
An adapter must independently re-check host authority and fencing before any
state change. A later reconciliation of `Quarantined` requires a distinct,
operator-reviewed protocol; this v1 pure policy never silently upgrades it.

Even a fully authenticated projection containing **no intent** for the
operation stays `EFFECT_INTENT_MISSING`, not proof of absence. The policy
requires a recorded `Intent` and an independently verified head-bound
non-start certificate before returning `ProvenNotStarted`.

The pure projection has **no arbitrary 10,000-entry rejection threshold**.
Large valid histories may still require paginated/streamed authenticated
verification and explicit host resource budgeting; those responsibilities
belong to a future adapter, not a permanent denial on every long session.

## Host-only physical effect record journal (next bounded slice)

`DurableEffectRecordJournal` writes **RCEFX1** in an existing private
Linux/POSIX directory outside the workspace (effective-UID-owned, mode
0700). The journal and persistent `.lock` file use mode 0600. The API
requires explicit `@ResumeOperatorOnlyApi` opt-in, a trusted host-supplied
secret key of at least 32 bytes (never stored with the journal), an exact
session identity, and a live `SessionResumeLease`. It does **not** grant
execution; the lease still admits only pre-execution histories.

`create` is `CREATE_NEW` and **never** reopens for append. A failed
create **after CREATE_NEW** leaves the partial journal in place; a later
attempt returns `EFFECT_JOURNAL_EXISTS` and requires operator inspection,
not automatic deletion or retry.

After the seven-byte `RCEFX1\n` magic, the **first mandatory frame** is a
HMAC-authenticated *genesis* JSON payload containing the exact `sessionId`,
`fenceGeneration` N, `admittedCheckpointSha256`, and
`toolAuthoritySha256` captured from the live `SessionResumeLease`.
The provided session ID must match the lease's trusted session. Even an
empty event stream has a verifiable genesis identity and a keyed head.
Subsequent frames are effect entries of the same session and issuing
generation N. Strict `inspect` requires the full
`EffectJournalExpectation` (session ID, N, checkpoint SHA and authority SHA)
rather than optional expected fields. The genesis binds identities
**inside this file** only; a later adapter still must independently
verify the issuing fence and checkpoint chain on disk.

Every frame is `int32 payload length | 32-byte HMAC-SHA256 tag |
canonical JSON payload`. The host supplies a master key of at least 32
bytes; the journal derives a dedicated HMAC-SHA256 subkey using
`recursive-coder/effect-record-journal/v1`. The tag includes `RCEFX1\n`,
the **normalized absolute file path** (`toAbsolutePath().normalize()`,
not a path canonicalized via `toRealPath()`), previous tag and entire payload.
The genesis tag starts from 32 zero bytes and becomes the seed for all
effect tags. Moving or restoring this file under another path invalidates
its HMAC. `Intent -> Started -> Committed` or
`Intent [-> Started] -> Unconfirmed` are enforced before physical writes
with an O(1) average-time last-state map by `operationId`, including
same-session operation-ID uniqueness. The writer holds the OS journal lock,
performs lease-held append + `FileChannel.force(true)` before acknowledgement,
and becomes poisoned after uncertain I/O; it never truncates or guesses
whether a post-fsync acknowledgement was observed.

`inspect` is **read-only** and requires one mandatory
`EffectJournalExpectation` with the independently known session ID,
issuing fence generation, admitted checkpoint SHA and tool-authority SHA.
The strict path refuses malformed or mismatched expectations, wrong key,
broken tag chain, noncanonical record, duplicate ID, illegal transition,
torn tail, live writer, missing lock or unsafe path. It independently
verifies the complete file under the journal's exclusive OS lock.

A separate `inspectForDiagnostics` permits only two incomplete tail
classes: `SHORT_FRAME` (insufficient bytes or incomplete declared frame),
and `ZERO_FILLED` (all remaining bytes from the first incomplete frame are
`0x00`). It reports `tailKind`, `tornTailOffset`,
`verifiedByteLength` and physical `byteLength`, retaining the authenticated
prefix without repairing, truncating or authorizing any operation. **Even
all-zero bytes could be deliberate tampering, not a proven crash.**
A partially written frame with a nonzero length and a zeroed MAC/body
is *not* classified as `ZERO_FILLED` and fails MAC verification.
Strict `inspect` rejects both tail classes; a torn genesis, a nonzero
MAC mismatch, a noncanonical payload or illegal transition rejects
even diagnostics.

The returned authenticated header, entry list, keyed head and file digest
are **diagnostic physical-file evidence only**, NOT the checkpoint-anchored
complete session-journal projection. In particular, HMAC alone cannot
detect deletion of a complete suffix or replay of an older valid file by a
same-UID actor: a separate independently published checkpoint/anchor
binding is mandatory before effects can be classified after a crash.

**Still forbidden:** physical tool/CAS execution, signed no-start/commit
proofs, automatic `ProvenNotStarted` or `ConfirmedCommitted`, history
projection into `EffectReconciliationPolicy`, resumed writes to a prior
journal, or expanding the pre-execution fenced-ledger event policy.
No caller may infer that an intact record tail is durable acknowledgement
or that an authenticated `Committed` has an independently verified external
result. Key creation/retention/rotation and explicit operator reconciliation
remain trusted-host responsibilities outside this slice.

`DurableEffectRecordJournalIntegrationTest` exercises fsync, authenticated
genesis with empty and full histories, exact expected context, correctly
MACed noncanonical and foreign-session/generation payloads, forged frame
length, strict vs diagnostic torn tails, unknown terminal transitions,
uncertain post-fsync acknowledgement, duplicate operations, stale lease,
live/missing/symlinked lock, workspace directory safety, path substitution,
and lease retirement.

## Trusted-head comparison (bounded inspection; no checkpoint integration)

The earlier proposal to write one `effect-head-*.rca` file for every
effect was **removed after PR #127 security review**. It made the
checkpoint directory unreadable to `SessionCheckpointStore.readAll`,
created orphanable temporary files, added a file fsync / hard-link /
directory fsync for every effect record, and gave **no additional
anti-rollback guarantee** over a genuinely trusted `(sequence, MAC)`
pin. Ordinary journal creation and append do not write anything into
the checkpoint directory. Existing checkpoint name validation stays
strict and unmodified.

`DurableEffectRecordJournal.inspectBound` requires a fully validated
`EffectJournalExpectation` and an independently trusted
`EffectJournalHeadPin(lastSequence, headMacSha256)`. It verifies the
entire RCEFX1 chain and refuses a missing complete last record, a
different head, a different sequence, or an additional complete record.
The HMAC binds the genesis, path and entire ordered record prefix.

## Canonical checkpoint v2 effect-head pin and operator v3 transition

The trusted host can now call `DurableEffectRecordJournal.headPin()`
to obtain the **current in-memory acknowledged** (sequence, HMAC) pair,
including the genesis head at sequence 0. This return value alone is
NOT an authority, because a future reader could derive it from an
attacker-rolled-back sidecar.

`SessionCheckpointStore.saveEffectHeadFenced` is a dedicated, host-only,
pre-execution pin-publication path. It holds the existing live
`SessionResumeLease.publishIfCurrent` monitor, checks that the journal
writer is the exact admitted writer under that lease, checks the
unchanged sidecar inode and physical length, and saves one canonical
SHA-chained checkpoint generation containing the issuing session,
fence generation, admitted checkpoint SHA, tool-authority SHA,
normalized absolute sidecar path SHA, last effect sequence and HMAC
head **in one authenticated checkpoint payload**. The journal must
have been fsynced before this checkpoint is persisted. The normal
`saveFenced` path cannot introduce an effect pin by copying or
forging fields. Pinned checkpoints cannot silently drop or regress
the pin; repeated sequence must retain the identical MAC head.

Legacy v1 checkpoint payloads remain byte-for-byte identical.
A checkpoint containing an effect pin has a distinct exact v2 JSON
field set and version, is still bounded by the same 512-KiB limit,
and participates in the existing checkpoint SHA-256 generation
chain, atomic replacement and directory-fsync publication.
`SessionCheckpointStore.inspectEffectHeadBound` verifies the latest
checkpoint, existing fence/ledger anchor, the exact sidecar path and
the fully authenticated RCEFX1 prefix against the checkpoint's
pinned expectation. Read-only checkpoint verification also checks
the pinned admission SHA against an **earlier** published checkpoint
and the pinned issuing generation/session/authority against the
committed fence chain. It never creates or repairs missing evidence.

An ambiguous pin-publication acknowledgement poisons the writer
separately from an ambiguous sidecar append: append, `headPin()`,
`checkpointHeadUnderLease()` and `close()` refuse with
`EFFECT_JOURNAL_PIN_PUBLICATION_UNCONFIRMED` (after releasing
resources on close). The sidecar path SHA is computed using one
normalized-absolute-path helper for both publication and inspection.

**Pinned checkpoints are always reconciliation-required, including
genesis-only pins.** They cannot be used for automatic resume, stage
acceptance, model execution or tool execution. No physical effect
executor is connected in this PR. The current v2 pin only permits
`created` or `accepted`, task attempt zero and no pending effects.
Future executor enablement requires an explicit phase-policy change
and tests, never an incidental widening.

A pinned session remains tied to its one issuing fence generation
until an **authenticated operator reconciliation transition record**
and validated new-generation rule are implemented. No ordinary resume
path currently creates that transition. See the pinned-generation
policy in [SESSION_CHECKPOINTS.md](SESSION_CHECKPOINTS.md).

**Important remaining rollback limitation:** the currently admitted
fence authenticates the checkpoint it *admitted*, not every later
published checkpoint generation. If an attacker can coherently remove
the newest checkpoint generation(s) AND the matching sidecar suffix,
the pre-existing admission root may fail to reveal the rollback.
Before enabling any external effect, the authoritative root must gain
an independently retained monotonic latest-pin/checkpoint-head guard
that cannot be reverted with those files; this PR does NOT claim that
a checkpoint v2 publication by itself solves whole-root rollback.

### Genesis-only operator reconciliation

The new `reconcileGenesisEffectHead` operator API can explicitly move
from a pinned checkpoint in generation N to a new, pre-execution lease in
generation N+1 **only when the entire HMAC-authenticated sidecar has no
effect records (genesis-only, sequence 0)**. An operator approval alone
cannot settle a `Started` effect. The caller must supply the exact old
checkpoint SHA, fence SHA/generation, host key and head MAC; any mismatch
or uncertainty fails closed.

A canonical checkpoint v3 transition record preserves the original
pinned head and its issuing fence/checkpoint with a dedicated
`hostSealMacSha256` (domain-separated host-key HMAC), plus an operator
identifier. It is **not a cryptographic signature of a human operator**:
the host attests the `operatorApproved` boolean. The seal is required
when replaying a v3 that was durably committed before its N+1 fence.
After that fence pins the v3 SHA, the immutable checkpoint chain
protects its bytes.

The v3 record belongs **only to the transition checkpoint**. Successors
must omit it and return to canonical v1 or v2; `readAll` verifies their
immediate v3 predecessor and the committed N+1 fence that pins the
transition SHA. Even **without** a successor checkpoint, once fence
N+1 is durably committed and pins the exact v3 SHA, the latest v3 no
longer requires another operator reconciliation: ordinary `admit()`
may acquire a pre-execution lease N+2. This survives a host crash after
the N+1 fence but before the first checkpoint under its lease.
An N+1 anchor at PREPARE alone is *not* sufficient. Later ordinary
or newly pinned checkpoints are accepted only if the N+1 fence pins
the v3 SHA, and a new v2 head is issued by its **own** existing fence
(N+2 or later), never inferred from the historical pin.
A current pinned v2 still blocks normal admission. This extends the
#128 transition rules only at
an explicit, independently verified boundary, not by accepting
arbitrary dropped pins.

The verified v3 generation is durably published **before** the successor
fence. If a crash leaves an already-PREPARED next anchor, the host must
call `reconcilePreparedGenesisEffectHead` with **both** the original
genesis approval and an exact `PreparedFenceReconciliation`. It verifies
the old host HMAC and physical genesis, the prepared fence SHA/generation,
and the immutable audit, then publishes the existing prepared fence.
Plain genesis retry cannot guess that an orphan PREPARE is safe.
Reconciliation of Intent/Started and external states remains deferred
to the verifier.

### Required ordering before enabling real effects

1. Write and fsync `Intent` to the RCEFX1 sidecar.
2. Write and fsync `Started` **before invoking any external effect**.
3. Commit an independent trusted head pin covering `Started` to the
   authoritative checkpoint/anchor **before external effect execution**.
   If this commit fails or is uncertain, do not invoke the effect.
4. Invoke the effect only under an actually effective execution fence.
   Independently verify the outcome; do not infer success from `Committed`.
5. Persist the terminal receipt, then update the trusted pin using the
   same fail-closed publication discipline.

A process can crash after sidecar fsync but **before** the trusted pin
publication. The sidecar may therefore contain a complete *extra* frame.
If the sidecar differs from the already trusted pin in **either**
direction, strict `inspectBound` fails with
`EFFECT_JOURNAL_PIN_MISMATCH`. Strict `inspect` and
operator-only `inspectForDiagnostics` can show the observed physical prefix and
(optional) torn/zero tail for investigation. They do not distinguish a
crash from malicious rollback, publish a new pin, authorize retry or
accept a checkpoint. A pin covering `Started` must never be inferred
after the external effect. The future authority adapter must address
ambiguous publication acknowledgements and kill/timeout points.

No `HostEffectEvidenceVerifier` is implemented by these physical-file
comparisons: `EffectJournalView` still requires an exact authenticated
projection of the authoritative *session-event* ledger, and actual Git
CAS/tool state requires independent verification. Hence this PR enables
no `ConfirmedCommitted`, `ProvenNotStarted`, automatic replay or stage
acceptance.

## Checkpoint-bound host evidence verifier (read-only adapter)

`CheckpointBoundHostEffectEvidenceVerifier` implements the existing
`HostEffectEvidenceVerifier` contract as a **host-only, read-only** adapter.
It never enables an executor, a resumed write, a stage acceptance, or a
`ProvenNotStarted` result. The operator/trusted host must construct it with
a **live OS-fenced `SessionResumeLease`**, the exact checkpoint directory,
ledger and sidecar paths, an external host secret key, a frozen expected
`EffectIdentity` and `EffectJournalView`, and an
`IndependentEffectStateProbe`. A probe must read actual external state
from the proper trusted, effect-kind-specific host; returning the receipt
or model-supplied digest is **not** independent confirmation.

Every positive `authenticateCompleteJournal` rereads (rather than
trusting caller-created DTOs): the complete validated RCLDG1 file,
the canonical SHA-chained checkpoint/fence/anchor chain, the latest
checkpoint's bound physical v2 head (or its exact sealed v3 historical
genesis transition), and the **entire HMAC-authenticated,
exact-pin RCEFX1** sidecar with its closed OS writer. It compares the
RCLDG1 last sequence and head SHA with the checkpoint and supplied
`EffectJournalView`. It then reconstructs the **entire** RCLDG1 effect
projection from strict metadata envelopes and requires one-to-one,
in-order equality of each record identity, physical RCEFX1 sequence,
record variant, actual host MAC, and observed digest/error code.
There is no caller-supplied filtering and no acceptance of a partial
projection, missing event, duplicate, unmarked tool start/completion,
torn tail, live writer, stale lease, wrong key, forged MAC or extra sidecar
frame. Every denial returns false to the reconciliation policy; a separate
internal diagnostic projection reports stable, redacted codes (for example
`PROJECTION_UNMARKED_TOOL_EVENT`, `PROJECTION_MAC_MISMATCH`,
`PROJECTION_EXTRA_PHYSICAL`). The codes are **not** authorization to
skip physical validation or retry a tool.

The canonical RCLDG1 effect envelope uses only whitelisted scalar
metadata: `effectOperationId`, `effectFenceGeneration`,
`effectKind`, `effectInputSha256`, `effectRecordSequence`,
`effectRecordMacSha256`, `effectState`, plus
`effectObservedStateSha256` for `committed` or `effectErrorCode`
for `unconfirmed`. These names have been added to the *host ledger
safety allowlist*, **not** to the pre-execution admission whitelist.
The physical writer and verifier use the same `EffectEvidenceEnvelopePolicy`:
any metadata key beginning with `effect` is reserved and must form the
**complete canonical envelope**, with no extra graph fields, partial
records, wrong event type or status. Only exact
`tool.started/started` (intent/started), `tool.completed/completed`
(committed) and `session.error/failed` (unconfirmed) are accepted.
Malformed envelopes are refused **before RCLDG1 append** with the stable
`LEDGER_EFFECT_ENVELOPE_INVALID` code. This does not authorize any
runtime to publish an effect checkpoint.
Session events may interleave with non-effect metadata; their sequence
numbers need not equal RCEFX1 record positions. Each effect envelope
must name the exact contiguous RCEFX1 sequence and HMAC.

`independentlyVerifyCommitted` additionally rechecks the full physical
projection, exact `Intent → Started → Committed` triple for that
operation, and a fresh external state digest from
`IndependentEffectStateProbe`. It never accepts the mere presence of
`Committed` as external proof. The deterministic internal
`CommittedEffectStatePolicy` tests the successful probe branch,
incorrect/malformed/null digests, probe exceptions, and re-reading
changed or unavailable evidence **after** a probe. The real verifier
always supplies its physical authenticated reader; injected test
evidence cannot replace that production path. `independentlyVerifyNotStarted`
**always returns false**: no independent actual no-start execution
barrier yet exists.

**Historical N→N+1, genesis-only read-only verification:** this
adapter now accepts either (a) a pin issued under the currently live
`SessionResumeLease` N, or (b) an **empty, historical** N RCEFX1 genesis
after the exact operator-approved canonical v3 transition has been
committed under the immediate successor N+1 lease. This works **only
while v3 remains the latest checkpoint AND is exactly the checkpoint
SHA admitted by that live N+1 lease**. Publishing even one ordinary
successor checkpoint under the same N+1 lease ends this verification
window; an N+2 lease cannot use it, even if v3 remains the tail.

The second path requires a complete validated checkpoint/fence/anchor
chain, a generation N+1 fence referencing that exact v3 checkpoint, a
matching historical source checkpoint and N issuer, an independently
checked v3 host-key HMAC seal, and complete physical reinspection
against the historical RCEFX1 pin and exact sidecar path. The v3 seal
itself authenticates the historical source head, operator, session,
trace and policy; it **does not individually bind the successor N+1
generation**. The independently verified fence and anchor chain,
together with the exact lease-admitted checkpoint SHA and immediate
successor rule, establishes that N+1 relationship. A future nonempty
protocol must not infer successor authority from the seal alone.

A missing/wrong seal, different key/path, uncommitted or later
lease, advanced checkpoint, non-genesis physical journal, caller-supplied
view or identity mismatch fails closed. The before/after checkpoint
and live-lease checks still bracket that inspection.

**Unforgeable pin provenance within the journal module:** the shared
physical reader accepts only a private, non-copyable
`SessionCheckpointStore.AuthenticatedEffectHead` capability. It is
minted inside the store after actual `inspect()` of on-disk
checkpoint/fence/anchor authority, not from a raw sidecar head or
caller-created `VerifiedSessionCheckpoint`. For the verifier the store
also rechecks the live lease, its expected ledger path, the exact
checkpoint SHA, and the canonical v3 host seal before reading the
physical journal. The pure `CheckpointBoundEffectHeadPolicy` helper
returns a candidate only; its caller cannot use that candidate to
access the private physical reader. A policy mismatch returns
`null`; an invalid seal throws `SessionResumeUnconfirmed`.
The verifier catches ordinary `Exception` and denies.

The real Linux integration test exercises a pinned N genesis,
explicit operator reconciliation to N+1, **positive authentication
of that historical empty pin**, and typed denial for a byte-identical,
readable sidecar copied to a different path. Other negatives exercise
wrong key/identity/view, nonzero physical torn tail, subsequent N+1
checkpoint and N+2 lease. A separate regression creates an authentic,
fsynced, host-HMAC-signed Intent frame under N, restores pinned genesis
for operator reconciliation, and later appends that real frame under
N+1. Physical inspection independently confirms its MAC and framing,
while the trusted head reader must reject it with precisely
`EFFECT_JOURNAL_PIN_MISMATCH`; restoration re-enables genesis-only
authentication. Internal `@Tag("unit")` tests assert the v3
seal MAC denial code independently of the RCEFX1 HMAC and reject an
effect envelope projected against physically empty genesis. These
tests do not fabricate a nonempty end-to-end production checkpoint.
They also confirm that an empty history **cannot** produce
`ConfirmedCommitted`, `ProvenNotStarted`, or a state-probe
invocation. This is provenance-only: no real effects are
reclassified or replayed. **Nonempty historical N→N+1 recovery remains unsupported**:
the pre-execution checkpoint writer cannot publish the effectful
RCLDG1 record history needed for it, and there is no verified
execution barrier. Do not widen canonical v3 or bypass the
pre-execution policy to make a nonempty journal appear authenticated.

**Limits and required future probe acceptance criteria:** an end-to-end
**positive classification of a nonempty physical effect journal is
currently unreachable**. The existing pre-execution-only checkpoint
writer refuses RCLDG1 histories containing effect events; therefore
no full RCEFX1 Intent/Started/Committed history can be pinned in the
current production runtime. The real integration tests authenticate
physical **genesis-only** provenance, writer/lease locks, and an actual
RCLDG1 effect-envelope round-trip with an isolated projection fixture.
They **do not** physically corrupt/replay nonempty RCEFX1 records.
Pure unit tests cover one-to-one, order, missing/duplicate, wrong MAC
and stable denial codes. None of these demonstrate successful external
recovery in production.

Before wiring any concrete `IndependentEffectStateProbe`, require:
(a) explicit registration by `EffectKind` rather than a universal callback;
(b) trusted resolution of targets/expected inputs from a **host-owned,
content-addressed store** keyed by `inputSha256` — never from model text or
a claimed receipt;
(c) a versioned canonical observed-state digest scheme common to both
the original receipt writer and independent observer; (d) a host-enforced
timeout/deadline with unavailable/timeout results quarantined;
(e) effect-kind-specific positive and negative conformance against actual
external state, including concurrent mutation and crash tests.
The narrow probe port in this PR satisfies none of these on its own.
Also require production `Intent → Started → effect → receipt`
durability/fencing, independent no-start barriers, **nonempty** historical
N→N+1 authority, and an independently rollback-resistant root.

**Fatal failures:** ordinary `Exception` from I/O or the probe is
quarantined. `Error` (e.g. VM exhaustion or stack overflow) is deliberately
**not** swallowed by the verifier or `EffectReconciliationPolicy`. The
trusted host must treat any escaped `Throwable` as a terminal failure
of the recovery attempt and must never resume effect execution after it.
Unit tests pin this propagation choice. Coherent rollback of the entire
checkpoint/fence/anchor/RCEFX1 root remains outside this adapter.

## Deferred work and acceptance

The existing checkpoint `pendingEffects` and pre-execution fenced ledger
have NOT been wired to these new types. The barrier field and
verifier method are a contract only: this sidecar storage adds real record
HMAC verification but does **not** provide a physical successor execution
barrier, independently verified external completion, or a no-start proof. A later PR must add an independent, rollback-resistant latest-head
authority root that survives coherent deletion of checkpoint suffixes,
then enforce the completed Started pin publication *before executing*
any external effect. It must also reconstruct the complete session-event
projection, implement actual external-state confirmation and successor-barrier
no-start proofs, provide production adapter conformance, and kill/timeout
tests around every intent-effect-receipt boundary. See #105 for the remaining full P1 acceptance criteria.
