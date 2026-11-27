# Independent authority credential rotation and quarantine recovery (#248)

**Status: proposed operational protocol, not approved for deployment.**
The standalone v1 adapter from #241 implements a single history HMAC key,
a single transport HMAC key per invocation and a fixed SSH forced command.
The tests accompanying this document exercise transport-key replacement
against the real host parser and persistence path in local JVM processes.
A separate qualification-only signed witness ledger and history-segment
continuity verifier are implemented in :infra:journal. They are not wired
to the v1 service. They do not implement remote witness deployment,
actual SSH revocation or quarantine release.
No runtime effect execution, live recovery or Started publication is enabled.

## Trust and continuity prerequisites

Three distinct administrative roles are required: the runtime host operator,
the authority host operator and a latestness witness operator. No runtime UID,
workspace process, authority service principal or administrator of either
host's snapshots may replace or roll back the witness inventory. A witness
must have separately administered credentials and durable storage; two files
on the authority host are not two independent witnesses. A trusted reviewer
and the owner approve enrollment and every credential change. If the
administrative separation cannot be established, the deployment is ineligible.

The immutable identity is the complete host ID, session ID, register ID and
epoch from the original one-shot provisioning. Enrollment records the exact
provisioning marker, both initial headers, mutex identity, installation
artifact digest, filesystem and storage identity, SSH endpoint and key
fingerprint, authorized client principal and public-key fingerprint, and
opaque credential IDs for transport and history keys. Secret bytes must not
appear in the inventory, diagnostics or review evidence. Fingerprints for
public keys are computed from the actual public keys, not operator labels.
Credential IDs are independently assigned, never reused and never derived
from journal keys. The inventory retains retired identities and credential
IDs permanently, including failed or partial enrollments.

An independent witness must durably retain a monotonic sequence of
authenticated, non-replaceable inventory records. The record format must
bind the exact identity, prior witness record digest, inventory generation,
credential generation, operational state, canonical root version and digest,
complete history byte lengths and SHA-256 digests, and marker digest.
For an initialized register the witness independently checks the complete
history, both equal logs, the canonical root chain from genesis, and the
provisioning marker. Digests must be measured from independently collected
bytes under quiescence, not supplied by the client as assertions. A
never-initialized sentinel is a distinct state, never a missing file.
Witness records must be authenticated by a separately controlled signing
credential, with verifier trust anchored outside both host rollback domains.
The external witness's own latestness must not depend on accepting a
caller-provided generation, cached signature, timestamp or old valid record.

Before any future production admission, the witness must be enrolled before
first publication and participate in every publication: force the authority
histories, independently validate the exact successor against the witness's
previous commitment, durably advance the witness by exact CAS, and only then
allow a response bound to the original invocation. Serialize the entire
sequence against competing publications and rotations. A crash anywhere
after publication begins but before a valid acknowledgement quarantines the
invocation. An observed matching head, even witnessed, never substitutes
for its acknowledgement. The existing v1 service does not implement this
sequence; an inventory assembled after an unobserved interval cannot prove
that an acknowledged suffix was not already removed.

## Routine credential cutover

This is a proposed offline, stop-the-world procedure. It does not expose a
new server command or relax the single-key verification of v1. No fallback
to old credentials is permitted. Routine SSH client, transport and server
host key replacement does not change the register identity, epoch or history
bytes. History-key replacement is a different protocol, described below.

1. Freeze admission and publication on both hosts. Remove access to the
   forced command, stop its entry points, terminate and account for all
   authenticated SSH sessions and authority processes, and ensure no
   in-flight invocation remains. The filesystem mutex alone cannot exclude
   a process that has already loaded an old key before taking the lock.
   An uncertain invocation, unaccounted process or unavailable witness
   prevents cutover and requires quarantine.
2. Independently verify the full inventory, marker, both immutable histories,
   exact latest root and witness record. Require matching lengths, bytes,
   root chain, storage identity, append-only protection and credential
   generation; absence or mismatch is not permission to establish a new
   baseline. Record a durable FROZEN commitment in the witness.
3. Generate fresh, distinct credentials in their respective trusted domains.
   Stage private files in protected directories with exact owner-only
   permissions, prepare the restricted SSH principal and independently
   authenticate the new server host public key out of band. Pin that key
   for the exact endpoint; do not trust network discovery or a key learned
   through an unverified connection. Record PREPARED with both generations
   and the unchanged history commitment in the witness.
4. While still inaccessible to clients, replace the transport-key files at
   both ends atomically within their respective filesystems, force the files
   and parent directories, replace the authorized client key and server host
   key, and replace the client's pinned known-hosts entry. Disable alternate
   login and host-key paths. Revoke the old client authorization and transport
   secret; remove the old host key from every accepted pin set. Do not
   temporarily accept both generations. Dispose of old private copies under
   the operator's retention policy; record revocation and destruction
   evidence without disclosing key material. Terminate any old connection or
   process that could still use an in-memory credential. Record CUTOVER
   durably; atomic replacement of one file is not a distributed transaction.
5. In a restricted maintenance window, verify the newly pinned SSH connection
   and forced command using the new credentials. Independently read and
   authenticate the complete unchanged history and latest witness commitment.
   Verify old SSH client credentials, old server host pin and old transport
   MAC each fail independently, including captured requests and responses.
   Verify neither old nor new credentials can bootstrap an initialized
   register, accept a stale predecessor or substitute another identity.
   Before the maintenance window closes, measure the histories again and
   require no change. Never issue a production effect to test a rotation.
6. The reviewer compares all evidence against the frozen commitment and
   records the result, with owner approval, in a VERIFIED witness record.
   Admission remains disabled in this repository; a future reviewed
   integration may consult VERIFIED only for a clean, non-quarantined
   register. A fresh read does not release any previously quarantined
   invocation. A new credential generation is not an epoch rotation.

Interruption at any step leaves admission closed. Independently reconcile
the witness state and the exact installed credential generations, processes
and immutable history. If there is complete evidence that publication
remained impossible and no history was lost, an independently approved
operator may finish the same prepared cutover, never restore the old
generation. If an interruption leaves ambiguous publication, unaccounted
credential exposure, a missing witness record or uncertain history, retire
the register. No timeout, restart, automatic retry or key rollback reopens
the gate. Record every state transition durably outside both host rollback
domains before relying on it.

## History authentication credential replacement (not supported by v1)

The v1 marker, headers and every record use the original history HMAC key.
Replacing it causes authentication failure. Re-MACing, copying or rewriting
old histories, relaxing validation, or making the old key an alternate
write credential is forbidden. HMAC verification uses the same secret as
signing; merely labeling the old key read-only is not cryptographic
revocation. Do not restore an old key to recover from a failed cutover.

A future, separately reviewed history format must preserve the existing
marker and complete original log bytes permanently. At a frozen, witnessed
head, seal the full previous history segment with an independently signed
manifest binding its exact bytes, lengths, credential generation, root
version/digest, identity and prior segment commitment. Create a successor
segment under a fresh history credential using exclusive creation; force
both new headers and directories. Its header must reference the sealed
predecessor and immutable witness commitment, not a fresh genesis.
The witness must commit the exact segment transition before any writes under
the new key. All future reads must validate every sealed segment in order,
the whole canonical root chain and the active segment against the latest
witness, rejecting omissions, forks, substitutions, old-generation writes
and duplicate or reordered segment transitions. The original authenticated
bytes remain available for independent audit; retained old HMAC keys, if
needed, stay outside the serving principal in isolated audit custody.
The independent signed seal, not a claim that a symmetric key is verify-only,
prevents changed old history from being accepted. No positive history-key
rotation is claimed until the format, verifier, custody, crash boundaries,
revocation and deployment are implemented and tested. A compromised history
key without a pre-compromise independent commitment cannot establish
continuity; retire the register.

## Quarantine decision table

| Observation | Required outcome |
| --- | --- |
| Unavailable endpoint; no publication attempted, complete latestness later verified | Remain closed until independently reviewed connectivity and identity verification; not an invocation acknowledgement. |
| Missing/reset marker, missing log, torn or corrupt history, unequal logs, wrong identity, invalid authentication | Freeze; retain original evidence; retire if complete continuity cannot be established without repair. |
| Coherent rollback or administrative restore of authoritative storage | Retire; do not use a restored snapshot as authoritative, even if all MACs verify. |
| Suspected key or administrator compromise | Revoke access; independently establish scope and pre-compromise latestness. If uncertain, permanently retire. No v1 history-key compromise recovery. |
| Ambiguous publication, lost response, timeout or crash during write | Quarantine the invocation permanently; no automatic replay, retry, no-start classification or readback acknowledgement. Retire the register if the exact immutable continuity cannot be established. |
| Witness missing, stale, unavailable, forked, corrupt or administratively restored | Stop all admission; retire if independently authenticated latestness cannot be established. |

Retirement is a durable witness record bound to the exact register identity,
not deletion. Keep both logs, marker, independent inventory, relevant
non-secret diagnostics and retirement evidence; never choose the longer
copy, repair from a backup, reset, re-provision or issue a new epoch for the
retired register. A future operator decision may restore connectivity to the
same service only when the complete continuity requirements hold. There is
no implemented quarantine release API and no authorization to reclassify
effects or resume execution.

## Implemented qualification witness and segment verifier

`AuthorityOperationsWitness` is an internal, standalone operator component.
It stores canonical Ed25519-signed records in an existing independently
administered append-only log, serializes exact witness-digest CAS with a
dedicated mutex, validates the full signed chain and forces each append.
The production-default attribute guard rejects unprotected witness logs;
the offline fixture relaxes it. Enrollment requires a never-initialized
marker and empty history. The operator must independently prevent
re-enrollment and rollback of the witness log itself; no file API can
prove that administrative property.

`AuthorityOperationsHistory` checks both copies of each bounded segment,
the exact marker, canonical full root chain and prior pinned byte prefixes.
An old segment must match its previous signed pin exactly. A new segment
header binds the preceding pin, final root and new history credential ID
under the new HMAC key. Only the current segment uses the active HMAC key;
the retired segment is authenticated by the independently signed exact
byte commitment. Old bytes are never rewritten. A cutover cannot advance
the root; publication requires exactly one valid successor. Frozen and
prepared states cannot publish, and quarantine cannot transition to any
active state. A stale witness CAS or torn signed record fails closed.

The qualification component consumes independently collected byte images
and operator evidence digests. It does not itself collect remote files,
attest the operator, install or revoke SSH keys, create new segment files,
or participate in the v1 service's publication acknowledgement. A signed
VERIFIED record is qualification evidence only, never runtime admission.
Tests exercise the component with an actual local signed log, but do not
demonstrate an independent physical deployment.

## Required deployment evidence and acceptance matrix

The following is a checklist for a separate, non-production qualification
register. Each row needs exact artifact and commit identifiers, independent
operator/reviewer identities, dated redacted observations and actual
outcomes. Do not weaken the production attribute guard to pass a real
deployment check. Destructive failure injection is limited to qualification
storage; never use the live register or restore it for testing.

| Gate | Evidence required | Current evidence |
| --- | --- | --- |
| Distinct administration and independent enrollment | Real separate Linux host and witness, independent inventory, protected directories and principals, reviewed artifact and dependency closure | Not performed |
| SSH client and host key rotation | Actual pinned connection, forced command only, denied old client and old host pin, no forwarding or lingering sessions | Not performed |
| Transport MAC cutover | Same identity and byte-identical history, denied old MAC/replay/stale CAS, new-key positive CAS on qualification register | Local process regression only; exact-head CI pending |
| History credential rotation | Implemented immutable segment transition, retained old bytes, full verification and revoked old write credential | Qualification verifier and signed witness tests; physical segment deployment and revocation not implemented |
| Latestness and quarantine | Independent witness continuity, coherent rollback, reset, wrong key, lost witness, administrative restore, retirement and interrupted cutover | Local signed witness and adapter fault tests only; independent witness deployment not performed |
| Concurrency and crashes | In-flight old process drainage, competing new/stale clients, crash before/after every witness and cutover boundary, ambiguous publication denial | Local process regression only; distributed boundaries not implemented |
| Append-only and durability | Actual positive Linux append-only enforcement, protected lock and directory identity, file/directory forces, controlled power interruption and restart on persistent hardware | Not performed |
| Repository trust | All six exact-head CI jobs, independent strict review, explicit owner approval | Pending |

For the real deployment record the OS/kernel, filesystem and mount options,
persistent device and write-cache/power-loss behavior, actual inode and
ownership metadata, append-only attributes, separate administrator and
witness control, installed artifact hashes, and verified SSH fingerprints.
Exercise normal publication and controlled abrupt power interruption at
each persistence boundary on the qualification system; a JVM halt or
successful fsync return is not evidence of physical power-loss durability.
After restart independently verify the witness, inventory and complete
authority histories; ambiguous publication must remain quarantined.

The present process fixture deliberately relaxes the append-only prerequisite
and cannot establish these claims. Keep #248 open until all required
evidence exists. Keep #105 runtime integration and Started publication
blocked; no Session Protocol v1, C3 pin or CI trust-boundary change is
authorized by this document.

Related: [Effect intents and independent authority deployment](EFFECT_INTENTS.md),
[checkpoint policies](SESSION_CHECKPOINTS.md), #104, #105, #106 and #241.
