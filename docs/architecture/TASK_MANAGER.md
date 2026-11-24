# Native Task Manager (issue #7)

The Task Manager is a local, first-party queue and dependency scheduler. It does not
require GitHub Issues, GitHub Actions, a hosted API, or an external runner.
It uses the current read-only `RunSessionUseCase` (`SessionController` remains a compatibility façade) and the configured FakeModel or explicitly authorized Model Gateway. No writable coding-agent capabilities are implied.

## Commands

Use the installed application from `./gradlew installDist`:

```bash
recursive-coder tasks --store /absolute/queue enqueue /absolute/session-request.json --priority 80
recursive-coder tasks --store /absolute/queue enqueue /absolute/dependent-request.json --depends-on root-task-id
recursive-coder tasks --store /absolute/queue list
recursive-coder tasks --store /absolute/queue show root-task-id
recursive-coder tasks --store /absolute/queue run --concurrency 2
recursive-coder tasks --store /absolute/queue pause
recursive-coder tasks --store /absolute/queue resume
recursive-coder tasks --store /absolute/queue cancel root-task-id
recursive-coder tasks --store /absolute/queue retry root-task-id
recursive-coder tasks --store /absolute/queue cancel-all
```

All commands also support generated help, for example `recursive-coder --help`,
`recursive-coder tasks --help`, `recursive-coder tasks enqueue --help`, and
`recursive-coder run --help`. The CLI uses Clikt 5.1.0 for typed option parsing.
Invalid command names, unknown options, missing values, malformed integer values,
repeated single-value options, and empty entries in `--depends-on` fail with
exit code 2 before task store initialization or session execution. Successful
`--help` exits 0. Standard JSON responses remain on stdout, while errors and
session summaries remain on stderr. Invoking only `recursive-coder`,
`recursive-coder tasks`, or `recursive-coder tasks --store PATH` without a
subcommand is a usage error (stderr, exit 2), not an implicit successful help
request. Path and syntax validation messages are formatted by Clikt and may
appear in a different order than in the previous parser (for example, an
invalid `--store` is reported before an invalid enqueue request path).
Neither parser-level failure creates task state. Service-level validation is
unchanged.

Omit `--store` to use `~/.recursive-coder/tasks`. The JSON request's
`task.taskId` is the native task identity. Dependencies must already be
enqueued. Priority is an integer from 0 to 100 (default 50).
Tasks may be inspected without starting a scheduler; `run` is a foreground
command that dispatches until no runnable tasks remain or the queue is paused.

For each attempt the scheduler derives `session-<taskId>-<attempt>` and
`request-<taskId>-<attempt>`, sets invocation source to `scheduler`, and
uses a fresh output directory beneath `<store>/runs/<taskId>/attempt-N`.
The request template is parsed and **shape-validated** at enqueue time and
snapshotted, so editing the input JSON file later cannot change the queued
configuration. Workspace existence, capability negotiation, credentials,
model availability, effective budgets and read-only tool policy are checked
by SessionController **at dispatch**, not promised at enqueue. Store the queue **outside** the
working Git repository so its session outputs cannot mutate the workspace.

## Pure Task policy boundary (issue #26)

Task entities, DAG validation, state-transition guards, dependency readiness,
fairness, retry/cancel decisions and claim ownership live in `TaskPolicy.kt`.
`TaskPolicy` consumes an immutable `TaskQueue` snapshot and a caller-provided
ISO timestamp, and returns a new snapshot (or a `TaskClaim` with a new snapshot
and selected task). No policy decision reads the system clock, accesses a file,
launches a process, runs a model or mutates shared state.

`TaskService` is a compatibility façade that delegates task business methods to independent Use Cases. `EnqueueTaskUseCase` owns enqueue admission; the remaining Use Cases own their corresponding policy decisions. Each mutation reads a snapshot, invokes `TaskPolicy` and commits the decision inside one `TaskRepository.update` under the same mutex and OS lock. `TaskScheduler` still owns
worker execution, shutdown and polling, not the claim decision. Storage
continues to serialize the existing v1 `tasks.json` fields, state names,
session ID scheme, event history (128 recent transitions plus pruned count)
and default priorities without a migration.

`TaskPolicyUnitTest` is tagged `unit` and covers the state matrix,
rejections, DAG errors, wake-up, starvation, queue ordering, pause/resume,
claim ownership, late completion, recovery and event retention using
in-memory fixtures. `TaskManagerTest` remains tagged `integration`
and exercises the real store, locks, scheduler workers, Git fixture and
durable restart behavior. Run `./gradlew unitTest integrationTest` for
the tiered suites, or `./gradlew test` for the full unfiltered suite.


## Application Use Cases (issues #58 and #59)

`tasks enqueue` now consumes the `EnqueueTaskUseCase` contract resolved from a command-local Koin composition root, with `DefaultEnqueueTaskUseCase(TaskRepository, Clock)` as the implementation. Submission parsing and priority admission occur before mutation; DAG/dependency checks and publication occur atomically under `TaskRepository.update`. A fixed-clock/in-memory test covers successful blocked/ready submissions, rejections with rollback and concurrent same-ID contention.

`recursive-coder run` and scheduled task execution use `RunSessionUseCase` resolved through Koin at `:app:cli`. The implementation owns all existing session lifecycle coordination and verification; the old `SessionController` API delegates for compatibility. There is no global Koin container and no shared mutable session state. A single task command-scoped `TaskComposition` shares one `TaskRepository` across all task Use Cases and scheduler operations. The CLI explicitly owns the `TaskStore` it constructs, whereas tests and external composition callers can borrow a repository without transferring its lifetime. On command termination the Koin container closes first, then an **owned** repository is closed if its adapter implements `AutoCloseable`; borrowed repositories are never closed by the composition. Shutdown is idempotent, attempts repository cleanup even after DI close fails, and retains multiple cleanup failures using suppressed exceptions. `executeCli` cleans up on success, Clikt usage errors, and command exceptions. The current `TaskStore` and in-memory adapter own no long-lived handles and therefore do not implement `AutoCloseable`; locks and channels continue to be scoped to individual transactions and scheduler leases. Session Koin containers are closed after each invocation.

Issue [#59](https://github.com/YuriiSokolenko/recursive-coder/issues/59)
migrates the remaining operations. `TaskQueriesUseCase` groups read-only
list/show/pause-status inspection. Cancel, retry, pause, resume, cancel-all,
and replace-dependencies each have an interface and a `Default*` implementation.
For scheduled attempts, `ClaimNextTaskUseCase`,
`CompleteTaskAttemptUseCase`, `InterruptTaskAttemptUseCase`,
`RecoverInterruptedAttemptsUseCase`, and `PrepareTaskAttemptUseCase`
are injected directly into `TaskScheduler`.

### Scheduler lease and recovery

The scheduler owns the exclusive repository lease across recovery, dispatch,
and worker shutdown. `RecoverInterruptedAttemptsUseCase` calls
`TaskRepository.requireSchedulerLockHeld()` **before** opening a transaction.
Without the lease, recovery throws `IllegalStateException` and does not read
or modify the queue. The in-memory adapter checks current-thread lock ownership.
The durable adapter exposes a current-thread guard only while holding the real
`.scheduler.lock` OS file lock.

Cross-process exclusion remains the OS file lock's responsibility. Before
opening `.scheduler.lock`, a JVM-wide set of canonical store directories
prevents another `TaskStore` instance or thread (including parent-path
aliases) from opening and closing a second channel on the same lock file.
This avoids accidentally releasing the first POSIX lock. The JVM guard is
cleared only **after** the OS lock and channel close.

Ordinary queue transactions use a separate `.store.lock`. Their JVM mutex
is also keyed by the canonical directory so aliases cannot open overlapping
channels; nested transactions on the same thread fail before opening a
second channel. This JVM serialization complements, rather than replaces,
cross-process file locking.

The recovery check is intentionally thread-bound because
`TaskScheduler.runUntilIdle` executes recovery synchronously while holding
the lease. Moving recovery to another thread would require an explicit lease
capability or a deliberate contract redesign. The compatibility method
`TaskService.recoverInterrupted()` has the same enforced precondition:
callers must hold `store.withSchedulerLock` on their current thread.

Claim and finish use atomic transactions. Completion rejects unsupported
statuses before mutation, and stale session IDs cannot overwrite newer
attempts. `TaskService` and its scheduler constructor are compatibility
adapters only. Fixed-clock, in-memory, durable-store, CLI, and sandbox tests
cover denial, contention, recovery, and stale-session fencing.

## State transitions

- New tasks begin in `created` then become `ready` or `blocked`.
- Unmet prerequisites keep tasks `blocked`; all successful prerequisites
  make the task `ready`.
- A scheduler atomically claims a ready task, records an increasing attempt
  and session identity, and transitions it to `running`.
- Success and failure produce `succeeded` and `failed`; cancellation
  produces terminal `cancelled`.
- A failed, cancelled, or attention-required prerequisite makes its
  descendants `needs-attention`, with the blocking task recorded in the reason.
  When the prerequisite is manually retried, dependency-derived attention
  clears and descendants return to `blocked` or `ready`.
- Global `pause` maps queued tasks to `paused`. Running sessions are
  allowed to complete. `resume` recomputes readiness, preserving positions
  only for tasks already ready when paused; tasks made runnable during the
  pause (including retries) join after existing ready tasks.
- `cancel` and `cancel-all` persist cancellation immediately.
  A foreground scheduler polls the store and requests cooperative cancellation
  of any active session. A late successful result cannot overwrite a
  cancelled task. `cancel-all` also pauses future launches.
- `retry` is explicit for failed, cancelled, or attention-required tasks.
  Attention caused by an unsuccessful dependency cannot be retried directly;
  the prerequisite must be resolved first. Each new claim gets a fresh session
  identity; no session output is reused. The snapshot retains the most recent
  128 structured transitions per task, plus the number pruned, while `list`
  and `show` omit request bodies. Previous attempt outputs remain in their
  separate attempt directories.
- The persisted attempt counter is a nonnegative `Int`; `Int.MAX_VALUE`
  is a valid final claim (issue #193), including its usual v1 request, owner
  and output directory. Reconciliation moves exhausted queued tasks into
  `needs-attention` with reason `attempt counter exhausted` before selection,
  including while paused, and propagates dependency attention in the same
  transaction. No dispatch is counted for exhaustion; other eligible tasks
  remain schedulable. A final running attempt may finish normally; recovery
  retains the interrupted owner and reason without replay. Retry at the limit
  fails before mutation, regardless of whether the final attempt failed,
  was cancelled or requires attention. Use a new task identity for further
  work; the counter is never reset or wrapped. No store or protocol migration
  is required.

## Ordering, durability, and recovery

The scheduler runs at most 1–32 local sessions concurrently. On seven of every eight dispatches the scheduler selects the maximum of
`priority + min(waitTurns,1000)*10`, breaking ties by earliest queue order.
Every eighth dispatch selects the oldest ready task regardless of priority.
A waiting task also gains one turn whenever another task is claimed. This
persistent FIFO quota prevents starvation even if all tasks entered the
queue together with unequal priorities. Aging dominates quickly: after about
ten unselected dispatches a priority-0 task outranks a fresh priority-100 task,
so priorities order bursts rather than long-running queues. Dependency unblocking and manual retries
re-enter the queue with fresh order, even when they become ready during a
global pause. Only tasks already ready before a pause preserve queue order
and waiting credit on resume. An active old attempt keeps consuming its
concurrency slot after cancel/retry until the worker actually exits; attempts
are tracked by session ID rather than task ID.

All queue mutations are serialized with both a JVM mutex and an OS file lock.
Changed state is written to an fsynced temporary snapshot and atomically renamed
over `tasks.json`, followed by a directory fsync; read-only and no-op
transactions do not rewrite it. On Linux a failed directory fsync fails the
transaction (#162): the renamed snapshot may already be visible but is not
known to be durable, so the operation reports an error instead of claiming a
durable commit that a power loss could undo.
A second scheduler cannot obtain the long-lived `.scheduler.lock`;
ordinary CLI operations retain separate short transaction locks. The lock
remains held until all local workers terminate, including on shutdown.
A corrupt store is rejected without being overwritten.

After a scheduler process exits abruptly, its OS lock is released. A newly
started scheduler changes residual `running` tasks to `needs-attention`
and **does not** replay them. Operators must reconcile any uncertain tool
effects before manually retrying. Orphan temporary files are ignored; the
last atomic `tasks.json` is authoritative. This is task-store recovery,
**not** session checkpoint replay or fencing of external effects.

Dependencies are validated as a directed acyclic graph with known,
non-duplicated task IDs using iterative traversal (so long chains do not
overflow the JVM stack). The `ReplaceTaskDependenciesUseCase` and compatibility `TaskService.replaceDependencies` remain **intentional programmatic APIs** for an embedding or future consumer, not an installed CLI subcommand. Direct use-case tests verify successful dependency edits and atomic rollback on invalid prerequisites; no CLI command is added solely to satisfy composition wiring. Edits are allowed only before the affected task's first attempt.

## Known limitations

The scheduler is intentionally foreground, with no permanent daemon,
remote workers, or replay of partially completed sessions. A task that
has been claimed but interrupted needs operator attention. The `tasks run`
CLI installs a shutdown hook for SIGTERM/normal JVM shutdown: it requests
cooperative cancellation and records unfinished attempts as `needs-attention`
rather than pretending the user cancelled the task. Unexpected scheduler
failures follow the same rule. SIGKILL/power loss cannot execute JVM hooks;
a later scheduler detects stranded running tasks on startup.
Cancellation is cooperative: an unresponsive provider can keep a worker alive
until SessionController enforces its deadline. Per-workspace locks may
serialize sessions even when the global concurrency limit exceeds one.

The new `DefaultRunSessionUseCase` implements the read-only bootstrap
engine behind `RunSessionUseCase`; `SessionController` is a compatibility façade. Full Koog coding workflows, **CLI integration of the existing standalone trusted write/check runtime**, and checkpoint recovery remain separate milestones. The standalone runtime exists in `:infra:workspace`; Task Manager cannot yet dispatch an autonomous write-capable development session.
