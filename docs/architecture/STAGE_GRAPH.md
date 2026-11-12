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
- Only the owning role can request a revision. Frozen manifests additionally
  require a host-attested independent review scoped to the exact path, digest and
  revision. The existing ToolAuthorizationPolicy still denies protected paths,
  role escalation and unapproved writes. Actual workspace revalidation and
  physical effect remain exclusively in the trusted write runtime.
- Stage state edges are CREATED → READY → RUNNING → ACCEPTED/REJECTED/FAILED/
  CANCELLED (early CANCELLED is allowed); terminal stages cannot be reopened.
  A retry uses a **new StageRunId**. Graph edges are CREATED → RUNNING →
  SUCCEEDED/FAILED/CANCELLED. Transitions are policy decisions, not mutations.
- StageBudget and ChildGraphReference contain typed, checked depth, dependency,
  token, time and child-count *metadata*. The graph contract checks coherence,
  duplicates and DAG cycles, but does not dispatch children, decrement budgets,
  merge results or implement recursive scheduling.

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
metadata, malformed/unauthorized manifests, frozen revision attempts, self-review,
mismatched content hashes, unverified reviews and reviewer rejection.

**Follow-on ownership:** #73 Koog and role execution; #74 host-adapter write
integration; #75–#77 artifact production, Developer and Reviewer execution;
#78 recursion and shared budget enforcement; #79 integration and correlated
event E2E; #10 durable recovery.
