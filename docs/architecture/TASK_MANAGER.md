# Native Task Manager (issue #7)

The Task Manager is a local, first-party queue and dependency scheduler. It does not
require GitHub Issues, GitHub Actions, a hosted API, or an external runner.
It uses the existing SessionController and its configured FakeModel or explicitly
authorized Model Gateway. No writable coding-agent capabilities are implied.

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

`TaskService` remains the public use-case API. Inside each existing
`TaskRepository.update` it takes a snapshot, invokes the policy, and applies
the result under the same mutex and OS lock. `TaskScheduler` still owns
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

## Ordering, durability, and recovery

The scheduler runs at most 1–32 local sessions concurrently. On seven of every eight dispatches the scheduler selects the maximum of
`priority + min(waitTurns,1000)*10`, breaking ties by earliest queue order.
Every eighth dispatch selects the oldest ready task regardless of priority.
A waiting task also gains one turn whenever another task is claimed. This
persistent FIFO quota prevents starvation even if all tasks entered the
queue together with unequal priorities. Dependency unblocking and manual retries
re-enter the queue with fresh order, even when they become ready during a
global pause. Only tasks already ready before a pause preserve queue order
and waiting credit on resume. An active old attempt keeps consuming its
concurrency slot after cancel/retry until the worker actually exits; attempts
are tracked by session ID rather than task ID.

All queue mutations are serialized with both a JVM mutex and an OS file lock.
Changed state is written to an fsynced temporary snapshot and atomically renamed
over `tasks.json`; read-only and no-op transactions do not rewrite it.
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
overflow the JVM stack). The TaskService also exposes a guarded
`replaceDependencies` operation for future API clients, only before the
affected task's first attempt. Invalid graph edits roll back as a single
transaction.

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

The current SessionController still implements the read-only bootstrap
engine; full Koog coding workflows, write tools, and checkpoint recovery
are separate milestones.
