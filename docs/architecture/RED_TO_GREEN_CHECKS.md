# Red-to-green check evidence design (issue #153)

**Status: design only; not implemented.** This document specifies a future
host-owned validation protocol. It does **not** change any current role gate,
check receipt, CLI plan schema, sandbox, or Session Protocol v1 event. In
particular, the current workflow makes **no red-to-green claim**.

## Existing behavior and the missing guarantee

`LinuxFiveRoleWorkflowHost` configures one `checks` map and one
`requiredChecks` set, and installs the same set in each writable stage's
`StageToolHostSnapshot`. `RunPreparationStageUseCase` requires
`git_diff_check` and an additional configured check after each SDD, ATDD and
TDD write. `RunDeveloperStageUseCase` requires that structure after Developer
writes. The installed CLI accepts one operator-selected `check` for all four
roles. These checks are **post-write validators**: their successful receipts
attest only that the selected command completed successfully against the
then-current accepted write manifest. They do not attest that a newly authored
test failed before implementation or that it was run at all.

If that single check executes ATDD/TDD tests during preparation, it must pass
before Developer writes; if it does not, later success says nothing about those
tests. `StageCheckEvidence.passed` currently represents only success and has
no expected-failure classification, selected-test identities, or artifact
binding. The check's name, exit status, or HMAC by itself cannot establish
test relevance, coverage, or causality. See
[Trusted writes](TRUSTED_WRITES.md#what-checks-do-and-do-not-prove) for the
current trust boundary and [#154](https://github.com/YuriiSokolenko/recursive-coder/issues/154)
for build-tool sandbox limitations.

The stronger *proposed* acceptance condition is:

> Every test artifact designated as a red-to-green gate is bound to the exact
> frozen ATDD/TDD artifact bytes and a host-selected test selection; on the
> pre-Developer baseline the selected tests have independently verified,
> meaningful failures, and after the Developer write the same selected tests
> pass under the same trusted check definition and pinned environment.

This is comparative evidence, **not** a mathematical proof that the Developer's
changes alone caused the result. Test quality, tampering via allowed production
code, and independent review remain separate concerns (see
[#152](https://github.com/YuriiSokolenko/recursive-coder/issues/152)).

## Host-owned check plan and role policy (proposed)

A versioned immutable `CheckPlan` would be selected and authenticated by the
trusted host **before** starting the affected graph. It fixes the policy,
selectors, and execution rules, **not** artifact bytes or test identities
that only become known after ATDD/TDD writes. Neither the model nor mutable
checkout configuration may supply or amend this plan. It would carry:

- Graph/session identity and a digest of the canonical check plan; executable,
  fixed arguments, allowed test-report adapter, pinned toolchain/dependency
  environment, time limits and isolation policy for every check.
- **Per-role** required validator IDs, rather than one global required set.
  The host derives the current stage snapshot from the appropriate immutable
  role entry; it must not let a model choose a role or omit checks.
- Separate ATDD and TDD *red-to-green gate* policies: stable gate/check
  IDs, expected producer stages and artifact kinds, permitted test paths,
  and host-controlled discovery/selection rules for exact test cases.
  Concrete artifact IDs, versions, paths, digests, and discovered test IDs
  are **not** pre-graph inputs; the policy constrains their later binding.
- A versioned classification rule for observed test failures (not simply a
  nonzero process exit), expected passing evidence, and permitted test
  execution environment. The same gate definition and selector are used in
  both phases.

After ATDD/TDD acceptance and freeze, but **before red preflight or any
Developer authority**, the host would derive a separate immutable,
host-authenticated `GateArtifactBinding`. It records the exact accepted
artifact IDs, versions, paths, SHA-256 digests and the independently discovered,
nonempty selected test inventory for every gate. It is derived only from the
authoritative graph, verified physical bytes and the pre-graph plan's discovery
rules; model-provided paths, globs, selectors or reports cannot redefine it.
The binding has its own canonical digest and is fenced to the graph generation
and clean pre-Developer baseline. Both red and green receipts must bind **both**
the original `CheckPlan` digest and this post-freeze binding digest. Any
change requires a newly authenticated binding and a fresh red run, never an
in-place update to the policy or an existing red/green pair.

Proposed role responsibilities (independent `git_diff_check` remains required
for every writable role):

| Boundary | Required evidence | What it does **not** require |
| --- | --- | --- |
| SDD output | Git/digest validation and SDD-specific manifest validator | Executable ATDD/TDD tests |
| ATDD output | Git/digest validation and ATDD-specific artifact/static validator | The new acceptance tests to pass |
| TDD output | Git/digest validation and TDD-specific artifact/static validator | The new unit tests to pass |
| Pre-Developer admission | Host-authenticated **red** receipts for bound ATDD and TDD gates | Passing the not-yet-implemented behavior |
| Developer output | Git/digest validation, **green** receipts for the *same* ATDD/TDD gates, and any additional Developer checks | Treating a previous red failure as a passed check |
| Reviewer | Read-only verification of handoff provenance and both phases | A Reviewer write session |

Preparation validators may check syntax, file format, policy, or other
appropriate structural conditions. They must never silently stand in for
the red phase. Where an ATDD/TDD artifact intentionally cannot compile yet,
a compilation failure is **not** sufficient evidence in the initial design:
only an adapter that can prove selected test assertions executed and failed
may mark the red phase `CONFIRMED_RED`. A future explicitly versioned adapter
could support tightly classified compile-first TDD; generic compiler failure
must not be accepted as red.

## Binding tests to artifacts

To create `GateArtifactBinding`, the host resolves the accepted/frozen ATDD
and TDD `StageArtifactManifest` records from its authoritative graph and
reads physical bytes with the existing no-follow path and digest checks.
For every gate, it verifies that:

1. Every selected test maps to a host-declared `tests/acceptance/`,
   `src/test/`, or `tests/unit/` path belonging to the correct, accepted
   artifact ID/version, producer stage, and digest. No extra or missing path,
   duplicate alias, or cross-role reassignment may satisfy the binding.
2. The actual sandbox test discovery/report shows those exact selected test
   identities were executed from that pinned source, with no missing, skipped,
   disabled, dry-run, or silently filtered tests. Merely pointing a command
   at a test directory or exiting zero is insufficient.
3. The test launcher, compiled test inputs, dependencies, runtime settings
   and classpath/test-discovery configuration are pinned and auditable. When
   generated outputs or a dependency cache are involved, their provenance
   must be bound to the frozen sources; cached tests from another revision
   cannot be substituted.
4. The operator cannot change selector or configuration between red and
   green. Both reports identify the same check-plan digest, gate ID, test
   inventory, frozen artifact digests and environment fingerprint.

The existing `StageArtifactManifest` has one declared output path per
preparation stage. Initial binding therefore covers those declared paths.
A future multi-file test artifact needs its own host-authenticated file
inventory; a path glob supplied by a model is not a substitute.

## When red and green run

1. **Freeze tests and bind gates first.** SDD, ATDD and TDD finish their
   existing approval and independent *preparation validator* gates. The host
   reconciles their exact accepted changes to a clean Git baseline as it
   already does between stages. Record `preDeveloperHead` **after TDD
   reconciliation and before any Developer write**; its tree contains the
   frozen accepted tests and pre-implementation production sources. Derive
   and authenticate `GateArtifactBinding` against that baseline under the
   already-fixed `CheckPlan`, before executing red.
2. **Run red before granting Developer authority.** Under a host-owned,
   read-only, generation-fenced check operation against that exact clean
   baseline, run every bound test gate. The trusted runner records test
   discovery and per-test outcomes. At least one selected test per required
   gate must have executed and failed a recognized behavioral assertion;
   unrelated failures, zero discovered tests and test harness errors deny
   admission. Other selected tests may already pass. Verify no workspace,
   Git HEAD/index, frozen artifact, or plan drift before and after the run.
   A red receipt is an *expected-failure attestation*, not
   `StageCheckEvidence.passed` and not a successful stage validation.
3. **Pin red to Developer admission.** Store the red attestations in the
   host's authoritative state and bind them to the specific Developer
   stage/attempt, `preDeveloperHead`, graph generation, accepted test
   manifests, check plan, test inventory and environment. The host verifies
   this link again at the Developer stage lease and before finish. An
   unrelated failed command, stale attempt, or model-declared "red" cannot
   satisfy admission.
4. **Run green after Developer writes.** The Developer can only write in
   permitted source scope. Execute the same selected gates using the same
   frozen tests, check configuration and environment, now against the
   Developer postimages. Every selected test must execute and pass; at
   least the tests that were red must now be explicitly reported passing.
   The ordinary `git_diff_check`, any additional Developer validators and
   the existing `verify()`/host approval requirements still apply.
5. **Commit and review.** Authenticate red and green together with the
   Developer's write/validation/manifest evidence and live graph authority.
   Re-read tests and production postimages and reject changed bytes before
   the fenced Developer finish CAS. The Reviewer receives **read-only**
   provenance for both phases and must reject a missing, stale or mismatched
   pair. Physical Git reconciliation and independent Reviewer acceptance
   remain separate requirements.

The red run is a new **host operation between stages**, not a fake failed
`RunPreparationStageUseCase` attempt and not a deliberate failure of the
existing successful-check contract. Do not re-use a single successful check
receipt to represent both phases.

## Receipt meaning and failure classification

Introduce a separate, versioned, host-authenticated red/green evidence record
rather than changing the meaning of existing `StageCheckEvidence.passed`.
Minimum fields for each phase are:

- `phase` (`RED` or `GREEN`), unique invocation ID, gate ID, check-plan
  digest, post-freeze artifact-binding digest and sandbox/toolchain
  environment fingerprint;
- graph/session, producing ATDD/TDD artifact IDs, producer stage IDs, paths,
  revisions and exact SHA-256 digests; Developer stage/attempt ID;
- `preDeveloperHead`, applicable post-write manifest/digest for GREEN,
  execution snapshot and complete selected/discovered test ID set;
- bounded structured per-test outcomes, normalized runner status, timestamp,
  nonce, terminal classification and trusted receipt identity/signature.

The host, not a model, normalizes the outcome. Suggested mutually exclusive
terminal classifications:

| Outcome | Meaning | May satisfy required evidence? |
| --- | --- | --- |
| `CONFIRMED_RED` | Selected tests actually ran; at least one bound assertion failed, without infrastructure failure | RED only |
| `CONFIRMED_GREEN` | All selected tests actually ran and passed; previously failing IDs now pass | GREEN only |
| `UNEXPECTED_PASS` | All selected tests already passed before Developer | No |
| `TEST_FAILURE_UNCLASSIFIED` | Nonzero exit without a verifiable selected-test assertion failure, or unrelated test failure | No |
| `TEST_SELECTION_INVALID` | Zero/missing/skipped/disabled/dry-run selected tests, or test/artifact binding mismatch | No |
| `CHECK_UNAVAILABLE` | Missing sandbox/tool, runner startup failure, timeout, interrupted execution or unreadable report | No |
| `STALE_CHECK_EVIDENCE` | Changed graph/lease, baseline, check plan, frozen input, environment or postimage | No |

A nonzero process exit must **never** be accepted as red without independently
validated structured evidence. The current sandbox's lack of Gradle/Maven/npm
and writable build outputs is `CHECK_UNAVAILABLE`, not a test failure.
The disposable checked overlay and pinned-cache design tracked in #154 is
a dependency for real build-runner support; the overlay must not grant the
model write authority or bypass the normal `verifyState()` post-check fence.

Receipt MACs remain host/runtime-internal, canonical and domain-separated
from successful check receipts. Red evidence must not be accepted by a
success-only validator, handed to the model as an authorization capability,
or relabelled as green. In-process HMACs do not provide durability or protect
against compromise of the same host process; crash/restart continuity would
need a separately specified durable authority and recovery protocol.

## Invalidation and fail-closed behavior

Any revision/supersession of bound ATDD/TDD artifacts, selected tests,
check-plan definition, derived artifact binding, toolchain environment,
source baseline, graph generation or Developer attempt invalidates the existing
pairing. Regenerate red on
the **new clean pre-Developer baseline**, not on a workspace already modified
by a failed Developer. Developer write retry, partial mutation or unknown
commit requires trusted Git/workspace reconciliation before new authority.
After a Developer write, every later mutation invalidates its green receipts,
as it does for today's required checks. A checksum mismatch or unavailable
authority must stop acceptance, not downgrade to a warning.

The host should prevent concurrent same-UID writers to the checkout during
both snapshots, maintain separate locks/generation fences for the red
preflight and writable Developer attempt, and refuse to synthesize evidence
from prior CI logs or user-supplied reports. The Reviewer cannot repair
missing evidence by approving the implementation manually.

## Implementation gates for a future PR (not supplied here)

- Role-specific policies are proven to run appropriate *different* checks:
  an ATDD/TDD test may fail before Developer without failing preparation
  acceptance, while Developer still requires `git_diff_check` and valid green.
- End-to-end test: real isolated checkout; ATDD/TDD frozen output; selected
  test has an observed assertion failure at pinned pre-Developer HEAD and
  a passing result after an authorized Developer source write. The exact
  previously failing test identity appears in both authenticated records.
- Negative fixtures reject `/usr/bin/false`, exit 1, thrown exception,
  missing tool, sandbox denial, timeout, compilation error, and zero tests
  as substitutes for confirmed red. Reject `/usr/bin/true`, dry-run and
  skipped tests as substitutes for green.
- Mutate one at a time: test content/path/digest, selector, check arguments,
  test report, toolchain/cache, baseline HEAD, source postimage, check plan,
  Developer attempt, stage generation, or receipt. Each must invalidate the
  pairing; stale green must fail after a subsequent write.
- Verify exact check/discovery coverage and prove a malicious model cannot
  supply or modify the host plan, normalized reports, failure classifications
  or signed receipts. Reviewer handoff rejects missing or mismatched phases.
- Run actual adapter integration/E2E checks on supported Linux with usable
  build-runner isolation. CI must fail on sandbox skips; offline fake-model
  fixtures alone do not establish this protocol. Retain all existing
  `PreparationStageHostContract` conformance and Developer/Reviewer gates.

**Migration:** No existing workflow result can be retroactively called
red-to-green. Keep existing successful-check semantics unchanged until the
new host contract, binding and receipt lifecycle have executable tests.
Any future CLI/plan or persisted-evidence format extension needs an explicit
version bump and compatibility/migration policy; it is **not** introduced by
this documentation issue.
