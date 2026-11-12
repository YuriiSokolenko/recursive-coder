# Koog-backed role execution (issue #73)

**Status:** Minimal opt-in adapter, not a five-role workflow or automatic CLI execution.

## Boundaries

- `:core:contracts` owns `StageRoleExecutor`, `StageRoleRequest`, typed outcomes,
  metadata-only events and the in-memory graph contracts from #72. No Koog
  dependency enters core code.
- `:agent:runtime` implements this port with a **fresh Koog `AIAgent` for
  each invocation**, one role-specific system context, one model request and
  an **empty tool registry**. The role must match the host-owned stage actor.
- `StageGraphPolicy.admitStage` validates the host snapshot and independently
  accepted input IDs *before* calling a provider. It does not mutate the
  stage. The returned `Proposed(text)` remains untrusted; it is **not an
  approved artifact, a stage transition, a review attestation, or success**.
- The Koin composition root exposes `roleExecutionModule(model, bridge)` for
  opt-in callers only. The existing two-turn read-only `RunSessionUseCase`,
  task scheduler and CLI commands do not select this module and remain
  unchanged. Session Protocol v1 has no new fields or event types.

## Model compatibility bridge

The existing `ModelGateway.next()` API only supports the bounded
`read_task`/SHA-256 confirmation loop. It cannot express arbitrary SDD,
ATDD, TDD, Developer or Reviewer proposals. Rather than silently
repurposing it, the new adapter takes an explicit `KoogPromptBridge` which
opens an isolated Koog `PromptExecutor` and `LLModel` per invocation.
The existing injectable `ModelGateway.negotiate()` still checks negotiated
capabilities before Koog executes.

**Only `fake/fake-v1` with the explicit `tool_calls` feature is currently
enabled** for the Koog bridge; production OpenAI-compatible role generation
is **not** wired. Unsupported providers, feature selections and tool
surfaces fail closed. Offline tests use the real Koog agent runtime and a
deterministic test prompt executor, not network inference.

Each Koog request is capped at one model call and a bounded wall time,
with output-size, model-output-token and aggregate token limits. Provider
usage metadata is mandatory; the offline fake provides deterministic
synthetic counts. The adapter rejects missing/negative/excessive usage,
tool calls, unapproved tool descriptors, attempts at streaming and
additional model requests. Cancellation and deadline expiry return typed
terminal outcomes and never a proposal.

Events retain only role, agent ID, stage/graph/session/request correlation,
status, safe error code and usage counters. Prompts, raw outputs, credentials
and exceptions are never included. A failed event sink suppresses success.

## Deferred work and trust

This adapter deliberately provides **no workspace or write tools**. Scoped
workspace tools and host revalidation belong to #74; artifact production,
stage acceptance and independent review belong to #75–#77; recursion and
cross-stage budget reservation belong to #78; end-to-end correlated
session event integration belongs to #79; durable recovery belongs to #10.

Run the offline tests via:

```sh
./gradlew :agent:runtime:integrationTest checkArchitecture checkTestTiers
```

No provider credentials or network service are required *at test execution
time*. Initial dependency resolution may use Maven Central.
