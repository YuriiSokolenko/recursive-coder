# Model Gateway (issue #6)

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
means an unauthenticated connection. No raw secret belongs in request JSON.
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
with a response size cap. `usage_metadata` requires token-usage data on
each response; otherwise usage remains explicitly `unknown` for the run.
`error_metadata` can be negotiated, but provider errors are normalized to
safe codes rather than persisting arbitrary error bodies.

## Side effects, retries and cancellation

The first model turn can request only `read_task` with the exact session task
path. The trusted workspace manager verifies the file and digest. The second
turn must confirm the observed byte count and SHA-256; arbitrary model output
cannot substitute for a trusted receipt.

Temporary 429/5xx failures can be retried **once on capability discovery or
on the first model turn only**, with a bounded `Retry-After` delay.
The gateway never automatically retries a completion after a trusted tool
operation. Timeouts, interruption, malformed or incomplete streams,
redirects, bad authentication, missing metadata and unexpected tool calls
produce explicit, sanitized errors. No response bodies, prompts, raw tool
outputs, authorization headers or private model reasoning are logged.
Provider/request durations, status codes, turn attempts, capability summaries
and observed token counts are the only model-related event metadata.

## Validation and limitations

Run `./gradlew test` for deterministic in-process HTTP test coverage.
The CI suite has **no live-model dependency**. A live smoke test is optional
and must not become mandatory for pull-request admission.

The current session engine is deliberately limited to two turns and one
read-only tool. This adapter provides an injection seam for Koog but does
**not** yet claim full Koog agent execution, general tool orchestration,
recovery, or recursive workflows. Those are separate milestones.
