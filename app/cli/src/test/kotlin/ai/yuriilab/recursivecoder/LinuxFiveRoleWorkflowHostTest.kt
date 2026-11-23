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
@PreparationHostConformance("ai.yuriilab.recursivecoder.LinuxFiveRoleWorkflowHost",
    pending = PreparationPendingProfile.REFUSED)
class LinuxFiveRoleWorkflowHostTest : PreparationStageHostContract() {
    @TempDir lateinit var temp: Path

    override fun createHostFixture(): PreparationHostFixture {
        var decision = PreparationApproval.ACCEPT
        val checkout = Files.createTempDirectory(temp, "host-contract-").fileName.toString()
        val (host, repo) = newHost(checkoutName = checkout) { _, _, _ -> decision }
        val snapshot = host.snapshot()
        val path = "specs/host-contract-read.md"
        val file = repo.resolve(path)
        val expected = byteArrayOf(0, 1, -1, 42)
        val read = PreparationReadFixture(path, expected) { state ->
            host.preparationReadTestHook = null
            if (state == PreparationReadState.ABSENT) {
                Files.deleteIfExists(file)
                assertTrue(Files.notExists(file))
            } else {
                Files.write(file, expected)
                assertContentEquals(expected, Files.readAllBytes(file))
            }
            // The real bytes remain present; inject the I/O failure at the
            // actual adapter read boundary, never replace the adapter.
            host.preparationReadTestHook = { requested ->
                if (requested == path) when (state) {
                    PreparationReadState.UNAVAILABLE -> throw java.io.IOException("induced I/O failure")
                    PreparationReadState.HOST_CANCELLED -> throw java.util.concurrent.CancellationException("host cancelled")
                    else -> Unit
                }
            }
        }
        return PreparationHostFixture(host, snapshot.graph,
            snapshot.graph.stages.single().correlation.stageRunId, read,
            prepareOutput = { lease, output, content ->
                val tool = assertIs<StageToolOpenResult.Granted>(host.open(lease.authority)).session
                try {
                    val write = tool.write(listOf(StageWrite.Create(output, content)))
                    assertTrue(write.permitted, write.deniedCode)
                    for (check in listOf("git_diff_check", "smoke")) {
                        val result = tool.runCheck(check)
                        assertTrue(result.passed, result.deniedCode)
                    }
                    tool.verify()
                } finally {
                    tool.close()
                }
            }, setApproval = { decision = it })
    }

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
        smoke: StageIsolatedCheck = StageIsolatedCheck(Path.of("/usr/bin/true")),
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
        // The host itself validates the graph; negative constructor fixtures
        // intentionally supply invalid parent correlations.
        val host = LinuxFiveRoleWorkflowHost(repo, graph, git(repo, "rev-parse", "HEAD"),
            "dev", mapOf("smoke" to smoke),
            setOf("git_diff_check", "smoke"),
            gateway, temp.resolve("locks-" + checkoutName), preauthorizedStages)
        return host to repo
    }

    @Test fun installedRecursiveConstructorRejectsNonFakeProviderAndModelId() {
        val (host, _) = newHost(checkoutName = "offline-gate")
        fun workflow(modelConfig: ModelConfig) = InstalledRecursiveWorkflow(
            "host-session", host,
            StageIsolatedCheck(Path.of("/usr/bin/true")), targets,
            emptyMap(), emptyList(), emptyMap(), modelConfig,
            Instant.now().plusSeconds(120), Cancellation(), OfflineFiveRoleModel(),
            emptyMap(), 10_000, 60_000, 1, 1, 16, 1000, null)
        assertFailsWith<IllegalArgumentException> {
            workflow(model.copy(provider = "openai"))
        }
        assertFailsWith<IllegalArgumentException> {
            workflow(model.copy(modelId = "different"))
        }
        workflow(model)
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
        assertEquals(StageRoleUsage(1, 100, 30), receipt.usage)
        assertEquals(ChildReceiptVerification.Accepted(receipt.manifest),
            bridge.verifyChild(child))
        assertEquals(listOf(receipt.manifest), bridge.manifestsFor(child))
        assertEquals(setOf(output.id), bridge.acceptedArtifactIds())
        assertEquals(receipt.manifest.sha256,
            sha(assertNotNull(bridge.readBytes(output.path)).toString(Charsets.UTF_8)))

        assertNull(bridge.manifestsFor(child.copy(
            parentStageRunId = StageRunId("forged-parent"))))
        assertNull(bridge.manifestsFor(child.copy(
            requestId = "forged-request")))
        assertEquals(ChildReceiptVerification.Denied(
            ChildReceiptDenialCode.ORIGIN_MISMATCH),
            bridge.verifyChild(child.copy(origin = StageKind.DEVELOP)))
        assertNull(bridge.manifestsFor(child.copy(
            origin = StageKind.DEVELOP)))
        assertEquals(ChildReceiptVerification.Denied(
            ChildReceiptDenialCode.OUTSIDE_WRITABLE_SCOPE),
            bridge.verifyChild(child.copy(writablePaths = setOf("specs/Other.md"))))
        assertNull(bridge.manifestsFor(child.copy(
            writablePaths = setOf("specs/Other.md"))))
        val noAuthority = LinuxAuthenticatedRecursiveChildIntegrationHost(host,
            "host-session", emptySet())
        assertNull(noAuthority.manifestsFor(child))
        assertTrue(noAuthority.acceptedArtifactIds().isEmpty())
        val crossSession = LinuxAuthenticatedRecursiveChildIntegrationHost(host,
            "other-session", setOf(childStageId))
        assertNull(crossSession.manifestsFor(child))
        assertTrue(crossSession.acceptedArtifactIds().isEmpty())
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

        val childTokenGrant = 500L
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
                emptyList(), StageBudget(1_000, 30_000, 2, 2))
            val writable = if (kind == StageKind.DEVELOP)
                setOf(target.path, "src/main/FeatureFull.kt") else setOf(target.path)
            val grant = RecursiveRootGrant(graph, rootStage,
                "root-task-" + kind.name.lowercase(), writable, emptySet())
            val executor = RecursiveStageExecutor { context ->
                val child = context.requestChild(RecursiveChildProposal(
                    requests.getValue(kind), context.graphId, context.stageRunId,
                    kind, "child-task-" + kind.name.lowercase(),
                    writablePaths = writable, maxTokens = childTokenGrant,
                    maxToolCalls = 5, maxDurationMillis = 5000))
                val admitted = child as? RecursiveDecision.Allowed
                    ?: return@RecursiveStageExecutor RecursiveExecutionOutcome.Failed(
                        (child as RecursiveDecision.Denied).code)
                val childResult = context.executeChild(admitted.child.graphId,
                    RecursiveStageExecutor { childContext ->
                        val attested = host.authenticatedAcceptedStage(childStageId(kind))
                            ?: return@RecursiveStageExecutor RecursiveExecutionOutcome.Failed(
                                "AUTHENTICATED_CHILD_RECEIPT_MISSING")
                        // FakeModel reports fixture usage, not independently
                        // measured tokens. Bill the full child token grant,
                        // never assume an unverified report means zero.
                        // Write + mandatory checks have genuine host receipts.
                        when (val billed = childContext.charge(RecursiveUsage(
                                childTokenGrant, attested.checkedIds.size.toLong() + 1))) {
                            is RecursiveDecision.Allowed -> RecursiveExecutionOutcome.Completed
                            is RecursiveDecision.Denied ->
                                RecursiveExecutionOutcome.Failed(billed.code)
                        }
                    })
                if (childResult != RecursiveExecutionOutcome.Completed)
                    return@RecursiveStageExecutor childResult
                when (val integrated = context.integrateChildren()) {
                    is ChildIntegrationDecision.Accepted -> RecursiveExecutionOutcome.Completed
                    is ChildIntegrationDecision.Denied ->
                        RecursiveExecutionOutcome.Failed(integrated.code)
                }
            }
            return RecursiveOriginSource(grant, RecursiveLimits(10, 2, 20_000,
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
            val charged = completed.snapshots.getValue(kind).nodes.single {
                it.stageRunId == childStageId(kind)
            }
            assertEquals(RecursiveUsage(childTokenGrant, 3), charged.spent,
                "Fixture tokens charge the full cap; real write/check calls are counted")
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

    @Test fun allAuthenticatedWritePostimagesMustFitChildScope() {
        val parentGraph = GraphId("scope-parent")
        val parentStage = StageRunId("scope-parent-stage")
        val request = "scope-child-request"
        val childGraph = RecursiveGraphPolicy.childId(parentGraph, parentStage, request)
        val childStage = RecursiveGraphPolicy.childStageId(childGraph)
        val (host, _) = newHost(startingStageId = childStage,
            startingGraphId = childGraph, startingRequestId = request,
            parentGraphId = parentGraph, parentStageRunId = parentStage,
            checkoutName = "scope-extra-write")
        val snapshot = host.snapshot()
        val lease = assertNotNull(host.beginStage(snapshot.graph, childStage,
            snapshot.acceptedIds))
        val output = targets.getValue(StageKind.SDD)
        val extra = "specs/Unexpected.md"
        val primaryText = "Host-approved primary artifact\n"
        val extraText = "Host-approved but child-unauthorized write\n"
        val session = assertIs<StageToolOpenResult.Granted>(
            host.open(lease.authority)).session
        val evidence = try {
            val write = session.write(listOf(
                StageWrite.Create(output.path, primaryText),
                StageWrite.Create(extra, extraText)))
            assertTrue(write.permitted, write.deniedCode)
            for (check in listOf("git_diff_check", "smoke")) {
                val result = session.runCheck(check)
                assertTrue(result.passed, result.deniedCode)
            }
            session.verify()
        } finally {
            session.close()
        }
        assertTrue(evidence.successful, evidence.deniedCode)
        val candidate = StageArtifactManifest(output.id,
            StageArtifactKind.REQUIREMENTS, output.path, sha(primaryText),
            EngineeringRole.SDD, childStage, 1L,
            ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE)
        val usage = StageRoleUsage(1, null, null)
        assertEquals(PreparationApproval.ACCEPT, host.approve(candidate, evidence, usage))
        val frozen = candidate.copy(approval = ArtifactApproval.ACCEPTED,
            freeze = ArtifactFreeze.FROZEN)
        val accepted = lease.graph.copy(
            stages = lease.graph.stages.map {
                it.copy(state = StageState.ACCEPTED, outputArtifacts = setOf(frozen.id))
            },
            artifacts = listOf(frozen))
        assertTrue(host.finishStage(lease, accepted, setOf(frozen.id)))
        val receipt = assertNotNull(host.authenticatedAcceptedStage(childStage))
        assertEquals(setOf(output.path, extra), receipt.writtenPostimages.keys)
        assertEquals(usage, receipt.usage)
        assertNull(receipt.usage.inputTokens)
        assertNull(receipt.usage.outputTokens)
        assertFailsWith<UnsupportedOperationException> {
            (receipt.writtenPostimages as MutableMap<String, String>)[extra] = "forged"
        }
        assertFailsWith<UnsupportedOperationException> {
            (receipt.checkedIds as MutableSet<String>).clear()
        }

        val child = RecursiveNode(childGraph, childStage, parentGraph,
            parentStage, request, StageKind.SDD, "scope-task",
            emptySet(), 1, setOf(output.path), emptySet(),
            emptySet(), RecursiveUsage(100, 2), deadline = Instant.now().plusSeconds(90))
        val bridge = LinuxAuthenticatedRecursiveChildIntegrationHost(host,
            "host-session", setOf(childStage))
        // Provisional host acceptance is not equivalent to child-scope authorization.
        assertEquals(setOf(output.id), bridge.acceptedArtifactIds())
        // A valid host receipt can still be forbidden by a narrower child grant.
        assertEquals(ChildReceiptVerification.Denied(
            ChildReceiptDenialCode.OUTSIDE_WRITABLE_SCOPE), bridge.verifyChild(child))
        assertNull(bridge.manifestsFor(child))
        val permittedChild = child.copy(writablePaths = setOf(output.path, extra))
        assertEquals(ChildReceiptVerification.Accepted(frozen),
            bridge.verifyChild(permittedChild))
        assertEquals(listOf(frozen), bridge.manifestsFor(permittedChild))
        assertEquals(ChildReceiptVerification.Denied(
            ChildReceiptDenialCode.WRITES_FROZEN_PATH),
            bridge.verifyChild(permittedChild.copy(frozenPaths = setOf(extra))))
    }

    @Test fun acceptedParentStageCannotBeRelabelledAsRecursiveChild() = runBlocking {
        val (host, _) = newHost(checkoutName = "parent-forgery")
        val stage = host.snapshot()
        val parentStage = StageRunId("host-sdd")
        val target = targets.getValue(StageKind.SDD)
        val outcome = RunPreparationStageUseCase(OfflineFiveRoleModel(), host, host)
            .execute(PreparationStageCommand(stage.graph, stage.acceptedIds,
                parentStage, target.id, target.path, "Parent output", model,
                Instant.now().plusSeconds(90), Cancellation()), noopRole, noopStage)
        assertIs<PreparationStageOutcome.Accepted>(outcome.outcome)
        assertNotNull(host.authenticatedAcceptedStage(parentStage),
            "The parent receipt is genuine; only its child identity is invalid")
        val bridge = LinuxAuthenticatedRecursiveChildIntegrationHost(host,
            "host-session", setOf(parentStage))
        assertTrue(bridge.acceptedArtifactIds().isEmpty())
        val claimedParent = GraphId("forged-parent-graph")
        val claimedParentStage = StageRunId("forged-parent-stage")
        val request = "forged-child-request"
        val claimedChildGraph = RecursiveGraphPolicy.childId(
            claimedParent, claimedParentStage, request)
        val counterfeit = RecursiveNode(claimedChildGraph, parentStage,
            claimedParent, claimedParentStage, request, StageKind.SDD, "forged-task",
            emptySet(), 1, setOf(target.path), emptySet(),
            emptySet(), RecursiveUsage(100, 2), deadline = Instant.now().plusSeconds(90))
        assertEquals(ChildReceiptVerification.Denied(
            ChildReceiptDenialCode.INVALID_DERIVED_IDENTITY),
            bridge.verifyChild(counterfeit))
        assertNull(bridge.manifestsFor(counterfeit))
    }

    @Test fun preauthorizedChildIdentitiesRejectBadConstructorsAndCollisions() =
        runBlocking {
        val parent = GraphId("preauth-parent")
        val parentStage = StageRunId("preauth-parent-stage")
        val request = "preauth-request"
        val childGraph = RecursiveGraphPolicy.childId(parent, parentStage, request)
        val childStage = RecursiveGraphPolicy.childStageId(childGraph)
        var sequence = 0
        fun construct(
            stages: Map<StageKind, Pair<StageRunId, String>>,
            graph: GraphId = childGraph,
            parentGraph: GraphId? = parent,
            parentRun: StageRunId? = parentStage,
            startingId: StageRunId = StageRunId("preauth-sdd")
        ): Pair<LinuxFiveRoleWorkflowHost, Path> =
            newHost(startingStageId = startingId, startingGraphId = graph,
                startingRequestId = request, parentGraphId = parentGraph,
                parentStageRunId = parentRun,
                preauthorizedStages = stages, checkoutName = "preauth-" + sequence++)

        for (kind in listOf(StageKind.SDD, StageKind.REVIEW)) {
            assertFailsWith<IllegalArgumentException> {
                construct(mapOf(kind to (childStage to request)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            construct(mapOf(StageKind.ATDD to (childStage to request)),
                parentGraph = null, parentRun = null)
        }
        assertFailsWith<IllegalArgumentException> {
            construct(mapOf(StageKind.ATDD to (childStage to request)),
                parentRun = null)
        }
        assertFailsWith<IllegalArgumentException> {
            construct(mapOf(StageKind.ATDD to (childStage to "invalid/request")))
        }
        assertFailsWith<IllegalArgumentException> {
            construct(mapOf(StageKind.ATDD to
                (StageRunId("wrong-child-stage") to request)))
        }
        assertFailsWith<IllegalArgumentException> {
            construct(mapOf(StageKind.ATDD to (childStage to request)),
                graph = GraphId("wrong-child-graph"))
        }
        // Every stage kind maps to the same policy child-stage ID; reject twice
        // pre-authorizing it instead of failing at the later ATDD/TDD handoff.
        assertFailsWith<IllegalArgumentException> {
            construct(mapOf(StageKind.ATDD to (childStage to request),
                StageKind.TDD to (childStage to request)))
        }

        // The run-time collision guard also rejects a planned stage ID that
        // is already used by the initial SDD, before Git reconciliation.
        val (host, repo) = construct(
            mapOf(StageKind.ATDD to (childStage to request)),
            startingId = childStage)
        val initial = host.snapshot()
        val target = targets.getValue(StageKind.SDD)
        val completed = RunPreparationStageUseCase(OfflineFiveRoleModel(), host, host)
            .execute(PreparationStageCommand(initial.graph, initial.acceptedIds,
                childStage, target.id, target.path, "Collision fixture", model,
                Instant.now().plusSeconds(90), Cancellation()), noopRole, noopStage)
        assertIs<PreparationStageOutcome.Accepted>(completed.outcome)
        val before = git(repo, "rev-parse", "HEAD")
        val ready = host.snapshot()
        assertNull(host.advance(ready, StageKind.ATDD))
        assertEquals(ready, host.snapshot())
        assertEquals(before, git(repo, "rev-parse", "HEAD"))
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
        assertFailsWith<java.io.IOException> { host.readBytes(".git/config") }
        Files.writeString(repo.resolve(".env"), "SECRET=never-read\n")
        Files.createDirectories(repo.resolve("secrets"))
        Files.writeString(repo.resolve("secrets/token"), "blocked\n")
        assertFailsWith<java.io.IOException> { host.readBytes(".env") }
        assertFailsWith<java.io.IOException> { host.readBytes("secrets/token") }
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
            val read = pool.submit<Unit> {
                assertFailsWith<java.io.IOException> { host.readBytes("specs/blocked-fifo.md") }
            }
            read.get(2, TimeUnit.SECONDS) // FIFO open must not block
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun intermediateReadLookupDistinguishesAbsenceFromUnavailable() {
        val (host, repo) = newHost()
        val intermediate = repo.resolve("specs/intermediate")
        val path = "specs/intermediate/read.md"
        assertNull(host.readBytes(path))
        Files.writeString(intermediate, "not a directory")
        assertFailsWith<java.io.IOException> { host.readBytes(path) }
        Files.delete(intermediate)
        Files.createDirectory(intermediate)
        Files.writeString(repo.resolve(path), "present")
        val permissions = Files.getPosixFilePermissions(intermediate)
        try {
            Files.setPosixFilePermissions(intermediate, emptySet())
            // Unprivileged CI must exercise a real lookup failure. Privileged
            // local runs still execute the non-directory regression above.
            if (!Files.isExecutable(intermediate))
                assertFailsWith<java.io.IOException> { host.readBytes(path) }
        } finally {
            Files.setPosixFilePermissions(intermediate, permissions)
        }
        assertEquals("present", host.readBytes(path)?.toString(Charsets.UTF_8))
    }

    @Test fun recursiveReceiptByteVerificationDeniesLateReadFailures() {
        fun acceptedHost(name: String): Pair<LinuxFiveRoleWorkflowHost, AuthenticatedAcceptedStageReceipt> {
            val (host, _) = newHost(checkoutName = name)
            val prepared = prepareVerifiedApproval(host)
            assertEquals(PreparationApproval.ACCEPT,
                host.approve(prepared.manifest, prepared.verification, prepared.usage))
            val frozen = prepared.manifest.copy(approval = ArtifactApproval.ACCEPTED,
                freeze = ArtifactFreeze.FROZEN)
            val graph = prepared.lease.graph.copy(
                stages = prepared.lease.graph.stages.map {
                    it.copy(state = StageState.ACCEPTED, outputArtifacts = setOf(frozen.id))
                },
                artifacts = listOf(frozen))
            assertTrue(host.finishStage(prepared.lease, graph, setOf(frozen.id)))
            return host to assertNotNull(host.authenticatedAcceptedStage(prepared.lease.stageRunId))
        }
        val (parent, parentReceipt) = acceptedHost("receipt-parent")
        val (child, childReceipt) = acceptedHost("receipt-child")
        val target = FiveRoleArtifactTarget(parentReceipt.manifest.id, parentReceipt.manifest.declaredPath)
        fun verify() = installedParentEvidenceMatches(StageKind.SDD, target, parent, child,
            parentReceipt, childReceipt, null)
        assertTrue(verify())
        for (host in listOf(parent, child)) {
            // Both receipts were authenticated before this second bytes check.
            host.preparationReadTestHook = { path ->
                if (path == target.path) throw java.io.IOException("late read unavailable")
            }
            try {
                assertFalse(verify())
            } finally {
                host.preparationReadTestHook = null
            }
            assertTrue(verify())
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

    /** Issue #160: a graph-validation denial must leave HEAD and the graph unchanged. */
    @Test fun advanceValidationDenialNeverMovesGitHead() = runBlocking {
        val (host, repo) = newHost()
        val initialHead = git(repo, "rev-parse", "HEAD")
        val validated = mutableListOf<StageKind>()
        host.advanceValidationDenialTestHook = { candidate ->
            validated += candidate.stages.last().kind
            true
        }
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
        val uncertain = assertIs<FiveRoleWorkflowOutcome.ReconciliationRequired>(outcome,
            outcome.toString())
        assertEquals("STAGE_RECONCILIATION_DENIED", uncertain.code)
        assertEquals(listOf(StageKind.SDD), roles.called)
        assertEquals(listOf(StageKind.ATDD), validated)
        // The denial happened before the reconciliation commit.
        assertEquals(initialHead, git(repo, "rev-parse", "HEAD"))
        assertEquals("1", git(repo, "rev-list", "--count", "HEAD"))
        val graph = host.snapshot().graph
        assertEquals(listOf(StageKind.SDD), graph.stages.map { it.kind })
        assertEquals(StageState.ACCEPTED, graph.stages.single().state)
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


    /**
     * Exact physical sandbox/receipts + graph supersession: the failed source
     * postimage must be removed before a fresh Developer identity gets a lease.
     */
    @Test @Tag("e2e")
    fun failedDeveloperCanBeReconciledAndRetriedThroughRealLinuxHost() = runBlocking {
        val sandbox = Path.of("/usr/bin/bwrap")
        val ci = System.getenv("CI").equals("true", true) ||
            System.getenv("GITHUB_ACTIONS").equals("true", true)
        if (ci) assertTrue(Files.isExecutable(sandbox))
        else assumeTrue(Files.isExecutable(sandbox))
        // SDD/ATDD/TDD see the original "1", and pass. The first Developer
        // proposal writes "2" and fails the actual networkless grep check.
        // The fresh run writes "13" and passes the same immutable check policy.
        val (host, repo) = newHost(checkoutName = "developer-recovery-e2e",
            smoke = StageIsolatedCheck(Path.of("/usr/bin/grep"),
                listOf("-q", "1", "/workspace/src/main/FeatureFull.kt")))
        val original = OfflineFiveRoleModel()
        var developerCalls = 0
        val roles = object : StageRoleExecutor {
            override suspend fun execute(request: StageRoleRequest,
                events: StageRoleEventSink): StageRoleResult {
                if (request.expectedRole != EngineeringRole.DEVELOPER)
                    return original.execute(request, events)
                developerCalls++
                val stage = request.graph.stages.single {
                    it.correlation.stageRunId == request.stageRunId
                }
                val value = if (developerCalls == 1) "2" else "13"
                val response = """{"files":[{"path":"src/main/FeatureFull.kt","content":"fun featureFull() = """ +
                    value + """\n"}]}"""
                return StageRoleResult(stage.correlation, EngineeringRole.DEVELOPER,
                    stage.agentId, StageRoleOutcome.Proposed(response),
                    StageRoleUsage(1, 100, 30))
            }
        }
        val preparer = RunPreparationStageUseCase(roles, host, host)
        val developer = RunDeveloperStageUseCase(roles, host, host)
        var snapshot = host.snapshot()
        for ((kind, next) in listOf(
            StageKind.SDD to StageKind.ATDD,
            StageKind.ATDD to StageKind.TDD,
            StageKind.TDD to StageKind.DEVELOP
        )) {
            val stage = snapshot.graph.stages.single {
                it.kind == kind && it.supersededBy == null
            }
            val target = targets.getValue(kind)
            val done = preparer.execute(PreparationStageCommand(
                snapshot.graph, snapshot.acceptedIds,
                stage.correlation.stageRunId, target.id, target.path,
                "Prepare " + kind, model, Instant.now().plusSeconds(90),
                Cancellation()), noopRole, noopStage)
            assertIs<PreparationStageOutcome.Accepted>(done.outcome,
                done.outcome.toString())
            snapshot = assertNotNull(host.advance(host.snapshot(), next))
        }
        val initial = snapshot.graph.stages.single {
            it.kind == StageKind.DEVELOP && it.supersededBy == null
        }
        suspend fun attempt(s: PreparationStageSnapshot, id: StageRunId):
            DeveloperStageResult = developer.execute(DeveloperStageCommand(
                s.graph, s.acceptedIds, id, targets.getValue(StageKind.DEVELOP).id,
                targets.getValue(StageKind.DEVELOP).path,
                listOf(DeveloperFileTarget("src/main/FeatureFull.kt", sha(oldSource))),
                "Implement host verification", model, Instant.now().plusSeconds(90),
                Cancellation()), noopRole, noopStage)

        val first = attempt(snapshot, initial.correlation.stageRunId)
        val failure = assertIs<DeveloperStageOutcome.Failed>(first.outcome,
            first.outcome.toString())
        assertEquals("CHECK_FAILED_OR_SANDBOX_UNAVAILABLE", failure.code)
        assertTrue(failure.workspaceMayBeDirty)
        assertEquals(newSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
        assertFailsWith<IllegalStateException> { host.snapshot() }
        val recovered = assertIs<StageGitFailedWriteResult.Recovered>(
            host.reconcileFailedDeveloper(initial.correlation.stageRunId))
        assertEquals(2, recovered.restoredPaths)
        assertEquals(recovered, host.lastDeveloperRecoveryAudit())
        assertFailsWith<IllegalStateException> { host.snapshot() }
        assertEquals(oldSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
        assertFalse(Files.exists(repo.resolve(
            targets.getValue(StageKind.DEVELOP).path)))
        val retryId = StageRunId("host-developer-retry")
        val next = assertNotNull(host.retryReconciledDeveloper(
            initial.correlation.stageRunId,
            initial.correlation.copy(stageRunId = retryId,
                requestId = "host-developer-retry-request",
                correlationId = "host-developer-retry-correlation"),
            AgentId("host-developer-retry-actor")))
        assertEquals(retryId, next.graph.stages.last().correlation.stageRunId)
        assertEquals(retryId, next.graph.stages.first {
            it.correlation.stageRunId == initial.correlation.stageRunId
        }.supersededBy)
        assertNull(host.retryReconciledDeveloper(initial.correlation.stageRunId,
            initial.correlation.copy(stageRunId = StageRunId("another-retry")),
            AgentId("another-retry-actor")))
        val second = attempt(next, retryId)
        assertIs<DeveloperStageOutcome.Accepted>(second.outcome, second.outcome.toString())
        assertEquals("fun featureFull() = 13\n",
            Files.readString(repo.resolve("src/main/FeatureFull.kt")))
        assertEquals(2, developerCalls)
        assertEquals(StageState.ACCEPTED, host.snapshot().graph.stages.last().state)
    }

    @Test @Tag("e2e")
    fun uncertainDeveloperLeaseCannotTriggerRollbackUntilAuthoritativeAbort() = runBlocking {
        val (host, repo) = newHost(checkoutName = "developer-uncertain-e2e")
        val preparer = RunPreparationStageUseCase(OfflineFiveRoleModel(), host, host)
        var snapshot = host.snapshot()
        for ((kind, next) in listOf(
            StageKind.SDD to StageKind.ATDD,
            StageKind.ATDD to StageKind.TDD,
            StageKind.TDD to StageKind.DEVELOP
        )) {
            val stage = snapshot.graph.stages.last()
            val target = targets.getValue(kind)
            assertIs<PreparationStageOutcome.Accepted>(
                preparer.execute(PreparationStageCommand(
                    snapshot.graph, snapshot.acceptedIds,
                    stage.correlation.stageRunId, target.id, target.path,
                    "Prepare", model, Instant.now().plusSeconds(90),
                    Cancellation()), noopRole, noopStage).outcome)
            snapshot = assertNotNull(host.advance(host.snapshot(), next))
        }
        val stage = snapshot.graph.stages.last()
        val id = stage.correlation.stageRunId
        val lease = assertNotNull(host.beginStage(snapshot.graph, id, snapshot.acceptedIds))
        val session = assertIs<StageToolOpenResult.Granted>(
            host.open(lease.authority)).session
        val output = targets.getValue(StageKind.DEVELOP)
        try {
            // A denied preimage is precise and does not consume the write session.
            val preimageDenial = session.write(listOf(
                StageWrite.Create("src/main/FeatureFull.kt", "unexpected\n")))
            assertEquals("CREATE_PREIMAGE_EXISTS", preimageDenial.deniedCode)
            val written = session.write(listOf(
                StageWrite.Edit("src/main/FeatureFull.kt", sha(oldSource), newSource),
                StageWrite.Create(output.path, "unaccepted\n")))
            assertTrue(written.permitted, written.deniedCode)
        } finally { session.close() }
        // A rejected/uncertain finish does not prove the host failed to accept.
        assertFalse(host.finishStage(lease, lease.graph, lease.acceptedIds))
        assertEquals("AUTHORITATIVE_ACCEPTANCE_UNCONFIRMED",
            assertIs<StageGitFailedWriteResult.NeedsHuman>(
                host.reconcileFailedDeveloper(id)).code)
        assertEquals(newSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
        assertNotNull(host.abortStage(lease, StageState.FAILED))
        // Simulate corrupted/replayed terminal generation. The actual terminal
        // lease, not the request's generation string, authorizes recovery.
        val generation = host.javaClass.getDeclaredField(
            "terminalFailedDeveloperLeaseGeneration")
        generation.isAccessible = true
        generation.set(host, "stale-lease-generation")
        assertEquals("STALE_DEVELOPER_LEASE_GENERATION",
            assertIs<StageGitFailedWriteResult.NeedsHuman>(
                host.reconcileFailedDeveloper(id)).code)
        assertEquals(newSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
        generation.set(host, lease.generation)
        assertIs<StageGitFailedWriteResult.Recovered>(host.reconcileFailedDeveloper(id))
        assertEquals(oldSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
    }

    @Test @Tag("e2e")
    fun fatalRecoveryAndConcurrentRetryCannotStrandFenceOrBlockHostMonitor() =
        runBlocking {
        val (host, repo) = newHost(checkoutName = "developer-retry-fences")
        val preparer = RunPreparationStageUseCase(OfflineFiveRoleModel(), host, host)
        var snapshot = host.snapshot()
        for ((kind, next) in listOf(
            StageKind.SDD to StageKind.ATDD,
            StageKind.ATDD to StageKind.TDD,
            StageKind.TDD to StageKind.DEVELOP
        )) {
            val stage = snapshot.graph.stages.last()
            val target = targets.getValue(kind)
            assertIs<PreparationStageOutcome.Accepted>(
                preparer.execute(PreparationStageCommand(
                    snapshot.graph, snapshot.acceptedIds,
                    stage.correlation.stageRunId, target.id, target.path,
                    "Prepare", model, Instant.now().plusSeconds(90),
                    Cancellation()), noopRole, noopStage).outcome)
            snapshot = assertNotNull(host.advance(host.snapshot(), next))
        }
        val stage = snapshot.graph.stages.last()
        val failedId = stage.correlation.stageRunId
        val lease = assertNotNull(host.beginStage(snapshot.graph, failedId, snapshot.acceptedIds))
        val tool = assertIs<StageToolOpenResult.Granted>(host.open(lease.authority)).session
        try {
            val write = tool.write(listOf(
                StageWrite.Edit("src/main/FeatureFull.kt", sha(oldSource), newSource),
                StageWrite.Create(targets.getValue(StageKind.DEVELOP).path, "unaccepted\n")))
            assertTrue(write.permitted, write.deniedCode)
        } finally { tool.close() }
        assertNotNull(host.abortStage(lease, StageState.FAILED))

        // A fatal Error after acquiring the in-progress fence must not leave
        // later recovery calls permanently at RECOVERY_IN_PROGRESS.
        host.developerRecoveryTestHook = { checkpoint ->
            if (checkpoint == DeveloperRecoveryTestCheckpoint.BEFORE_RECONCILE_IO)
                throw AssertionError("injected fatal recovery")
        }
        assertFailsWith<AssertionError> { host.reconcileFailedDeveloper(failedId) }
        assertFailsWith<IllegalStateException> { host.snapshot() }
        assertEquals(newSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
        host.developerRecoveryTestHook = null
        assertIs<StageGitFailedWriteResult.Recovered>(
            host.reconcileFailedDeveloper(failedId))
        assertFailsWith<IllegalStateException> { host.snapshot() }

        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        host.developerRecoveryTestHook = { checkpoint ->
            if (checkpoint == DeveloperRecoveryTestCheckpoint.BEFORE_RETRY_IO) {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
            }
        }
        val nextId = StageRunId("developer-retry-fenced")
        val correlation = stage.correlation.copy(
            stageRunId = nextId, requestId = "developer-retry-fenced-request",
            correlationId = "developer-retry-fenced-correlation")
        val actor = AgentId("developer-retry-fenced-actor")
        val result = AtomicReference<PreparationStageSnapshot?>()
        val error = AtomicReference<Throwable?>()
        val worker = thread(name = "host-retry-preflight") {
            try {
                result.set(host.retryReconciledDeveloper(failedId, correlation, actor))
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }
        val probe = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // This call needs the host monitor. It must complete even while
            // the retry's disk work is paused; a duplicate retry is rejected.
            val observed = probe.submit<String?> {
                try {
                    host.snapshot()
                    "UNEXPECTED_SUCCESS"
                } catch (failure: IllegalStateException) {
                    failure.message
                }
            }
            assertEquals("WORKSPACE_RECONCILIATION_REQUIRED",
                observed.get(2, TimeUnit.SECONDS))
            assertNull(host.retryReconciledDeveloper(failedId, correlation, actor))
        } finally {
            proceed.countDown()
            worker.join(10_000)
            probe.shutdownNow()
            host.developerRecoveryTestHook = null
        }
        assertFalse(worker.isAlive)
        assertNull(error.get())
        val replaced = assertNotNull(result.get())
        assertEquals(nextId, replaced.graph.stages.last().correlation.stageRunId)
        assertEquals(nextId, host.snapshot().graph.stages.last().correlation.stageRunId)
        assertEquals(oldSource, Files.readString(repo.resolve("src/main/FeatureFull.kt")))
    }

}
