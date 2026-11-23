# Model Gateway (issue #6)

**Implemented** in `:infra:model` and selectable by the read-only CLI Session Runtime. This provider adapter is not a Koog agent runtime or a general coding-loop executor. The advertised discovery fields below are **required backend extensions**, not universal OpenAI API fields.

The gateway is an injectable JVM interface: `ModelGateway.negotiate` checks compatibility,
then `ModelGateway.next` proposes a tool call or a final read confirmation.
**It does not execute tools.** Only `WorkspaceManager` can perform the authorized read.
The existing `FakeModel` implements the same interface and remains fully offline.

## Providers

- `fake` / `fake-v1`: deterministic two-turn tool-call fixture, no network or token accounting.
- `openai-compatible`: strict OpenAI Chat Completions adapter that uses
  `GET {baseUrl}/models/{modelId}` for capability discovery and
  `POST {baseUrl}/chat/completions` for model turns.
- Other providers are rejected before execution. No server, model ID, account,
  hardware, hostname, or port is compiled into the implementation.

The OpenAI-compatible provider is a **strict compatibility subset**, not a claim that
all servers with an OpenAI-shaped Chat Completions endpoint support discovery.
The discovery response must contain the selected `id`, positive integer
`context_length` and `max_output_tokens`, and explicit boolean `capabilities`:

```json
{
  "id": "configured-model-id",
  "context_length": 32768,
  "max_output_tokens": 4096,
  "capabilities": {
    "tool_calls": true,
    "tool_choice": true,
    "streaming": true,
    "structured_outputs": true,
    "usage_metadata": true,
    "error_metadata": true
  }
}
```

**Important:** `capabilities`, `context_length` and `max_output_tokens`
are discovery extensions and are not guaranteed by the OpenAI API.
If your compatible backend does not advertise them, the adapter rejects
it with `MODEL_CAPABILITY_UNSUPPORTED`. Implement a separately reviewed
provider adapter or discovery bridge rather than silently guessing capabilities.

## Request configuration

A `SessionRequest v1` model section can specify:

```json
{
  "provider": "openai-compatible",
  "modelId": "configured-model-id",
  "baseUrl": "http://127.0.0.1:4001/v1",
  "credentialRef": "env:RECURSIVE_CODER_MODEL_TOKEN",
  "contextTokens": 16384,
  "maxOutputTokens": 2048,
  "features": ["tool_calls", "streaming", "usage_metadata"],
  "connectTimeoutMs": 3000,
  "requestTimeoutMs": 30000
}
```

This example uses a loopback address **only as illustrative configuration**;
no default network address is embedded in code.
For a remote endpoint, use HTTPS and supply the named environment variable
with its bearer token. For local loopback services, an unset token reference
means an unauthenticated connection. No raw secret belongs in request JSON. For a remote provider the first prompt
currently discloses the absolute local task-file path (not its contents);
choose trusted endpoints and consider workspace path privacy.
Redirects are denied to prevent credential forwarding. Timeouts are optional
integers from 100 through 120000 milliseconds.

`requiredCapabilities` must include `session-events-v1`,
`read-only-tools-v1`, and `model-gateway-v1` for the HTTP adapter.
`fake-model-v1` is valid for the fake adapter only.
The selected feature set must contain `tool_calls`; every other selected
feature must have an affirmative discovery claim. The configured context and
output budgets must fit the advertised limits. Unknown or unadvertised
features fail closed.

`tool_choice` requests a required tool call on the first turn.
`structured_outputs` requests `json_schema` for the final confirmation.
`streaming` requests SSE on both turns and requires a final `[DONE]` and
`finish_reason`. Partial tool argument and content deltas are assembled
with a response size cap. SSE events are currently assembled **after the
bounded body is read in full**; there are no incremental progress callbacks.
`usage_metadata` requires token-usage data on
each response. Session admission **requires** `usage_metadata` for the
`openai-compatible` provider (`UNSUPPORTED_MODEL` otherwise, #155): without
reported usage the engine could not enforce `budgets.maxTokens`. Usage
completeness is still reported as `unknown` if a fake or future adapter
returns none.
`error_metadata` can be negotiated, but provider errors are normalized to
safe codes rather than persisting arbitrary error bodies.

## Side effects, retries and cancellation

The first model turn can request only `read_task` with the exact session task
path. The trusted workspace manager verifies the file and digest. The second
turn must confirm the observed byte count and SHA-256; arbitrary model output
cannot substitute for a trusted receipt.

Temporary 429/5xx failures and transport disconnects/timeouts may be retried
**once on capability discovery or on the first model turn only**. The provider
limits `Retry-After` to a maximum of one second and accepts its integer-seconds
form; HTTP-date hints currently fall back to a 100 ms delay.
The gateway never automatically retries a completion after a trusted tool
operation. `maxModelRequests` counts actual Chat Completions POST attempts,
including retries, and reserves one POST for the final verification turn.
Capability-discovery GETs do not count as model inference requests. Timeouts, interruption, malformed or incomplete streams,
redirects, bad authentication, missing metadata and unexpected tool calls
produce explicit, sanitized errors. No response bodies, prompts, raw tool
outputs, authorization headers or private model reasoning are logged.
Provider/request durations, status codes, turn attempts, capability summaries
and observed token counts are the only model-related event metadata.


## Opt-in fail-closed provider usage boundary (issue #173, first slice)

`FailClosedProviderUsageGateway` is an **in-memory host-owned wrapper**
over the existing `ModelGateway`. It is deliberately not installed in
the writable five-role/recursive CLI, which remains **FakeModel-only**.

A host constructs a separate wrapper for each admitted workflow with a
positive token grant and an absolute child deadline. The wrapper:

- Requires the `openai-compatible` provider and `usage_metadata`
  capability. It **rejects streaming** in this first slice: the existing SSE
  parser does not yet prove that usage is an authoritative terminal summary.
  It only accepts a successful (HTTP 2xx) **single-attempt** nonstreaming
  completion with explicit input/output token counters reported by the
  host-owned provider adapter, rather than model-generated JSON/text.
  Each accepted call records a metadata-only receipt containing the model,
  response status, token counts and `host-adapter-response` provenance.
- Passes `allowRetry=false` to the delegate irrespective of caller
  preference. A partial, timed-out, rejected or failed completion may
  already have billed tokens, so it cannot be retried without a separate
  per-attempt accounting protocol. Capability-discovery GET retries are
  unaffected; they are not completion/inference requests.
- Bounds `maxOutputTokens` to remaining *total* grant before dispatch
  and clamps the HTTP request timeout (including capability discovery)
  to the admitted deadline's remaining milliseconds. This is best-effort
  transport limiting; a stalled body read
  can still require an external cancellation/watchdog.
  Sums input+output tokens across all calls, retaining valid observed
  consumption even if it exceeds the grant. Any overrun, response after
  deadline, or invalid provider usage stops the workflow before further
  model calls. The API **cannot preempt an in-flight request** at an exact
  total-token boundary: input costs are known only after provider return.
  **Worst-case overspend** is the input tokens of one newly dispatched prompt
  plus its capped output, on top of already recorded usage. A small remaining
  grant does not establish that the prompt fits. The host must separately
  budget prompt size before permitting future unattended execution.
- Marks missing, zero-input, negative, overflowing, multi-attempt or
  unproven-status usage as **unconfirmed**; a thrown exception after a
  possible completion dispatch does the same (including cancellation).
  An unconfirmed meter is permanently poisoned and refuses any next
  completion or negotiation.
- Exposes nonblocking `snapshot()` with metadata-only per-call receipts
  for **accepted, budget-exceeded, late, cancelled, and unconfirmed** attempts,
  observed input/output counters, a conservative in-flight or uncertain-usage
  reservation and an uncertainty flag. Failed requests without usage have null token counts, never fake zero
  usage. A concurrent `next` is denied as `MODEL_CALL_IN_FLIGHT`; the
  network call never holds the state monitor. Capability discovery uses a
  separate in-flight operation: it blocks concurrent inference but does
  **not** reserve inference tokens in `snapshot()`. Invalid configured
  request timeouts fail with `MODEL_REQUEST_TIMEOUT_INVALID` before
  dispatch and record no inference charge.
  `accountedTokens` reports observed input-plus-output tokens for confirmed
  responses, including fully metered late/cancelled responses and overruns
  (which can exceed the grant). It reserves `maxOf(grant, observed)` only
  during an in-flight completion or when usage is unconfirmed. A later
  pre-dispatch terminal denial does **not** forfeit unused grant, although
  the terminal state still blocks additional work.
  The snapshot also retains a sanitized provider `causeCode` (no exception
  text, paths or credentials). Cancellation after a fully metered response
  retains confirmed usage but terminates the meter.
  The allocation is **not a durable scheduler debit**, a verified provider
  invoice, or proof of an independently authenticated billing feed.
  It must not be double-counted against later scheduler accounting.

The trusted boundary is **the host-injected adapter and its HTTP response
parser**, not the model or its prompts. Provider-reported usage is only
as trustworthy as the selected backend's reporting. A caller must not inject
a model-controlled `ModelGateway` implementation and treat its `ModelTurn`
as provider-authenticated evidence.

This first slice is exercised with recorded adapter outcomes and a local
HTTP test server. It does not include durable parent debit/resume accounting,
a stage-role/recursive-workflow integration, streaming per-attempt billing,
host-authenticated provider receipts, write/check authority, or any live
writable workflow switch. **Issue #173 stays open and the live-model
release gate stays closed** until the remaining end-to-end requirements and
independent security review pass.

## Validation and limitations

Run `./gradlew test` for deterministic in-process HTTP test coverage.
The CI suite has **no live-model dependency** and runs on GitHub-hosted Ubuntu 24.04 since merged [PR #71](https://github.com/YuriiSokolenko/recursive-coder/pull/71). The adapter itself is not tied to CI infrastructure. A live smoke test is optional
and must not become mandatory for pull-request admission.

The current session engine is deliberately limited to two turns and one
read-only tool. This adapter provides an injection seam for Koog but does
**not** yet claim full Koog agent execution, general tool orchestration,
recovery, or recursive workflows. Those are separate milestones.
