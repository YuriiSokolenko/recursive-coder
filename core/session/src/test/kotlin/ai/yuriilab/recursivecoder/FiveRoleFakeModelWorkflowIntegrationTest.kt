package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Execute ALL five real use cases/roles offline, not merely a prebuilt graph:
 * FakeModel -> SDD -> ATDD -> TDD -> Developer -> independent Reviewer.
 * The host and sandbox port are deterministic fixtures; real bubblewrap E2E
 * remains in StageToolSessionIntegrationTest / DeveloperLinuxStageIntegrationTest.
 */
@Tag("integration")
class FiveRoleFakeModelWorkflowIntegrationTest {
    private val graphId = GraphId("graph-77-full")
    private val sessionId = "session-77-full"
    private val outputPaths = mapOf(
        StageKind.SDD to "specs/FeatureFull.md",
        StageKind.ATDD to "tests/acceptance/FeatureFull.feature",
        StageKind.TDD to "tests/unit/FeatureFullTest.kt",
        StageKind.DEVELOP to "src/main/ImplementationFull.txt"
    )
    private val proposed = mapOf(
        StageKind.SDD to "Requirement: feature full\n",
        StageKind.ATDD to "Scenario: feature full\n",
        StageKind.TDD to "fun testFeatureFull() = Unit\n"
    )
    private val sourcePath = "src/main/FeatureFull.kt"
    private val oldSource = "fun featureFull() = 1\n"
    private val newSource = "fun featureFull() = 2\n"
    private val ids = StageKind.entries.associateWith {
        StageRunId("full-" + it.name.lowercase())
    }
    private val artifactIds = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD, StageKind.DEVELOP)
        .associateWith { ArtifactId("full-artifact-" + it.name.lowercase()) }
    private val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
        "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000)
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(bytes(s)).joinToString("") { "%02x".format(it) }
    private fun correlation(kind: StageKind): StageCorrelation =
        StageCorrelation(sessionId, graphId, ids.getValue(kind),
            "request-full-" + kind.name.lowercase(),
            "corr-full-" + kind.name.lowercase())
    private fun node(kind: StageKind): StageNode {
        val requirements = kind.requiredInputs
        val predecessors = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD, StageKind.DEVELOP)
            .filter { it.output in requirements }
        return StageNode(correlation(kind), kind, StageState.CREATED,
            AgentId("actor-full-" + kind.name.lowercase()),
            dependencies = predecessors.map { ids.getValue(it) }.toSet(),
            inputArtifacts = predecessors.map { artifactIds.getValue(it) }.toSet())
    }

    private class FakeFiveRoleModel(private val sourcePath: String,
        private val newSource: String, private val proposed: Map<StageKind, String>) :
        StageRoleExecutor {
        val called = mutableListOf<StageKind>()
        override suspend fun execute(
            request: StageRoleRequest, events: StageRoleEventSink
        ): StageRoleResult {
            val stage = request.graph.stages.single {
                it.correlation.stageRunId == request.stageRunId
            }
            called += stage.kind
            assertEquals(stage.kind.role, request.expectedRole)
            assertTrue(request.requestedTools.isEmpty())
            val response = when (stage.kind) {
                StageKind.SDD, StageKind.ATDD, StageKind.TDD ->
                    proposed.getValue(stage.kind)
                StageKind.DEVELOP ->
                    """{"files":[{"path":"src/main/FeatureFull.kt","content":"fun featureFull() = 2\n"}]}"""
                StageKind.REVIEW ->
                    """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":["check:git_diff_check","check:offline","write:write-full-develop"]}"""
            }
            return StageRoleResult(stage.correlation, request.expectedRole, stage.agentId,
                StageRoleOutcome.Proposed(response), StageRoleUsage(1, 100, 30))
        }
    }

    /** Shared authoritative graph and files; two separate CAS leases by design. */
    private inner class FakePreparationHost : PreparationStageHost {
        val files = mutableMapOf(sourcePath to bytes(oldSource))
        var graph = StageGraph(GraphCorrelation(sessionId, graphId, "workflow-full"),
            StageGraphState.RUNNING, listOf(node(StageKind.SDD)), emptyList(),
            StageBudget(20_000, 60_000, 0, 0))
        var accepted: Set<ArtifactId> = emptySet()
        private var active: String? = null
        private var generation = 0
        private var approval: StageArtifactManifest? = null
        override fun readBytes(path: String): ByteArray? = files[path]?.clone()
        override fun beginStage(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): PreparationStageLease? {
            if (active != null || graph != expectedGraph || accepted != expectedAcceptedIds)
                return null
            val stage = graph.stages.single { it.correlation.stageRunId == stageRunId }
            val ready = if (stage.state == StageState.CREATED) {
                if (StageGraphPolicy.authorizeStageTransition(graph, stageRunId,
                        StageState.READY) != StageGateDecision.Permit) return null
                graph.copy(stages = graph.stages.map {
                    if (it.correlation.stageRunId == stageRunId)
                        it.copy(state = StageState.READY) else it
                })
            } else graph
            if (StageGraphPolicy.admitStage(ready, stageRunId, accepted) !=
                StageGateDecision.Permit) return null
            graph = ready.copy(stages = ready.stages.map {
                if (it.correlation.stageRunId == stageRunId)
                    it.copy(state = StageState.RUNNING) else it
            })
            val token = "lease-full-" + ++generation
            active = token
            approval = null
            val current = graph
            val acceptedSnapshot = accepted
            val requestId = stage.correlation.requestId
            val snapshot = StageToolHostSnapshot(current, stageRunId, requestId,
                acceptedSnapshot, Path.of("/offline/full"), "a".repeat(40), "dev",
                checks = mapOf("offline" to StageIsolatedCheck(Path.of("/usr/bin/true"))),
                requiredChecks = setOf("git_diff_check", "offline"))
            return PreparationStageLease(token, stageRunId, current, acceptedSnapshot,
                StageToolAuthoritySource { if (active == token) snapshot else null })
        }
        override fun beginHumanApproval(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): PreparationStageLease? = null
        override fun current(lease: PreparationStageLease): Boolean =
            active == lease.generation && graph == lease.graph && accepted == lease.acceptedIds
        override fun approve(
            candidate: StageArtifactManifest, evidence: StageValidationEvidence,
            usage: StageRoleUsage
        ): PreparationApproval {
            approval = candidate
            return PreparationApproval.ACCEPT
        }
        override fun finishStage(
            lease: PreparationStageLease, updatedGraph: StageGraph,
            updatedAcceptedIds: Set<ArtifactId>, pending: PreparationPendingApproval?
        ): Boolean {
            if (!current(lease) || !PreparationStageFinishPolicy.permits(
                    lease, graph, accepted, updatedGraph, updatedAcceptedIds,
                    pending, PreparationApproval.ACCEPT, approval)) return false
            graph = updatedGraph
            accepted = updatedAcceptedIds
            active = null
            approval = null
            return true
        }
        override fun abortStage(
            lease: PreparationStageLease, terminal: StageState
        ): PreparationStageSnapshot? {
            if (!current(lease)) return null
            graph = graph.copy(stages = graph.stages.map {
                if (it.correlation.stageRunId == lease.stageRunId)
                    it.copy(state = terminal) else it
            })
            active = null
            return PreparationStageSnapshot(graph, accepted)
        }
        fun append(kind: StageKind) {
            val newGraph = graph.copy(stages = graph.stages + node(kind))
            assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(newGraph))
            graph = newGraph
        }
    }

    private inner class FakeToolPort(private val host: FakePreparationHost) :
        StageToolSessionOpener {
        override fun open(authority: StageToolAuthoritySource): StageToolOpenResult {
            val snapshot = authority.current() ?: return StageToolOpenResult.Denied("NO_AUTHORITY")
            val node = snapshot.graph.stages.single {
                it.correlation.stageRunId == snapshot.stageRunId
            }
            return StageToolOpenResult.Granted(object : StageToolSession {
                val checked = mutableListOf<StageCheckEvidence>()
                override fun write(operations: List<StageWrite>): StageWriteEvidence {
                    if (authority.current() != snapshot) return StageWriteEvidence(null,
                        "AUTHORITY_REVOKED")
                    for (op in operations) {
                        val old = host.files[op.path]
                        when (op) {
                            is StageWrite.Create -> if (old != null)
                                return StageWriteEvidence(null, "CREATE_EXISTS")
                            is StageWrite.Edit -> if (old == null ||
                                sha(String(old)) != op.expectedSha256)
                                return StageWriteEvidence(null, "STALE_PREIMAGE")
                            is StageWrite.Delete -> return StageWriteEvidence(null, "NO_DELETE")
                        }
                    }
                    for (op in operations) {
                        host.files[op.path] = bytes(when (op) {
                            is StageWrite.Create -> op.content
                            is StageWrite.Edit -> op.content
                            is StageWrite.Delete -> error("No delete")
                        })
                    }
                    return StageWriteEvidence("write-full-" + node.kind.name.lowercase())
                }
                override fun runCheck(checkId: String): StageCheckEvidence {
                    if (checkId !in snapshot.requiredChecks)
                        return StageCheckEvidence(checkId, null, deniedCode = "UNAUTHORIZED")
                    val evidence = StageCheckEvidence(checkId,
                        "check-full-" + checkId, snapshot.baselineHead,
                        "policy-full", "manifest-full")
                    checked += evidence
                    return evidence
                }
                override fun verify(): StageValidationEvidence =
                    StageValidationEvidence(node.correlation, requireNotNull(node.agentId),
                        checked.toList(), checked.map { it.checkId }.toSet() ==
                            snapshot.requiredChecks)
                override fun close() = Unit
            })
        }
    }

    private inner class FakeReviewHost(
        private val preparation: FakePreparationHost,
        private val handoff: ReviewerDeveloperHandoff
    ) : ReviewerStageHost {
        private var active: String? = null
        override fun beginStage(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): ReviewerStageLease? {
            if (active != null || preparation.graph != expectedGraph ||
                preparation.accepted != expectedAcceptedIds) return null
            val stage = preparation.graph.stages.single {
                it.correlation.stageRunId == stageRunId
            }
            val ready = preparation.graph.copy(stages = preparation.graph.stages.map {
                if (it.correlation.stageRunId == stageRunId)
                    it.copy(state = StageState.READY) else it
            })
            if (stage.state != StageState.CREATED ||
                StageGraphPolicy.admitStage(ready, stageRunId, preparation.accepted) !=
                StageGateDecision.Permit) return null
            preparation.graph = ready.copy(stages = ready.stages.map {
                if (it.correlation.stageRunId == stageRunId)
                    it.copy(state = StageState.RUNNING) else it
            })
            active = "lease-full-review"
            return ReviewerStageLease(requireNotNull(active), stageRunId, preparation.graph,
                preparation.accepted, handoff)
        }
        override fun current(lease: ReviewerStageLease): Boolean =
            active == lease.generation && preparation.graph == lease.graph &&
                preparation.accepted == lease.acceptedIds
        override fun readBytes(path: String): ByteArray? = preparation.readBytes(path)
        override fun verifyDeveloperEvidence(lease: ReviewerStageLease): Boolean =
            current(lease) && lease.handoff == handoff &&
                handoff.developerResult.validation.requiredChecks.all { it.passed }
        override fun finishStage(
            lease: ReviewerStageLease, updatedGraph: StageGraph,
            acceptedIds: Set<ArtifactId>, decision: ReviewerDecisionRecord
        ): Boolean {
            if (!current(lease) || ReviewerStageFinishPolicy.permits(lease, updatedGraph,
                    acceptedIds, decision, verifyDeveloperEvidence(lease)) !=
                StageGateDecision.Permit) return false
            preparation.graph = updatedGraph
            active = null
            return true
        }
        override fun abortStage(
            lease: ReviewerStageLease, terminal: StageState
        ): ReviewerStageSnapshot? {
            if (!current(lease)) return null
            preparation.graph = preparation.graph.copy(stages = preparation.graph.stages.map {
                if (it.correlation.stageRunId == lease.stageRunId)
                    it.copy(state = terminal) else it
            })
            active = null
            return ReviewerStageSnapshot(preparation.graph, preparation.accepted)
        }
    }

    @Test fun allFiveFakeModelsExecuteInOrderAndTrustedGraphSucceeds() = runBlocking {
        val host = FakePreparationHost()
        val roles = FakeFiveRoleModel(sourcePath, newSource, proposed)
        val tools = FakeToolPort(host)
        for (kind in listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD)) {
            if (kind != StageKind.SDD) host.append(kind)
            val result = RunPreparationStageUseCase(roles, tools, host).execute(
                PreparationStageCommand(host.graph, host.accepted, ids.getValue(kind),
                    artifactIds.getValue(kind), outputPaths.getValue(kind),
                    "Produce " + kind.name, model, Instant.now().plusSeconds(60),
                    Cancellation()), StageRoleEventSink { }, PreparationStageEventSink { })
            assertIs<PreparationStageOutcome.Accepted>(result.outcome,
                "failed preparation: " + result.outcome)
        }
        host.append(StageKind.DEVELOP)
        val dev = RunDeveloperStageUseCase(roles, tools, host).execute(
            DeveloperStageCommand(host.graph, host.accepted, ids.getValue(StageKind.DEVELOP),
                artifactIds.getValue(StageKind.DEVELOP),
                outputPaths.getValue(StageKind.DEVELOP),
                listOf(DeveloperFileTarget(sourcePath, sha(oldSource))),
                "Implement full workflow", model, Instant.now().plusSeconds(60),
                Cancellation()), StageRoleEventSink { }, PreparationStageEventSink { })
        val handoff = assertIs<DeveloperStageOutcome.Accepted>(dev.outcome,
            "failed developer: " + dev.outcome)
        host.append(StageKind.REVIEW)
        val reviewed = RunReviewerStageUseCase(roles, FakeReviewHost(host,
            ReviewerDeveloperHandoff(DeveloperReviewEvidence(
                handoff.manifest, handoff.changedFiles.map {
                    ReviewerChangedFile(it.path, it.beforeSha256, it.afterSha256)
                }, handoff.writeToolId, handoff.validation, handoff.usage),
                host.graph.stages.take(4).map {
                    ReviewerHistoryEntry(it.correlation, it.kind.role, it.state)
                }
            ))).execute(
            ReviewerStageCommand(host.graph, host.accepted, ids.getValue(StageKind.REVIEW),
                model, "Review full workflow", Instant.now().plusSeconds(60), Cancellation()),
            StageRoleEventSink { }, ReviewerDecisionSink { })
        assertIs<ReviewerStageOutcome.Accepted>(reviewed.outcome,
            "failed reviewer: " + reviewed.outcome)
        assertEquals(StageKind.entries.toList(), roles.called)
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeGraphTransition(
            host.graph, StageGraphState.SUCCEEDED, host.accepted,
            setOf(ids.getValue(StageKind.REVIEW))))
    }

    @Test fun coordinatorRunsFiveRolesAcrossExplicitHostBarriers() = runBlocking {
        for (tamperAcceptedSpecification in listOf(false, true)) {
        val preparationHost = FakePreparationHost()
        val roles = FakeFiveRoleModel(sourcePath, newSource, proposed)
        val tools = FakeToolPort(preparationHost)
        var reviewAdapter: FakeReviewHost? = null
        val reviewHost = object : ReviewerStageHost {
            override fun beginStage(graph: StageGraph, stageRunId: StageRunId,
                accepted: Set<ArtifactId>) =
                requireNotNull(reviewAdapter).beginStage(graph, stageRunId, accepted)
            override fun current(lease: ReviewerStageLease) =
                requireNotNull(reviewAdapter).current(lease)
            override fun readBytes(path: String) = preparationHost.readBytes(path)
            override fun verifyDeveloperEvidence(lease: ReviewerStageLease) =
                requireNotNull(reviewAdapter).verifyDeveloperEvidence(lease)
            override fun finishStage(lease: ReviewerStageLease, graph: StageGraph,
                accepted: Set<ArtifactId>, decision: ReviewerDecisionRecord) =
                requireNotNull(reviewAdapter).finishStage(lease, graph, accepted, decision)
            override fun abortStage(lease: ReviewerStageLease, terminal: StageState) =
                requireNotNull(reviewAdapter).abortStage(lease, terminal)
        }
        val coordinatorHost = object : FiveRoleWorkflowHost {
            override fun snapshot() =
                PreparationStageSnapshot(preparationHost.graph, preparationHost.accepted)

            override fun advance(expected: PreparationStageSnapshot, next: StageKind,
                developerHandoff: DeveloperReviewEvidence?): PreparationStageSnapshot? {
                if (snapshot() != expected || next == StageKind.SDD) return null
                if (tamperAcceptedSpecification && next == StageKind.ATDD) {
                    // A malicious/accidental write after SDD acceptance must
                    // stop the workflow before the next stage is admitted.
                    preparationHost.files[outputPaths.getValue(StageKind.SDD)] =
                        bytes("tampered after acceptance\n")
                }
                if (expected.graph.artifacts.any { manifest ->
                    manifest.id !in expected.acceptedIds ||
                    preparationHost.readBytes(manifest.declaredPath)?.let {
                        sha(String(it)) == manifest.sha256
                    } != true
                }) return null
                if (next == StageKind.REVIEW) {
                    val handoff = developerHandoff ?: return null
                    if (handoff.manifest.kind != StageArtifactKind.IMPLEMENTATION ||
                        handoff.manifest.id !in expected.acceptedIds ||
                        !handoff.validation.successful) return null
                    reviewAdapter = FakeReviewHost(preparationHost,
                        ReviewerDeveloperHandoff(handoff, expected.graph.stages.map {
                            ReviewerHistoryEntry(it.correlation, it.kind.role, it.state)
                        }))
                } else if (developerHandoff != null) return null
                preparationHost.append(next)
                return snapshot()
            }

            override fun finishSuccess(expected: PreparationStageSnapshot,
                reviewerId: StageRunId): StageGraph? {
                if (snapshot() != expected || reviewAdapter == null) return null
                if (StageGraphPolicy.authorizeGraphTransition(expected.graph,
                    StageGraphState.SUCCEEDED, expected.acceptedIds,
                    setOf(reviewerId)) != StageGateDecision.Permit) return null
                val finished = expected.graph.copy(state = StageGraphState.SUCCEEDED)
                preparationHost.graph = finished
                return finished
            }
        }
        val plan = FiveRoleWorkflowPlan(
            StageKind.entries.filter { it != StageKind.REVIEW }.associateWith {
                FiveRoleArtifactTarget(artifactIds.getValue(it), outputPaths.getValue(it))
            },
            StageKind.entries.associateWith { "Run " + it.name },
            listOf(DeveloperFileTarget(sourcePath, sha(oldSource))),
            model, Instant.now().plusSeconds(60), Cancellation()
        )
        val runner = RunFiveRoleWorkflowUseCase(
            RunPreparationStageUseCase(roles, tools, preparationHost),
            RunDeveloperStageUseCase(roles, tools, preparationHost),
            RunReviewerStageUseCase(roles, reviewHost),
            coordinatorHost
        )
        val result = runner.execute(plan, StageRoleEventSink { },
            PreparationStageEventSink { }, ReviewerDecisionSink { })
        if (tamperAcceptedSpecification) {
            val denied = assertIs<FiveRoleWorkflowOutcome.ReconciliationRequired>(result)
            assertEquals("STAGE_RECONCILIATION_DENIED", denied.code)
            assertEquals(StageKind.SDD, denied.stage)
            assertEquals(listOf(StageKind.SDD), roles.called)
        } else {
            val completed = assertIs<FiveRoleWorkflowOutcome.Completed>(result,
                "workflow=$result; called=${roles.called}")
            assertEquals(StageGraphState.SUCCEEDED, completed.graph.state)
            assertEquals(StageKind.entries.toList(), roles.called)
            assertEquals(4, preparationHost.accepted.size)
            assertEquals(newSource, String(preparationHost.files.getValue(sourcePath)))
        }
        }
    }
}
