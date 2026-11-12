# Bounded recursive stage requests (issue #78)

**Status:** Host-owned in-memory recursive authorization, reservation, accounting,
cancellation, deterministic execution and fake-executor coverage. No persisted
child execution, production Koog child dispatcher, workspace writes, CLI five-role
end-to-end workflow or crash recovery are claimed here. Those integration
boundaries remain assigned to #79 and #10.

## Ownership and admission

An already admitted, RUNNING SDD, ATDD, TDD, Developer or Reviewer stage is
selected by the host from a `StageGraph` passing `StageGraphPolicy.validate`.
The host constructs a `RecursiveRootGrant` with exact writable-path and
read-tool scopes, global deadline and shared limits. A root scope cannot
include frozen paths or paths forbidden by `ToolAuthorizationPolicy`.
`StageGraph.children` must be empty: pre-existing child references are not
reconciled by this in-memory scheduler and require authoritative integration
(#79). The constructor fails closed rather than ignoring them.

The host invokes a `RecursiveStageExecutor` with a
`RecursiveStageContext`. This stage-owned port may propose a typed
`RecursiveChildProposal`, and may report host-measured token/tool usage.
Neither a model response nor an arbitrary caller-supplied graph ID is a
capability. The scheduler validates the context against the active parent and
checks that the proposed parent graph ID, parent stage run ID and origin match.

Child stages keep the parent's stage kind and therefore the **same role**.
They receive a fresh, host-computed stage run ID and a stable graph ID computed
from the parent graph ID, stage run ID and request ID (SHA-256). Write paths
are exact subsets of parent paths, read-tool sets are subsets of parent grants,
and frozen paths are inherited unchanged. Reviewers can propose **read-only**
children but cannot acquire writes or revise accepted/frozen artifacts.
All physical writes still need the existing trusted host authority, current
workspace checks, stage-specific review, receipts and commit CAS; this scheduler
provides none of those credentials.

## Graph and progress limits

`RecursiveGraphPolicy.validate` is pure and verifies unique graph/stage IDs,
parent-stage identity, role and scope inheritance, child reservation sums,
per-parent child ceilings, global child count and a Kahn-traversed acyclic
parent/dependency DAG. A child may only depend on already-known siblings.
The admission gate refuses self-dependency, invalid/forward dependencies,
repeat request IDs, same-task siblings, and ancestor task-identity loops.
Denied proposals (including malformed ones) count toward a **per-parent**
attempt limit; siblings and unrelated subtrees have independent limits.
A repeated request ID is refused. Accepted sibling and ancestor `taskId`
values participate in no-progress detection. Denied task IDs are **not**
retained as no-progress facts: a corrected proposal with a fresh request ID
may retry the same task, but only within that parent's bounded attempt limit.
Each parent has at most max(16, maxChildren * 4) recorded valid-ID attempts,
so retained request IDs are bounded by that cap times the number of parents.
A hard ceiling of 512 children bounds in-memory topology traversal.

There is one shared budget **within a single root stage's subtree**. Every
child **irrevocably reserves** tokens
and tool calls from the direct parent's unspent and undelegated grant.
Grandchildren reserve *within* the parent child's allocation and are not
double-counted at the root. Host-measured `charge` increases spent usage
monotonically and fails closed if it exceeds the remaining unallocated amount.
A failed charge marks the stage failed and cancels its descendants; an executor
cannot turn that error into a successful run. Reservations are never refunded,
including for rejected, failed or cancelled children. Unknown model/tool usage
must be conservatively charged at its reserved upper bound by the caller.
Separate root schedulers for SDD, ATDD, TDD, Developer and Reviewer do **not**
yet share one aggregate graph-wide budget; the host must grant non-overlapping
allocations or implement cross-stage accounting in #79. The PR's shared
reservation guarantee is scoped to one scheduler instance.

Depth, per-parent child count, global child count, request attempt count,
individual stage duration, inherited parent deadline and global deadline
are enforced. Stage timeouts are bounded during execution, and authoritative
clock checks also run at entry, admission, charge and completion. Only
SUCCEEDED dependencies can execute; parent completion requires every child to
be SUCCEEDED. A dependent of a FAILED child is never run; its parent finishes
with `CHILD_WORK_INCOMPLETE` and cancels unfinished descendants.

Cancellation traverses descendants iteratively, marks unfinished nodes
CANCELLED, signals their `Cancellation` objects and cancels the corresponding
active coroutine `Job` without erasing completed history. Host-initiated
`cancel(childId)` produces the typed `Cancelled` outcome while leaving an
active calling/ancestor job intact. **Cancellation of the caller itself is
re-thrown** after terminal state recording, preserving structured concurrency.
A parent may finish cleanup after child cancellation, but cannot report
success while any child remains incomplete. A `CancellationException`
raised by an executor without cancellation of its stage job or caller is
classified as `STAGE_EXECUTOR_FAILED`, not as host-initiated cancellation.
Only this scheduler's `withTimeoutOrNull` deadline maps to `TimedOut`.
Executors performing blocking or external work **must** observe their
cancellation token or coroutine cancellation and stop promptly; the scheduler
cannot forcibly terminate uncooperative native or external processes.
Failed usage charges and timeouts also signal the stage's own token.

## Events and protocol compatibility

`RecursiveEvent` records the stage origin, parent/child graph and stage
identities, optional request identity, typed denial/status and cumulative
spent/delegated usage. Event payloads deliberately exclude prompts, model
text, raw tool outputs and credentials. The in-memory event sink is
best-effort telemetry, **not** an authenticated persistent audit or source of
authority. The sink is invoked under the scheduler lock and must be non-blocking
and bounded; asynchronous durable emission belongs to the host integration
(#79). Downstream event persistence/correlation belongs to #79.

This is an **in-process Kotlin contract**. No change is made to Session
Protocol v1, CLI JSON, task-store schema, `StageGraph` serialization or
existing scheduler recovery. Do not interpret graph-policy `Allowed` as an
authority grant, or child completion as acceptance of a workspace artifact.

## Deterministic verification

- `RecursiveGraphPolicyUnitTest` (`@Tag("unit")`) covers cycles, orphan
  parents, invalid root, invalid reservations and dependencies, inconsistent
  terminal-parent states, duplicate IDs, no-progress/repeat requests, depth,
  hard and configured child ceilings, token/tool exhaustion, monotonic usage,
  deadline admission, cancellation admission and permission/frozen scope.
- `RecursiveGraphSchedulerIntegrationTest` (`@Tag("integration")`) uses
  offline stage executors, a fixed clock and an injectable mutable clock.
  SDD and Developer each run a two-level nested graph. All five origins
  demonstrate denied escalations; Reviewer runs an approved read-only child.
  The suite exercises per-parent attempt isolation, global child limit,
  dependent failure, host cancellation of a nested child without cancelling its
  parent, caller cancellation propagation, own wall-clock timeouts,
  stage/global charge deadlines and failed usage.

Run:

```sh
./gradlew :core:contracts:unitTest :core:session:integrationTest checkArchitecture checkTestTiers
```
