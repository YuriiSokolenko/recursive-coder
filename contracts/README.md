# Recursive Coder Session Protocol v1

**Canonical contract of the standalone Recursive Coder runtime.** These definitions are **pre-release**: version `1.0` describes the currently reviewed wire shape; no released client compatibility is claimed yet. The engine, CLI and public API will use the same versioned envelopes.

## Files and envelopes

- [session-v1.schema.json](v1/session-v1.schema.json) — Draft 2020-12 JSON Schema with `SessionRequest`, `SessionResult`, `SessionEvent` and `SessionCheckpoint` selected by `kind`.
- [session-v1.examples.json](v1/session-v1.examples.json) — synthetic CLI session examples (fresh/resume, success/failure, event, checkpoint and compatible minor version). No IDs, outputs or timestamps are evidence of actual executions.
- [session-v1.test.mjs](v1/session-v1.test.mjs) — lightweight fixture regression checks plus an unsupported-schema-keyword guard.
- [Full schema conformance tests](../tests/test_contract_v1.py) — Python `jsonschema` validating Draft 2020-12, formats, negative cases and examples. CI runs both.

## Ownership and execution

**Recursive Coder owns the complete task/session lifecycle.** Sessions start via its own CLI (`invocation.source=cli`), future HTTP API (`api`) or internal scheduler (`scheduler`). The engine creates and validates an isolated workspace, allocates task and child-agent budgets, enforces tool policy, writes events, checkpoints and final result. An external system may invoke a public interface but has no mandatory role or privileged control over the internal execution.

The `workspace` object identifies a trusted, engine-managed Git workspace and its baseline/head SHA, independent of remote hosting. A local directory with no Git baseline is a future capability and must be explicitly versioned; never silently fake a SHA.

`invocationId` stays stable across retries of the same entrypoint request; `invocation.attempt` increases on each attempt. `sessionId` remains stable during a legitimate resume, while `requestId` changes. An engine-owned exclusive lease and increasing `resume.fenceGeneration` are required before accepting a checkpoint. A broken/missing checkpoint produces a rejected or failed session, not a fresh run against partially modified files.

## Compatibility and security

- Versions are `MAJOR.MINOR`. An unknown major fails closed. Compatible minor changes may use `extensions.x-*`, but the runtime must explicitly support the version and all `requiredCapabilities` before running tools. The current `SessionProtocol.supports()` permits only `1.0`.
- `model.credentialRef` is an **environment-variable reference**, grammar `env:[A-Z][A-Z0-9_]*` (example `env:MODEL_API_KEY`). No credential values in requests, logs, events or artifacts. Model endpoints must be allowlisted by runtime configuration.
- **The schema is shape validation, never tool authorization.** The runtime must enforce deny precedence for tools, canonical/symlink-safe path containment, protected spec/test artifacts, workspace baseline, secret and endpoint restrictions, maximum recursion/tokens/tools/deadline and capability negotiation. Unknown permissions, unexpected paths or uncontrolled side effects fail closed.
- Checkpoints record hashes of agent graph, model state and side-effect journal. Replay requires verifying hashes, workspace digest, policy and exclusive generation fencing; uncertain writes block automatic replay.
- Events are append-only, redacted and correlated with `sessionId`, agent/child IDs, `correlationId`, `causationId`, time and optional usage completeness. No raw secrets or hidden reasoning are implied by event logging.
- Session successes require independently verified tool/check evidence and immutable output references. A model's final prose alone is not an authoritative result.

These are **design requirements**, not implemented runtime guarantees yet. Security implementation and fault-injection testing are part of the agent's own roadmap.

## Tests

```bash
node --test contracts/v1/session-v1.test.mjs
python -m pip install jsonschema==4.25.1
python -m unittest discover -s tests -p 'test_contract*.py' -v
```

The independent [CI workflow](../.github/workflows/ci.yml) also runs `gradle test` on JDK 21. All checks use repository-local fixtures and require no model provider or remote service.
