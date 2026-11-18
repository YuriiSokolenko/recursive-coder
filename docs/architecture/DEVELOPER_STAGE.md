# Developer stage: frozen-input implementation handoff (issue #76)

**Status:** Explicitly opted-in in-process implementation stage. No change to
the default read-only CLI or Session Protocol v1; no reviewer or repair loop.

The trusted host must first complete #75 SDD, ATDD and TDD, freeze their
REQUIREMENTS, ACCEPTANCE_TESTS and UNIT_TESTS manifests, and bind all three
to the Developer node and its declared dependencies. A mere manifest claiming
`ACCEPTED` is insufficient: `StageGraphPolicy.admitStage` also requires
the authoritative host-accepted artifact ID set. Every input is read from the
workspace and checked against its SHA-256 before the role receives it.

## Explicit invocation

Opt-in compositions combine `roleExecutionModule(model, bridge)`,
`stageToolModule(lockDirectory)` and `developerStageModule(host)`.
The host supplies `DeveloperStageCommand`: its graph, accepted IDs, stage ID,
fresh implementation artifact ID, host-selected manifest path, one to seven
`DeveloperFileTarget` values, deadline and cancellation. Each target pins a
path and either an expected source SHA-256 (edit) or null (create). The host
must use the same workspace root for `readBytes` and its authoritative
stage-tool snapshot. Model output cannot set an executable, check, permission,
baseline, target set or expected preimage hash.

Koog receives a single independent DEVELOPER role invocation, with the
verified and entity-escaped frozen input text, current source bytes for
pre-approved edits, and the host-chosen target paths. The current role bridge
only supports offline `fake/fake-v1` and never exposes model tools. Its
single-request quota and token metadata are checked again at the use-case
boundary. The proposal must be a strict JSON object:

```json
{
  "files": [
    {"path": "src/main/Feature.kt", "content": "fun feature() = 2\n"}
  ]
}
```

Exactly the host-selected target set must be present; unknown fields,
duplicate paths, supplied operations/hashes, deletes and renames are rejected.
This iteration supports CREATE/EDIT only. One stage can modify up to **seven
files**; the eighth operation creates the host-derived implementation manifest.
That manifest is intentionally under `src/main/`, since the existing
Developer path policy forbids other write trees; its presence in the
production source tree is a current limitation, not an invitation for the
model to change build configuration.
Existing source preimages must exactly match host-provided SHA-256. The model
does not write the manifest. It records each changed path, previous SHA (or
`-` for a new file) and intended resulting SHA, sorted deterministically.
The `StageArtifactManifest` pins the physical manifest bytes and becomes
the IMPLEMENTATION handoff artifact for independent Reviewer #77.

## Physical enforcement

The host acquires the same generation-fenced atomic stage lease as #75.
The host `approve` callback is synchronous and must be prompt: coroutine
`withTimeoutOrNull` cannot forcibly interrupt blocked host I/O. The host owns
its own deadline enforcement and generation-fenced late-result handling.
No write occurs before this lease is acquired, validated and confirmed live.
Only `LinuxStageToolSessionOpener` can map the stage authority to the
`TrustedWriteRuntime` implementation permission matrix. The runtime requires
a clean, exact Git HEAD and branch, refuses staging/index changes, unsafe Git
configuration, symlinks and nonregular entries, and verifies the full workspace
diff (not merely the proposed files). The Developer role may touch
`src/main/` only; protected test/spec paths, test-named source files,
cross-role writes and paths escaping via `../` remain forbidden. Neither a
model-supplied shell command nor a rename/symlink/staging operation exists on
the model's stage surface.

The trusted session runs host-allowlisted checks: **`git_diff_check` plus at
least one independently configured isolated check**. The Linux runtime runs
the latter in a networkless read-only bubblewrap sandbox, with fixed arguments,
timeout and bounded captured output. The receipt HMAC remains private to the
runtime. Success requires the runtime's `verify()` and exactly matching check
evidence, stable manifest hashes, distinct tool IDs, stage identity and current
baseline. A model assertion or hand-written receipt cannot satisfy the
trusted port. Missing tools, failed checks, tampering and cancellation never
cause an accepted stage.

Before and after host approval, the orchestrator re-reads every changed file,
the generated manifest and all three frozen input artifacts, verifying exact
bytes/hashes. It rechecks the live generation and authority snapshot. An
independent host `ACCEPT` for the exact candidate is required to atomically
commit the Developer stage as ACCEPTED and the IMPLEMENTATION manifest as
ACCEPTED/FROZEN, adding precisely one accepted artifact ID.

Preflight reads distinguish `TARGET_READ_UNAVAILABLE` and `INPUT_READ_UNAVAILABLE`
from confirmed target conflicts or missing/changed frozen artifacts. Confirmed
violations take precedence over unavailable reads; target paths are checked in
stable lexical order. No lease is acquired on a preflight denial.
After admission, any confirmed changed or missing file yields
`FROZEN_INPUT_OR_OUTPUT_CHANGED`; otherwise unavailable output/manifest reads
yield `OUTPUT_READ_UNAVAILABLE`, or unavailable frozen inputs yield
`INPUT_READ_UNAVAILABLE`. Post-approval authority drift takes precedence over
read diagnostics. A throwing checkpoint or initial authority snapshot yields
`STAGE_AUTHORITY_UNAVAILABLE`. Approval failures yield `HOST_APPROVAL_UNAVAILABLE`.
Host-local cancellation at role/tool boundaries is a stage failure, not
caller cancellation. Actual Job cancellation is propagated after non-cancellable
cleanup; a secondary session-close failure cannot mask the primary failure.
A throwing finish CAS remains `STAGE_FINISH_UNCONFIRMED` with no trusted snapshot.

An explicit `REJECT` is terminal. A `HUMAN_REVIEW_REQUIRED` result is
**not accepted or silently queued**: this Developer iteration fails closed
and must be retried with a new stage attempt after human coordination.
Unlike #75's preparation continuation, Developer human resume and durable
workspace recovery are not implemented here. Failed/cancelled attempts may
have left unapproved dirty workspace bytes. Failures and uncertain CAS
outcomes carry a conservative `workspaceMayBeDirty` flag set before a write
attempt, so the host must inspect and safely reconcile the workspace before
reusing it. Uncertain successes are never reported as definite denials.
Issue #85 adds an explicit, host-owned Linux recovery operation for
**confirmed failed/aborted Developer writes**. Before entering the trusted write
port, `LinuxFiveRoleWorkflowHost` retains bounded original bytes, intended
postimage digests, the lease generation and frozen artifact hashes in memory.
On terminal failure it records the actual writer lease generation separately
from the failed-write record; recovery requires a matching value, not merely
a syntactically valid identifier. Both values originate from the same in-process
writer lease: the comparison detects stale or corrupted evidence, not an
independent external CAS authority.
Preimage capture failures return precise known denial codes and do not consume
the one-shot write session because no physical write has begun.
After a terminal **non-accepted** graph/CAS outcome, the host quarantines the
workspace. An operator may call `reconcileFailedDeveloper(stageRunId)`:
`LinuxStageGitBaselineReconciler.recoverFailedDeveloper` obtains the same
workspace file lease as the trusted writer, verifies the pinned Git HEAD,
branch, Git configuration/index, all frozen hashes, exact trusted before-images,
regular nofollow paths and intended after-image digests, and restores only
unaccepted bytes. It verifies restored bytes again with descriptor-relative
SHA-256 reads, independent of Git's stat/cache status checks. Unexpected/staged/ignored/foreign edits, mode changes,
symlinks, submodule entries or ref drift return typed `NeedsHuman` without
performing restoration; any uncertainty after physical mutation retains
quarantine as `ReconciliationRequired`. No `git reset`, arbitrary cleanup
or model-driven rollback is allowed.

A `Recovered(restoredPaths, auditSha256)` result is bounded, has a
versioned `recursive-coder/developer-failed-write-recovery/v1` SHA-256 domain,
and contains no source text. Recovery runs Git/file I/O outside the host monitor;
an in-progress fence plus continued quarantine block concurrent lease admission.
Quarantine remains in force even after successful restoration, until the
replacement Developer stage has passed the graph/clean-baseline/frozen checks.
The retry path also fences concurrent retry attempts, performs its Git checks
outside the host monitor, then rechecks the original graph/head/accepted
snapshot before publishing. Fatal errors release the in-process recovery/retry
activity fence in `finally` without lifting quarantine; a fatal exception
never constitutes proof of successful recovery.
The terminal stage is **not** resumed: the host must call
`retryReconciledDeveloper(failedId, newCorrelation, newAgent)` to supersede it
with a fresh identity, then pass the ordinary clean-baseline, frozen input,
policy and trusted-write lease gates again. A failed or unconfirmed finish CAS,
live writer, accepted manifest or missing in-memory evidence cannot authorize
rollback. This is limited to the existing in-process Linux host, and requires
an exclusively owned checkout: an external same-UID writer can race advisory
locks. Crash/restart durability, recovery of accepted/unconfirmed commits and
durable audit persistence still belong to issue #10. The default read-only CLI
and Session Protocol v1 remain unchanged.

## Typed reviewer handoff and verification

`DeveloperStageOutcome.Accepted` contains the frozen implementation manifest,
a typed list of changed paths with before/after SHA-256 in the same
lexicographic path order as the manifest, the runtime-issued
write tool ID, the trusted check list (each check ID, tool ID, baseline,
policy and manifest hashes), and bounded `StageRoleUsage`. The returned
graph and accepted IDs come only from an acknowledged host CAS. A stale or
unconfirmed begin/finish returns `ReconciliationRequired` with no trusted
graph snapshot. Correlated stage events contain metadata, never raw source
or model prompts.

The deterministic `RunDeveloperStageIntegrationTest` covers a FakeModel
proposal, host-fenced acceptance, modified frozen inputs, missing approval,
unauthorized target paths, stale source preimages, attempted path expansion,
rename/tool-ID injection, failed checks, receipt tampering, post-approval
changes, explicit cancellation, host rejection, CAS uncertainty,
target-count/duplicate attacks, timeouts and usage-budget denials. Its fake
host also runs the reusable `PreparationStageHostContract`. Existing concrete Linux
stage-tool integration and bubblewrap E2E tests exercise the actual
`LinuxStageToolSessionOpener` and receipt boundary. The cross-module `DeveloperLinuxStageIntegrationTest` in
`:app:cli` composes `RunDeveloperStageUseCase` with the actual
`LinuxStageToolSessionOpener` in a clean physical Git fixture and executes
both required checks through the trusted receipt-backed Linux runtime.

The physical Developer sandbox success and failed-check cases run as
`@Tag("e2e")` in `DeveloperLinuxStageIntegrationTest`. The focused
`integrationTest` task explicitly excludes them; CI requires each E2E case
exactly once with no skips. The real failed check must leave changed source
bytes on disk, report `Failed("CHECK_FAILED_OR_SANDBOX_UNAVAILABLE", workspaceMayBeDirty = true)`,
and never publish host acceptance. The `workspaceMayBeDirty` constructor
parameter is mandatory, including explicit `false` for pre-write outcomes.

Run all gates:

```sh
./gradlew :core:session:integrationTest :app:cli:integrationTest \
  :infra:workspace:e2eTest checkArchitecture checkTestTiers
```

The independent Reviewer, recursion, crash recovery and full cross-stage
end-to-end event journaling remain owned by #77, #78, #10 and #79.
