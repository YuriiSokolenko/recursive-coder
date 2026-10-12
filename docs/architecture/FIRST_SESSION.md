# First executable session (issue #4)

The standalone pre-release CLI accepts one **SessionRequest v1** JSON file:

1. Install JDK 21 and Gradle 9.8.1.
2. Run `./gradlew test installDist`.
3. Run `build/install/recursive-coder/bin/recursive-coder run /absolute/path/request.json`.

A self-contained runnable fixture and canonical JSON Schema check:

`python -m pip install jsonschema==4.25.1`

`python -m unittest discover -s tests -p 'test_cli_runtime_v1.py' -v`

The fixture constructs a local Git workspace, writes a task and owned request,
executes the installed distribution, then checks result/event envelopes against
`contracts/v1/session-v1.schema.json`. No remote provider or queue is involved.

## Supported admission (v0.1 bootstrap)

- Version exactly 1.0; invocation source `cli`, attempt 1; fresh mode with fenceGeneration 1.
- Provider `fake`, modelId `fake-v1`, model.features `["tool_calls"]` only.
- Read-only policy, allowedTools `["read"]`; no writable paths or immutable inputs.
- Supported required capabilities: `session-events-v1`, `fake-model-v1`, `read-only-tools-v1`. Events capability is mandatory.
- An existing Git workspace whose declared baseline/head equals current HEAD, with a bounded in-workspace task file matching the declared SHA-256. Session outputs must be outside the workspace.

Requests with unknown versions, permissions or capabilities are rejected before
model invocation. Unknown/corrupt request JSON fails closed before files are written.
The fake URL and credential reference are never contacted or resolved.

## Observable execution and outputs

The sample agent performs two genuine fake-model turns: first it requests a
trusted read of the task file; then it acknowledges the *observed* byte count and
SHA-256. Events record state transitions, agent/model/tool operations, IDs and
timings. Journal payloads never include the task text, raw prompts or secrets.
No file mutations are permitted. Token usage is explicitly `unknown`, not
fabricated.

stdout is a single machine-readable SessionResult JSON; stderr is a readable
summary. Exit 0 means succeeded, 1 means failed/cancelled/timed out, 2 means
rejected/invalid input. Result metadata contains SHA-256 references to the
append-only `events.jsonl` and `diagnostics.json`. Checkpoint/side-effect
receipts remain null. Existing outputs are not overwritten.

The CLI installs a JVM shutdown hook for SIGTERM/SIGINT and a watchdog that
interrupts a blocked fake model at its deadline. SIGKILL/power loss cannot
finish a terminal result; durable recovery is deferred to issue #10.

## Explicit scope limitations

This is an offline fake-agent milestone, **not** full Koog model execution.
Koog is a pinned compilation dependency; actual Koog-backed provider and
protocol capability negotiation are in issue #6. Trusted read-only workspace
tools and race-safe containment are in issue #5. The `gradlew` executable is
a temporary delegate to an installed Gradle 9.8.1, not a downloaded
self-contained wrapper; complete packaging is part of issue #12.
