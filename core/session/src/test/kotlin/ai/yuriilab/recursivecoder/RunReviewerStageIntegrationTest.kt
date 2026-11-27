package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Offline five-role graph: four accepted handoffs + independently executed FakeModel Reviewer. */
@Tag("integration")
class RunReviewerStageIntegrationTest {
    private val graphId = GraphId("graph-77")
    private val sessionId = "session-77"
    private val roles = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD, StageKind.DEVELOP)
    private val ids = roles.associateWith { StageRunId(it.name.lowercase() + "-77") }
    private val artifacts = roles.associateWith { ArtifactId(it.name.lowercase() + "-artifact-77") }
    private val reviewId = StageRunId("review-77")
    private val implementationPath = "src/main/ImplementationManifest77.txt"
    private val sourcePath = "src/main/Feature77.kt"
    private val source = "fun feature77() = 2\n"
    private val paths = mapOf(
        StageKind.SDD to "specs/Feature77.md",
        StageKind.ATDD to "tests/acceptance/Feature77.feature",
        StageKind.TDD to "tests/unit/Feature77Test.kt",
        StageKind.DEVELOP to implementationPath
    )
    private val documents = mapOf(
        StageKind.SDD to "Requirement 77\n",
        StageKind.ATDD to "Scenario 77\n",
        StageKind.TDD to "fun testFeature77() = Unit\n",
        StageKind.DEVELOP to "implementation manifest 77\n"
    )
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(text: String) = sha(bytes(text))
    private fun correlation(kind: StageKind): StageCorrelation {
        val id = if (kind == StageKind.REVIEW) reviewId else ids.getValue(kind)
        return StageCorrelation(sessionId, graphId, id,
            "request-" + kind.name.lowercase(), "corr-" + kind.name.lowercase())
    }
    private fun graph(): StageGraph {
        val nodes = roles.map { kind ->
            val predecessors = when (kind) {
                StageKind.SDD -> emptySet()
                StageKind.ATDD -> setOf(ids.getValue(StageKind.SDD))
                StageKind.TDD -> setOf(ids.getValue(StageKind.SDD), ids.getValue(StageKind.ATDD))
                StageKind.DEVELOP -> setOf(ids.getValue(StageKind.SDD), ids.getValue(StageKind.ATDD),
                    ids.getValue(StageKind.TDD))
                else -> emptySet()
            }
            val inputs = roles.filter { it != kind && it in when (kind) {
                StageKind.SDD -> emptyList()
                StageKind.ATDD -> listOf(StageKind.SDD)
                StageKind.TDD -> listOf(StageKind.SDD, StageKind.ATDD)
                StageKind.DEVELOP -> listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD)
                else -> emptyList()
            } }.map { artifacts.getValue(it) }.toSet()
            StageNode(correlation(kind), kind, StageState.ACCEPTED,
                AgentId("actor-" + kind.name.lowercase()),
                dependencies = predecessors, inputArtifacts = inputs,
                outputArtifacts = setOf(artifacts.getValue(kind)))
        }
        val reviewer = StageNode(correlation(StageKind.REVIEW), StageKind.REVIEW,
            StageState.CREATED, AgentId("actor-reviewer"),
            dependencies = ids.values.toSet(), inputArtifacts = artifacts.values.toSet())
        val manifests = roles.map { kind ->
            StageArtifactManifest(artifacts.getValue(kind), requireNotNull(kind.output),
                paths.getValue(kind), sha(documents.getValue(kind)), kind.role, ids.getValue(kind),
                1, ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
        }
        return StageGraph(GraphCorrelation(sessionId, graphId, "workflow-77"),
            StageGraphState.RUNNING, nodes + reviewer, manifests,
            StageBudget(20_000, 60_000, 0, 0))
    }
    private fun handoff(graph: StageGraph): ReviewerDeveloperHandoff {
        val dev = graph.stages.single { it.kind == StageKind.DEVELOP && it.supersededBy == null }
        val manifest = graph.artifacts.single { it.producerStageRunId == dev.correlation.stageRunId }
        val evidence = StageValidationEvidence(dev.correlation, requireNotNull(dev.agentId),
            listOf(
                StageCheckEvidence("git_diff_check", "check-diff-77", "a".repeat(40),
                    "policy-77", "manifest-77"),
                StageCheckEvidence("offline", "check-offline-77", "a".repeat(40),
                    "policy-77", "manifest-77")
            ), true)
        return ReviewerDeveloperHandoff(DeveloperReviewEvidence(manifest,
            listOf(ReviewerChangedFile(sourcePath, sha("fun feature77() = 1\n"), sha(source))),
            "write-77", evidence, StageRoleUsage(1, 120, 55)),
            graph.stages.take(4).map {
                ReviewerHistoryEntry(it.correlation, it.kind.role, it.state)
            })
    }
    private inner class FakeHost(initial: StageGraph = graph()) : ReviewerStageHost {
        var graph = initial
        var accepted = graph.artifacts.map { it.id }.toSet()
        var trustedHandoff = handoff(graph)
        var verifiedReceipts = true
        var throwAfterFinish = false
        var returnUnknownFinish = false
        var refuseFinish = false
        var finishDenialCode: String? = null
        /** Simulates loss of ownership immediately after a successful beginStage. */
        var revokeImmediatelyAfterBegin = false
        /** Revokes the lease after finish has denied but before cleanup checks it. */
        var onAbortAttempt: (() -> Unit)? = null
        var abortAttempts = 0
        /** Simulates an edit/revocation strictly AFTER the use-case preflight. */
        var onFinishAttempt: (() -> Unit)? = null
        var active: String? = null
        var generation = 0
        var committedDecision: ReviewerDecisionRecord? = null
        var aborted = 0
        val files = mutableMapOf<String, ByteArray>()
        init {
            roles.forEach { kind ->
                files[paths.getValue(kind)] = bytes(documents.getValue(kind))
            }
            files[sourcePath] = bytes(source)
        }
        @Synchronized override fun beginStage(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): ReviewerStageLease? {
            if (active != null || graph != expectedGraph || accepted != expectedAcceptedIds)
                return null
            val stage = graph.stages.singleOrNull { it.correlation.stageRunId == stageRunId }
                ?: return null
            val ready = if (stage.state == StageState.CREATED) {
                if (StageGraphPolicy.authorizeStageTransition(graph, stageRunId,
                        StageState.READY) != StageGateDecision.Permit) return null
                graph.copy(stages = graph.stages.map {
                    if (it.correlation.stageRunId == stageRunId) it.copy(state = StageState.READY)
                    else it
                })
            } else graph
            if (StageGraphPolicy.admitStage(ready, stageRunId, accepted) != StageGateDecision.Permit)
                return null
            graph = ready.copy(stages = ready.stages.map {
                if (it.correlation.stageRunId == stageRunId)
                    it.copy(state = StageState.RUNNING) else it
            })
            val token = "review-lease-" + ++generation
            active = token
            if (revokeImmediatelyAfterBegin) active = null
            return ReviewerStageLease(token, stageRunId, graph, accepted, trustedHandoff)
        }
        @Synchronized override fun current(lease: ReviewerStageLease): Boolean =
            active == lease.generation && graph == lease.graph && accepted == lease.acceptedIds
        @Synchronized override fun readBytes(path: String): ByteArray? = files[path]?.clone()
        private fun frozenAndPostimagesIntact(lease: ReviewerStageLease): Boolean {
            val reviewer = graph.stages.singleOrNull {
                it.correlation.stageRunId == lease.stageRunId
            } ?: return false
            val inputs = graph.artifacts.filter { it.id in reviewer.inputArtifacts }
            val changes = lease.handoff.developerResult.changedFiles
            return inputs.size == reviewer.inputArtifacts.size &&
                inputs.all { file ->
                    files[file.declaredPath]?.let { sha(it) == file.sha256 } == true
                } && changes.all { file ->
                    files[file.path]?.let { sha(it) == file.afterSha256 } == true
                }
        }
        @Synchronized override fun verifyDeveloperEvidence(lease: ReviewerStageLease): Boolean =
            current(lease) && verifiedReceipts && lease.handoff == trustedHandoff &&
                frozenAndPostimagesIntact(lease)
        @Synchronized override fun finishStage(
            lease: ReviewerStageLease, updatedGraph: StageGraph, acceptedIds: Set<ArtifactId>,
            decision: ReviewerDecisionRecord
        ): ReviewerFinishResult {
            onFinishAttempt?.invoke()
            // Atomic fake-host commit boundary: re-evaluate receipts AND disk
            // hashes after any concurrent mutation, not just in the use case.
            if (!current(lease))
                return ReviewerFinishResult.Denied(ReviewerFinishDenialCodes.STALE_LEASE)
            if (refuseFinish)
                return ReviewerFinishResult.Denied(finishDenialCode ?: "REVIEW_FINISH_DENIED")
            if (!frozenAndPostimagesIntact(lease))
                return ReviewerFinishResult.Denied("FROZEN_INPUT_OR_SOURCE_CHANGED")
            if (!verifyDeveloperEvidence(lease))
                return ReviewerFinishResult.Denied("UNVERIFIED_DEVELOPER_EVIDENCE")
            val gate = ReviewerStageFinishPolicy.permits(
                lease, updatedGraph, acceptedIds, decision, true)
            if (gate is StageGateDecision.Deny)
                return ReviewerFinishResult.Denied(gate.code)
            if (returnUnknownFinish) return ReviewerFinishResult.Unknown
            graph = updatedGraph
            accepted = acceptedIds
            committedDecision = decision
            active = null
            if (throwAfterFinish) error("CAS committed, response lost")
            return ReviewerFinishResult.Committed
        }
        @Synchronized override fun abortStage(
            lease: ReviewerStageLease, terminal: StageState
        ): ReviewerStageSnapshot? {
            abortAttempts++
            onAbortAttempt?.invoke()
            if (!current(lease) || terminal !in setOf(StageState.FAILED, StageState.CANCELLED))
                return null
            graph = graph.copy(stages = graph.stages.map {
                if (it.correlation.stageRunId == lease.stageRunId)
                    it.copy(state = terminal) else it
            })
            active = null
            aborted++
            return ReviewerStageSnapshot(graph, accepted)
        }
    }
    private inner class FakeModel : StageRoleExecutor {
        val requests = mutableListOf<StageRoleRequest>()
        var response = """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":["check:git_diff_check","check:offline","write:write-77"]}"""
        var onExecute: (() -> Unit)? = null
        var throwsAfterInvocation = false
        var usage = StageRoleUsage(1, 140, 40)
        override suspend fun execute(
            request: StageRoleRequest, events: StageRoleEventSink
        ): StageRoleResult {
            requests += request
            onExecute?.invoke()
            if (throwsAfterInvocation) error("Provider failed without usage receipt")
            val stage = request.graph.stages.single {
                it.correlation.stageRunId == request.stageRunId
            }
            return StageRoleResult(stage.correlation, request.expectedRole, stage.agentId,
                StageRoleOutcome.Proposed(response), usage)
        }
    }
    private val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
        "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000)
    private suspend fun run(host: FakeHost, fake: FakeModel,
        eventLog: MutableList<ReviewerDecisionRecord> = mutableListOf()
    ): ReviewerStageResult = RunReviewerStageUseCase(fake, host).execute(
        ReviewerStageCommand(host.graph, host.accepted,
            host.graph.stages.single { it.kind == StageKind.REVIEW &&
                it.supersededBy == null }.correlation.stageRunId,
            model, "Review independently", Instant.now().plusSeconds(60), Cancellation()),
        StageRoleEventSink { }, ReviewerDecisionSink { eventLog += it }
    )

    @Test fun fullFiveRoleGraphAcceptsOnlyIndependentEvidenceBackedReview() = runBlocking {
        val host = FakeHost()
        val fake = FakeModel()
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, fake, events)
        val accepted = assertIs<ReviewerStageOutcome.Accepted>(result.outcome)
        assertEquals("OK", accepted.decision.reasonCode)
        assertEquals(StageState.ACCEPTED, host.graph.stages.last().state)
        assertEquals(1, events.size)
        assertEquals(EngineeringRole.REVIEWER, fake.requests.single().expectedRole)
        assertTrue(fake.requests.single().requestedTools.isEmpty())
        assertEquals(1, fake.requests.single().maxModelRequests)
        assertTrue(fake.requests.single().instruction.contains("<verified-check"))
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeGraphTransition(
            host.graph, StageGraphState.SUCCEEDED, host.accepted, setOf(reviewId)))
    }

    @Test fun reviewerRejectsThenRoutesBoundedDeveloperRepairAndAcceptsRetest() = runBlocking {
        val host = FakeHost()
        val fake = FakeModel().apply {
            response = """{"verdict":"REJECT","code":"IMPLEMENTATION_DEFECT","scope":"IMPLEMENTATION_ONLY","evidence":["file:src/main/Feature77.kt"]}"""
        }
        val initial = assertIs<ReviewerStageOutcome.Rejected>(run(host, fake).outcome)
        assertEquals(StageState.REJECTED, host.graph.stages.last().state)
        val nextDev = StageCorrelation(sessionId, graphId, StageRunId("develop-77-retry"),
            "request-develop-retry", "corr-develop-retry")
        val nextReview = StageCorrelation(sessionId, graphId, StageRunId("review-77-retry"),
            "request-review-retry", "corr-review-retry")
        val plan = ReviewerRepairPlanner.plan(host.graph, reviewId, setOf(reviewId),
            initial.decision, 1, nextDev, AgentId("developer-retry-77"),
            nextReview, AgentId("reviewer-retry-77"))
        val planned = assertIs<ReviewerRepairResult.Planned>(plan).graph
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(planned))
        assertEquals(nextDev.stageRunId, planned.stages.single {
            it.correlation.stageRunId == ids.getValue(StageKind.DEVELOP)
        }.supersededBy)
        assertEquals(ReviewerRepairResult.Denied("RETRY_LIMIT_EXHAUSTED"),
            ReviewerRepairPlanner.plan(
                host.graph, reviewId, setOf(reviewId), initial.decision, 0,
                nextDev, AgentId("developer-retry-77"),
                nextReview, AgentId("reviewer-retry-77")))

        // Offline host emulates the NEXT independently validated Developer attempt.
        // The real retry uses RunDeveloperStageUseCase and its existing sandbox checks.
        val secondManifestText = "implementation manifest 77 repaired\n"
        val secondManifest = StageArtifactManifest(ArtifactId("implementation-77-retry"),
            StageArtifactKind.IMPLEMENTATION, "src/main/ImplementationManifest77Retry.txt",
            sha(secondManifestText), EngineeringRole.DEVELOPER, nextDev.stageRunId, 1,
            ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
        val repairedSource = "fun feature77() = 3\n"
        val next = planned.copy(
            stages = planned.stages.map {
                when (it.correlation.stageRunId) {
                    nextDev.stageRunId -> it.copy(state = StageState.ACCEPTED,
                        outputArtifacts = setOf(secondManifest.id))
                    nextReview.stageRunId -> it.copy(
                        inputArtifacts = it.inputArtifacts + secondManifest.id)
                    else -> it
                }
            },
            artifacts = planned.artifacts + secondManifest)
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(next))
        host.graph = next
        host.accepted += secondManifest.id
        host.files[secondManifest.declaredPath] = bytes(secondManifestText)
        host.files[sourcePath] = bytes(repairedSource)
        val prior = host.trustedHandoff.developerResult
        host.trustedHandoff = ReviewerDeveloperHandoff(prior.copy(
            manifest = secondManifest,
            changedFiles = listOf(ReviewerChangedFile(sourcePath, sha(source),
                sha(repairedSource))),
            validation = prior.validation.copy(correlation = nextDev,
                actor = AgentId("developer-retry-77"))
        ), host.trustedHandoff.history + ReviewerHistoryEntry(
            nextDev, EngineeringRole.DEVELOPER, StageState.ACCEPTED))
        fake.response = """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":["check:git_diff_check","check:offline","write:write-77"]}"""
        assertIs<ReviewerStageOutcome.Accepted>(run(host, fake).outcome)
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.authorizeGraphTransition(
            host.graph, StageGraphState.SUCCEEDED, host.accepted, setOf(nextReview.stageRunId)))
    }

    @Test fun selfReviewAndMissingOrFailedCheckCannotApprove() = runBlocking {
        val self = graph().copy(stages = graph().stages.map {
            if (it.kind == StageKind.REVIEW) it.copy(agentId = AgentId("actor-develop"))
            else it
        })
        val selfHost = FakeHost(self)
        assertEquals("SHARED_ROLE_ACTOR",
            assertIs<ReviewerStageOutcome.Denied>(
                run(selfHost, FakeModel()).outcome).code)
        val absent = FakeHost()
        absent.trustedHandoff = absent.trustedHandoff.copy(
            developerResult = absent.trustedHandoff.developerResult.copy(
                validation = absent.trustedHandoff.developerResult.validation.copy(
                    requiredChecks = listOf(StageCheckEvidence("git_diff_check", null))
                )
            )
        )
        assertEquals("UNVERIFIED_DEVELOPER_EVIDENCE",
            assertIs<ReviewerStageOutcome.Failed>(run(absent, FakeModel()).outcome).code)
        val failed = FakeHost()
        failed.trustedHandoff = failed.trustedHandoff.copy(
            developerResult = failed.trustedHandoff.developerResult.copy(
                validation = failed.trustedHandoff.developerResult.validation.copy(
                    requiredChecks = listOf(
                        StageCheckEvidence("git_diff_check", "check-diff-77",
                            "a".repeat(40), "policy-77", "manifest-77"),
                        StageCheckEvidence("offline", null, deniedCode = "CHECK_FAILED")
                    )
                )
            )
        )
        assertEquals("UNVERIFIED_DEVELOPER_EVIDENCE",
            assertIs<ReviewerStageOutcome.Failed>(run(failed, FakeModel()).outcome).code)
    }

    @Test fun forgedEvidenceAndAttemptToReviseFrozenTestsFailClosed() = runBlocking {
        val fake = FakeModel().apply {
            response = """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":["check:made-up","write:write-77"]}"""
        }
        val host = FakeHost()
        assertEquals("UNSUPPORTED_REVIEW_EVIDENCE",
            assertIs<ReviewerStageOutcome.Failed>(run(host, fake).outcome).code)
        val frozen = FakeHost()
        val humanModel = FakeModel().apply {
            response = """{"verdict":"NEEDS_HUMAN","code":"FROZEN_ARTIFACT_CHANGE","scope":"FROZEN_INPUTS","evidence":["file:src/main/Feature77.kt"]}"""
        }
        val result = assertIs<ReviewerStageOutcome.NeedsHuman>(
            run(frozen, humanModel).outcome)
        assertEquals(ReviewVerdict.REJECT, frozen.graph.stages.last().review?.verdict)
        val plan = ReviewerRepairPlanner.plan(frozen.graph, reviewId, setOf(reviewId),
            result.decision, 2,
            StageCorrelation(sessionId, graphId, StageRunId("developer-another"),
                "req-another-dev", "corr-another-dev"), AgentId("another-developer"),
            StageCorrelation(sessionId, graphId, StageRunId("reviewer-another"),
                "req-another-review", "corr-another-review"), AgentId("another-reviewer"))
        assertEquals(ReviewerRepairResult.Denied("UNAUTHORIZED_REPAIR"), plan)
    }

    @Test fun acceptanceWithoutWriteReceiptOrAllChecksIsRejected() = runBlocking {
        val host = FakeHost()
        val fake = FakeModel().apply {
            response = """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":["check:git_diff_check","check:offline"]}"""
        }
        assertEquals("INCOMPLETE_REVIEW_EVIDENCE",
            assertIs<ReviewerStageOutcome.Failed>(run(host, fake).outcome).code)
        assertNull(host.committedDecision)
    }

    @Test fun frozenInputMutationAndHostEvidenceRevocationFailBeforeCommit() = runBlocking {
        val changed = FakeHost()
        val model = FakeModel().apply {
            onExecute = { changed.files[paths.getValue(StageKind.TDD)] = bytes("tampered") }
        }
        val preFinish = assertIs<ReviewerStageOutcome.Failed>(run(changed, model).outcome)
        assertEquals("FROZEN_INPUT_OR_SOURCE_CHANGED", preFinish.code)
        assertNull(preFinish.denialCode)
        val revoked = FakeHost().apply { verifiedReceipts = false }
        assertEquals("UNVERIFIED_DEVELOPER_EVIDENCE",
            assertIs<ReviewerStageOutcome.Failed>(run(revoked, FakeModel()).outcome).code)
    }

    @Test fun reviewerPromptExplainsEvidenceReferenceGrammar() = runBlocking {
        val host = FakeHost()
        val fake = FakeModel()
        assertIs<ReviewerStageOutcome.Accepted>(run(host, fake).outcome)
        val prompt = fake.requests.single().instruction
        assertContains(prompt, "check:<verified-check id>")
        assertContains(prompt, "write:<verified-write id>")
        assertContains(prompt, "file:<changed-file path>")
        assertContains(prompt, "Copy the tag ID/path with its prefix")
        assertContains(prompt, "Choose exactly one allowed verdict/code/scope triple")
        assertContains(prompt, "ACCEPT/OK/NONE")
        assertContains(prompt, "REJECT/IMPLEMENTATION_DEFECT/IMPLEMENTATION_ONLY")
        assertContains(prompt, "NEEDS_HUMAN/FROZEN_ARTIFACT_CHANGE/FROZEN_INPUTS")
        assertContains(prompt, "id=\"git_diff_check\"")
        assertContains(prompt, "id=\"write-77\"")

        val unprefixed = FakeModel().apply {
            response = """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":["git_diff_check","offline","write-77"]}"""
        }
        assertEquals("UNSUPPORTED_REVIEW_EVIDENCE",
            assertIs<ReviewerStageOutcome.Failed>(
                run(FakeHost(), unprefixed).outcome).code)
    }

    @Test fun finishCommitRevalidatesFrozenBytesAndReceiptRevocation() = runBlocking {
        val changed = FakeHost()
        changed.onFinishAttempt = {
            changed.files[paths.getValue(StageKind.ATDD)] = bytes("post-check edit")
        }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val changedResult = run(changed, FakeModel(), events)
        val changedFailure = assertIs<ReviewerStageOutcome.Failed>(changedResult.outcome)
        assertEquals("REVIEW_FINISH_DENIED", changedFailure.code)
        assertEquals("FROZEN_INPUT_OR_SOURCE_CHANGED", changedFailure.denialCode)
        assertEquals(changed.graph, changedResult.graph)
        assertNull(changed.committedDecision)
        assertEquals(StageState.FAILED, changed.graph.stages.last().state)
        assertNull(changed.active)
        assertEquals(1, changed.aborted)
        assertTrue(events.isEmpty())

        val revoked = FakeHost()
        revoked.onFinishAttempt = { revoked.verifiedReceipts = false }
        val revokedResult = run(revoked, FakeModel())
        val revokedFailure = assertIs<ReviewerStageOutcome.Failed>(revokedResult.outcome)
        assertEquals("REVIEW_FINISH_DENIED", revokedFailure.code)
        assertEquals("UNVERIFIED_DEVELOPER_EVIDENCE", revokedFailure.denialCode)
        assertEquals(revoked.graph, revokedResult.graph)
        assertNull(revoked.committedDecision)
        assertEquals(StageState.FAILED, revoked.graph.stages.last().state)
        assertNull(revoked.active)
        assertEquals(1, revoked.aborted)
    }

    @Test fun invalidOrHumanContradictoryResponseRetainsSpentUsage() = runBlocking {
        val host = FakeHost()
        val model = FakeModel().apply {
            response = """{"verdict":"NEEDS_HUMAN","code":"IMPLEMENTATION_DEFECT","scope":"NONE","evidence":["file:src/main/Feature77.kt"]}"""
        }
        val result = run(host, model)
        assertEquals("INVALID_REVIEW_RESPONSE",
            assertIs<ReviewerStageOutcome.Failed>(result.outcome).code)
        assertEquals(model.usage, result.usage)
        assertNull(host.committedDecision)
    }

    @Test fun unknownProposalVocabularyFailsClosed() = runBlocking {
        val valid = """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":["check:git_diff_check","check:offline","write:write-77"]}"""
        val invalid = listOf(
            valid.replace("ACCEPT", "FUTURE_VERDICT"),
            valid.replace("NONE", "FUTURE_SCOPE"),
            valid.replace("OK", "FUTURE_REASON"),
            valid.replace("\"evidence\"", "\"futureField\""),
            valid.replace("\"verdict\"", "\"Verdict\""),
            valid.dropLast(1) + ""","futureField":1}""",
            valid.replace(""""scope":"NONE",""", "")
        )
        for (response in invalid) {
            val host = FakeHost()
            val model = FakeModel().apply { this.response = response }
            val result = run(host, model)
            assertEquals("INVALID_REVIEW_RESPONSE",
                assertIs<ReviewerStageOutcome.Failed>(result.outcome).code)
            assertEquals(model.usage, result.usage)
            assertNull(host.committedDecision)
            assertEquals(StageState.FAILED, host.graph.stages.last().state)
        }
    }

    @Test fun definitiveHostDenialLeavesNoFalseAcceptance() = runBlocking {
        val host = FakeHost().apply { refuseFinish = true }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, FakeModel(), events)
        val failed = assertIs<ReviewerStageOutcome.Failed>(result.outcome)
        assertEquals("REVIEW_FINISH_DENIED", failed.code)
        assertEquals("REVIEW_FINISH_DENIED", failed.denialCode)
        assertEquals(host.graph, result.graph)
        assertEquals(host.accepted, result.hostAcceptedArtifactIds)
        assertNull(host.committedDecision)
        assertEquals(StageState.FAILED, host.graph.stages.last().state)
        assertNull(host.active)
        assertEquals(1, host.aborted)
        assertTrue(events.isEmpty())
    }

    @Test fun staleLeaseAtDefinitiveDenialRequiresReconciliation() = runBlocking {
        val host = FakeHost()
        host.onFinishAttempt = { host.active = null }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, FakeModel(), events)
        val uncertain = assertIs<ReviewerStageOutcome.ReconciliationRequired>(result.outcome)
        assertEquals("REVIEW_FINISH_DENIED_STALE", uncertain.code)
        assertEquals(ReviewerFinishDenialCodes.STALE_LEASE, uncertain.denialCode)
        assertNull(result.graph)
        assertNull(host.committedDecision)
        assertEquals(StageState.RUNNING, host.graph.stages.last().state)
        assertEquals(0, host.aborted)
        assertEquals(0, host.abortAttempts)
        assertTrue(events.isEmpty())
    }

    @Test fun leaseLostImmediatelyAfterAdmissionRequiresReconciliationWithoutAbort() = runBlocking {
        val host = FakeHost().apply { revokeImmediatelyAfterBegin = true }
        val fake = FakeModel()
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, fake, events)
        val uncertain = assertIs<ReviewerStageOutcome.ReconciliationRequired>(result.outcome)
        assertEquals("REVIEW_LEASE_STALE", uncertain.code)
        assertNull(uncertain.denialCode)
        assertNull(result.graph)
        assertNull(result.hostAcceptedArtifactIds)
        assertEquals(StageState.RUNNING, host.graph.stages.last().state)
        assertEquals(0, host.abortAttempts)
        assertEquals(0, host.aborted)
        assertTrue(fake.requests.isEmpty())
        assertNull(host.committedDecision)
        assertTrue(events.isEmpty())
    }

    @Test fun stalePreFinishLeaseNeverAttemptsAbort() = runBlocking {
        val host = FakeHost()
        val fake = FakeModel().apply { onExecute = { host.active = null } }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, fake, events)
        val uncertain = assertIs<ReviewerStageOutcome.ReconciliationRequired>(result.outcome)
        assertEquals("REVIEW_LEASE_STALE", uncertain.code)
        assertNull(uncertain.denialCode)
        assertNull(result.graph)
        assertNull(result.hostAcceptedArtifactIds)
        assertEquals(StageState.RUNNING, host.graph.stages.last().state)
        assertEquals(0, host.abortAttempts)
        assertNull(host.committedDecision)
        assertTrue(events.isEmpty())
    }

    @Test fun leaseLostBetweenDefinitiveDenialAndAbortRetainsOriginalCause() = runBlocking {
        val host = FakeHost().apply {
            refuseFinish = true
            onAbortAttempt = { active = null }
        }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, FakeModel(), events)
        val uncertain = assertIs<ReviewerStageOutcome.ReconciliationRequired>(result.outcome)
        assertEquals("REVIEW_ABORT_UNCONFIRMED", uncertain.code)
        assertEquals("REVIEW_FINISH_DENIED", uncertain.denialCode)
        assertNull(result.graph)
        assertNull(result.hostAcceptedArtifactIds)
        assertEquals(1, host.abortAttempts)
        assertEquals(0, host.aborted)
        assertEquals(StageState.RUNNING, host.graph.stages.last().state)
        assertNull(host.committedDecision)
        assertTrue(events.isEmpty())
    }

    @Test fun postFinishRoleCancelledDenialStillFailsTheCurrentLease() = runBlocking {
        val host = FakeHost().apply {
            refuseFinish = true
            finishDenialCode = "ROLE_CANCELLED"
        }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, FakeModel(), events)
        val failed = assertIs<ReviewerStageOutcome.Failed>(result.outcome)
        assertEquals("REVIEW_FINISH_DENIED", failed.code)
        assertEquals("ROLE_CANCELLED", failed.denialCode)
        assertEquals(StageState.FAILED, host.graph.stages.last().state)
        assertEquals(1, host.aborted)
        assertEquals(1, host.abortAttempts)
        assertNull(host.active)
        assertNull(host.committedDecision)
        assertTrue(events.isEmpty())
    }

    @Test fun invalidBlankDenialConstructionIsUnknownNotAbortable() = runBlocking {
        val host = FakeHost().apply {
            refuseFinish = true
            finishDenialCode = ""
        }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, FakeModel(), events)
        assertEquals("REVIEW_FINISH_UNCONFIRMED",
            assertIs<ReviewerStageOutcome.ReconciliationRequired>(result.outcome).code)
        assertNull(result.graph)
        assertEquals(StageState.RUNNING, host.graph.stages.last().state)
        assertEquals(0, host.abortAttempts)
        assertNull(host.committedDecision)
        assertTrue(events.isEmpty())
    }

    @Test fun explicitUnknownHostFinishRequiresReconciliation() = runBlocking {
        val host = FakeHost().apply { returnUnknownFinish = true }
        val events = mutableListOf<ReviewerDecisionRecord>()
        val result = run(host, FakeModel(), events)
        assertEquals("REVIEW_FINISH_UNCONFIRMED",
            assertIs<ReviewerStageOutcome.ReconciliationRequired>(result.outcome).code)
        assertNull(result.graph)
        assertNull(host.committedDecision)
        assertTrue(events.isEmpty())
    }

    @Test fun executorExceptionDoesNotInventUnobservableUsage() = runBlocking {
        val host = FakeHost()
        val fake = FakeModel().apply { throwsAfterInvocation = true }
        val result = run(host, fake)
        assertEquals("REVIEW_STAGE_FAILED",
            assertIs<ReviewerStageOutcome.Failed>(result.outcome).code)
        assertEquals(StageRoleUsage(), result.usage)
        assertNull(result.usage.inputTokens)
        assertNull(result.usage.outputTokens)
        assertNull(host.committedDecision)
        assertEquals(1, host.aborted)
    }

    @Test fun unknownHostFinishDoesNotClaimSuccessOrPublishVerdict() = runBlocking {
        val host = FakeHost().apply { throwAfterFinish = true }
        val events = mutableListOf<ReviewerDecisionRecord>()
        assertEquals("REVIEW_FINISH_UNCONFIRMED",
            assertIs<ReviewerStageOutcome.ReconciliationRequired>(
                run(host, FakeModel(), events).outcome).code)
        assertTrue(events.isEmpty())
        assertNotNull(host.committedDecision)
    }
}
