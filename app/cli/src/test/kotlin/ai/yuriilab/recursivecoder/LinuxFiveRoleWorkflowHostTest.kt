package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real Linux Git and trusted-write-port regressions; no model or fake receipts. */
@Tag("integration")
class LinuxFiveRoleWorkflowHostTest {
    @TempDir lateinit var temp: Path

    private val oldSource = "fun featureFull() = 1\n"
    private val newSource = "fun featureFull() = 2\n"
    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
        "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000)
    private val targets = mapOf(
        StageKind.SDD to FiveRoleArtifactTarget(ArtifactId("host-requirement"),
            "specs/FeatureFull.md"),
        StageKind.ATDD to FiveRoleArtifactTarget(ArtifactId("host-acceptance"),
            "tests/acceptance/FeatureFull.feature"),
        StageKind.TDD to FiveRoleArtifactTarget(ArtifactId("host-unit"),
            "tests/unit/FeatureFullTest.kt"),
        StageKind.DEVELOP to FiveRoleArtifactTarget(ArtifactId("host-implementation"),
            "src/main/FeatureFullManifest.xml")
    )
    private val noopRole = StageRoleEventSink { }
    private val noopStage = PreparationStageEventSink { }
    private val noopReview = ReviewerDecisionSink { }

    private class OfflineFiveRoleModel : StageRoleExecutor {
        val called = mutableListOf<StageKind>()
        override suspend fun execute(request: StageRoleRequest,
            events: StageRoleEventSink): StageRoleResult {
            val stage = request.graph.stages.single {
                it.correlation.stageRunId == request.stageRunId
            }
            called += stage.kind
            val proposed = when (stage.kind) {
                StageKind.SDD -> "Requirement: positive host integration\n"
                StageKind.ATDD -> "Scenario: positive host integration\n"
                StageKind.TDD -> "fun testHost() = Unit\n"
                StageKind.DEVELOP ->
                    """{"files":[{"path":"src/main/FeatureFull.kt","content":"fun featureFull() = 2\n"}]}"""
                StageKind.REVIEW -> {
                    val receipts = Regex("""<verified-check id="([^"]+)" receipt="[^"]+" />""")
                        .findAll(request.instruction).map { "check:" + it.groupValues[1] }.toList()
                    val writeId = Regex("""<verified-write id="([^"]+)" />""")
                        .find(request.instruction)?.groupValues?.get(1)
                        ?: error("Missing trusted developer write receipt")
                    val refs = (receipts + ("write:" + writeId)).joinToString(",") { "\"$it\"" }
                    """{"verdict":"ACCEPT","code":"OK","scope":"NONE","evidence":[$refs]}"""
                }
            }
            return StageRoleResult(stage.correlation, request.expectedRole, stage.agentId,
                StageRoleOutcome.Proposed(proposed), StageRoleUsage(1, 100, 30))
        }
    }

    private fun git(root: Path, vararg args: String): String {
        val command = listOf("/usr/bin/git", "-C", root.toString(),
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null") + args
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment().clear()
            environment()["LC_ALL"] = "C"
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment()["GIT_TERMINAL_PROMPT"] = "0"
        }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }

    private fun newHost(
        startingStageId: StageRunId = StageRunId("host-sdd"),
        startingGraphId: GraphId = GraphId("host-graph"),
        startingRequestId: String = "host-request",
        parentGraphId: GraphId? = null,
        parentStageRunId: StageRunId? = null,
        sessionId: String = "host-session",
        checkoutName: String = "checkout",
        preauthorizedStages: Map<StageKind, Pair<StageRunId, String>> = emptyMap(),
        gateway: (StageArtifactManifest, StageValidationEvidence, StageRoleUsage) ->
            PreparationApproval = { _, _, _ -> PreparationApproval.ACCEPT }
    ): Pair<LinuxFiveRoleWorkflowHost, Path> {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        val repo = temp.resolve(checkoutName)
        Files.createDirectories(repo)
        git(repo, "init", "-b", "dev")
        git(repo, "config", "user.email", "host@example.invalid")
        git(repo, "config", "user.name", "Host Test")
        for (directory in listOf("specs", "tests/acceptance", "tests/unit", "src/main"))
            Files.createDirectories(repo.resolve(directory))
        Files.writeString(repo.resolve("README.md"), "Initial checkout\n")
        Files.writeString(repo.resolve(".gitignore"), "*.ignored\n")
        Files.writeString(repo.resolve("src/main/FeatureFull.kt"), oldSource)
        git(repo, "add", "README.md", ".gitignore", "src/main/FeatureFull.kt")
        git(repo, "commit", "-m", "Initial")
        val id = startingGraphId
        val stage = StageNode(
            StageCorrelation(sessionId, id, startingStageId,
                startingRequestId, "host-stage-correlation"),
            StageKind.SDD, StageState.CREATED, AgentId("host-sdd-actor")
        )
        val graph = StageGraph(GraphCorrelation(sessionId, id,
            "host-graph-correlation", parentGraphId = parentGraphId,
            parentStageRunId = parentStageRunId),
            StageGraphState.RUNNING, listOf(stage),
            emptyList(), StageBudget(10_000, 60_000, 0, 0))
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(graph))
        val host = LinuxFiveRoleWorkflowHost(repo, graph, git(repo, "rev-parse", "HEAD"),
            "dev", mapOf("smoke" to StageIsolatedCheck(Path.of("/usr/bin/true"))),
            setOf("git_diff_check", "smoke"),
            gateway, temp.resolve("locks-" + checkoutName), preauthorizedStages)
        return host to repo
    }

    @Test fun realAcceptedChildWriteCheckReceiptIsBoundToExactRecursiveIdentity() =
        runBlocking {
        val parentGraphId = GraphId("trusted-parent-graph")
        val parentStageRunId = StageRunId("trusted-parent-stage")
        val requestId = "trusted-child-request"
        val childGraphId = RecursiveGraphPolicy.childId(
            parentGraphId, parentStageRunId, requestId)
        val childStageId = RecursiveGraphPolicy.childStageId(childGraphId)
        val (host, repo) = newHost(startingStageId = childStageId,
            startingGraphId = childGraphId, startingRequestId = requestId,
            parentGraphId = parentGraphId, parentStageRunId = parentStageRunId)
        val child = RecursiveNode(childGraphId, childStageId, parentGraphId,
            parentStageRunId, requestId, StageKind.SDD, "child-task",
            emptySet(), 1, setOf("specs/FeatureFull.md"), emptySet(),
            emptySet(), RecursiveUsage(100, 2), deadline = Instant.now().plusSeconds(90))
        val bridge = LinuxAuthenticatedRecursiveChildIntegrationHost(host,
            "host-session", setOf(childStageId))

        // The existence of a graph or path does not convey accepted evidence.
        assertNull(bridge.manifestsFor(child))
        assertTrue(bridge.acceptedArtifactIds().isEmpty())
        val stage = host.snapshot()
        val output = targets.getValue(StageKind.SDD)
        val result = RunPreparationStageUseCase(OfflineFiveRoleModel(), host, host)
            .execute(PreparationStageCommand(stage.graph, stage.acceptedIds,
                childStageId, output.id, output.path, "Write authenticated child",
                model, Instant.now().plusSeconds(90), Cancellation()),
                noopRole, noopStage)
        assertIs<PreparationStageOutcome.Accepted>(result.outcome, result.outcome.toString())

        val receipt = assertNotNull(host.authenticatedAcceptedStage(childStageId))
        assertEquals(childGraphId, receipt.graphCorrelation.graphId)
        assertEquals(parentGraphId, receipt.graphCorrelation.parentGraphId)
        assertEquals(parentStageRunId, receipt.graphCorrelation.parentStageRunId)
        assertEquals(setOf("git_diff_check", "smoke"), receipt.checkedIds)
        assertTrue(receipt.writeToolId.isNotBlank())
        assertEquals(setOf(output.path), receipt.writtenPostimages.keys)
        assertEquals(listOf(receipt.manifest), bridge.manifestsFor(child))
        assertEquals(setOf(output.id), bridge.acceptedArtifactIds())
        assertEquals(receipt.manifest.sha256,
            sha(assertNotNull(bridge.readBytes(output.path)).toString(Charsets.UTF_8)))

        assertNull(bridge.manifestsFor(child.copy(
            parentStageRunId = StageRunId("forged-parent"))))
        assertNull(bridge.manifestsFor(child.copy(
            requestId = "forged-request")))
        assertNull(bridge.manifestsFor(child.copy(
            origin = StageKind.DEVELOP)))
        assertNull(bridge.manifestsFor(child.copy(
            writablePaths = setOf("specs/Other.md"))))
        val noAuthority = LinuxAuthenticatedRecursiveChildIntegrationHost(host,
            "host-session", emptySet())
        assertNull(noAuthority.manifestsFor(child))
        assertTrue(noAuthority.acceptedArtifactIds().isEmpty())
        assertNull(LinuxAuthenticatedRecursiveChildIntegrationHost(host,
            "other-session", setOf(childStageId)).manifestsFor(child))
        // A live byte change revokes even a previously accepted host receipt.
        Files.writeString(repo.resolve(output.path), "post-acceptance tampering\\n")
        assertNull(host.authenticatedAcceptedStage(childStageId))
        assertNull(bridge.manifestsFor(child))
        assertTrue(bridge.acceptedArtifactIds().isEmpty())
    }

    @Test fun twoRealLinuxChildWriteCheckOriginsIntegrateWithoutFakeReceiptStores() =
        runBlocking {
        val sessionId = "real-children-session"
        val requests = mapOf(StageKind.SDD to "child-sdd",
            StageKind.DEVELOP to "child-develop")
        fun rootId(kind: StageKind) = GraphId("trusted-root-" + kind.name.lowercase())
        fun parentId(kind: StageKind) = StageRunId(
            "trusted-root-stage-" + kind.name.lowercase())
        fun childId(kind: StageKind) = RecursiveGraphPolicy.childId(rootId(kind),
            parentId(kind), requests.getValue(kind))
        fun childStageId(kind: StageKind) = RecursiveGraphPolicy.childStageId(childId(kind))

        // SDD child is a separately admitted Linux stage with actual write/check
        // receipts; it is not a fabricated in-memory integration host.
        val (sddHost, _) = newHost(
            startingGraphId = childId(StageKind.SDD),
            startingStageId = childStageId(StageKind.SDD),
            startingRequestId = requests.getValue(StageKind.SDD),
            parentGraphId = rootId(StageKind.SDD),
            parentStageRunId = parentId(StageKind.SDD),
            sessionId = sessionId, checkoutName = "sdd-child")
        val first = sddHost.snapshot()
        val sddTarget = targets.getValue(StageKind.SDD)
        val prepared = RunPreparationStageUseCase(OfflineFiveRoleModel(), sddHost, sddHost)
            .execute(PreparationStageCommand(first.graph, first.acceptedIds,
                childStageId(StageKind.SDD), sddTarget.id, sddTarget.path,
                "Child SDD", model, Instant.now().plusSeconds(120),
                Cancellation()), noopRole, noopStage)
        assertIs<PreparationStageOutcome.Accepted>(prepared.outcome,
            prepared.outcome.toString())

        // A Developer child also needs genuine trusted SDD/ATDD/TDD input
        // provenance. Run its complete standalone host workflow and pre-bind
        // only the Developer child's policy-derived run/request identity.
        val (developerHost, _) = newHost(
            startingGraphId = childId(StageKind.DEVELOP),
            parentGraphId = rootId(StageKind.DEVELOP),
            parentStageRunId = parentId(StageKind.DEVELOP),
            sessionId = sessionId, checkoutName = "developer-child",
            preauthorizedStages = mapOf(StageKind.DEVELOP to
                (childStageId(StageKind.DEVELOP) to requests.getValue(StageKind.DEVELOP))))
        val workflow = RunFiveRoleWorkflowUseCase(
            RunPreparationStageUseCase(OfflineFiveRoleModel(), developerHost, developerHost),
            RunDeveloperStageUseCase(OfflineFiveRoleModel(), developerHost, developerHost),
            RunReviewerStageUseCase(OfflineFiveRoleModel(), developerHost.reviewerHost),
            developerHost
        ).execute(FiveRoleWorkflowPlan(targets,
            StageKind.entries.associateWith { "Child " + it.name },
            listOf(DeveloperFileTarget("src/main/FeatureFull.kt", sha(oldSource))),
            model, Instant.now().plusSeconds(120), Cancellation()),
            noopRole, noopStage, noopReview)
        assertIs<FiveRoleWorkflowOutcome.Completed>(workflow, workflow.toString())
        assertNotNull(developerHost.authenticatedAcceptedStage(
            childStageId(StageKind.DEVELOP)))

        fun source(kind: StageKind, host: LinuxFiveRoleWorkflowHost): RecursiveOriginSource {
            val rootGraph = rootId(kind)
            val rootStage = parentId(kind)
            val target = targets.getValue(kind)
            val graph = StageGraph(GraphCorrelation(sessionId, rootGraph,
                "root-correlation-" + kind.name.lowercase()), StageGraphState.RUNNING,
                listOf(StageNode(StageCorrelation(sessionId, rootGraph, rootStage,
                    "root-request-" + kind.name.lowercase(), "stage-correlation-" +
                        kind.name.lowercase()),
                    kind, StageState.RUNNING,
                    AgentId("root-actor-" + kind.name.lowercase()))),
                emptyList(), StageBudget(500, 30_000, 2, 2))
            val grant = RecursiveRootGrant(graph, rootStage,
                "root-task-" + kind.name.lowercase(), setOf(target.path), emptySet())
            val executor = RecursiveStageExecutor { context ->
                val child = context.requestChild(RecursiveChildProposal(
                    requests.getValue(kind), context.graphId, context.stageRunId,
                    kind, "child-task-" + kind.name.lowercase(),
                    writablePaths = setOf(target.path), maxTokens = 100,
                    maxToolCalls = 2, maxDurationMillis = 5000))
                val admitted = child as? RecursiveDecision.Allowed
                    ?: return@RecursiveStageExecutor RecursiveExecutionOutcome.Failed(
                        (child as RecursiveDecision.Denied).code)
                val childResult = context.executeChild(admitted.child.graphId,
                    RecursiveStageExecutor { RecursiveExecutionOutcome.Completed })
                if (childResult != RecursiveExecutionOutcome.Completed)
                    return@RecursiveStageExecutor childResult
                when (val integrated = context.integrateChildren()) {
                    is ChildIntegrationDecision.Accepted -> RecursiveExecutionOutcome.Completed
                    is ChildIntegrationDecision.Denied ->
                        RecursiveExecutionOutcome.Failed(integrated.code)
                }
            }
            return RecursiveOriginSource(grant, RecursiveLimits(5, 2, 20_000,
                Instant.now().plusSeconds(60)),
                LinuxAuthenticatedRecursiveChildIntegrationHost(host, sessionId,
                    setOf(childStageId(kind))), executor)
        }

        val journalPath = temp.resolve("authenticated-child-events.jsonl")
        val journal = SessionEventJournal(sessionId, "real-child-trace",
            "real-child-request", java.time.Clock.systemUTC(),
            JsonlEventSink.open(journalPath))
        val completed = RunTwoOriginRecursiveChildrenUseCase(
            events = CorrelatedRecursiveSessionEvents(journal)).execute(
                source(StageKind.DEVELOP, developerHost),
                source(StageKind.SDD, sddHost))
        assertIs<TwoOriginRecursiveOutcome.Completed>(completed,
            completed.toString())
        assertEquals(listOf(StageKind.SDD, StageKind.DEVELOP),
            completed.integrated.keys.toList())
        for (kind in listOf(StageKind.SDD, StageKind.DEVELOP)) {
            val artifact = completed.integrated.getValue(kind).artifacts.single()
            assertEquals(childStageId(kind), artifact.manifest.producerStageRunId)
            assertEquals(targets.getValue(kind).path, artifact.manifest.declaredPath)
            assertTrue(completed.snapshots.getValue(kind).nodes.all {
                it.state == RecursiveNodeState.SUCCEEDED
            })
        }
        assertEquals(64, journal.finish().length)
        val events = Files.readString(journalPath)
        assertTrue(events.contains("\"origin\":\"SDD\""))
        assertTrue(events.contains("\"origin\":\"DEVELOP\""))
        assertTrue(events.contains("INTEGRATED"))
    }

    @Test fun cannotForgeAcceptanceWithoutARealTrustedWriteReceipt() {
        val (host, _) = newHost()
        val first = host.snapshot()
        assertNull(host.advance(first, StageKind.ATDD))
        val id = first.graph.stages.single().correlation.stageRunId
        val lease = assertNotNull(host.beginStage(first.graph, id, first.acceptedIds))
        assertNull(host.beginStage(first.graph, id, first.acceptedIds))
        assertNull(host.reviewerHost.beginStage(first.graph, id, first.acceptedIds))

        val manifest = StageArtifactManifest(ArtifactId("host-artifact"),
            StageArtifactKind.REQUIREMENTS, "specs/Test.md", "0".repeat(64),
            EngineeringRole.SDD, id, 1L, ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE)
        val forged = StageValidationEvidence(lease.graph.stages.single().correlation,
            AgentId("host-sdd-actor"), emptyList(), true)
        assertEquals(PreparationApproval.REJECT, host.approve(manifest, forged,
            StageRoleUsage(1, 1, 1)))

        val acceptedManifest = manifest.copy(approval = ArtifactApproval.ACCEPTED,
            freeze = ArtifactFreeze.FROZEN)
        val forgedGraph = lease.graph.copy(
            stages = lease.graph.stages.map {
                it.copy(state = StageState.ACCEPTED,
                    outputArtifacts = setOf(acceptedManifest.id))
            },
            artifacts = listOf(acceptedManifest)
        )
        assertFalse(host.finishStage(lease, forgedGraph, setOf(acceptedManifest.id)))
        assertTrue(host.current(lease))
        val aborted = assertNotNull(host.abortStage(lease, StageState.FAILED))
        assertEquals(StageState.FAILED, aborted.graph.stages.single().state)
        assertEquals(aborted, host.snapshot())
    }

    @Test fun writeAttemptThenAbortQuarantinesTheWorkspace() {
        val (host, repo) = newHost()
        val first = host.snapshot()
        val lease = assertNotNull(host.beginStage(first.graph,
            first.graph.stages.single().correlation.stageRunId, first.acceptedIds))
        val opened = host.open(lease.authority)
        val tool = assertIs<StageToolOpenResult.Granted>(opened).session
        try {
            val result = tool.write(listOf(StageWrite.Create("specs/UnsafeToResume.md",
                "Uncommitted, host-only physical bytes\n")))
            assertTrue(result.permitted, result.deniedCode)
        } finally {
            tool.close()
        }
        assertNotNull(host.abortStage(lease, StageState.FAILED))
        assertTrue(Files.exists(repo.resolve("specs/UnsafeToResume.md")))
        assertFailsWith<IllegalStateException> { host.snapshot() }
        assertNull(host.beginStage(first.graph,
            first.graph.stages.single().correlation.stageRunId, first.acceptedIds))
    }
    @Test fun maliciousGitConfigAndIgnoredFilesCannotPassBaselinePreflight() {
        val (host, repo) = newHost()
        val state = host.snapshot()
        val stageId = state.graph.stages.single().correlation.stageRunId
        git(repo, "config", "core.fsmonitor", "/usr/bin/false")
        assertNull(host.beginStage(state.graph, stageId, state.acceptedIds),
            "Unsafe local Git configuration must never be used for admission")
        assertEquals("UNSAFE_GIT_CONFIGURATION", host.lastBaselineDenialCode())
        git(repo, "config", "--unset", "core.fsmonitor")
        Files.writeString(repo.resolve("untracked.ignored"), "hidden contents\n")
        assertNull(host.beginStage(state.graph, stageId, state.acceptedIds),
            "An ignored file is not a clean, isolated checkout")
        assertEquals("STAGE_WORKSPACE_CHANGED", host.lastBaselineDenialCode())
        Files.delete(repo.resolve("untracked.ignored"))
        assertNotNull(host.beginStage(state.graph, stageId, state.acceptedIds))
        assertNull(host.lastBaselineDenialCode())
    }

    @Test fun fifoAndProtectedPathsAreNotReadable() {
        val (host, repo) = newHost()
        assertNull(host.readBytes(".git/config"))
        Files.writeString(repo.resolve(".env"), "SECRET=never-read\n")
        Files.createDirectories(repo.resolve("secrets"))
        Files.writeString(repo.resolve("secrets/token"), "blocked\n")
        assertNull(host.readBytes(".env"))
        assertNull(host.readBytes("secrets/token"))
        val roleOnly = "tests/unit/LegitimateTest.kt"
        Files.writeString(repo.resolve(roleOnly), "fun approvedTest() = Unit\n")
        assertEquals("fun approvedTest() = Unit\n",
            host.readBytes(roleOnly)?.toString(Charsets.UTF_8),
            "An allowed TDD-only source must remain readable to Reviewer")
        val fifo = repo.resolve("specs/blocked-fifo.md")
        val process = ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start()
        assertEquals(0, process.waitFor())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val read = pool.submit<ByteArray?> { host.readBytes("specs/blocked-fifo.md") }
            assertNull(read.get(2, TimeUnit.SECONDS), "FIFO open must not block")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun authenticFiveRoleWorkflowRunsThroughGitAndIndependentReviewer() = runBlocking {
        val (host, repo) = newHost()
        val roles = OfflineFiveRoleModel()
        val plan = FiveRoleWorkflowPlan(targets,
            StageKind.entries.associateWith { "Run " + it.name },
            listOf(DeveloperFileTarget("src/main/FeatureFull.kt", sha(oldSource))),
            model, Instant.now().plusSeconds(120), Cancellation())
        val outcome = RunFiveRoleWorkflowUseCase(
            RunPreparationStageUseCase(roles, host, host),
            RunDeveloperStageUseCase(roles, host, host),
            RunReviewerStageUseCase(roles, host.reviewerHost),
            host
        ).execute(plan, noopRole, noopStage, noopReview)
        assertIs<FiveRoleWorkflowOutcome.Completed>(outcome, outcome.toString())
        assertEquals(StageKind.entries.toList(), roles.called)
        assertEquals(StageGraphState.SUCCEEDED, host.snapshot().graph.state)
        assertEquals(newSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
        assertEquals("", git(repo, "status", "--porcelain=v1", "--ignored=matching"))
    }

    @Test fun tamperedDeveloperHandoffIsRejectedBeforeReviewerAdmission() = runBlocking {
        val (host, repo) = newHost()
        val roles = OfflineFiveRoleModel()
        val prep = RunPreparationStageUseCase(roles, host, host)
        for (kind in listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD)) {
            if (kind != StageKind.SDD)
                assertNotNull(host.advance(host.snapshot(), kind))
            val current = host.snapshot()
            val stageId = current.graph.stages.last().correlation.stageRunId
            val output = targets.getValue(kind)
            val result = prep.execute(
                PreparationStageCommand(current.graph, current.acceptedIds,
                    stageId, output.id, output.path,
                    "Produce " + kind.name, model, Instant.now().plusSeconds(120),
                    Cancellation()), noopRole, noopStage)
            assertIs<PreparationStageOutcome.Accepted>(result.outcome, result.outcome.toString())
        }
        assertNotNull(host.advance(host.snapshot(), StageKind.DEVELOP))
        val before = host.snapshot()
        val output = targets.getValue(StageKind.DEVELOP)
        val developer = RunDeveloperStageUseCase(roles, host, host).execute(
            DeveloperStageCommand(before.graph, before.acceptedIds,
                before.graph.stages.last().correlation.stageRunId, output.id, output.path,
                listOf(DeveloperFileTarget("src/main/FeatureFull.kt", sha(oldSource))),
                "Implement feature", model, Instant.now().plusSeconds(120), Cancellation()),
            noopRole, noopStage)
        val accepted = assertIs<DeveloperStageOutcome.Accepted>(
            developer.outcome, developer.outcome.toString())
        val genuine = DeveloperReviewEvidence(accepted.manifest,
            accepted.changedFiles.map {
                ReviewerChangedFile(it.path, it.beforeSha256, it.afterSha256)
            }, accepted.writeToolId, accepted.validation, accepted.usage)
        val snapshot = host.snapshot()
        assertNull(host.advance(snapshot, StageKind.REVIEW,
            genuine.copy(usage = genuine.usage.copy(outputTokens = 31))))
        assertNull(host.advance(snapshot, StageKind.REVIEW,
            genuine.copy(writeToolId = "forged-write")))
        assertNull(host.advance(snapshot, StageKind.REVIEW,
            genuine.copy(changedFiles = genuine.changedFiles.map {
                it.copy(afterSha256 = "0".repeat(64))
            })))
        assertEquals(snapshot, host.snapshot(), "Forged handoffs cannot mutate the stage graph")
        val review = assertNotNull(host.advance(snapshot, StageKind.REVIEW, genuine))
        val stage = review.graph.stages.last()
        val reviewed = RunReviewerStageUseCase(roles, host.reviewerHost).execute(
            ReviewerStageCommand(review.graph, review.acceptedIds,
                stage.correlation.stageRunId, model, "Independent review",
                Instant.now().plusSeconds(120), Cancellation()), noopRole, noopReview)
        assertIs<ReviewerStageOutcome.Accepted>(reviewed.outcome, reviewed.outcome.toString())
        val reviewedGraph = host.snapshot()
        val finished = assertNotNull(host.finishSuccess(reviewedGraph,
            stage.correlation.stageRunId))
        assertEquals(StageGraphState.SUCCEEDED, finished.state)
        assertEquals("", git(repo, "status", "--porcelain=v1", "--ignored=matching"))
    }

    @Test fun throwingApprovalIsRejectedAndCannotLeaveResumableWrites() = runBlocking {
        val (host, repo) = newHost { _, _, _ ->
            throw IllegalStateException("Never trust a failing external approval provider")
        }
        val state = host.snapshot()
        val artifact = targets.getValue(StageKind.SDD)
        val result = RunPreparationStageUseCase(OfflineFiveRoleModel(), host, host)
            .execute(PreparationStageCommand(state.graph, state.acceptedIds,
                state.graph.stages.single().correlation.stageRunId,
                artifact.id, artifact.path, "Create SDD", model,
                Instant.now().plusSeconds(120), Cancellation()), noopRole, noopStage)
        val denied = assertIs<PreparationStageOutcome.Denied>(
            result.outcome, result.outcome.toString())
        assertEquals("HOST_REJECTED", denied.code)
        assertTrue(Files.exists(repo.resolve(artifact.path)))
        assertFailsWith<IllegalStateException> { host.snapshot() }
    }

    @Test fun approvalCallbackDoesNotHoldHostMonitor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (host, _) = newHost { _, _, _ ->
            entered.countDown()
            assertTrue(release.await(20, TimeUnit.SECONDS))
            PreparationApproval.ACCEPT
        }
        val roles = OfflineFiveRoleModel()
        val current = host.snapshot()
        val artifact = targets.getValue(StageKind.SDD)
        val worker = thread {
            runBlocking {
                val result = RunPreparationStageUseCase(roles, host, host).execute(
                    PreparationStageCommand(current.graph, current.acceptedIds,
                        current.graph.stages.single().correlation.stageRunId,
                        artifact.id, artifact.path, "Produce SDD", model,
                        Instant.now().plusSeconds(40), Cancellation()), noopRole, noopStage)
                assertIs<PreparationStageOutcome.Accepted>(result.outcome,
                    result.outcome.toString())
            }
        }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS),
                "Trusted write and checks should reach external approval")
            val pool = Executors.newSingleThreadExecutor()
            try {
                val snapshot = pool.submit<PreparationStageSnapshot> { host.snapshot() }
                assertEquals(StageState.RUNNING,
                    snapshot.get(2, TimeUnit.SECONDS).graph.stages.single().state,
                    "A slow external approver cannot lock the host authority")
            } finally {
                pool.shutdownNow()
            }
        } finally {
            release.countDown()
            worker.join(30000)
        }
        assertFalse(worker.isAlive)
    }

    private data class PreparedApproval(
        val lease: PreparationStageLease,
        val manifest: StageArtifactManifest,
        val verification: StageValidationEvidence,
        val usage: StageRoleUsage
    )

    private fun prepareVerifiedApproval(host: LinuxFiveRoleWorkflowHost): PreparedApproval {
        val snapshot = host.snapshot()
        val id = snapshot.graph.stages.single().correlation.stageRunId
        val lease = assertNotNull(host.beginStage(snapshot.graph, id, snapshot.acceptedIds))
        val tool = assertIs<StageToolOpenResult.Granted>(
            host.open(lease.authority)).session
        val path = "specs/PendingApproval.md"
        val bytes = "original trusted approval bytes\\n"
        val verification = try {
            val write = tool.write(listOf(StageWrite.Create(path, bytes)))
            assertTrue(write.permitted, write.deniedCode)
            for (check in listOf("git_diff_check", "smoke")) {
                val receipt = tool.runCheck(check)
                assertTrue(receipt.passed, receipt.deniedCode)
            }
            tool.verify()
        } finally {
            tool.close()
        }
        assertTrue(verification.successful, verification.deniedCode)
        val manifest = StageArtifactManifest(ArtifactId("pending-approval"),
            StageArtifactKind.REQUIREMENTS, path, sha(bytes),
            EngineeringRole.SDD, id, 1L, ArtifactApproval.DRAFT,
            ArtifactFreeze.EDITABLE)
        return PreparedApproval(lease, manifest, verification, StageRoleUsage(1, 3, 2))
    }

    @Test fun approvalDriftIsTerminalAndCannotRepromptOrDoubleInvokeGateway() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invoked = AtomicInteger(0)
        val (host, repo) = newHost { _, _, _ ->
            invoked.incrementAndGet()
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
            PreparationApproval.ACCEPT
        }
        val prepared = prepareVerifiedApproval(host)
        val result = AtomicReference<PreparationApproval?>()
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                result.set(host.approve(prepared.manifest,
                    prepared.verification, prepared.usage))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            assertEquals(PreparationApproval.REJECT, host.approve(prepared.manifest,
                prepared.verification, prepared.usage),
                "Concurrent approve must not invoke the gateway twice")
            assertEquals(1, invoked.get())
            Files.writeString(repo.resolve(prepared.manifest.declaredPath),
                "drift while human gateway is pending\\n")
        } finally {
            release.countDown()
            worker.join(30_000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError("Approval thread failed", it) }
        assertEquals(PreparationApproval.REJECT, result.get())
        // Evidence was previously valid but the post-gateway bytes changed.
        // The failed decision must be terminal for this still-current lease.
        assertEquals(PreparationApproval.REJECT,
            host.approve(prepared.manifest, prepared.verification, prepared.usage))
        assertEquals(1, invoked.get())
        assertNotNull(host.abortStage(prepared.lease, StageState.FAILED))
        assertFailsWith<IllegalStateException> { host.snapshot() }
    }

    @Test fun deniedWriteBeforeDiskMutationStillQuarantinesFailClosed() {
        val (host, repo) = newHost()
        val state = host.snapshot()
        val id = state.graph.stages.single().correlation.stageRunId
        val lease = assertNotNull(host.beginStage(state.graph, id, state.acceptedIds))
        val tool = assertIs<StageToolOpenResult.Granted>(host.open(lease.authority)).session
        try {
            val denied = tool.write(listOf(StageWrite.Create(".env", "no access\\n")))
            assertFalse(denied.permitted)
        } finally {
            tool.close()
        }
        assertFalse(Files.exists(repo.resolve(".env")))
        assertNotNull(host.abortStage(lease, StageState.FAILED))
        assertFailsWith<IllegalStateException> { host.snapshot() }
    }

}
