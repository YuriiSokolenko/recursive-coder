# Session protocol v1 — canonical owner: Recursive Coder

This repository owns the **canonical, language-neutral GitHub ↔ agent runtime wire contract**. The initial definition was reviewed in [social-mcp PR #791](https://github.com/YuriiSokolenko/social-mcp/pull/791) (issue #784) and imported here with its version unchanged. The former schema/fixtures in `social-mcp/docs/agent-harness/contracts/` are **legacy snapshots**, not a second independent version authority.

## Files

- [session-v1.schema.json](v1/session-v1.schema.json): JSON Schema draft 2020-12, envelopes selected by `kind`. `SessionRequest`, `SessionResult`, `SessionEvent`, `SessionCheckpoint`; structured `SessionError` nested in the result/event.
- [session-v1.examples.json](v1/session-v1.examples.json): reproducible syntactic fixtures for fresh/resume, success/failure, event, checkpoint and a forward-compatible minor version. **Synthetic example IDs, paths and endpoints are not proof of a historical Koog execution.**
- [session-v1.test.mjs](v1/session-v1.test.mjs): inherited fixture/negative/regression cases and an explicit supported-keyword guard.
- [tests/test_contract_v1.py](../tests/test_contract_v1.py): **full** JSON Schema 2020-12 validation with `jsonschema` including format checking. This is the authoritative schema conformance check in CI, not the limited Node fixture helper.

## Contract policy

- Schema version `MAJOR.MINOR`, starting at `1.0`. Breaking changes require a new major; minor versions preserve required fields and safety meanings. The schema accepts compatible `1.x` wire syntax, but a runtime **must explicitly negotiate/allow the exact version and required capabilities**. `SessionProtocol.supports()` intentionally permits **only 1.0** until protocol/capability negotiation is implemented in #785.
- `model.credentialRef` grammar: **`env:VARIABLE_NAME`**, with `VARIABLE_NAME` matching `[A-Z][A-Z0-9_]*`. Example: `env:OPENAI_API_KEY`. The string is a reference only; never place a credential value in JSON, events, logs or artifacts. Resolution is by trusted runtime credentials binding.
- **JSON Schema defines shape, not authorization.** No schema property authorizes network destinations, GitHub tokens or tools. Adapter + runtime admission must reject denied tool names even when `allowedTools` also contains them; enforce an explicit trusted allowlist for `model.baseUrl`, `requiredCapabilities`, actual maximum depth/children/tokens/time, worktree path containment and canonical/symlink-safe protected/writable paths. An empty `deniedTools` list is **not** a permission grant. These are implementation requirements for #785 and #787 and must be enforced before the first tool call. The admission layer fails closed on unsupported values.
- The current baseline, `worktreeId`, policy hash, checkpoint hash and strictly greater fencing generation must be verified by the trusted caller on resume. A resumed job attempts the *same logical session*, not a second concurrent root. Unknown side effects block replay; a failed backend cannot grant PR/CI/Merge Gate permission.
- GitHub control plane remains the owner of worktrees, publication and global state in `social-mcp`. Recursive Coder owns the session-internal model/tool/child-agent graph, events and checkpoints. The full inventory and control-plane rules remain documented in [social-mcp's Koog boundary](https://github.com/YuriiSokolenko/social-mcp/blob/dev/docs/agent-harness/KOOG_BOUNDARY.md); this copy is the canonical **wire contract** only.

## Independent verification

From this repository, with Java 21, Gradle 9.8.1, Node 22 and Python 3.12:

```bash
gradle test
node --test contracts/v1/session-v1.test.mjs
python -m pip install jsonschema==4.25.1
python -m unittest discover -s tests -p 'test_contract*.py' -v
```

The [CI workflow](../.github/workflows/ci.yml) runs all three checks on **GitHub-hosted `ubuntu-latest`** runners for PRs and pushes targeting `dev`/`main`, plus manual dispatch. It does **not** use the N150 runner or HP Nano and does not attempt live model calls, CI merges, issue transitions or PR publication.

Koog agent execution, secure admission, model compatibility verification and durable replay are **not implemented** by these contract fixtures; they belong to #785–#789. Do not weaken this distinction to claim a working agent prematurely.
