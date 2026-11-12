# Independent Reviewer and bounded repair (issue #77)

The Reviewer is an explicitly opted-in, read-only fifth role, after accepted/frozen
SDD, ATDD, TDD and Developer handoffs. It never receives StageToolSessionOpener,
TrustedWriteRuntime, shell commands, filesystem writes, approval IDs or host
receipts. It receives verified requirements, frozen tests, Developer source
postimages, an implementation manifest, check/write receipt **identifiers** and
sanitized role history. Model text has no authorization authority.

## Trusted admission and evidence

`RunReviewerStageUseCase` uses a new `ReviewerStageHost` CAS lease, separate
from preparation/Developer leases (those require a writable output and cannot
commit a typed review). The host must atomically claim the stage, retain the
actual Developer handoff, independently revalidate cryptographically authentic
check receipts and live workspace hashes, and fence every read/finish by the
active lease. The in-process `StageValidationEvidence.passed` boolean alone
does **not** prove that a check ran. A host adapter that trusts it or reads
receipts supplied by the model is invalid.

The role is admitted only with the exact four host-accepted frozen artifacts.
Identity and correlation are matched against the active Developer and Reviewer.
The Reviewer actor must differ from the Developer actor. Review snapshots may
expose only bounded metadata, and no raw model prose is recorded in events.

## Decision format and gates

A Reviewer proposal is exactly one JSON object with four keys:

```json
{
  "verdict": "ACCEPT",
  "code": "OK",
  "scope": "NONE",
  "evidence": ["check:git_diff_check", "check:offline", "write:write-id"]
}
```

The allowed choices are `ACCEPT`, `REJECT` and `NEEDS_HUMAN`.
An acceptance requires **every** trusted check ID and the trusted write ID;
a rejection must cite trusted file/check evidence. All evidence references must
match the host-retained handoff. Reason codes are allowlisted, not free text.
The model cannot forge checks, invent new evidence IDs, approve itself, modify
frozen inputs, or silently replace independent host approval. `REJECT` is
reserved for implementation-only correction; any required change to an
approved spec or test is `NEEDS_HUMAN`, not a Developer bypass.

`ReviewerStageFinishPolicy` checks that only the Reviewer node changed,
the verdict matches the pinned Developer manifest and independent actors,
and no accepted ID or artifact was mutated. The authoritative host must
run this policy and independently recheck live receipt provenance while
committing the decision atomically. Any uncertain begin/finish requires
reconciliation; it is never reported as success.

## Bounded repair routing

`ReviewerRepairPlanner.plan` accepts only an independently host-verified
`REJECT / IMPLEMENTATION_ONLY` decision on an active rejected Reviewer.
It produces a **new** Developer stage and a **new** Reviewer stage with
fresh host-issued correlations and agents; the old terminal nodes and
manifests are kept as superseded history. Attempts are capped by
`maxDeveloperRetries` (additional attempts after the first).
Exhaustion, invalid identities and requests affecting frozen specs or
tests deny the plan. The next Reviewer is not runnable until the next
Developer has produced and frozen a new implementation manifest and the host
has updated its inputs.

Planning is not physical execution, and it does not permit a write. Any
subsequent Developer attempt still uses its own immutable-input verification,
host-authorized source targets, sandbox checks and trusted receipt validation.
If recovery of a dirty workspace is needed, use the separate safe
reconciliation work (#85); do not silently retry in-place. Durable job
scheduling, nested graphs and crash checkpoints remain separate issues.

## Verification and compatibility

The offline `FiveRoleFakeModelWorkflowIntegrationTest` executes all five
use cases in order with FakeModel and trusted fake tool/host ports. Separately,
`RunReviewerStageIntegrationTest` covers five-role graph acceptance,
independent FakeModel verdicts, rejection and bounded replan,
a repaired second review, receipt missing/failure, unsupported evidence,
self-review, frozen-input tampering, attempted frozen-test revision, host
evidence revocation and uncertain finish. Existing unit, integration, physical
sandbox E2E, architecture and Session Protocol v1 CI gates are unchanged.

These contracts are in-process only and require explicit
`reviewerStageModule(host)` composition. The default CLI remains read-only.

`ReviewerStageResult.usage` retains validated model usage for rejected, failed,
and CAS-uncertain post-invocation outcomes. Only the trusted host can confirm
an accepted review; in-process preflight reads cannot close the final TOCTOU
window. Production hosts MUST recheck receipt and byte integrity atomically
with their compare-and-swap commit.

Pure Reviewer policies are independently tested in `ReviewerStagePolicyUnitTest`
(`@Tag("unit")`), including literal denial codes and the historical-attempt
retry boundary, separate from the offline integration/E2E tests.
