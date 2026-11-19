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
generation N. `inspect` requires an expected session ID and can additionally
enforce expected N, checkpoint SHA, and authority SHA. The genesis binds
identities **inside this file** only; a later adapter still must independently
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
Strict `inspect` rejects both; a torn genesis, a nonzero MAC mismatch,
a noncanonical payload or illegal transition rejects even diagnostics.

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

## Checkpoint-directory effect-head receipts (next bounded slice)

When the host creates an effect journal, it durably publishes an immutable
genesis head receipt (`effect-head-<generation>-000000000000.rca`) in the
existing private checkpoint directory. Each subsequently acknowledged effect
append is fsynced to the sidecar **and then** published as an immutable,
hash-chained head receipt in the checkpoint directory before the caller gets
an acknowledgement. The receipt contains the full genesis context,
normalized absolute sidecar path hash, effect sequence, HMAC head and prior
receipt SHA-256. Publication uses a forced temporary file, same-directory
hard link, and parent-directory fsync. An uncertain publication poisons the
writer; never truncate/repair the sidecar or claim a confirmed effect.

`DurableEffectRecordJournal.inspectBound` checks a strict physical
journal against a mandatory `EffectJournalExpectation`, a trusted
`EffectJournalHeadPin` (last sequence, head MAC, head receipt SHA), and the
complete independent checkpoint-directory receipt chain. A removed complete
effect record, missing receipt, tampered receipt or mismatched pin fails
closed. The pinned SHA **MUST** be retrieved from an independently trusted
durable checkpoint/authority root; deriving the pin from the effect sidecar
or listing this checkpoint directory is unsafe. Current checkpoint schema
does not yet record the effect head, so such authority-root integration is
**not** supplied here. In particular, deleting a complete suffix of both
sidecar and head receipts cannot be detected without a separately retained
pin. The receipt files are independent of the sidecar but not a complete
external anti-rollback guarantee.

**Compatibility:** journals created by the earlier unanchored sidecar
version have no receipt chain and must fail `inspectBound`; do not synthesize
missing anchors from an untrusted file. They remain available to strict
physical inspection and operator diagnostics only.

This slice also does **not** implement `HostEffectEvidenceVerifier`:
`EffectJournalView` refers to a complete authoritative *session-event*
ledger projection, not to this separate sidecar. Nor can the adapter
independently verify a Git CAS or external tool's actual state yet.
Therefore no `ConfirmedCommitted`, `ProvenNotStarted`, replay or
automatic accepted stage is authorized by any of these receipts.

## Deferred work and acceptance

The existing checkpoint `pendingEffects` and pre-execution fenced ledger
have NOT been wired to these new types. The barrier field and
verifier method are a contract only: this sidecar storage adds real record
HMAC verification but does **not** provide a physical successor execution
barrier, independently verified external completion, or a no-start proof. A later PR must bind the trusted head pin into an independently verified
checkpoint/anchor authority root, reconstruct and authenticate the complete
session-event projection, implement actual external-state confirmation and
successor-barrier no-start proofs, provide production adapter conformance,
and kill/timeout tests around each intent-effect-receipt boundary. See #105 for the remaining full P1 acceptance criteria.
