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

Omit `--store` to use `~/.recursive-coder/tasks`. The JSON request's
`task.taskId` is the native task identity. Dependencies must already be
enqueued. Priority is an integer from 0 to 100 (default 50).
Tasks may be inspected without starting a scheduler; `run` is a foreground
command that dispatches until no runnable tasks remain or the queue is paused.

For each attempt the scheduler derives `session-<taskId>-<attempt>` and
`request-<taskId>-<attempt>`, sets invocation source to `scheduler`, and
uses a fresh output directory beneath `<store>/runs/<taskId>/attempt-N`.
The request template is validated and snapshotted at enqueue time, so editing
the input JSON file later cannot alter queued task configuration.
SessionController still enforces its normal workspace, model, budget,
deadline, and read-only tool admission. Store the queue **outside** the
working Git repository so its session outputs cannot mutate the workspace.

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
  allowed to complete. `resume` recomputes readiness.
- `cancel` and `cancel-all` persist cancellation immediately.
  A foreground scheduler polls the store and requests cooperative cancellation
  of any active session. A late successful result cannot overwrite a
  cancelled task. `cancel-all` also pauses future launches.
- `retry` is explicit for failed, cancelled, or attention-required tasks.
  Each new claim gets a new session identity; no session output is reused.
  Complete history (transitions, attempts, timestamps, session IDs, reasons)
  is kept in the task snapshot, while `list` and `show` omit request bodies.

## Ordering, durability, and recovery

The scheduler runs at most 1–32 local sessions concurrently. Among ready
tasks it selects the maximum of `priority + min(waitTurns,1000)*10`, breaking
ties by earliest queue order. A waiting task gains one turn whenever another
task is claimed, preventing unbounded starvation by newly arriving higher
priority tasks. Dependency unblocking and manual retries re-enter the queue
with fresh order; pause/resume keeps existing waiting credit.

All queue mutations are serialized with both a JVM mutex and an OS file lock.
State is written to an fsynced temporary snapshot and atomically renamed
over `tasks.json`. A second scheduler cannot obtain the long-lived
`.scheduler.lock`; ordinary CLI operations retain separate short transaction
locks. A corrupt store is rejected without being overwritten.

After a scheduler process exits abruptly, its OS lock is released. A newly
started scheduler changes residual `running` tasks to `needs-attention`
and **does not** replay them. Operators must reconcile any uncertain tool
effects before manually retrying. Orphan temporary files are ignored; the
last atomic `tasks.json` is authoritative. This is task-store recovery,
**not** session checkpoint replay or fencing of external effects.

Dependencies are validated as a directed acyclic graph with known,
non-duplicated task IDs. The TaskService also exposes a guarded
`replaceDependencies` operation for future API clients, only before the
affected task's first attempt. Invalid graph edits roll back as a single
transaction.

## Known limitations

The scheduler is intentionally foreground, with no permanent daemon,
remote workers, or replay of partially completed sessions. A task that
has been claimed but interrupted needs operator attention. Cancellation
is cooperative: an unresponsive provider can keep a worker alive until
SessionController enforces its deadline. Per-workspace locks may serialize
sessions even when the global concurrency limit is greater than one.

The current SessionController still implements the read-only bootstrap
engine; full Koog coding workflows, write tools, and checkpoint recovery
are separate milestones.
