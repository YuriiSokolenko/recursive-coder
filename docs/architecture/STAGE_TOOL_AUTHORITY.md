# Stage tool authority boundary (issue #74)

**Status:** An opt-in, in-process host stage-tool adapter is available. It does
not enable write tools in Koog, switch the CLI into write mode, dispatch a
five-stage workflow, accept artifacts, or change Session Protocol v1.

## Wiring and trust ownership

- `:core:contracts` declares `StageToolSessionOpener`,
  `StageToolAuthoritySource`, `StageToolSession`, and typed evidence.
  The core layer has no dependency on Koog, Koin or infrastructure.
- `:infra:workspace` implements `LinuxStageToolSessionOpener` by opening
  the existing `TrustedWriteRuntime`. There is no second filesystem writer,
  process execution path or receipt verifier.
- The CLI composition root exposes `stageToolModule(lockDirectory)` as an
  **additional, explicitly selected Koin module**. Neither `sessionModule` nor
  the existing `roleExecutionModule` registers it. The bootstrap CLI
  continues to use read-only `WorkspaceManager` with its unchanged v1 output.
  The Koog role executor still has an empty tool registry; it cannot construct
  an authority snapshot from model text or execute writes.

An authorized orchestrator supplies a `StageToolAuthoritySource` returning
the **current host snapshot** for the target stage. It must be backed by the
host's authenticated graph/approval store; never by generated or serialized
agent messages. The snapshot contains stage, graph and request correlation,
host-accepted artifact IDs, Git branch and baseline, workspace root,
host-allowlisted executable definitions and required checks. The stage's actor
and role are derived from the graph, not from model-supplied role claims.
Reviewer stages cannot open a write session.

An admitted stage must be READY or RUNNING, active, correctly correlated, and
have accepted, frozen inputs from accepted predecessors. A RUNNING stage is
checked against the same prerequisite gate without changing its state. Every
host-accepted ID must reference an actually accepted and frozen manifest.
Every frozen manifest is pinned by its SHA-256; missing or changed files deny
admission. Frozen edits require the existing `StageRevisionAuthorization`
for that exact artifact ID, revision, digest and independent reviewer, in
addition to the runtime's review/path checks.

## Writes, checks and evidence

The session maps only typed `StageWrite.Create/Edit/Delete` operations into
`TrustedWriteRuntime.apply`. The existing policy enforces SDD `specs/`,
ATDD `tests/acceptance/`, TDD `src/test/` or `tests/unit/`, Developer
`src/main/`, and Reviewer read-only. Untrusted paths cannot escape the
workspace via absolute paths, traversal, symlinks, protected files, case
aliases or submodules. The runtime rechecks Git HEAD/branch/index, approved
bytes, exact SHA preconditions and the descriptor-relative filesystem before
each operation.

The authority source is consulted again before each write, check and final
verification. If the host snapshot changes or is unavailable, the session
latches `STALE_STAGE_AUTHORITY` and cannot regain access merely because an
older snapshot reappears. Reopening requires a new trusted admission and a
clean workspace. Each attempted write conservatively discards the stage's
previous check evidence.

Only the host-selected IDs `git_diff_check` and explicitly allowlisted
external checks can run. External checks reuse the existing Linux
bubblewrap boundary (no network, read-only checkout, private scratch,
bounded output/deadline). There is no unsandboxed fallback. Developers must
require both the Git diff check and an independent sandboxed check.

`StageWriteEvidence` and `StageCheckEvidence` contain receipt metadata
and denial codes; **no HMAC receipt or signing secret is returned to the
agent**. The adapter keeps actual `TrustedCheckReceipt` instances privately
and invokes `TrustedWriteRuntime.finish` with the current required set.
Only successful final verification returns
`StageValidationEvidence.successful = true`, after which the session
disallows further writes and checks. Failed checks, expired receipts, changed
manifests, stale host authority or unavailable sandbox produce non-success.
Evidence is not portable authorization; downstream graph gates must still
recheck the current host state, stage identity, artifact hashes and review.

## Verification and limits

```sh
./gradlew :infra:workspace:integrationTest :infra:workspace:e2eTest \
  :app:cli:integrationTest checkArchitecture checkTestTiers
```

The stage-port integration suite checks four role ownership boundaries,
immutable artifacts, cross-role edits, symlink/path escapes, stale host
snapshots, Git baselines, frozen digests, missing approval and check
requirements, receipt invalidation, and read-only Koin defaults. CI requires
the port's real bubblewrap E2E to pass **with zero skips**, in addition to the
existing standalone trusted-write sandbox test.

The host must use a single trusted authority source and prevent concurrent
same-UID writers; the runtime does not claim to defeat hostile concurrent
workspace mutation. The adapter does not choose test quality, generate code,
provide model-facing tools, accept stage artifacts or integrate graph
scheduling; those are follow-on responsibilities for #75–#79.
