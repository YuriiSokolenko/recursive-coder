package ai.yuriilab.recursivecoder

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Pure scripted host/role boundaries: not evidence of real receipt or Git fencing. */
@Tag("unit")
class FiveRoleWorkflowCoordinatorUnitTest {
    private class Fixture : FiveRoleWorkflowHost, FiveRoleStageActions {
        private val graphId = GraphId("five-role-unit-79")
        private val ids = StageKind.entries.associateWith {
            StageRunId("unit-" + it.name.lowercase())
        }
        val outputs = StageKind.entries.filter { it != StageKind.REVIEW }.associateWith {
            FiveRoleArtifactTarget(ArtifactId("artifact-" + it.name.lowercase()), when (it) {
                StageKind.SDD -> "specs/Requirement.md"
                StageKind.ATDD -> "tests/acceptance/Requirement.feature"
                StageKind.TDD -> "tests/unit/RequirementTest.kt"
                StageKind.DEVELOP -> "src/main/Implementation.json"
                StageKind.REVIEW -> error("No reviewer output")
            })
        }
        var state = PreparationStageSnapshot(StageGraph(
            GraphCorrelation("session-unit-79", graphId, "correlation-unit-79"),
            StageGraphState.RUNNING, listOf(node(StageKind.SDD)), emptyList(),
            StageBudget(10000, 60000, 0, 0)
        ), emptySet())
        var snapshotCount = 0
        var snapshotMode = ""
        var prepareMode = ""
        var developMode = ""
        var advanceMode = ""
        var reviewMode = ""
        var acceptedDeveloperEvidence: DeveloperReviewEvidence? = null
        val receivedHandoffs = mutableListOf<Pair<StageKind, DeveloperReviewEvidence?>>()
        var finishMode = ""
        var called = mutableListOf<StageKind>()

        private fun node(kind: StageKind): StageNode {
            val producers = outputs.keys.filter { producer ->
                producer.output?.let { it in kind.requiredInputs } == true
            }
            val correlation = StageCorrelation("session-unit-79", graphId,
                ids.getValue(kind), "request-" + kind.name.lowercase(),
                "correlation-" + kind.name.lowercase())
            return StageNode(correlation, kind, StageState.CREATED,
                AgentId("actor-" + kind.name.lowercase()),
                dependencies = producers.map { ids.getValue(it) }.toSet(),
                inputArtifacts = producers.map { outputs.getValue(it).id }.toSet())
        }

        fun plan() = FiveRoleWorkflowPlan(
            outputs, StageKind.entries.associateWith { "Execute " + it.name },
            listOf(DeveloperFileTarget("src/main/Feature.kt", "b".repeat(64))),
            ModelConfig("fake", "fake-v1", "http://localhost/unused",
                "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000),
            Instant.MAX, Cancellation()
        )

        override fun snapshot(): PreparationStageSnapshot {
            snapshotCount++
            if (snapshotMode == "throw" ||
                (snapshotMode == "throw-on-stop" && snapshotCount == 3))
                error("Host unavailable")
            if (snapshotMode == "changed" && snapshotCount == 2)
                return state.copy(graph = state.graph.copy(
                    budget = state.graph.budget.copy(maxTokens = 9999)))
            return state
        }

        private fun approved(graph: StageGraph, kind: StageKind,
            artifactId: ArtifactId, path: String): Pair<StageArtifactManifest, PreparationStageSnapshot> {
            val stage = graph.stages.single { it.kind == kind && it.supersededBy == null }
            val manifest = StageArtifactManifest(artifactId, requireNotNull(kind.output),
                path, "a".repeat(64), kind.role, stage.correlation.stageRunId, 1,
                ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
            val updated = graph.copy(stages = graph.stages.map {
                if (it == stage) it.copy(state = StageState.ACCEPTED,
                    outputArtifacts = setOf(artifactId)) else it
            }, artifacts = graph.artifacts + manifest)
            return manifest to PreparationStageSnapshot(updated, state.acceptedIds + artifactId)
        }

        override suspend fun prepare(command: PreparationStageCommand,
            roleEvents: StageRoleEventSink, events: PreparationStageEventSink
        ): PreparationStageResult {
            val kind = command.graph.stages.single {
                it.correlation.stageRunId == command.stageRunId
            }.kind
            called += kind
            if (prepareMode == "denied") return PreparationStageResult(
                state.graph, state.acceptedIds, PreparationStageOutcome.Denied("DENIED_BY_STAGE"))
            if (prepareMode == "failed") return PreparationStageResult(
                state.graph, state.acceptedIds, PreparationStageOutcome.Failed("STAGE_FAILED"))
            if (prepareMode == "reported-stale") return PreparationStageResult(
                state.graph.copy(budget = state.graph.budget.copy(maxTokens = 9998)),
                state.acceptedIds, PreparationStageOutcome.Failed("STAGE_FAILED"))
            val (manifest, accepted) = approved(command.graph, kind,
                command.artifactId, command.outputPath)
            if (prepareMode == "human") return PreparationStageResult(state.graph,
                state.acceptedIds, PreparationStageOutcome.AwaitingHuman(manifest))
            if (prepareMode != "no-commit") state = accepted
            val response = if (prepareMode == "wrong-output")
                manifest.copy(id = ArtifactId("incorrect-output")) else manifest
            return PreparationStageResult(accepted.graph,
                if (prepareMode == "missing-acceptance") null else accepted.acceptedIds,
                PreparationStageOutcome.Accepted(response,
                    StageValidationEvidence(
                        command.graph.stages.single { it.kind == kind }.correlation,
                        requireNotNull(command.graph.stages.single { it.kind == kind }.agentId),
                        emptyList(), true), StageRoleUsage(1, 100, 30)))
        }

        override suspend fun develop(command: DeveloperStageCommand,
            roleEvents: StageRoleEventSink, events: PreparationStageEventSink
        ): DeveloperStageResult {
            called += StageKind.DEVELOP
            when (developMode) {
                "denied" -> return DeveloperStageResult(state.graph, state.acceptedIds,
                    DeveloperStageOutcome.Denied("DEVELOPER_DENIED"))
                "failed" -> return DeveloperStageResult(state.graph, state.acceptedIds,
                    DeveloperStageOutcome.Failed("DEVELOPER_FAILED", false))
                "uncertain" -> return DeveloperStageResult(null, null,
                    DeveloperStageOutcome.ReconciliationRequired("DEVELOPER_UNCERTAIN", true))
            }
            val (manifest, accepted) = approved(command.graph, StageKind.DEVELOP,
                command.artifactId, command.manifestPath)
            val stage = command.graph.stages.single { it.kind == StageKind.DEVELOP }
            val result = DeveloperStageOutcome.Accepted(manifest,
                listOf(DeveloperChangedFile("src/main/Feature.kt", "b".repeat(64),
                    "c".repeat(64))),
                "write-unit", StageValidationEvidence(stage.correlation,
                    requireNotNull(stage.agentId), emptyList(), true),
                StageRoleUsage(1, 100, 30))
            acceptedDeveloperEvidence = DeveloperReviewEvidence(result.manifest,
                result.changedFiles.map { ReviewerChangedFile(it.path, it.beforeSha256,
                    it.afterSha256) }, result.writeToolId, result.validation, result.usage)
            state = accepted
            return DeveloperStageResult(
                if (developMode == "missing-graph") null else accepted.graph,
                if (developMode == "missing-acceptance") null else accepted.acceptedIds,
                when (developMode) {
                    "wrong-output" -> result.copy(manifest = manifest.copy(
                        id = ArtifactId("incorrect-developer-output")))
                    "tampered-handoff" -> result.copy(writeToolId = "forged-write")
                    else -> result
                })
        }

        override suspend fun review(command: ReviewerStageCommand,
            roleEvents: StageRoleEventSink, events: ReviewerDecisionSink
        ): ReviewerStageResult {
            called += StageKind.REVIEW
            when (reviewMode) {
                "denied" -> return ReviewerStageResult(state.graph, state.acceptedIds,
                    ReviewerStageOutcome.Denied("REVIEWER_DENIED"))
                "failed" -> return ReviewerStageResult(state.graph, state.acceptedIds,
                    ReviewerStageOutcome.Failed("REVIEWER_FAILED"))
                "uncertain" -> return ReviewerStageResult(null, null,
                    ReviewerStageOutcome.ReconciliationRequired("REVIEWER_UNCERTAIN"))
            }
            val stage = state.graph.stages.single { it.kind == StageKind.REVIEW }
            val implementation = state.graph.artifacts.single {
                it.kind == StageArtifactKind.IMPLEMENTATION
            }
            val verdict = when (reviewMode) {
                "reject" -> ReviewerDisposition.REJECT
                "human" -> ReviewerDisposition.NEEDS_HUMAN
                else -> ReviewerDisposition.ACCEPT
            }
            val decision = ReviewerDecisionRecord(stage.correlation, verdict,
                if (verdict == ReviewerDisposition.ACCEPT) "OK" else
                    if (verdict == ReviewerDisposition.REJECT) "IMPLEMENTATION_DEFECT"
                    else "EVIDENCE_MISSING",
                if (verdict == ReviewerDisposition.REJECT)
                    ReviewerCorrectionScope.IMPLEMENTATION_ONLY else ReviewerCorrectionScope.NONE,
                setOf("check:git_diff_check"), implementation.sha256)
            if (verdict != ReviewerDisposition.ACCEPT) {
                val outcome = if (verdict == ReviewerDisposition.REJECT)
                    ReviewerStageOutcome.Rejected(decision, StageRoleUsage())
                else ReviewerStageOutcome.NeedsHuman(decision, StageRoleUsage())
                return ReviewerStageResult(state.graph, state.acceptedIds, outcome)
            }
            val dev = state.graph.stages.single { it.kind == StageKind.DEVELOP }
            val changed = state.graph.copy(stages = state.graph.stages.map {
                if (it.kind == StageKind.REVIEW)
                    it.copy(state = StageState.ACCEPTED, review = StageReviewDecision(
                        dev.correlation.stageRunId, implementation.id,
                        implementation.sha256, requireNotNull(dev.agentId),
                        requireNotNull(it.agentId), ReviewVerdict.ACCEPT))
                else it
            })
            state = state.copy(graph = changed)
            return ReviewerStageResult(changed, state.acceptedIds,
                ReviewerStageOutcome.Accepted(decision, StageRoleUsage()))
        }

        override fun advance(expected: PreparationStageSnapshot, next: StageKind,
            developerHandoff: DeveloperReviewEvidence?): PreparationStageSnapshot? {
            if (state != expected) return null
            receivedHandoffs += next to developerHandoff
            if (advanceMode == "denied") return null
            if (advanceMode == "throw") error("Commit uncertain")
            if (advanceMode == "missing-next") return state
            if (developerHandoff != if (next == StageKind.REVIEW)
                    acceptedDeveloperEvidence else null) return null
            val candidate = state.copy(graph = state.graph.copy(
                stages = state.graph.stages + node(next)))
            if (advanceMode != "uncommitted-next") state = candidate
            return candidate
        }

        override fun finishSuccess(expected: PreparationStageSnapshot,
            reviewerId: StageRunId): StageGraph? {
            if (state != expected) return null
            if (finishMode == "denied") return null
            if (finishMode == "throw") error("Commit uncertain")
            if (StageGraphPolicy.authorizeGraphTransition(expected.graph,
                StageGraphState.SUCCEEDED, expected.acceptedIds,
                setOf(reviewerId)) != StageGateDecision.Permit) return null
            val graph = expected.graph.copy(state = if (finishMode == "invalid")
                StageGraphState.CREATED else StageGraphState.SUCCEEDED)
            if (finishMode != "not-persisted") state = state.copy(graph = graph)
            return graph
        }

        suspend fun run(plan: FiveRoleWorkflowPlan = plan()) =
            RunFiveRoleWorkflowUseCase(this, this).execute(plan,
                StageRoleEventSink { }, PreparationStageEventSink { },
                ReviewerDecisionSink { })
    }

    private suspend fun expectStopped(f: Fixture, code: String, stage: StageKind = StageKind.SDD,
        plan: FiveRoleWorkflowPlan = f.plan()) {
        val outcome = assertIs<FiveRoleWorkflowOutcome.Stopped>(f.run(plan))
        assertEquals(code, outcome.code)
        assertEquals(stage, outcome.stage)
        if (code != "INVALID_WORKFLOW_PLAN") assertEquals(f.state, outcome.authoritative)
        else assertNull(outcome.authoritative)
    }

    private suspend fun expectUncertain(f: Fixture, code: String,
        stage: StageKind = StageKind.SDD) {
        val outcome = assertIs<FiveRoleWorkflowOutcome.ReconciliationRequired>(f.run())
        assertEquals(code, outcome.code)
        assertEquals(stage, outcome.stage)
    }

    @Test fun invalidPlansCannotReachAnyRoleOrHost() = runBlocking {
        val prototype = Fixture().plan()
        val bad = listOf(
            prototype.copy(artifacts = prototype.artifacts - StageKind.ATDD),
            prototype.copy(artifacts = prototype.artifacts + (
                StageKind.ATDD to prototype.artifacts.getValue(StageKind.SDD).copy())),
            prototype.copy(artifacts = prototype.artifacts + (
                StageKind.SDD to prototype.artifacts.getValue(StageKind.SDD)
                    .copy(path = "tests/acceptance/Forbidden.feature"))),
            prototype.copy(artifacts = prototype.artifacts + (
                StageKind.TDD to prototype.artifacts.getValue(StageKind.TDD)
                    .copy(path = prototype.artifacts.getValue(StageKind.SDD).path))),
            prototype.copy(instructions = prototype.instructions + (StageKind.SDD to "")),
            prototype.copy(developerTargets = emptyList()),
            prototype.copy(developerTargets = (0..7).map {
                DeveloperFileTarget("src/main/Target$it.kt")
            }),
            prototype.copy(developerTargets = listOf(DeveloperFileTarget(
                prototype.artifacts.getValue(StageKind.SDD).path))),
            prototype.copy(developerTargets = listOf(DeveloperFileTarget(
                "specs/Protected.md"))),
            prototype.copy(developerTargets = listOf(DeveloperFileTarget(
                "tests/unit/ProtectedTest.kt")))
        )
        for (plan in bad) {
            val fixture = Fixture()
            expectStopped(fixture, "INVALID_WORKFLOW_PLAN", plan = plan)
            assertEquals(0, fixture.snapshotCount)
            assertEquals(emptyList(), fixture.called)
        }
    }

    @Test fun authoritativeInputsAndStoppedResultsMustBeVerified() = runBlocking {
        val initial = Fixture()
        initial.state = initial.state.copy(
            graph = initial.state.graph.copy(state = StageGraphState.CREATED))
        expectStopped(initial, "INVALID_INITIAL_STAGE_GRAPH")
        val drift = Fixture()
        drift.snapshotMode = "changed"
        expectUncertain(drift, "WORKFLOW_STATE_CHANGED")
        val unavailable = Fixture()
        unavailable.snapshotMode = "throw"
        expectUncertain(unavailable, "HOST_SNAPSHOT_UNAVAILABLE")
        val denied = Fixture()
        denied.prepareMode = "denied"
        expectStopped(denied, "DENIED_BY_STAGE")
        val stopped = Fixture()
        stopped.prepareMode = "failed"
        expectStopped(stopped, "STAGE_FAILED")
        val staleReport = Fixture()
        staleReport.prepareMode = "reported-stale"
        expectUncertain(staleReport, "UNCONFIRMED_STOP_STATE")
        val human = Fixture()
        human.prepareMode = "human"
        expectStopped(human, "HUMAN_APPROVAL_REQUIRED")
        val stoppedReadFailure = Fixture()
        stoppedReadFailure.prepareMode = "denied"
        stoppedReadFailure.snapshotMode = "throw-on-stop"
        expectUncertain(stoppedReadFailure, "HOST_STOP_SNAPSHOT_UNAVAILABLE")
        val running = Fixture()
        running.state = running.state.copy(graph = running.state.graph.copy(
            stages = running.state.graph.stages.map { it.copy(state = StageState.RUNNING) }))
        expectStopped(running, "WORKFLOW_STAGE_NOT_READY")
    }

    @Test fun outputAndCommitGatesRejectUnconfirmedAcceptance() = runBlocking {
        val mismatched = Fixture()
        mismatched.prepareMode = "wrong-output"
        expectUncertain(mismatched, "WORKFLOW_OUTPUT_MISMATCH")
        val missing = Fixture()
        missing.prepareMode = "missing-acceptance"
        expectUncertain(missing, "MISSING_HOST_ACCEPTANCE")
        val uncommitted = Fixture()
        uncommitted.prepareMode = "no-commit"
        expectUncertain(uncommitted, "UNCONFIRMED_STAGE_COMMIT")
        for ((mode, code) in listOf(
            "denied" to "STAGE_RECONCILIATION_DENIED",
            "throw" to "STAGE_RECONCILIATION_UNCONFIRMED",
            "uncommitted-next" to "STAGE_RECONCILIATION_UNCONFIRMED",
            "missing-next" to "INVALID_NEXT_STAGE_GRAPH"
        )) {
            val fixture = Fixture()
            fixture.advanceMode = mode
            expectUncertain(fixture, code,
                if (mode == "missing-next") StageKind.ATDD else StageKind.SDD)
        }
        val tamperedHandoff = Fixture()
        tamperedHandoff.developMode = "tampered-handoff"
        expectUncertain(tamperedHandoff, "STAGE_RECONCILIATION_DENIED", StageKind.DEVELOP)
        val passed = requireNotNull(tamperedHandoff.receivedHandoffs.last().second)
        assertEquals("forged-write", passed.writeToolId)
        assertEquals("write-unit",
            requireNotNull(tamperedHandoff.acceptedDeveloperEvidence).writeToolId)
    }

    @Test fun developerAndReviewerFailuresNeverBecomeSuccessfulStages() = runBlocking {
        for ((mode, code, needsReconciliation) in listOf(
            Triple("denied", "DEVELOPER_DENIED", false),
            Triple("failed", "DEVELOPER_FAILED", false),
            Triple("uncertain", "DEVELOPER_UNCERTAIN", true)
        )) {
            val fixture = Fixture()
            fixture.developMode = mode
            if (needsReconciliation) expectUncertain(fixture, code, StageKind.DEVELOP)
            else expectStopped(fixture, code, StageKind.DEVELOP)
            assertEquals(StageKind.DEVELOP, fixture.called.last())
        }
        for ((mode, code) in listOf(
            "wrong-output" to "WORKFLOW_OUTPUT_MISMATCH",
            "missing-acceptance" to "MISSING_HOST_ACCEPTANCE",
            "missing-graph" to "MISSING_COMMITTED_GRAPH"
        )) {
            val fixture = Fixture()
            fixture.developMode = mode
            expectUncertain(fixture, code, StageKind.DEVELOP)
        }
        for ((mode, code, needsReconciliation) in listOf(
            Triple("denied", "REVIEWER_DENIED", false),
            Triple("failed", "REVIEWER_FAILED", false),
            Triple("uncertain", "REVIEWER_UNCERTAIN", true)
        )) {
            val fixture = Fixture()
            fixture.reviewMode = mode
            if (needsReconciliation) expectUncertain(fixture, code, StageKind.REVIEW)
            else expectStopped(fixture, code, StageKind.REVIEW)
            assertEquals(StageKind.REVIEW, fixture.called.last())
        }
    }

    @Test fun developerEvidenceIsForwardedOnlyToReviewerAndWithoutMutation() = runBlocking {
        val fixture = Fixture()
        assertIs<FiveRoleWorkflowOutcome.Completed>(fixture.run())
        assertEquals(listOf(StageKind.ATDD, StageKind.TDD, StageKind.DEVELOP,
            StageKind.REVIEW), fixture.receivedHandoffs.map { it.first })
        assertEquals(listOf(null, null, null),
            fixture.receivedHandoffs.take(3).map { it.second })
        val actual = requireNotNull(fixture.receivedHandoffs.last().second)
        assertEquals(fixture.acceptedDeveloperEvidence, actual)
        assertEquals("src/main/Implementation.json", actual.manifest.declaredPath)
        assertEquals("write-unit", actual.writeToolId)
        assertEquals(listOf(ReviewerChangedFile("src/main/Feature.kt",
            "b".repeat(64), "c".repeat(64))), actual.changedFiles)
        assertEquals(StageKind.DEVELOP.role, actual.manifest.ownerRole)
    }

    @Test fun reviewerAndFinalGraphFailuresRemainFailClosed() = runBlocking {
        val rejected = Fixture()
        rejected.reviewMode = "reject"
        expectStopped(rejected, "REVIEWER_REJECTED", StageKind.REVIEW)
        val human = Fixture()
        human.reviewMode = "human"
        expectStopped(human, "REVIEWER_NEEDS_HUMAN", StageKind.REVIEW)
        for ((mode, code) in listOf(
            "denied" to "GRAPH_FINISH_DENIED",
            "throw" to "GRAPH_FINISH_UNCONFIRMED",
            "invalid" to "INVALID_FINISHED_GRAPH",
            "not-persisted" to "GRAPH_FINISH_UNCONFIRMED"
        )) {
            val fixture = Fixture()
            fixture.finishMode = mode
            expectUncertain(fixture, code, StageKind.REVIEW)
        }
        val completed = Fixture()
        val result = assertIs<FiveRoleWorkflowOutcome.Completed>(completed.run())
        assertEquals(StageGraphState.SUCCEEDED, result.graph.state)
        assertEquals(StageKind.entries.toList(), completed.called)
    }
}
