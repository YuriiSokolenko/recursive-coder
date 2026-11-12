# Stage graph contracts and policy gates (issue #72)

**Status:** Pure Kotlin contracts and deterministic host-side decisions implemented in
:core:contracts. Agent execution, recursion scheduling, physical writes, recovery and
event emission are not implemented by this change.

## The five roles and their immutable handoffs

| Stage | Actor role | Required accepted inputs | Output |
| --- | --- | --- | --- |
| SDD | SDD | — | Requirements manifest |
| ATDD | ATDD | Requirements | Acceptance-test manifest |
| TDD | TDD | Requirements, acceptance tests | Unit-test manifest |
| DEVELOP | Developer | Requirements, acceptance tests, unit tests | Implementation manifest |
| REVIEW | Reviewer | Requirements, acceptance tests, unit tests, implementation | Independent typed verdict |

A stage's explicit dependency IDs must include every producer of its declared inputs.
Before a stage can enter RUNNING, all dependencies must be ACCEPTED, each required
artifact kind must be provided exactly once, and all inputs must be accepted and
frozen. Crucially, acceptance is checked against **host-owned accepted artifact IDs**
in addition to the manifest state. Model-generated content or a forged manifest
cannot populate that set. Similarly, accepting a stage's output requires host
approval of the output artifact; accepting a review requires a separate host-verified
review-stage ID, matching implementation digest and independent actor identities.
The active SDD, ATDD, TDD, Developer and Reviewer nodes must use distinct AgentId
values. This does not imply distinct model providers or physical processes.

These terms distinguish **handoff acceptance** from **final workflow success**.
The Developer's implementation may be accepted as an immutable handoff for
independent review, but the graph is not SUCCEEDED until REVIEW accepts it and
the host independently verifies that verdict. A reviewer is read-only and has no
manifest output or write authority.

## Immutable data and ownership

- GraphId, StageRunId, ArtifactId and AgentId are distinct, validated ID value
  types; GraphCorrelation and StageCorrelation tie stage events back to a session,
  request, graph, correlation and causation ID. Children carry a separate graph ID
  and a parent stage reference.
- StageArtifactManifest pins the declared path, lowercase SHA-256 digest, role,
  producing stage, positive revision, approval and freeze state. A new revision
  produces a new value; a manifest is not an instruction to mutate a workspace.
- Only the owning role can request a revision while the graph is RUNNING.
  Frozen manifests additionally require a host-attested independent review
  scoped to the exact path, digest and revision. The same declared path may
  not be claimed by two distinct manifests (including historical attempts):
  ambiguous path ownership fails with DUPLICATE_ARTIFACT_PATH. A retry that
  emits a new artifact ID must use a new path; editing the original frozen
  path requires the original owner and its approved revision process.
  The existing ToolAuthorizationPolicy still denies protected paths,
  role escalation and unapproved writes. Actual workspace revalidation and
  physical effect remain exclusively in the trusted write runtime.
- Stage state edges are CREATED → READY → RUNNING → ACCEPTED/REJECTED/FAILED/
  CANCELLED (early CANCELLED is allowed); terminal states are immutable.
  A retry creates a **new StageRunId**, and the terminal predecessor points to
  the later same-role attempt via **supersededBy**. The predecessor and its
  original artifact manifests remain history, but only one non-superseded
  attempt per stage kind may be active. Supersession must point forward and
  preserve stage kind. This annotation is trusted host state, not an LLM
  instruction or a stage-state transition.
- The graph can SUCCEED if exactly one active attempt of each of the five kinds
  is ACCEPTED, with all active outputs host-accepted, all active input producers
  non-superseded, and the active reviewer independently verified. Old failed
  attempts and their artifacts never substitute for active accepted handoffs.
  In particular, retries must update downstream inputs and dependency IDs.
- Graph edges are CREATED → RUNNING → SUCCEEDED/FAILED/CANCELLED. FAILED or
  CANCELLED at the graph level is a *decision*, not a command to cancel active
  stages; cleanup and cancellation propagation belong to the future executor.
  Stage/graph transition decisions do not mutate snapshots.
- Stages with RUNNING/ACCEPTED statuses are not retroactively invalidated
  by changes to a predecessor in a raw snapshot. The *admission gate* checks
  accepted predecessor states and host-approved inputs; graph SUCCEEDED checks
  that its active handoff chain is current. Revision/digest-based downstream
  invalidation and stale review revocation require authoritative host revision
  records and are deferred to #75/#78/#79. Artifact-ID membership alone is not
  a cryptographic attestation of a **specific** revision.
- StageBudget and ChildGraphReference contain typed, checked depth, dependency,
  token, time and child-count *metadata*. The graph contract checks coherence,
  duplicates and DAG cycles, but does not dispatch children, decrement budgets,
  **sum child reservations**, merge results or implement recursive scheduling.
  Those tasks are owned by #78.

## Trust and serialization boundary

StageGraphPolicy consumes snapshot values and explicit host attestations,
never prompts, raw model responses, tool command text or files. Callers are
responsible for producing the host-accepted ID sets and independently verifying
review provenance. A StageGateDecision.Permit alone is **not** a tool capability:
trusted adapters must recheck the active workspace, policy, manifest digest,
independent approval and receipts before taking action. Both admission and
revision authorization fail closed when evidence is absent.

These are **in-process Kotlin contracts**, not a new externally serialized
wire shape. Session Protocol 1.0 JSON Schema, fixture files, event envelopes,
CLI behavior and tasks.json are unchanged. If persistence or external wire
serialization of stage graphs is introduced later, it must receive an explicit
version and compatibility tests rather than silently extending Session v1.

## Deterministic verification

Run:

~~~sh
./gradlew :core:contracts:unitTest checkArchitecture checkTestTiers
~~~

The tagged pure-unit suite StageGraphPolicyUnitTest contains a fixed five-role
success fixture and literal transition matrices for every stage and graph
state. Negative cases cover missing inputs and host acceptance, illegal edges,
dependency cycles, mismatched session/graph IDs, duplicate IDs, invalid budget
metadata, malformed/unauthorized manifests, frozen revision attempts,
duplicate paths, missing actor assignments, terminal retry supersession,
stale downstream handoffs, self-review, mismatched content hashes, unverified
reviews and reviewer rejection.

**Follow-on ownership:** #73 Koog and role execution; #74 host-adapter write
integration; #75–#77 artifact production, Developer and Reviewer execution;
#78 recursion and shared budget enforcement; #79 integration and correlated
event E2E; #10 durable recovery.
