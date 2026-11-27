# Host-authenticated effect intent and receipt contracts (#105, first slice)

**Scope:** The first slice is a pure `:core:contracts` state machine.
The isolated host-only journal supplies a physically durable,
HMAC-authenticated effect record file under `:infra:journal`. #239 adds
standalone independent authority contracts and pure validation policy.
None of these slices executes external tools or Git CAS, produces usable
runtime resume permission, supplies an independent external-state verifier,
or enables CLI/replay wiring.

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

## Physical pin comparison and checkpoint-bound authentication

The earlier proposal to write one `effect-head-*.rca` file for every
effect was **removed after PR #127 security review**. It made the
checkpoint directory unreadable to `SessionCheckpointStore.readAll`,
created orphanable temporary files, added a file fsync / hard-link /
directory fsync for every effect record, and gave **no additional
anti-rollback guarantee** over a genuinely trusted `(sequence, MAC)`
pin. Ordinary journal creation and append do not write anything into
the checkpoint directory. Existing checkpoint name validation stays
strict and unmodified.

`DurableEffectRecordJournal.inspectPinnedForDiagnostics` requires a validated
`EffectJournalExpectation` and an **arbitrary caller-provided**
`EffectJournalHeadPin(lastSequence, headMacSha256)`. It checks the
entire RCEFX1 chain and refuses a missing complete last record, a
different head, a different sequence, or an additional complete record.
The HMAC binds the genesis, path and the observed ordered prefix, but
**not** the provenance of the caller pin. This API returns only an
`EffectJournalPinDiagnostic` (no entries or recovery authority). A caller
can self-pin a real post-pin append or a restored genesis; exact physical
equality does not prove non-execution, commit, or permission to replay.
The former public `inspectBound(...): VerifiedEffectRecordJournal` entrypoint
was intentionally **removed** rather than retained as a deprecated alias:
there is no safe compatibility contract for callers treating arbitrary
raw pins as authenticated recovery evidence. Migrate diagnostic callers
to `inspectPinnedForDiagnostics`; checkpoint readers must go through the
store-minted private head capability.

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

### #133/#239: independent anti-rollback root protocol

The checkpoint, RCLDG1, fence, anchor, audit and RCEFX1 files currently
share a rollback domain. After a real, valid-MAC Intent is appended beyond
a pinned genesis, truncating that suffix restores a byte-identical,
MAC-valid genesis. Exact pin comparison and even the genesis-only
operator transition can succeed again. **The absence of an Intent after
rollback is not proof that the external effect never began.** The existing
physical journal does NOT implement the independent root below. #239
implements its standalone contract and pure policy only.

**Required root.** Before enabling physical effects, use a separately
administered, irreversible monotonic register outside the same filesystem,
snapshot and agent-UID rollback domain: e.g. an authenticated remote
append-only linearizable CAS register with durable acknowledgements and
immutable version history, or a hardware-backed monotonic counter paired
with integrity-bound head data that the agent UID cannot reset or replace.
The register identity, epoch and trusted bootstrap must themselves survive
rollback. Another local HMAC file, hash chain or checkpoint generation in
the same snapshot domain is NOT independent. A counter alone cannot
authenticate a history without a binding to the exact head data.

Each register version must bind: host/session and register epoch, strictly
increasing version and previous-version digest, the live issuing fence
generation and immutable fence/anchor digest, exact checkpoint SHA,
canonical sidecar path digest, RCEFX1 sequence and head MAC, operation ID,
and a state such as `START_BARRIER_COMMITTED` or
`TERMINAL_EVIDENCE_COMMITTED`. The service must reject stale-version CAS,
reused operation IDs, forks, and non-successor generation transitions.
Host-key authorization and register-write authorization must be isolated.

### Independent authority deployment (#241)

The standalone adapter uses a dedicated, separately
administered Linux authority host. A trusted host client invokes a
fixed SSH forced command; no agent UID, workspace process, journal
HMAC key, local backup or local snapshot administrator may administer
that host, access its history HMAC key or replace its provisioned
register. The service is first-party and self-hostable. No cloud
provider or GitHub API is a runtime dependency.

The authority operator must provision a unique host/session/register/
epoch tuple exactly once, retain an independent inventory of issued
identities, and create two append-only histories on persistent storage
outside the local rollback domain. Provisioning is an offline
administrative operation, not a request endpoint. The never-initialized
sentinel must exist in both histories before bootstrap; absence is
never interpreted as permission to create a new register. The operator
must never restore an older authority snapshot, recreate a register,
rotate an epoch, remove append-only protection or reuse credentials
after loss. A lost or inconsistent history is quarantined, not repaired
by copying whichever replica is longer. Stop all publication and retain
both logs, the marker, inventory and invocation diagnostics under the
operator's control. For unavailability, restore connectivity only to
the same independently authenticated service and keep uncertain
invocations quarantined. Do not reinitialize or resume from readback.
For suspected compromise, revoke the SSH principal and transport
credentials; do not delete or rewrite history. A reviewed rotation
procedure must preserve the same identity, immutable history and
authenticated latestness. History-key rotation and release from
quarantine are unsupported until the separately reviewed protocol in
#248 exists.
Administrative restore of an older authority snapshot is prohibited;
if independently authenticated latestness cannot be proven, retire
the register without replacement or epoch rotation.

The implemented durability boundary is an exclusive interprocess lock,
complete-history validation, exact CAS, append and force of both
pre-existing logs before responding. A crash between the two forces
may leave unequal logs; availability is sacrificed rather than
guessing the committed state. Both logs are within the authority
domain; duplication detects one-sided corruption or truncation, not
coherent rollback of the authority itself. The authority operator
and kernel/storage stack are trusted to enforce append-only retention,
honor fsync and keep backups from rolling back the authoritative logs.
A malicious authority administrator, compromised authority history
HMAC key, coherent restore of both logs and their provisioning
inventory, or dishonest storage durability are outside this model.
Those events cannot be detected solely from the surviving bytes.
The `coherentAuthorityDomainRollbackIsOutsideThreatModel` regression
demonstrates that privileged truncation of both logs to a valid prefix
is undetectable when append-only enforcement is deliberately disabled.

The `SshEffectAntiRollbackAuthority` client invokes a fixed, pinned
OpenSSH forced command once per request. It authenticates canonical
request and response envelopes with a dedicated 32-byte HMAC key,
domain-separated from a distinct authority-only history HMAC key.
The request contains a cryptographically random nonce, exact register,
verb and canonical predecessor/proposal. The response includes the
SHA-256 of the complete authenticated request and, for success, the
exact proposed root digest. Reads return the complete authenticated
history, revalidated by the client. No raw paths, secrets or exception
payloads are returned. Transport failure, timeout or interruption
returns an unconfirmed publication; no automatic retry or readback
promotion is implemented. The host must quarantine the invocation even
if a subsequent read finds the exact proposed root.

The operator deploys the JVM runtime and its dependencies on the
authority host and configures a dedicated restricted SSH principal
with a fixed `EffectAuthorityHostCommand` invocation. Disable shells,
PTY, forwarding, alternate commands and other login methods for this
principal; keep its authorized-keys configuration, JVM classpath,
directories and server history key under independent administration.
The client must pin the actual SSH host key, protect its private SSH
key and transport key separately from the journal key and agent UID,
and explicitly approve the independent deployment when connecting.
There is no default endpoint, discovery, auto-provision or CLI wiring.
The command and client are co-packaged in `:infra:journal` to reuse the
existing host-only storage adapter boundary without adding a tenth
product module or changing the dependency graph. The operator deploys
the independently verified journal JAR and its runtime dependency
closure on the separate host, not the agent CLI or its composition
root. Co-packaging does not grant the agent access to remote keys or
administration; no runtime registers or invokes the command. The
authority host must use its own reviewed artifact installation.

The command arguments, fixed by the operator rather than the client,
are the mode, primary log path, mirror log path, lock/marker path,
transport-key file, history-key file, host ID, session ID, register ID
and epoch. Generate distinct random 32-byte binary keys out of band.
After checking the independent identity inventory, run the offline
`provision-never-initialized` mode once, in protected pre-existing
directories. The command creates and forces the marker and both
headers authenticated with the history HMAC key, plus a dedicated
mutex file derived by adding `.mutex` to the marker path. All four
use exclusive creation; the parent directories are forced. Partial
provisioning is quarantined, never retried.
Set Linux append-only attributes (`chattr +a`) on the marker and both
logs,
with permissions for the dedicated service principal to read and
append but not administer attributes or directories. The mutex file
must allow the service to acquire an exclusive lock, with no ability
to replace it or its parent directory. The forced command uses only `serve-independent`; never expose provisioning over
SSH. The service checks four regular, distinct, non-symlink files and
the three required append-only attributes on every invocation. The operator must ensure
the files reside on a local Linux filesystem supporting these
attributes, interprocess locks and honest file/directory fsync, with
persistent media and protected parent directories. The process does
not attest those deployment assumptions or physical hardware.

Under the exclusive dedicated mutex-file lock, the service compares
both bounded logs byte-for-byte, verifies the HMAC-authenticated
provisioning marker, headers and every canonical root, and validates
the complete history. The mutex is never opened a second time inside
its lock, because closing another descriptor for a POSIX-locked inode
can release the process's lock. It forces the equal histories before
authenticated read or comparison, then appends and forces each copy before acknowledging a
successful transition. An absent provisioning marker or a verified
zero-root history returns missing/reset on read. Divergent, unprotected,
oversized or missing history files return unavailable; malformed or
MAC-invalid equal histories return unauthenticated. Neither copy is
selected for repair. Key files must be regular, owner-only, owned by
the service user and opened without following the final symlink.
The operator protects the parent directories against rename or
replacement; the JVM does not attest the filesystem administrator.
The finite 16 MiB log limit is fail closed; there is no pruning or
rollover.
A later authenticated read, including after process death before the
second force, is not the lost invocation's acknowledgement.

Offline conformance uses the same client exchange, wire codec, host
handler, file locks and fsync path in independent JVM processes. It
does not require SSH, privileged append-only attributes or a remote
host in CI; the fixture invokes the real command parser and key loader
with an internal fault seam that relaxes the attribute prerequisite.
The production attribute guard has a negative test and the flag
parser has positive, negative and malformed-output fixtures. This is not
evidence of a physically independent or power-loss-tested deployment.
Until the operator establishes and independently verifies those
prerequisites, the adapter is not approved for live use. Physical
checkpoint/fence/sidecar authentication and live Started publication
remain separate integration gates; no effect execution is enabled.

### Standalone authority contract (#239)

`HostEffectAntiRollbackAuthority` in `:core:contracts` defines the
host-owned bootstrap, authenticated read and atomic compare-and-set port.
`EffectAuthorityRoot` and `EffectAuthorityEvidence` contain immutable
scalar values, an `EffectAuthorityRegister` and an optional immutable
`EffectIdentity`. They are proposed evidence, not self-authenticating
capabilities. The #239 contract does not itself implement a service, credential
management, an execution hook or a session wire-format change.
The standalone #241 adapter is described above.

The independently provisioned register binds host ID, session ID, register
ID and an opaque 64-character lower-case hexadecimal epoch identifier.
The provisioning and immutable history must enforce uniqueness for the
host/session across reset, replacement and administration changes. Bootstrap
is allowed only for an independently proven never-initialized register.
A missing or reset root is not a bootstrap credential.

The v1 canonical record is a fixed-order ASCII encoding, with one LF after
every field, including the last field:

1. Domain `recursive-coder/effect-authority-root/v1`.
2. Host ID, session ID, register ID and epoch (one line each).
3. Version, predecessor digest, issuing fence generation.
4. Fence/anchor digest, exact checkpoint digest, normalized absolute
   sidecar path digest, RCEFX1 sequence and head MAC.
5. Operation ID, explicit effect-kind token, input digest and state token.

Each comma-separated field in the list occupies its own line. IDs use the
existing bounded host-identifier grammar. Digests and MACs are exactly
64 lower-case hexadecimal characters. Counters are canonical base-10
positive signed-64-bit values, except the nonnegative sequence. A single
dash denotes an absent predecessor or absent operation field. Operation
session and issuing generation must equal the corresponding root fields,
so they are bound once. The root digest is SHA-256 of these exact bytes.
Canonical bytes are newly allocated on each call. No secrets, raw paths,
exception payloads or free-form result data enter the encoding.

The initial version is 1, has no predecessor or operation, and binds
a genesis-only sidecar at sequence 0. Each successor increments version
exactly once, names the exact predecessor digest and retains the entire
register identity. Version/sequence overflow, prior checkpoint/head reuse,
forks and changes of the fence/anchor or sidecar identity within the
same issuing generation are denied. With a single serialized operation,
the start barrier advances sequence by exactly two (Intent and Started);
terminal evidence advances it by exactly one, for the same complete
operation identity. The state tokens are `genesis`,
`start-barrier-committed`, `terminal-evidence-committed`, and
`terminal-unconfirmed`. Effect-kind tokens deliberately reuse the exact
RCEFX1 and effect-envelope vocabulary: `TRUSTED_WRITE`, `ISOLATED_CHECK`,
`GIT_CAS`, `ACCEPTANCE_HANDOFF`, and `RESULT_PUBLICATION`. Literal byte
and SHA-256 fixtures pin every operation kind and publication state.

A subsequent operation requires a new operation ID across the complete session
history. An unconfirmed terminal state blocks further transitions. Only an
immediate genesis-to-genesis successor generation is structurally supported,
with fence/anchor and sidecar path digests never used in any prior generation.
Even that transition requires independently authenticated successor evidence; no
numeric generation establishes lease ownership. Nonempty historical generation
transitions remain unsupported.

The adapter must perform the exact authenticated predecessor comparison,
transition validation, historical uniqueness checks and irreversible append
inside one linearizable transaction. The pure policy accepts a stable,
complete authenticated history snapshot. It validates bootstrap, every
consecutive version and predecessor digest, register identity and every
historical transition, including uniqueness. It rejects empty, truncated,
reordered, disconnected or structurally invalid histories; it cannot verify
provenance or that the supplied latest root is actually current. The
validation traverses history once, indexing already validated identities.
The trusted host must authenticate the physical checkpoint, fence/anchor
and complete RCEFX1 prefix before proposing these values. Syntactically
valid hashes are not proof of the corresponding physical evidence.

Reads distinguish authenticated latest state, missing/reset, unavailable
and unauthenticated state. Publication distinguishes a durable exact-digest
acknowledgement, a proven non-mutating refusal, unavailable/unauthenticated
responses and uncertain acknowledgement. The disposition helper
quarantines unavailable, unauthenticated, uncertain or mismatched
acknowledgements. An acknowledgement refers to that invocation and its
exact proposal; a later readback, even an exact matching root, is not
the original acknowledgement. No result or diagnostic comparison grants
execution, automatic retry, no-start proof, or external-state recovery.
An uncertain invocation stays quarantined until a separately reviewed
reconciliation protocol; an ordinary read does not clear it.

The in-memory authority model exists only in unit-test sources. It
serializes concurrent CAS and injects loss of acknowledgement before
and after a modeled commit. It is neither authenticated nor durable and
cannot prevent rollback of the test process. Its tests demonstrate
contract behavior, not the security of an independent production store.

**Required write order (future, separately reviewed):**

1. Read and authenticate the independent root, including epoch and exact
   predecessor, while holding the correct live OS-fenced lease. A missing,
   reset, unavailable or uncertain root means quarantine.
2. Force Intent, then Started to RCEFX1 and publish a matching checkpoint
   head. CAS an independent root version binding the exact Started head;
   receive an unambiguous *durable* acknowledgement **before any external
   call**. Verify the exact acknowledged proposal digest. An ambiguous CAS
   acknowledgement must not trigger the effect, a new pin, or a positive
   no-start inference. Stop admission/publication for that invocation and
   quarantine on timeout, interruption, missing acknowledgement or thrown
   failure. Follow the host-owned cancellation and retirement contract in
   [FENCED_RESUME.md](FENCED_RESUME.md): cancelling a waiter does not prove
   physical I/O stopped, and readback is not a durability receipt.
3. After a confirmed outcome, force the terminal receipt, publish its
   checkpoint head and CAS a new immutable root version. A MAC-valid
   Committed record alone does not prove `ConfirmedCommitted`: external
   state verification is separately required.
4. At **every** restart, admission, fence transition and effect-recovery
   attempt, read the root afresh and compare epoch, version, fence/anchor,
   checkpoint and physical sidecar. If the root is ahead of local files,
   diverges, or is unavailable, quarantine without synthesizing a pin,
   repairing a missing history, or replaying.
5. `ProvenNotStarted` additionally requires a separately authenticated
   *successor* execution barrier and independent evidence that the
   external operation never began. Neither a pinned genesis nor a
   surviving Started barrier provides this proof.

**Failure / attacker model:**

| Case | Coverage and required response |
| --- | --- |
| Crash during sidecar fsync or root CAS | No external call before durable Started-root acknowledgement; ambiguous state => quarantine |
| Crash after Started-root commit but before/during effect | Surviving root proves barrier publication, NOT outcome; independent probe required |
| Same-UID attacker truncates/replaces MAC-valid local suffix or rolls back local checkpoints/fence/anchor | Root outside that UID/snapshot detects disagreement; refuse recovery |
| Same UID can modify/reset the alleged independent root | Not covered: such a root fails the independence requirement |
| Host HMAC key compromised, register isolated | MAC records can be forged, but rewind of root is rejected; effect outcome still unproven |
| Host key and root writer credentials compromised | Forged forward versions or denial remain possible; never claim execution integrity or no-start |
| Whole local checkpoint/sidecar/fence snapshot restored but independent root survives | Root mismatch => quarantine, including valid pinned genesis |
| Independent root also reverted, lost, reset or re-bootstrapped with no external anchor | Information-theoretically indistinguishable; fail closed, cannot prove what ran |
| External effect begins before barrier acknowledgement or bypasses fenced host | Out of scope; must be prevented before any executor is enabled |

The root is **necessary, not sufficient**, for nonempty N→N+1 history
recovery. There is still no approved barrier/probe/authority protocol;
physical execution, replay, inferred no-start, confirmed commit and
nonempty recovery remain disabled and pre-execution guards remain intact.

### #133: physical reader / authority caller audit

| Actual or proposed caller | Trust class and allowed result |
| --- | --- |
| `DurableEffectRecordJournal.inspect` / `inspectForDiagnostics` | Physical RCEFX1 only; both return the **entries-bearing** `VerifiedEffectRecordJournal`, which proves MAC/framing but carries **no** checkpoint pin provenance |
| `DurableEffectRecordJournal.inspectPinnedForDiagnostics` | Physical exact equality to an arbitrary caller self-pin, returns `EffectJournalPinDiagnostic` without entries or authority |
| `SessionCheckpointStore.inspectEffectHeadBound` | Read-only latest checkpoint/fence/anchor plus private physical pin comparison; returns `CheckpointAuthenticatedEffectJournal`, NOT independent effect authorization |
| `SessionCheckpointStore.inspectEffectHeadForLease` | Current lease plus freshly inspected checkpoint, issuing fence and sealed historical-v3 constraints; returns `CheckpointAuthenticatedEffectJournal` for fail-closed verifier only |
| `SessionCheckpointStore.withGenesisReconciliationEvidence` | Locked operator-approved, genesis-only transition; any nonempty physical history denies |
| Proposed nonempty RCLDG1 projector or external executor | **Forbidden** until independent root, Started barrier and external-state authority are reviewed |

A private, store-minted `AuthenticatedEffectHead` protects **checkpoint
pin provenance**, and the private `StoreBoundEffectJournal` is the only
implementation of the public sealed `CheckpointAuthenticatedEffectJournal`
wrapper. `EffectEvidenceProjection.fromSessionLedger` now requires that
wrapper. `EffectEvidenceProjection.inspectForDiagnostics` still supports
raw `VerifiedEffectRecordJournal` for inspection and tests, but cannot
feed it into the authority projection API. None of these types makes the
checkpoint/sidecar/fence root rollback-resistant; a diagnostic pin match
has neither authenticated provenance nor anti-rollback protection.

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
If the sidecar differs from the store-authenticated pin in **either**
direction, the private `SessionCheckpointStore.inspectAuthenticatedEffectHead`
reader fails with `EFFECT_JOURNAL_PIN_MISMATCH`. Public
`inspectPinnedForDiagnostics` performs the same physical comparison
against a self-asserted pin, never granting checkpoint authority. Strict
`inspect` and operator-only `inspectForDiagnostics` show the physical prefix and
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

**Unforgeable capability only within `SessionCheckpointStore`:** the
private `inspectAuthenticatedEffectHead` reader requires a non-copyable
`AuthenticatedEffectHead` and returns a distinct sealed
`CheckpointAuthenticatedEffectJournal` wrapper, minted after fresh on-disk
checkpoint/fence/anchor verification (or the locked, checked genesis-only
operator transition), never from a caller-provided snapshot or sidecar.
**This claim does not apply to public `DurableEffectRecordJournal` APIs.**
The raw-pin `inspectPinnedForDiagnostics` returns `EffectJournalPinDiagnostic`
and cannot promote caller pins into checkpoint authority. For the verifier
the store
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

The existing checkpoint `pendingEffects` and pre-execution fenced ledger have
NOT been wired to these new types. The barrier field and verifier method are a
contract only: this sidecar storage adds real record HMAC verification but does
**not** provide a physical successor execution barrier, independently verified
external completion, or a no-start proof. A later PR must verify the
independent #241 authority deployment,
integrate it with the runtime so it survives coherent local rollback, then
enforce the completed Started pin publication *before executing* any external
effect. It must also reconstruct the complete session-event projection,
implement actual external-state confirmation and successor-barrier no-start
proofs, provide production adapter conformance, and kill/timeout tests around
every intent-effect-receipt boundary. See #105 for the remaining full P1
acceptance criteria.
