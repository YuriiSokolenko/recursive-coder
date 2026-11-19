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

`create` is `CREATE_NEW` and **never** reopens for append. Each frame is
`int32 payload length | 32-byte HMAC-SHA256 tag | canonical JSON payload`.
The HMAC includes a version domain, canonical path, previous tag, and full
payload; the payload includes record kind, exact operation ID, issuing
generation, proposed input digest, and any terminal receipt field.
`Intent -> Started -> Committed` or `Intent [-> Started] -> Unconfirmed`
are enforced before physical writes, including global same-session
operation-ID uniqueness. The writer holds the OS journal lock, performs
lease-held append + `FileChannel.force(true)` before acknowledgement,
and becomes poisoned after uncertain I/O; it never truncates or guesses
whether a post-fsync acknowledgement was observed.

`inspect` is **read-only** and fails on an invalid key, broken tag chain,
noncanonical record, duplicate ID, unsupported transition, torn tail,
live writer, missing lock, or unsafe path. It independently verifies the
entire file under the journal's exclusive OS lock. The returned entry list,
keyed head, file digest and byte length are **diagnostic physical-file
evidence only**, NOT the checkpoint-anchored complete session-journal
projection. In particular, HMAC alone cannot detect deletion of a
complete suffix or replay of an older valid file by a same-UID actor:
a separate independently published checkpoint/anchor binding is mandatory
before effects can be classified after a crash.

**Still forbidden:** physical tool/CAS execution, signed no-start/commit
proofs, automatic `ProvenNotStarted` or `ConfirmedCommitted`, history
projection into `EffectReconciliationPolicy`, resumed writes to a prior
journal, or expanding the pre-execution fenced-ledger event policy.
No caller may infer that an intact record tail is durable acknowledgement
or that an authenticated `Committed` has an independently verified external
result. Key creation/retention/rotation and explicit operator reconciliation
remain trusted-host responsibilities outside this slice.

`DurableEffectRecordJournalIntegrationTest` exercises fsync, authentic
replay, wrong key, tampering, torn tails, uncertain post-fsync acknowledgement,
non-replayed terminal states, duplicate operations, stale generation, live
lock, missing lock, private directory, path substitution and lease retirement.

## Deferred work and acceptance

The existing checkpoint `pendingEffects` and pre-execution fenced ledger
have NOT been wired to these new types. The barrier field and
verifier method are a contract only: this sidecar storage adds real record
HMAC verification but does **not** provide a physical successor execution
barrier, independently verified external completion, or a no-start proof. A later PR must supply canonical
serialization and physical host MAC verification, crash-safe durable append,
whole-journal verification, authoritative external-state checks and no-start
proofs, atomic receipt publication with the current resume lease, adapter
conformance, and kill/timeout testing around every intent-effect-receipt
boundary. See #105 for the remaining full P1 acceptance criteria.
