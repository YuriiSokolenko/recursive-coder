# Trusted C3 replacement candidate (#207)

Status: **proposal requiring independent security review**, not approval to remove
the existing source pins from dev. Review the security controls and the subsequent
constant migration separately. This branch depends on #206; merge/rebase order is
#206 first, then the reviewed replacement candidate. Do not close #194 or #207
solely because this branch exists.

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
approved. The module graph remains core:session -> core:contracts and
infra:journal -> core:session/core:contracts; no new production edges.

## Existing guarantee and proposed replacement

| Existing protection | Replacement or retained control |
| --- | --- |
| Exact source bytes unchanged | Exact source path and marker/declaration shape; compiled marker and class/method reference checks; independent review of body/dependency edits |
| Compiler opt-in cannot be bypassed by ordinary code | Existing ERROR-level opt-in and real Kotlin compiler fixtures retained; source gate still denies aliases, marker misuse, suppressions, overrides, compiler-wide opt-in and unexpected plugins |
| Host adapter cannot launder authority through another interface | Existing source header checks plus compiled port/adapter supertype and implementation checks |
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
behavioral equivalence. The original pins still protect dev until this
replacement is independently reviewed and accepted.

## Verification and review gate

- Review exact source declarations, marker retention/level, compiled call
  owners, compiler fixtures and all newly allowed/denied cases.
- Run real Kotlin opt-in compiler tests, architecture and configuration-cache
  fixtures (including harmless source changes), independent production JVM
  bytecode verification, pure unit, full JVM integration and installed CLI E2E.
- Independently review the literal-only substitutions against the previous
  source, especially explicit ledger allowlists and the runtime resume mode.
- Check branch protection/rulesets and required independent owner approvals.
  The existing repository documentation describes CODEOWNERS as advisory in
  this solo repository. The GitHub integration returned HTTP 403 when reading
  dev branch protection; enforcement is therefore **unverified**. Do not
  claim CODEOWNERS alone replaces the approval guarantee. Do not silently
  alter repository settings or remove required protections.
- If reviewer or repository enforcement is unavailable, retain the source
  pins on dev and treat this branch as a candidate, not completed #207.

No changes to journal/checkpoint digests, HMAC, effect evidence, distribution
checksums, dependency or CI toolchain integrity are intended. Protocol v1
and durable formats remain unchanged.

## Vocabulary audit

Targeted scan of all 84 production Kotlin files on #206 (1 agent/runtime,
8 app/cli, 25 core/contracts, 19 core/session, 8 core/tasks, 15 infra/journal,
2 infra/model, 1 infra/task-store, 5 infra/workspace) covered the existing
event-kind/envelope discriminators, stage graph metadata keys, selected
stage/tool/workspace denial codes, model provider/fake model identifiers,
metadata-only redaction and git-diff-check identifiers. The remaining
non-declaration matches were the two CLI session.error emissions, C3
runtime/policy/ledger/host emissions and the separate checkpoint binding
graphId field. The candidate migrates the C3 and CLI event consumers while
retaining the binding key in the checkpoint codec. Independent test literals,
explicit authorization allowlists, phase and task states, external HTTP names,
and durable cryptographic domains are not mechanically merged by spelling.

The scope is targeted; exhaustive semantic classification of all remaining
diagnostics, numeric bounds, adapter-local values and cross-language tooling
is still open under #194/#197–#205. No independent review or local full Gradle
execution is claimed.
