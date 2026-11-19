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

## Canonical checkpoint v2 effect-head pin (next bounded slice)

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
