# Host-owned five-role orchestration (issue #79)

**Status: partial implementation.** `RunFiveRoleWorkflowUseCase` provides a
bounded, in-process coordinator for `SDD → ATDD → TDD → Developer → Reviewer`.
An in-memory fake-host integration test executes all five existing role use
cases. This is **not** an installed writable CLI or a completed issue #79.

## Execution and authority

The coordinator never executes a model tool directly and never grants physical
workspace authority. It delegates role proposals to the already existing
`RunPreparationStageUseCase`, `RunDeveloperStageUseCase`, and
`RunReviewerStageUseCase`. Those use cases retain their independent
`PreparationStageHost`, `StageToolSessionOpener`, and `ReviewerStageHost`
permissions and checks.

The host constructs the `FiveRoleWorkflowPlan` (output IDs/paths, source file
targets, immutable instructions, model and budgets). A model response is not a
trusted plan, artifact approval, tool receipt, or Reviewer decision.

At each stage boundary the coordinator:

1. Reads an authoritative host graph/accepted-ID snapshot and rejects drift.
2. Requires the stage to be CREATED or READY; the corresponding use case
   performs its own policy admission and atomic stage lease.
3. Accepts only the concrete use case's committed ACCEPTED result; a denial,
   failure, human-approval requirement or uncertain CAS stops execution.
4. Re-reads the authoritative snapshot to confirm the committed stage result.
5. Calls `FiveRoleWorkflowHost.advance` before admitting the next role.

`advance` is a **trusted host CAS boundary**, not a request to automatically
commit any dirty workspace. A production implementation must verify the
trusted write/check receipts, current accepted/frozen artifact digests and
source postimages, then reconcile that exact accepted change set into a clean
Git baseline suitable for the next `TrustedWriteRuntime.open`. It must fence
concurrent workspace mutations throughout the read/commit interval. A policy
`Permit` does not substitute for this evidence.

A Developer handoff includes its output manifest, changed-file pre/postimages,
write tool ID, checked evidence and usage. The host must independently verify
these against retained authenticated receipts before constructing the Reviewer
lease. The Reviewer has no write tools; its acceptance remains distinct from
the Developer role and is rechecked at `finishSuccess`.

`finishSuccess` must atomically validate all accepted frozen bytes, the
independent Reviewer acceptance attestation and legal
`RUNNING → SUCCEEDED` transition. The coordinator checks the returned graph
shape but cannot make a host's unverified claims authoritative.

## Compatibility

The existing `recursive-coder run` command remains read-only and unchanged.
No `FiveRoleWorkflowHost` binding is provided by default session composition.
The native TaskScheduler and Session Protocol v1 schema are unchanged.

## Outstanding issue #79 acceptance

- Implement a production Linux host with real trusted receipt retention,
  isolation and fenced per-stage Git reconciliation.
- Wire an explicit opt-in scoped-write CLI route and stage-specific deterministic
  FakeModel fixtures. An executable CLI E2E must cover all five roles.
- Wire bounded `RecursiveGraphScheduler` child graphs with **two distinct
  nested stage origins**, accepted child manifests and parent integration.
- Exercise negative CLI acceptance: dependency cycle, budget limit, Reviewer
  rejection, protected specification/test mutation and artifact conflict.
- Wire correlated role, child, transition, request, tool and budget event metadata
  into the installed CLI's durable journal without representing unknown usage
  as zero or disclosing prompts/credentials.

No checkpoint/crash recovery (#10), performance benchmark (#11), live model
provider or external task coordinator is required for #79.
