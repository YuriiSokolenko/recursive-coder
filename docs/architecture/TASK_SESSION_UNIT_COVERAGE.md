# Task and Session pure-policy unit coverage audit (#37)

This matrix maps decision invariants to **JUnit Jupiter `@Tag("unit")`** test methods.
The baseline policy extraction tests belong to #26 (Task) and #28 (Session).
The #37 coverage tests target uncovered combinations and boundary values, not a
line-coverage quota or a particular number of test classes. All rows run with
value-only Task/Session inputs, handcrafted snapshots, fixed timestamps and no
filesystem, Git, HTTP, subprocess, model gateway, Koog or real-time waits.

Paths below are relative to `core/tasks/src/test/kotlin/ai/yuriilab/recursivecoder/`
and `core/session/src/test/kotlin/ai/yuriilab/recursivecoder/`, respectively.

## Task invariants

| Decision / invariant | Baseline unit test (#26) | Gap case (#37) |
| --- | --- | --- |
| All allowed and denied phase edges; reason/history timestamps; input immutability | `TaskPolicyUnitTest.legalTransitionsAreTableDrivenAndTimestamped`, `illegalTransitionsAreRejectedBeforeChangingState`, `unchangedReasonDoesNotAppendHistoryButChangedAttentionReasonDoes` | `TaskPolicyCoverageUnitTest.transitionMatrixCoversEveryDeclaredEdgeAndEveryDeniedEdge` checks the complete state-pair table, including same-state reason updates |
| Unique IDs, dependency validity, acyclicity, topological ordering | `dependencyGraphIsTopologicalAndRejectsInvalidEdits`, `iterativeGraphHandlesLargeDependencyChainsAndCycles` | `storedScalarInvariantsRejectInvalidSnapshotsWithoutAdapters` checks accepted and denied persisted scalar domain boundaries |
| Enqueue validation, dependency edits, immediate DAG readiness | `enqueuingAndChangingDependenciesRejectInvalidDomainInputs`, `dependencyFailurePropagatesAndRetryWakesDependentsInOneDecision` | `dependencyReadinessAndFailurePropagationCoverPrerequisiteStates` includes success, queued, running, failed, cancelled and attention prerequisites; `firstFailedPrerequisiteFollowsDeclaredDependencyOrder` pins failure reason precedence; `dependencyEditsArePermittedOnlyBeforeFirstAttempt` covers a valid edit and a denied edit after execution |
| Deterministic priority/FIFO and starvation relief | `priorityAndFIFOOrderingIsDeterministic`, `oldestTaskIsSelectedOnEveryEighthDispatchEvenWithHighPriorityArrivals`, `waitCreditIsCappedAndExcludedRunningTaskCannotBeReclaimed` | `eighthDispatchOldestSelectionAndExclusionsAreDeterministic` checks both sides of eighth-dispatch boundaries, exclusions, wait credit and no-dispatch behavior |
| Pause/resume keeps original ready ordering | `pauseResumePreservesOnlyAlreadyReadyPositions` | Existing table covers ready vs blocked when paused and dependency completion while paused; no new policy gap found |
| Retry, cancellation, ownership and late result fencing | `cancellationAndLateCompletionCannotOverrideTerminalStateOrNewOwner`, `recoveryRequiresAttentionWithoutReplayingSessions` | `cancelAllAndManualRetryFenceStaleCompletionAndKeepQueuePaused` covers active/terminal global cancellation, retry under pause, owner clearing, stale completion and successful current-owner completion |
| All terminal session outcomes map to Task dispositions | `everyTerminalSessionOutcomeHasAnExplicitTaskDisposition` | Existing complete outcome table; no new policy gap found |
| Bounded transition history | `boundedHistoryRetainsMostRecent128EventsWithDeterministicTimes` | Existing pruning and chronological-tail assertions; no new policy gap found |

## Session invariants

| Decision / invariant | Baseline unit test (#28) | Gap case (#37) |
| --- | --- | --- |
| Lifecycle legal/illegal edges, one terminal result, cancellation/timeout paths | `SessionPolicyUnitTest.onlyDeclaredLifecycleTransitionsAreLegal`, `aTerminalOutcomeCannotBeOverwrittenByCancellationTimeoutOrFailure`, `cancellationTimeoutAndDenialHaveDeterministicPathsFromEveryNonterminalPhase` | Existing exhaustive transition and nonterminal reason matrices; no duplicate test added |
| Admission: protocol, invocation, resume, capability, provider, tools, budget | `admissionDeniesUnsupportedInvocationsCapabilitiesModelAndUnsafePolicies` | `SessionPolicyCoverageUnitTest.admissionDenialPrecedenceIsStableWhenMultipleInputsAreInvalid` tests overlapping errors; `admissionBoundariesCoverModelAndToolPolicyVariants` adds provider/tool/counter variants; `schedulerAttemptRangeIsNotValidatedByPureAdmission` characterizes the existing scheduler attempt handling |
| Resume preflight does not convert unsupported resume into later denials | `resumeProbePreservesProtocolV1PrecedenceAcrossLaterDenials`, `resumeFilesystemDetailAcceptsOnlySafeHostVolumeIdentifiers` | `admissionDenialPrecedenceIsStableWhenMultipleInputsAreInvalid` includes a simultaneous resume/capability refusal |
| Two-turn model request reservation and retry quota | `modelRequestReservationAndToolQuotaAreFailClosed` | `modelTurnReservationHandlesExactCapacityAndInvalidCounters` checks exact/quota, negative counters and invalid turn indices; `recordedModelUsageRespectsTokenAndRequestEdgesWithoutOverflow` checks retry enabled/disabled and Long boundaries |
| Token observation accounting, unknown usage, overflow/negative fail closed | `tokenAccountingRejectsOverLimitNegativeAndOverflowWithoutSideEffects` | `recordedModelUsageRespectsTokenAndRequestEdgesWithoutOverflow` checks exact capacity, combined usage, negative prior counters, overflow-sized usage and cumulative accounting |
| Tool-call consumption before dispatch, fail-closed at quota | `modelRequestReservationAndToolQuotaAreFailClosed` | `toolQuotaGuardsZeroNegativeExactAndLongMaxBoundaries` covers negative, zero, exact and Long.MAX_VALUE counters |
| Explicit deadline and timeout-before-cancellation precedence | `effectiveDeadlineRespectsTheEarlierOfRequestAndWallBudget`, `deadlineTakesPrecedenceOverSimultaneousCancellation`, `injectedCancellationClockAndExplicitTimePreserveTimeoutPrecedence` | `interruptionMatrixMakesTimeoutDominantAtAndAfterDeadline` enumerates both signal flags before/on/after deadline and checks all three public host-gate outcomes (no interruption, cancellation, timeout) |
| Protocol-result diagnostics are mapped from typed outcomes | `SessionResultMapperUnitTest.diagnosticMessagesAreFixedAndDoNotIncorporateInboundRequestText` | Existing complete typed-outcome table; no new policy gap found |

## Tier and adapter boundary

New test classes: `TaskPolicyCoverageUnitTest.kt` and
`SessionPolicyCoverageUnitTest.kt`. Both are class-tagged `unit`.
They must be included by `unitTest`; the ordinary `test` task remains
unfiltered. Tier verification introduced in #34 is not bypassed.

The following are **intentionally not pure-unit assertions** and retain their
existing integration/E2E owners:

- `:infra:task-store` TaskManager/TaskRepository tests: file-backed state,
  process/lock races, restart recovery and durable queue encoding.
- `:app:cli` Task/Session CLI tests: installed behavior, real adapters,
  sandbox execution and command/result compatibility.
- `:infra:journal` tests: physical ledger/checkpoint publication and recovery;
  `:infra:model` tests: provider/gateway behavior and usage evidence.
- `contracts/v1/session-v1.test.mjs` and installed Python CLI suites:
  Session Protocol v1 envelopes, event ordering and adapter validation.

The pure admission policy currently accepts scheduler attempts of zero and
negative values when all other facts are valid. The characterization test
pins that existing behavior, not a new authorization guarantee; protocol
validation and trusted caller constraints are separate. Tightening admission
would require a separate behavior-changing issue.

Remaining integration concerns include actual worker scheduling races and
persistent recovery, model/gateway wire errors, filesystem boundary checks,
sandbox effects, and installed CLI/protocol conformance. They must not be
substituted with mock-heavy pure tests. This audit asserts no numerical
coverage percentage or guarantee about unexercised infrastructure paths.

## Verification

Run `./gradlew :core:tasks:unitTest :core:session:unitTest` for the focused
pure cases; `./gradlew unitTest checkTestTiers checkArchitecture` to verify
classification/dependency boundaries; `./gradlew test installDist` for the
unfiltered JVM suite and distribution. Preserve the existing sandbox E2E and
Session Protocol v1 CI checks. The PR's CI result, rather than this document,
is authoritative for whether those integration checks passed.
