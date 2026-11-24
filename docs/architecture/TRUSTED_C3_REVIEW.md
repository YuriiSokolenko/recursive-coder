# Trusted C3 source pins and defense in depth (#207)

Status: **retain source pins; add structural and JVM verification**. The
[AI security review of PR #208](https://github.com/YuriiSokolenko/recursive-coder/pull/208#issuecomment-7136222362) on 2026-11-24 reviewed the source
migration at `c383735` and recommended keeping the pins. It was posted from
the owner's account; it is not independent human approval. The pin retirement
proposal is deferred under #207. PR #206 was squash-merged at `cc338ee`;
PR #208 was squash-merged at `191170451c01259f0fee14860852c4ec1678d7ee`
with all six source pins retained. Owner acceptance of that baseline is not
independent human approval. Future pin baselines require explicit review;
#207 remains open.

## Source pin review ledger

These SHA-256 values identify the exact reviewed source bytes at `c383735`.
Five files changed relative to dev; TrustedSessionPreExecutionHost.kt retains
its original hash. The pin update in PR #208 does not change those source bytes.

| Repository-relative path | SHA-256 | Review / date |
| --- | --- | --- |
| `core/session/src/main/kotlin/ai/yuriilab/recursivecoder/SessionRuntime.kt` | `a48ec483e9c19dd116ff77ff09c456fb45b1eef9440a2a0c269504e0c3f1f310` | [AI review](https://github.com/YuriiSokolenko/recursive-coder/pull/208#issuecomment-7136222362), 2026-11-24 |
| `core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedRunningBoundaryPolicy.kt` | `8a4f30fd26e6058e10a4684e495e2fce0083da6b75f64eb49cf04af61ec49a60` | [AI review](https://github.com/YuriiSokolenko/recursive-coder/pull/208#issuecomment-7136222362), 2026-11-24 |
| `core/session/src/main/kotlin/ai/yuriilab/recursivecoder/TrustedSessionPreExecutionHost.kt` | `da9512f54c0b32fe84eda6186d0a7b240dfdb415662f581b8b1ba70efbdd3546` | [AI review](https://github.com/YuriiSokolenko/recursive-coder/pull/208#issuecomment-7136222362), 2026-11-24 |
| `infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableAcceptedRunningHandoff.kt` | `507690ca1822059a2ab4d8c96bde5bfaf3cbcbadb63d77a48dae961e0b176b8c` | [AI review](https://github.com/YuriiSokolenko/recursive-coder/pull/208#issuecomment-7136222362), 2026-11-24 |
| `infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableRuntimeAcceptedRunningHost.kt` | `6ee179a41a653b9f231732890f66e36c18a5dbdbf9cdaaa6181bdb482272b9b8` | [AI review](https://github.com/YuriiSokolenko/recursive-coder/pull/208#issuecomment-7136222362), 2026-11-24 |
| `infra/journal/src/main/kotlin/ai/yuriilab/recursivecoder/DurableSessionEventLedger.kt` | `93cdf2a420ab4624332f2f612eed318f88a78747feb6ae593851832e1315426f` | [AI review](https://github.com/YuriiSokolenko/recursive-coder/pull/208#issuecomment-7136222362), 2026-11-24 |

Future pin updates must record the exact path, digest, reviewed revision, and
review link/date. Do not automatically refresh pins to make CI pass. This is
an audit trail, not proof that an independent human approved the change.

## Threat model and inventory

C3 is an effect-free, host-only ACCEPTED-to-RUNNING checkpoint publication,
not a resume or execution grant. Untrusted inputs include model/CLI requests,
event metadata, forged checkpoint labels and task data. A contributor could
also change an approved class, a policy dependency, Gradle compiler inputs,
bytecode, CI scripts or the approval rules themselves. Arbitrary repository or
runner write access cannot be made safe by a check in that same repository.

| Reviewed source | Approved entry or responsibility |
| --- | --- |
| SessionRuntime.kt | DefaultRunSessionUseCase.publishTrustedAcceptedRunning; no ordinary production caller |
| TrustedRunningBoundaryPolicy.kt | ERROR-level marker and exact root metadata transition validation |
| TrustedSessionPreExecutionHost.kt | separately marked TrustedSessionRunningHost port and informational receipt |
| DurableRuntimeAcceptedRunningHost.kt | final adapter implements only the marked port |
| DurableAcceptedRunningHandoff.kt | internal marked object, authenticated C2-to-C3 fenced publication |
| DurableSessionEventLedger.kt | marked internal appendTrustedAcceptedRunning and fault seam; private privileged append implementation |

Expected compiled call edges: runtime to marked port, host adapter to marked
handoff, handoff to ledger trusted append, ledger to its own marked fault seam.
No ordinary production caller of the runtime entry or C3 host constructor is
approved. The ledger's Kotlin-generated no-op callback may only be referenced
by the ledger itself with a matching private static method declaration and
exact callback descriptor; it does not exempt the privileged append entry.
The module graph remains core:session -> core:contracts and
infra:journal -> core:session/core:contracts; no new production edges.

## Existing guarantees and additional controls

| Existing protection | Retained or additional control |
| --- | --- |
| Exact source bytes unchanged | SHA-256 pins retained as a review tripwire; additional exact path and marker/declaration shape checks; independent review of body/dependency edits |
| Compiler opt-in cannot be bypassed by ordinary code | Existing ERROR-level opt-in and real Kotlin compiler fixtures retained; source gate still denies aliases, marker misuse, suppressions, overrides, compiler-wide opt-in and unexpected plugins |
| Host adapter cannot launder authority through another interface | Existing source header checks plus compiled port/adapter supertype and implementation checks; runtime, ledger, handoff and host must be final |
| Unexpected caller of a privileged API | Independent compiled class-level method and MethodHandle reference allowlist; tests for direct and indirect calls |
| C3 does not authorize model/tools/resume | Existing unit/integration/fault/lease/evidence tests retained; literal JSON oracle extends boundary coverage |
| Review required on changes | Broad CODEOWNERS coverage of transitive policy, contracts, adapters and test oracles; actual protected review must be established independently |

The class-file guard is not method-level control-flow verification. It does
not prove that the body of an allowed class or method is safe, that an allowed
call is made on a safe path, or that new unmarked methods cannot leak authority.
It cannot authenticate arbitrary compiler/CI/verification code controlled by
the same contributor, external dependency behavior or an attacker with runner
or repository-admin privileges. A whole-file hash likewise could not protect
transitive dependency changes. These are review obligations, not claims of
behavioral equivalence. The six source pins remain mandatory on dev; the
reviewed digest updates are visible in PR #208. The JVM check supplements,
not replaces, them. In particular, a new unmarked path to
the private privileged append implementation, weaker transition validation,
or an unmarked helper leaking authority requires a source-pin change, even
if the call-reference allowlist cannot distinguish it. The pins do not
prevent a contributor from editing the pins themselves.

## Verification and review gate

- Review exact source declarations, marker retention/level, compiled call
  owners, compiler fixtures and all newly allowed/denied cases.
- Run real Kotlin opt-in compiler tests, architecture and configuration-cache
  fixtures (including rejection of harmless but unreviewed pinned-source
  changes), independent production JVM bytecode verification, pure unit, full
  JVM integration and installed CLI E2E.
- Independently review the literal-only substitutions against the previous
  source, especially explicit ledger allowlists and the runtime resume mode.
- Check branch protection/rulesets and required independent owner approvals.
  Repository ruleset `27857731` (`Protect dev - Required CI and PR (fixed)`)
  is active and targets `~DEFAULT_BRANCH`; repository metadata identifies `dev`
  as the default branch. The ruleset requires PRs, all six CI checks and a
  current base, with deletion and non-fast-forward updates prohibited. However,
  `required_approving_review_count` is 0, `require_code_owner_review` is false,
  `require_last_push_approval` is false, and stale reviews are not dismissed.
  `require_extra_approval_for_unattributed_changes` is true; this does not
  establish general independent review.
  Thus independent approval is **not required by this ruleset**, despite CI
  enforcement. The classic branch-protection endpoint returned HTTP 403;
  the ruleset listing and detail endpoint were readable. See
  https://github.com/YuriiSokolenko/recursive-coder/rules/27857731.
  Do not claim CODEOWNERS alone replaces the approval guarantee. Before pin
  retirement, establish an eligible independent reviewer and protected review
  of security-sensitive changes, including subsequent pushes. Do not silently
  alter repository settings or remove required protections.
- Retain the source pins. Keep #207 open until an eligible independent human
  reviewer is available, at least one independent approval and last-push
  approval are enforced, and the replacement guarantees have been reviewed.
  A green CI run and this AI review are not a substitute for those conditions.

No changes to journal/checkpoint digests, HMAC, effect evidence, distribution
checksums, dependency or CI toolchain integrity are intended. Protocol v1
and durable formats remain unchanged.

## Follow-up behavioral oracle (#207)

The pure unit tests in `TrustedRunningBoundaryPolicyUnitTest` exercise
literal wire records, not production emitters, field constants or expected
values obtained from the implementation. The additional cases cover:

- Removal of each required field and each required nested actor, metadata
  and transition key; unexpected null keys, populated execution actor slots,
  mismatched string session/root identity and a present null timing field.
- The transitive `ToolAuthorizationPolicy.validHostIdentifier` grammar:
  independent positive ASCII cases and the 160-character limit, plus
  disallowed leading punctuation, separators, whitespace, non-ASCII and
  overlong values.
- A literal pre-execution prefix and exactly one final running edge;
  missing, duplicate and trailing edges and unsupported checkpoint phases;
  all known non-pre-execution event kinds and an unknown kind on either
  side of the accepted edge, plus malformed checkpoint transitions and
  non-completed prefix records. A separate completeness assertion compares
  the literal deny set with the public constant vocabulary without using
  the vocabulary to generate expected policy outcomes.
- The pure policy compares the textual content of `sessionId` without an
  `isString` check. A numeric `sessionId` with a matching root actor can
  pass that policy in isolation. Physical ledger validation requires a
  string identifier; the handoff also binds the checkpoint identity.
  The ledger hardening integration test supplies matching string and
  numeric session identifiers in independently hashed frames and requires
  rejection of the numeric case, not a hash failure. Pure policy is not
  a substitute for the ledger or evidence checks.

These are regression oracles for selected changes to the shared vocabulary,
identifier policy and pre-execution policy; they are not mutation-testing
results or a proof of arbitrary dependency behavior. They do not replace
durable evidence, physical framing, identity/sequence, integration or fault
checks. Production sources, dependency edges, pin digests and the compiled
guard are unchanged by this follow-up.

### Operational gate for pin retirement

Ruleset 27857731 was re-read on 2026-11-24 after #208 merged. It remains
active with all six required checks, strict current-base policy and no
bypass actors, but still requires zero approvals, no code-owner review, no
last-push approval and no dismissal of stale reviews. Classic branch
protection remains unreadable (HTTP 403). The connected integration cannot
establish independent human approval or change ruleset settings.

Currently `.github/CODEOWNERS` names only `@YuriiSokolenko`, the same
account that authors these PRs. GitHub does not permit self-approval.
Enabling mandatory code-owner and independent approval with that owner set
would block the current author workflow on dev. Do not enable the settings
until a real independent human reviewer with the necessary access is added
and the review workflow is verified. An AI review from the owner's account
or another account controlled by the same person is not independence.

Before proposing removal of the six pins, a repository administrator must
identify an eligible reviewer independent of the author/latest pusher,
enable at least one required approval, require approval of the latest
reviewable push, and dismiss stale approvals or establish equivalent
protected review. Require code-owner review with eligible independent
owners for the protected sources, transitive policy and verification
surface. Preserve the existing PR and CI requirements and verify effective
rules, including bypasses, on dev. Record the exact reviewed candidate
revision and approval evidence; re-review subsequent changes. A change
to this document or green CI cannot satisfy those external conditions.
Retain the pins and keep #207 open until the conditions are established
and the residual method-body and dependency guarantees are independently
reviewed. Alternatively, the owner may explicitly choose permanent pin
retention and revise #207's acceptance criteria to mark pin retirement as
not planned. That is a separate owner decision, not an implied acceptance
of risk or a completed retirement in this PR.

## Vocabulary audit

Targeted scan of all 84 production Kotlin files on #206 (1 agent/runtime,
8 app/cli, 25 core/contracts, 19 core/session, 8 core/tasks, 15 infra/journal,
2 infra/model, 1 infra/task-store, 5 infra/workspace) covered the existing
event-kind/envelope discriminators, stage graph metadata keys, selected
stage/tool/workspace denial codes, model provider/fake model identifiers,
metadata-only redaction and git-diff-check identifiers. The remaining
non-declaration matches were the two CLI session.error emissions, C3
runtime/policy/ledger/host emissions and the separate checkpoint binding
graphId field. PR #208 migrates the C3 and CLI event consumers while
retaining the binding key in the checkpoint codec. Independent test literals,
explicit authorization allowlists, phase and task states, external HTTP names,
and durable cryptographic domains are not mechanically merged by spelling.

The scope is targeted; exhaustive semantic classification of all remaining
diagnostics, numeric bounds, adapter-local values and cross-language tooling
is still open under #194/#197–#205. No independent human approval or local full
Gradle execution is claimed.
