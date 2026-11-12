# Host-approved preparation stages (issue #75)

**Status:** In-process, explicitly opted-in SDD → ATDD → TDD. Developer is now a separate opt-in stage (#76); there is no Reviewer,
child graph, persistence implementation, production model bridge or new CLI command.
See [Developer stage](DEVELOPER_STAGE.md) for its separate host workflow. The existing CLI is read-only.

## The independent roles and host-owned lease

`RunPreparationStageUseCase.execute` invokes **one role**. The host calls it
three times, with separate stage/agent/request identities. SDD produces a
requirements manifest, ATDD requires accepted/frozen requirements and produces
acceptance tests, and TDD requires accepted/frozen requirements plus acceptance
tests and produces unit tests. Every input is checked against host-accepted
artifact IDs and its **actual workspace bytes**. Only strictly decoded UTF-8
inputs enter the next role's instruction. They are wrapped in explicit
`<verified-input kind="..." sha256="...">` boundaries to reduce ambiguity
between instructions and untrusted earlier model-authored content.
The body is entity-escaped (`&` → `&amp;`, `<` → `&lt;`), so code-like
inputs appear encoded to the next role; the role must treat them as quoted
data, not literal source to execute. These delimiters are not a
prompt-injection security guarantee.

A pure `StageGraphPolicy` decision is not a transaction. Before the model is
called, **a trusted `PreparationStageHost.beginStage` must atomically compare
the complete expected graph and accepted IDs against authoritative state**,
apply READY → RUNNING and issue a unique, single-use `PreparationStageLease`.
A second attempt with stale input cannot acquire that lease. beginStage accepts a CREATED or READY expected node and atomically performs any necessary CREATED → READY transition before setting RUNNING. The host must
implement a real compare-and-set over its durable or otherwise authoritative
graph and generation record; an implementation that just echoes the passed
`StageGraph` is unsafe and does not satisfy the interface contract.
`current(lease)` and `finishStage` must recheck the generation; the latter
atomically replaces graph + accepted IDs and consumes the lease. The host MUST
independently authorize every proposed commit against its authoritative graph:
only the leased stage and one owned output can change; accepted IDs may grow
only by that output; an ACCEPTED/FROZEN artifact requires the host's own ACCEPT
decision for the exact digest and identity. Missing/surplus pending evidence is
forbidden. `PreparationStageFinishPolicy` in `:core:contracts` supplies a
reusable pure check for these invariants, while the host remains responsible
for atomic generation fencing and the integrity of its approval evidence. An interrupted
caller must never reuse a stale lease, and a failed CAS is never a success.
A `null` begin means definite rejection; an exception means the CAS may
already have succeeded. It returns `ReconciliationRequired` with **no graph
or accepted IDs**. An exception during finish also means unconfirmed commit,
distinct from a definite `false` result; callers must reload host state.
`abortStage` is the explicit failure/cancellation cleanup for active
generations. A final CAS that linearized before cancellation must not be
rolled back by a subsequent client-side cancellation.

The existing `StageRoleExecutor` (Koog adapter #73) receives a READY view to
satisfy its independent proposal admission policy, while the authoritative
host already holds the RUNNING lease. Model outputs have **no** approval or
tool authority. The live `StageToolAuthoritySource` is created by the trusted
host from the lease; neither the model nor this use case can mint it. The
source must revoke on generation mismatch, terminal state, host approval or
baseline changes. The use case revalidates the lease and source before each
effect and before acceptance; the #74 Linux implementation independently
revalidates the current source during tool operations.

## Materialization, checks and approval

- The new proposal is checked for blank/oversized text, limited by the shared
  `StageRoleLimits`. Only a host-chosen path in the correct role scope can be
  created. Cross-role and frozen spec/test edits remain prohibited by
  `ToolAuthorizationPolicy` and the trusted write runtime.
- The trusted stage-tool session requires `git_diff_check` plus an independently
  host-allowlisted isolated check for **every** SDD/ATDD/TDD stage. Both run
  behind the existing receipt-backed and sandboxed tool port. Denials,
  failed checks, stale authority and write errors are failures, never evidence
  of passing validation.
- Blocking tool operations and host I/O are dispatched to an injectable coroutine dispatcher (default `Dispatchers.IO`). Host `approve` must be prompt, bounded and must return `HUMAN_REVIEW_REQUIRED` instead of blocking for a human; coroutine timeouts cannot forcibly interrupt an uncooperative blocking host implementation. Deadline/cancellation are
  checked before creation, between checks and before host approval. The
  an approval decision must not irrevocably mutate accepted state or trigger
  external effects before the fenced `finishStage` CAS. Late approval responses
  after timeout must be discardable or idempotent. The use case accepts an
  injectable `Clock` for deterministic deadline checks. The
  caller's coroutine cancellation (unlike a bounded approval timeout) is
  rethrown after attempting to mark the host attempt CANCELLED via
  non-cancellable cleanup. In-flight synchronous host commits are fenced
  by their host generation; the authoritative host result wins if a commit
  linearizes before cancellation. An interrupted
  tool call can leave unapproved workspace bytes; the host is responsible for
  safe cleanup/retry and reconciliation. A timed-out approval is an explicit
  failed/timed-out outcome, not a synthetic cancellation of the caller's
  coroutine. No arbitrary subprocess is launched
  by the new orchestration use case.
- The output SHA-256 is computed from **physical output bytes**, matched
  against the intended UTF-8 proposal bytes. Each frozen input is checked
  again before and after independent approval.
- A DRAFT/EDITABLE `StageArtifactManifest` is presented to the trusted host
  for ACCEPT, REJECT or HUMAN_REVIEW_REQUIRED. Accept freezes it as
  ACCEPTED/FROZEN and commits the terminal stage plus the additional
  host-accepted artifact ID atomically. Rejection is terminal and unaccepted.

A HUMAN_REVIEW_REQUIRED decision persists an explicitly pending RUNNING/DRAFT
manifest; it is not an acceptance. The caller can subsequently invoke
`completeHumanApproval` on the **exact pending host graph**, with its own cancellation signal and deadline.
`beginHumanApproval` atomically leases the pending state and returns the
host's **previously stored** successful validation evidence and role usage.
The host contract lives in `:core:contracts`; a
`finishStage(lease, draftGraph, acceptedIds, pending = PreparationPendingApproval(...))`
call MUST atomically store the pending evidence/usage alongside RUNNING/DRAFT.
`approve` itself must not be relied upon to persist that information.
The use case rechecks the draft bytes, frozen inputs and current generation, calls the
host/human approval policy again, then atomically records accepted/frozen,
rejected or still pending. A tampered draft/input triggers a terminal
`abortStage(..., FAILED)`, rather than returning an unclosable RUNNING draft.
An expired or cancelled approval attempt retains the pending draft for
explicit retrial. An exception while reading a draft or frozen input is not
proof of corruption: it yields `HUMAN_APPROVAL_READ_UNAVAILABLE` and keeps
the host-owned pending draft so a later attempt can retry safely. Only a
confirmed missing/mismatched artifact is terminal. Model-generated evidence or a bare human approval
string is never sufficient. This is a volatile in-process continuation; the
durable crash/restart story remains part of #10.

An `Accepted` result includes the frozen manifest, role usage, check
evidence, and the committed in-process graph/accepted IDs. Stage events
include status, approval, digest, role and session/graph/stage correlation
only. Event delivery is best-effort: a throwing event sink cannot downgrade an already committed result or leave an active lease. The host must persist its own authoritative transition audit independently. No raw model output is recorded as an event, no new Session Protocol v1
schema fields are added, and no success is claimed after a failed host commit.
The host is responsible for audit persistence and for applying its own
source-integrity constraints, including a single trusted writer.

## Composition and tests

The CLI composition root provides
`preparationStageModule(trustedHost)`, which must be explicitly combined with
`roleExecutionModule(model, bridge)` and `stageToolModule(lockDirectory)`.
Normal task/session commands resolve none of these privileged stage paths.
The existing Koog bridge remains **fake/fake-v1 only**, and the existing
Linux tool port remains opt-in.

To form a downstream plan, the trusted host attaches artifact IDs as follows:

- SDD: no inputs
- ATDD: the active accepted SDD requirements ID
- TDD: the active accepted SDD requirements ID **and** ATDD tests ID

The required producer IDs must be declared in the stage dependency set.
The host must atomically record the updated plan before beginning the next
stage. The test fixture demonstrates this host-owned binding. The use case
does not silently infer missing dependencies or manufacture approval IDs.

```sh
./gradlew :core:session:integrationTest :app:cli:integrationTest \
  :infra:workspace:e2eTest checkArchitecture checkTestTiers
```

The host port, lease, snapshot and pending-evidence types belong to
`:core:contracts` so a future infrastructure implementation does not depend
on `:core:session`. Its reusable
`PreparationStageHostContract` is published in `core/contracts/src/testFixtures`;
host adapter tests can depend on `testFixtures(project(":core:contracts"))`.
It covers two-thread CAS admission, stale graph/accepted IDs, generation
consumption, authority revocation, rejection of changes to unrelated stages
or accepted IDs, exact host approval for frozen outputs, malformed pending
transactions and pending-human evidence round trips.

Original deterministic integration-tier fixtures use a stateful fake host with
atomic begin/finish/abort and per-attempt generation fences. They cover three
independent roles, exact digest handoffs, concurrent/stale begin rejection,
role and frozen-path fences, missing prerequisites, check/approval failure,
explicit human continuation, cancellation, mutation after approval, and
typed denial evidence, real coroutine Job cancellation, unknown CAS outcomes
and bounded approval timeouts. They have no live I/O but construct typed java.nio.file.Path values in the stage-tool snapshot, which the project's bytecode test tier guard deliberately places in integration. The concrete Koog offline integration and Linux
sandbox E2E remain independently covered by #73 and #74. CI is the
source of executable test status.

**Follow-on:** #76 Developer, #77 Reviewer, #78 recursion, #79 event
integration, #10 durable restart recovery.
