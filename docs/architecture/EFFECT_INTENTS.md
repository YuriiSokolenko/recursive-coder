# Host-authenticated effect intent and receipt contracts (#105, first slice)

**Scope:** Pure, in-memory `:core:contracts` state machine and fixture tests only.
No external tool execution, Git CAS, journal storage, crypto-key management, CLI
registration, runtime resume, or automatic replay is enabled by this change.

## Contract and strict publication order

A host-generated `EffectIdentity` pins session ID, operation ID, fence generation,
effect kind and digest of the exact frozen proposed input. Operation IDs must be
unique for a session across *all* generations and effect kinds; a retry must get
a new ID and pass a new independent admission decision. The recovery caller
must pass the **current host-authoritative fence generation** separately;
a matching record from an old generation cannot authorize a safe classification.

A future trusted adapter must force and authenticate records in order:
`Intent` (before any physical start) -> `Started` (before the external call)
-> `Committed` (only after confirmed outcome), or `Unconfirmed` upon
ambiguous/failed observation. For uncertain Git/CAS responses, timeouts,
exceptions or crash tails, the host must not guess a commit from a local diff
or infer non-execution from a missing `Started` record.

`EffectJournalEntry` carries the exact sequence and an opaque host MAC.
`EffectJournalView` identifies a complete authoritative journal head. Only
a trusted host may implement `HostEffectEvidenceVerifier`: it must verify
canonical bytes, the host MACs and the **complete durable journal prefix**,
including gaps due to unrelated journal events. Matching SHA-256 strings
without actual MAC and prefix verification are not authentication.

## Recovery classification

- **ConfirmedCommitted**: exactly one `Intent -> Started -> Committed`,
  authenticated and ordered under the exact generation and operation ID,
  with the observed external result independently checked.
- **ProvenNotStarted**: exactly one `Intent`, with an authenticated head-bound
  `EffectNotStartedEvidence` independently proving non-execution or a
  trustworthy execution barrier. Absence of `Started` alone is insufficient.
- **Unconfirmed**: everything else, including stale generation, reused
  operation ID, duplicate entries, a recorded `Unconfirmed`, conflicting
  proofs, missing/unverifiable state, an invalid sequence or verifier failure.

All outcomes are **informational**; none is permission to call a tool, repeat
a write, accept a parent stage/checkpoint, advance a lease or publish a result.
An adapter must independently re-check host authority and fencing before any
state change. A later reconciliation of `Unconfirmed` requires a distinct,
operator-reviewed protocol; this v1 pure policy never silently upgrades it.

## Deferred work and acceptance

The existing checkpoint `pendingEffects` and pre-execution fenced ledger
have NOT been wired to these new types. A later PR must supply canonical
serialization and physical host MAC verification, crash-safe durable append,
whole-journal verification, authoritative external-state checks and no-start
proofs, atomic receipt publication with the current resume lease, adapter
conformance, and kill/timeout testing around every intent-effect-receipt
boundary. See #105 for the remaining full P1 acceptance criteria.
