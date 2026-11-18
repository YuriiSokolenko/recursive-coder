package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Deterministic FakeModel + trusted-host contract fixture. No external model or network. */
@Tag("integration")
@PreparationHostConformance("ai.yuriilab.recursivecoder.RunDeveloperStageIntegrationTest\$FakeHost")
class RunDeveloperStageIntegrationTest : PreparationStageHostContract() {
    private val graphId = GraphId("graph-76")
    private val devId = StageRunId("develop-76")
    private val stages = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD)
    private val stageIds = stages.associateWith { StageRunId(it.name.lowercase() + "-76") }
    private val artifactIds = stages.associateWith { ArtifactId(it.name.lowercase() + "-artifact-76") }
    private val paths = mapOf(
        StageKind.SDD to "specs/Feature.md",
        StageKind.ATDD to "tests/acceptance/Feature.feature",
        StageKind.TDD to "tests/unit/FeatureTest.kt"
    )
    private val texts = mapOf(
        StageKind.SDD to "Requirement: add one feature.\n",
        StageKind.ATDD to "Scenario: feature works.\n",
        StageKind.TDD to "fun testFeature() = Unit\n"
    )
    private val head = "a".repeat(40)
    private val source = "src/main/Feature.kt"
    private val extra = "src/main/Extra.kt"
    private val manifestPath = "src/main/ImplementationManifest.txt"
    private val oldSource = "fun feature() = 1\n"
    private val newSource = "fun feature() = 2\n"
    private val newExtra = "fun extra() = true\n"

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
    private fun sha(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }
    private fun sha(value: String) = sha(bytes(value))
    private fun escaped(value: String) =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun proposal(vararg files: Pair<String, String>): String =
        "{\"files\":[" + files.joinToString(",") { (path, body) ->
            "{\"path\":\"${escaped(path)}\",\"content\":\"${escaped(body)}\"}"
        } + "]}"

    private fun graph(): StageGraph {
        val acceptedNodes = stages.map { kind ->
            StageNode(StageCorrelation("session-76", graphId, stageIds.getValue(kind),
                "request-" + kind.name.lowercase(), "corr-" + kind.name.lowercase()),
                kind, StageState.ACCEPTED, AgentId("actor-" + kind.name.lowercase()),
                outputArtifacts = setOf(artifactIds.getValue(kind)))
        }
        val acceptedManifests = stages.map { kind ->
            StageArtifactManifest(artifactIds.getValue(kind),
                requireNotNull(kind.output), paths.getValue(kind), sha(texts.getValue(kind)),
                kind.role, stageIds.getValue(kind), 1L,
                ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
        }
        val developer = StageNode(StageCorrelation("session-76", graphId, devId,
            "request-develop", "corr-develop"),
            StageKind.DEVELOP, StageState.CREATED, AgentId("developer-76"),
            dependencies = stageIds.values.toSet(), inputArtifacts = artifactIds.values.toSet())
        return StageGraph(GraphCorrelation("session-76", graphId, "workflow-76"),
            StageGraphState.RUNNING, acceptedNodes + developer,
            acceptedManifests, StageBudget(10_000, 60_000, 0, 0))
    }

    private fun hostContractGraph(): StageGraph {
        val sdd = StageNode(StageCorrelation("session-76", graphId,
            StageRunId("contract-sdd"), "request-contract-sdd", "corr-contract-sdd"),
            StageKind.SDD, StageState.CREATED, AgentId("contract-actor-sdd"))
        val atdd = StageNode(StageCorrelation("session-76", graphId,
            StageRunId("contract-atdd"), "request-contract-atdd", "corr-contract-atdd"),
            StageKind.ATDD, StageState.CREATED, AgentId("contract-actor-atdd"),
            dependencies = setOf(sdd.correlation.stageRunId))
        return StageGraph(GraphCorrelation("session-76", graphId, "workflow-contract"),
            StageGraphState.RUNNING, listOf(sdd, atdd), emptyList(),
            StageBudget(10_000, 60_000, 0, 0))
    }

    override fun createHostFixture(): PreparationHostFixture {
        val graph = hostContractGraph()
        val host = FakeHost(graph, emptySet())
        val path = "specs/host-contract-read.md"
        val expected = byteArrayOf(0, 1, -1, 42)
        val read = PreparationReadFixture(path, expected) { state ->
            if (state == PreparationReadState.ABSENT) {
                host.files.remove(path)
                assertFalse(path in host.files)
            } else {
                host.files[path] = expected.copyOf()
                assertContentEquals(expected, host.files.getValue(path))
            }
            host.contractReadFailure = when (state) {
                PreparationReadState.UNAVAILABLE -> path to java.io.IOException("host I/O unavailable")
                PreparationReadState.HOST_CANCELLED -> path to java.util.concurrent.CancellationException("host cancelled")
                else -> null
            }
        }
        return PreparationHostFixture(host, graph, graph.stages.first().correlation.stageRunId, read,
            prepareOutput = { lease, output, content ->
                host.files[output] = bytes(content)
                fakePreparationEvidence(lease)
            }, setApproval = { host.decision = it })
    }

    private inner class FakeHost(
        initialGraph: StageGraph = graph(),
        initialAccepted: Set<ArtifactId> = artifactIds.values.toSet()
    ) : PreparationStageHost {
        val files = mutableMapOf<String, ByteArray>()
        var graph: StageGraph = initialGraph
        var accepted: Set<ArtifactId> = initialAccepted
        var generation = 0
        var active: String? = null
        var decision = PreparationApproval.ACCEPT
        var approval: Pair<StageArtifactManifest, PreparationApproval>? = null
        var mutateOnApproval: String? = null
        var baseline = head
        var revocations = 0
        var pending: PreparationPendingApproval? = null
        var throwAfterBegin = false
        var throwAfterFinish = false
        var refuseFinish = false
        var refuseAbort = false
        var invalidLease = false
        init {
            texts.forEach { (kind, value) -> files[paths.getValue(kind)] = bytes(value) }
            files[source] = bytes(oldSource)
        }
        var contractReadFailure: Pair<String, Exception>? = null
        var readFailureOnApprovalPath: String? = null
        var readFailureOnApproval: Exception = java.io.IOException("post-approval read unavailable")
        var beforeReadPath: ((String) -> Unit)? = null
        var approvalFailure: Exception? = null
        var beforeApprove: (() -> Unit)? = null
        var beforeCurrent: (() -> Unit)? = null
        var beforeAuthority: (() -> Unit)? = null
        var approvals = 0
        @Synchronized override fun readBytes(path: String): ByteArray? {
            beforeReadPath?.invoke(path)
            contractReadFailure?.let { (failedPath, failure) ->
                if (failedPath == path) throw failure
            }
            return files[path]?.clone()
        }
        @Synchronized override fun beginStage(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): PreparationStageLease? {
            if (active != null || graph != expectedGraph || accepted != expectedAcceptedIds)
                return null
            val ready = graph.copy(stages = graph.stages.map {
                if (it.correlation.stageRunId == stageRunId && it.state == StageState.CREATED)
                    it.copy(state = StageState.READY) else it
            })
            if (StageGraphPolicy.admitStage(ready, stageRunId, accepted) != StageGateDecision.Permit)
                return null
            graph = ready.copy(stages = ready.stages.map {
                if (it.correlation.stageRunId == stageRunId)
                    it.copy(state = StageState.RUNNING) else it
            })
            val lease = makeLease(stageRunId)
            if (throwAfterBegin) error("CAS committed; response lost")
            return lease
        }

        @Synchronized private fun makeLease(
            stageRunId: StageRunId, pendingApproval: PreparationPendingApproval? = null
        ): PreparationStageLease {
            val token = "developer-lease-${++generation}"
            active = token
            approval = null
            val stage = graph.stages.single { it.correlation.stageRunId == stageRunId }
            val snap = StageToolHostSnapshot(graph, stageRunId, stage.correlation.requestId,
                accepted, Path.of("/offline/fixture"), baseline, "dev",
                checks = mapOf("offline-test" to StageIsolatedCheck(Path.of("/usr/bin/true"))),
                requiredChecks = setOf("git_diff_check", "offline-test"))
            return PreparationStageLease(token, stageRunId,
                if (invalidLease) graph.copy(state = StageGraphState.FAILED) else graph,
                accepted, StageToolAuthoritySource {
                    synchronized(this) {
                        beforeAuthority?.invoke()
                        if (active == token) snap else null
                    }
                }, pendingApproval)
        }

        @Synchronized override fun beginHumanApproval(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): PreparationStageLease? {
            if (active != null || pending == null || graph != expectedGraph ||
                accepted != expectedAcceptedIds) return null
            val stage = graph.stages.singleOrNull {
                it.correlation.stageRunId == stageRunId
            } ?: return null
            if (stage.state != StageState.RUNNING || stage.outputArtifacts.size != 1)
                return null
            return makeLease(stageRunId, pending)
        }
        @Synchronized override fun current(lease: PreparationStageLease): Boolean {
            beforeCurrent?.invoke()
            return active == lease.generation && graph == lease.graph && accepted == lease.acceptedIds
        }
        @Synchronized override fun finishStage(
            lease: PreparationStageLease, updatedGraph: StageGraph,
            updatedAcceptedIds: Set<ArtifactId>, pending: PreparationPendingApproval?
        ): Boolean {
            if (refuseFinish || !current(lease) || !PreparationStageFinishPolicy.permits(
                    lease, graph, accepted, updatedGraph, updatedAcceptedIds,
                    pending, approval?.second, approval?.first)) return false
            graph = updatedGraph
            accepted = updatedAcceptedIds.toSet()
            active = null
            approval = null
            this.pending = pending
            revocations++
            if (throwAfterFinish) error("CAS committed; response lost")
            return true
        }
        @Synchronized override fun abortStage(
            lease: PreparationStageLease, terminal: StageState
        ): PreparationStageSnapshot? {
            if (refuseAbort || !current(lease) || terminal !in setOf(StageState.FAILED, StageState.CANCELLED))
                return null
            graph = graph.copy(stages = graph.stages.map {
                if (it.correlation.stageRunId == lease.stageRunId)
                    it.copy(state = terminal) else it
            })
            active = null
            pending = null
            revocations++
            return PreparationStageSnapshot(graph, accepted)
        }
        @Synchronized override fun approve(
            candidate: StageArtifactManifest, evidence: StageValidationEvidence,
            usage: StageRoleUsage
        ): PreparationApproval {
            approvals++
            beforeApprove?.invoke()
            approvalFailure?.let { throw it }
            approval = candidate to decision
            readFailureOnApprovalPath?.let {
                contractReadFailure = it to readFailureOnApproval
            }
            mutateOnApproval?.let { files[it] = bytes("tampered after validation") }
            return decision
        }
    }

    private inner class FakeModel : StageRoleExecutor {
        val calls = mutableListOf<StageRoleRequest>()
        var response = proposal(source to newSource, extra to newExtra)
        var onExecute: (() -> Unit)? = null
        var forgedTool = false
        var usage = StageRoleUsage(1, 150, 55)
        override suspend fun execute(
            request: StageRoleRequest, events: StageRoleEventSink
        ): StageRoleResult {
            calls += request
            onExecute?.invoke()
            val stage = request.graph.stages.single { it.correlation.stageRunId == devId }
            return StageRoleResult(stage.correlation, request.expectedRole, stage.agentId,
                if (forgedTool) StageRoleOutcome.Failed("MODEL_UNAUTHORIZED_TOOL_CALL")
                else StageRoleOutcome.Proposed(response),
                usage)
        }
    }

    private inner class FakeTools(private val host: FakeHost) : StageToolSessionOpener {
        var failCheck = false
        var tamperReceipt = false
        var cancelOnCheck: (() -> Unit)? = null
        var closeCount = 0
        var failAt: String? = null
        var closeFailure: Exception? = null
        private fun fail(boundary: String) {
            if (failAt == boundary) throw kotlinx.coroutines.CancellationException("host $boundary cancelled")
        }
        var writes = 0
        val requestedChecks = mutableListOf<String>()
        override fun open(authority: StageToolAuthoritySource): StageToolOpenResult {
            fail("open")
            val snapshot = authority.current() ?: return StageToolOpenResult.Denied("REVOKED")
            return StageToolOpenResult.Granted(object : StageToolSession {
                val checked = mutableListOf<StageCheckEvidence>()
                override fun write(operations: List<StageWrite>): StageWriteEvidence {
                    fail("write")
                    if (operations.size !in 1..8) return StageWriteEvidence(null, "INVALID_WRITE_BATCH")
                    val result = mutableListOf<Pair<String, ByteArray>>()
                    for (operation in operations) {
                        val kind = if (operation is StageWrite.Create) WriteOperationKind.CREATE
                            else WriteOperationKind.EDIT
                        val denial = ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                            EngineeringRole.DEVELOPER, kind, operation.path,
                            immutableArtifactPaths = snapshot.graph.artifacts.map {
                                it.declaredPath
                            }.toSet()
                        ))
                        if (denial is ToolAuthorizationDecision.Deny)
                            return StageWriteEvidence(null, denial.deniedCode)
                        val current = host.readBytes(operation.path)
                        val content = when (operation) {
                            is StageWrite.Create -> {
                                if (current != null) return StageWriteEvidence(null, "CREATE_CONFLICT")
                                operation.content
                            }
                            is StageWrite.Edit -> {
                                if (current == null || sha(current) != operation.expectedSha256)
                                    return StageWriteEvidence(null, "EDIT_PRECONDITION_FAILED")
                                operation.content
                            }
                            is StageWrite.Delete -> return StageWriteEvidence(null, "DELETE_DENIED")
                        }
                        result += operation.path to bytes(content)
                    }
                    result.forEach { (path, value) -> host.files[path] = value }
                    writes++
                    return StageWriteEvidence("write-76")
                }
                override fun runCheck(checkId: String): StageCheckEvidence {
                    fail("check")
                    requestedChecks += checkId
                    cancelOnCheck?.invoke()
                    if (failCheck) return StageCheckEvidence(checkId, null,
                        deniedCode = "CHECK_FAILED")
                    val evidence = StageCheckEvidence(checkId, "check-$checkId",
                        snapshot.baselineHead, "policy-76", "manifest-76")
                    checked += evidence
                    return evidence
                }
                override fun verify(): StageValidationEvidence {
                    fail("verify")
                    return StageValidationEvidence(snapshot.graph.stages.single {
                        it.correlation.stageRunId == devId
                    }.correlation, AgentId("developer-76"),
                        if (tamperReceipt) checked.mapIndexed { index, record ->
                            if (index == 0) record.copy(manifestHash = "forged") else record
                        } else checked.toList(), true)
                }
                override fun close() { closeCount++; closeFailure?.let { throw it }; fail("close") }
            })
        }
    }

    private val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
        "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000)

    private suspend fun execute(
        host: FakeHost, fake: FakeModel, tools: FakeTools,
        targets: List<DeveloperFileTarget> = listOf(
            DeveloperFileTarget(source, sha(oldSource)), DeveloperFileTarget(extra)
        ),
        accepted: Set<ArtifactId> = host.accepted,
        events: MutableList<PreparationStageEvent> = mutableListOf(),
        modify: (DeveloperStageCommand) -> DeveloperStageCommand = { it }
    ): DeveloperStageResult =
        RunDeveloperStageUseCase(fake, tools, host).execute(modify(DeveloperStageCommand(
            host.graph, accepted, devId, ArtifactId("implementation-76"), manifestPath,
            targets, "Implement the approved feature", model,
            Instant.now().plusSeconds(30), Cancellation())),
            StageRoleEventSink { }, PreparationStageEventSink { events += it })

    @Test fun successfulFakeModelImplementationRequiresChecksAndFreezesHandoff() = runBlocking {
        val host = FakeHost()
        val fake = FakeModel()
        val tools = FakeTools(host)
        val events = mutableListOf<PreparationStageEvent>()
        val result = execute(host, fake, tools, events = events)
        val outcome = assertIs<DeveloperStageOutcome.Accepted>(result.outcome)
        assertEquals(StageState.ACCEPTED, host.graph.stages.last().state)
        assertEquals(ArtifactApproval.ACCEPTED, outcome.manifest.approval)
        assertEquals(ArtifactFreeze.FROZEN, outcome.manifest.freeze)
        assertEquals(2, outcome.changedFiles.size)
        assertEquals(listOf(extra, source), outcome.changedFiles.map { it.path })
        assertNull(outcome.changedFiles.first().beforeSha256)
        assertEquals(sha(newExtra), outcome.changedFiles.first().afterSha256)
        assertEquals(sha(oldSource), outcome.changedFiles.last().beforeSha256)
        assertEquals(sha(newSource), outcome.changedFiles.last().afterSha256)
        assertEquals(newSource, String(host.files.getValue(source)))
        assertEquals(newExtra, String(host.files.getValue(extra)))
        assertEquals(sha(host.files.getValue(manifestPath)), outcome.manifest.sha256)
        assertEquals("write-76", outcome.writeToolId)
        assertEquals(setOf("git_diff_check", "offline-test"), tools.requestedChecks.toSet())
        assertTrue(outcome.validation.successful)
        assertTrue(outcome.validation.requiredChecks.all { it.passed })
        assertEquals(1, outcome.usage.modelRequests)
        assertEquals(host.accepted, result.hostAcceptedArtifactIds)
        assertTrue(ArtifactId("implementation-76") in host.accepted)
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(host.graph))
        assertEquals(listOf(StageState.RUNNING, StageState.ACCEPTED), events.map { it.state })
        assertEquals(1, tools.writes)
        assertEquals(1, tools.closeCount)
        assertEquals(1, host.revocations)
        assertEquals(1, fake.calls.size)
        assertEquals(EngineeringRole.DEVELOPER, fake.calls.single().expectedRole)
        assertTrue(fake.calls.single().requestedTools.isEmpty())
        for (kind in stages) assertEquals(bytes(texts.getValue(kind)).toList(),
            host.files.getValue(paths.getValue(kind)).toList())
    }

    @Test fun missingHostAcceptanceAndChangedFrozenTestsDenyBeforeModel() = runBlocking {
        val missing = FakeHost()
        val fake = FakeModel()
        val tools = FakeTools(missing)
        val refused = execute(missing, fake, tools,
            accepted = missing.accepted - artifactIds.getValue(StageKind.TDD))
        assertEquals(DeveloperStageOutcome.Denied("ARTIFACT_NOT_ACCEPTED"), refused.outcome)
        assertTrue(fake.calls.isEmpty())
        assertEquals(0, tools.writes)

        val changed = FakeHost()
        changed.files[paths.getValue(StageKind.ATDD)] = bytes("tampered acceptance test")
        val result = execute(changed, fake, FakeTools(changed))
        assertEquals(DeveloperStageOutcome.Denied("ARTIFACT_DIGEST_MISMATCH"), result.outcome)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun stalePreimageAndUnauthorizedTargetsNeverReachWriter() = runBlocking {
        val attacks = listOf("specs/Feature.md", "tests/acceptance/Feature.feature",
            "tests/unit/FeatureTest.kt", "src/test/Injected.kt",
            "src/main/FeatureTest.kt", "src/main/../src/test/Escape.kt")
        for (path in attacks) {
            val host = FakeHost()
            val model = FakeModel()
            val tools = FakeTools(host)
            val result = execute(host, model, tools, listOf(DeveloperFileTarget(path)))
            assertEquals(DeveloperStageOutcome.Denied("DEVELOPER_TARGET_NOT_AUTHORIZED"),
                result.outcome, path)
            assertTrue(model.calls.isEmpty())
            assertEquals(0, tools.writes)
        }
        val host = FakeHost()
        val fake = FakeModel()
        val tools = FakeTools(host)
        val stale = execute(host, fake, tools,
            listOf(DeveloperFileTarget(source, "f".repeat(64))))
        assertEquals(DeveloperStageOutcome.Denied("STALE_TARGET_BASELINE"), stale.outcome)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun modelCannotExpandPathsRenameDeleteOrSupplyPermissionFields() = runBlocking {
        val proposals = listOf(
            proposal(source to newSource, "src/test/Injected.kt" to "unauthorized"),
            "{\"files\":[{\"path\":\"$source\",\"content\":\"x\",\"operation\":\"rename\"}]}",
            "{\"files\":[{\"path\":\"$source\",\"content\":\"x\",\"toolId\":\"forged\"}]}",
            "{\"files\":[{\"path\":\"$source\",\"content\":\"x\"},{\"path\":\"$source\",\"content\":\"x\"}]}"
        )
        for (value in proposals) {
            val host = FakeHost()
            val fake = FakeModel().apply { response = value }
            val tools = FakeTools(host)
            val outcome = execute(host, fake, tools).outcome
            assertEquals(DeveloperStageOutcome.Failed("INVALID_DEVELOPER_PROPOSAL", false), outcome)
            assertEquals(0, tools.writes)
            assertFalse(ArtifactId("implementation-76") in host.accepted)
            assertEquals(oldSource, String(host.files.getValue(source)))
        }
    }

    @Test fun testFailureTamperedReceiptAndChangesAfterApprovalFailClosed() = runBlocking {
        for (mode in listOf("check", "receipt", "output", "frozen")) {
            val host = FakeHost()
            val fake = FakeModel()
            val tools = FakeTools(host)
            when (mode) {
                "check" -> tools.failCheck = true
                "receipt" -> tools.tamperReceipt = true
                "output" -> host.mutateOnApproval = source
                "frozen" -> host.mutateOnApproval = paths.getValue(StageKind.TDD)
            }
            val result = execute(host, fake, tools)
            val failure = assertIs<DeveloperStageOutcome.Failed>(result.outcome)
            assertEquals(when (mode) {
                "check" -> "CHECK_FAILED"
                "receipt" -> "STAGE_VALIDATION_FAILED"
                else -> "FROZEN_INPUT_OR_OUTPUT_CHANGED"
            }, failure.code, mode)
            assertTrue(failure.workspaceMayBeDirty, mode)
            assertEquals(StageState.FAILED, host.graph.stages.last().state)
            assertEquals(artifactIds.values.toSet(), host.accepted)
            assertEquals(1, tools.closeCount)
            assertFalse(ArtifactId("implementation-76") in host.accepted)
        }
    }

    @Test fun unavailableVerificationBytesFailClosedBeforeAndAfterApproval() = runBlocking {
        for (afterApproval in listOf(false, true)) {
            for (path in listOf(source, manifestPath, paths.getValue(StageKind.TDD))) {
                for (failure in listOf(java.io.IOException("read unavailable"),
                    kotlinx.coroutines.CancellationException("host read cancelled"))) {
                    val host = FakeHost()
                    val tools = FakeTools(host)
                    if (afterApproval) {
                        host.readFailureOnApprovalPath = path
                        host.readFailureOnApproval = failure
                    } else {
                        tools.cancelOnCheck = { host.contractReadFailure = path to failure }
                    }
                    val result = execute(host, FakeModel(), tools)
                    val code = if (path == paths.getValue(StageKind.TDD))
                        "INPUT_READ_UNAVAILABLE" else "OUTPUT_READ_UNAVAILABLE"
                    assertEquals(DeveloperStageOutcome.Failed(code, true),
                        result.outcome, "$path afterApproval=$afterApproval")
                    assertEquals(if (afterApproval) 1 else 0, host.approvals)
                    assertEquals(StageState.FAILED, host.graph.stages.last().state)
                    assertEquals(artifactIds.values.toSet(), host.accepted)
                    assertNull(host.active)
                    assertEquals(1, tools.closeCount)
                    assertNotNull(host.files[path], "The bytes were present, not missing")
                }
            }
        }
    }

    @Test fun preflightReadFailuresDoNotAdmitDeveloper() = runBlocking {
        for ((path, code) in listOf(source to "TARGET_READ_UNAVAILABLE",
            paths.getValue(StageKind.TDD) to "INPUT_READ_UNAVAILABLE")) {
            for (failure in listOf(java.io.IOException("unavailable"),
                kotlinx.coroutines.CancellationException("host cancelled"))) {
                val host = FakeHost()
                val original = host.graph
                val model = FakeModel()
                host.contractReadFailure = path to failure
                val result = execute(host, model, FakeTools(host))
                assertEquals(DeveloperStageOutcome.Denied(code), result.outcome)
                assertEquals(original, host.graph)
                assertEquals(0, host.generation)
                assertTrue(model.calls.isEmpty())
                assertNull(host.active)
            }
        }
    }

    @Test fun localToolAndRoleCancellationFailsWithoutCancellingCaller() = runBlocking {
        for (boundary in listOf("role", "open", "write", "check", "verify", "close")) {
            val host = FakeHost()
            val model = FakeModel()
            val tools = FakeTools(host)
            if (boundary == "role") model.onExecute = {
                throw kotlinx.coroutines.CancellationException("local model failure")
            } else tools.failAt = boundary
            val result = execute(host, model, tools)
            assertEquals(DeveloperStageOutcome.Failed("DEVELOPER_STAGE_FAILED",
                boundary !in setOf("role", "open")), result.outcome, boundary)
            assertEquals(StageState.FAILED, host.graph.stages.last().state)
            assertNull(host.active)
            assertEquals(if (boundary in setOf("role", "open")) 0 else 1, tools.closeCount)
        }
    }

    @Test fun developerAuthorityFailuresAfterModelAreTyped() = runBlocking {
        for (at in listOf("current", "snapshot")) {
            val host = FakeHost()
            val model = FakeModel().apply {
                onExecute = {
                    val failOnce: () -> Unit = {
                        host.beforeCurrent = null
                        host.beforeAuthority = null
                        throw kotlinx.coroutines.CancellationException("authority unavailable")
                    }
                    if (at == "current") host.beforeCurrent = failOnce
                    else host.beforeAuthority = failOnce
                }
            }
            val result = execute(host, model, FakeTools(host))
            assertEquals(DeveloperStageOutcome.Failed("STAGE_AUTHORITY_UNAVAILABLE", false),
                result.outcome, at)
            assertEquals(StageState.FAILED, host.graph.stages.last().state)
            assertNull(host.active)
        }
    }

    @Test fun confirmedChangeWinsOverUnavailableDeveloperReads() = runBlocking {
        for (reverse in listOf(false, true)) {
            val host = FakeHost()
            host.contractReadFailure = source to java.io.IOException("unavailable")
            host.files[extra] = bytes("unexpected")
            val targets = listOf(DeveloperFileTarget(source, sha(oldSource)), DeveloperFileTarget(extra))
            val result = execute(host, FakeModel(), FakeTools(host),
                targets = if (reverse) targets.reversed() else targets)
            assertEquals(DeveloperStageOutcome.Denied("CREATE_TARGET_EXISTS"), result.outcome)
            assertNull(host.active)
        }
        for (afterApproval in listOf(false, true)) {
            val host = FakeHost()
            val tools = FakeTools(host)
            val inject = {
                host.contractReadFailure = source to java.io.IOException("unavailable")
                host.files[paths.getValue(StageKind.TDD)] = bytes("changed")
            }
            if (afterApproval) host.beforeApprove = inject else tools.cancelOnCheck = inject
            val result = execute(host, FakeModel(), tools)
            assertEquals(DeveloperStageOutcome.Failed("FROZEN_INPUT_OR_OUTPUT_CHANGED", true),
                result.outcome)
            assertNull(host.active)
        }
    }

    @Test fun realCallerCancellationDuringApprovalOrToolCleanupPropagates() = runBlocking {
        for (at in listOf("approval", "check")) {
            val host = FakeHost()
            val tools = FakeTools(host)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val gate = {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            if (at == "approval") host.beforeApprove = gate
            else {
                tools.cancelOnCheck = gate
                tools.closeFailure = kotlinx.coroutines.CancellationException("close failed")
            }
            val job = async(kotlinx.coroutines.Dispatchers.Default) { execute(host, FakeModel(), tools) }
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS), at)
                job.cancel()
            } finally { release.countDown() }
            assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
            assertEquals(StageState.CANCELLED, host.graph.stages.last().state)
            assertNull(host.active)
            assertEquals(1, tools.closeCount)
        }
    }

    @Test fun hostLocalApprovalCancellationIsFailedAndDirty() = runBlocking {
        val host = FakeHost().apply {
            approvalFailure = kotlinx.coroutines.CancellationException("host approval cancelled")
        }
        val result = execute(host, FakeModel(), FakeTools(host))
        assertEquals(DeveloperStageOutcome.Failed("HOST_APPROVAL_UNAVAILABLE", true), result.outcome)
        assertEquals(StageState.FAILED, host.graph.stages.last().state)
        assertNull(host.active)
        assertEquals(artifactIds.values.toSet(), host.accepted)
    }

    @Test fun unavailableVerificationDoesNotMaskUnconfirmedFinish() = runBlocking {
        val host = FakeHost().apply {
            readFailureOnApprovalPath = source
            readFailureOnApproval = kotlinx.coroutines.CancellationException("host read cancelled")
            throwAfterFinish = true
        }
        val result = execute(host, FakeModel(), FakeTools(host))
        assertEquals(DeveloperStageOutcome.ReconciliationRequired("STAGE_FINISH_UNCONFIRMED", true),
            result.outcome)
        assertNull(result.graph)
        assertNull(result.hostAcceptedArtifactIds)
        assertNull(host.active)
    }

    @Test fun realCallerCancellationDuringDeveloperReadPropagates() = runBlocking {
        for (afterAdmission in listOf(false, true)) {
            val host = FakeHost()
            val tools = FakeTools(host)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val gate: (String) -> Unit = { path ->
                if (path == source) {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            if (afterAdmission) tools.cancelOnCheck = { host.beforeReadPath = gate }
            else host.beforeReadPath = gate
            val job = async(kotlinx.coroutines.Dispatchers.Default) {
                execute(host, FakeModel(), tools)
            }
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                job.cancel()
            } finally {
                release.countDown()
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
            assertNull(host.active)
            assertEquals(if (afterAdmission) StageState.CANCELLED else StageState.CREATED,
                host.graph.stages.last().state)
            assertEquals(artifactIds.values.toSet(), host.accepted)
        }
    }

    @Test fun cancellationAndUnauthorizedModelToolCannotClaimSuccess() = runBlocking {
        val host = FakeHost()
        val signal = Cancellation()
        val fake = FakeModel()
        val tools = FakeTools(host)
        tools.cancelOnCheck = { signal.cancel() }
        val result = RunDeveloperStageUseCase(fake, tools, host).execute(
            DeveloperStageCommand(host.graph, host.accepted, devId,
                ArtifactId("implementation-76"), manifestPath,
                listOf(DeveloperFileTarget(source, sha(oldSource)), DeveloperFileTarget(extra)),
                "Implement", model, Instant.now().plusSeconds(30), signal),
            StageRoleEventSink { }, PreparationStageEventSink { })
        assertEquals(DeveloperStageOutcome.Failed("ROLE_CANCELLED", true), result.outcome)
        assertEquals(StageState.CANCELLED, host.graph.stages.last().state)
        assertFalse(ArtifactId("implementation-76") in host.accepted)

        val anotherHost = FakeHost()
        val forged = FakeModel().apply { forgedTool = true }
        val forbidden = execute(anotherHost, forged, FakeTools(anotherHost))
        assertEquals(DeveloperStageOutcome.Failed("MODEL_UNAUTHORIZED_TOOL_CALL", false),
            forbidden.outcome)
        assertFalse(ArtifactId("implementation-76") in anotherHost.accepted)
    }

    @Test fun hostRejectionAndHumanApprovalRequiredNeverPublishAcceptance() = runBlocking {
        val host = FakeHost().apply { decision = PreparationApproval.REJECT }
        val result = execute(host, FakeModel(), FakeTools(host))
        assertEquals(DeveloperStageOutcome.Failed("HOST_REJECTED", true), result.outcome)
        assertEquals(StageState.REJECTED, host.graph.stages.last().state)
        assertFalse(ArtifactId("implementation-76") in host.accepted)

        val pending = FakeHost().apply { decision = PreparationApproval.HUMAN_REVIEW_REQUIRED }
        val awaiting = execute(pending, FakeModel(), FakeTools(pending))
        assertEquals(DeveloperStageOutcome.Failed("DEVELOPER_HUMAN_APPROVAL_REQUIRED", true),
            awaiting.outcome)
        assertFalse(ArtifactId("implementation-76") in pending.accepted)
    }

    @Test fun unconfirmedBeginFinishAndAbortNeverReturnTrustedSnapshots() = runBlocking {
        val begin = FakeHost().apply { throwAfterBegin = true }
        val beginResult = execute(begin, FakeModel(), FakeTools(begin))
        assertEquals(DeveloperStageOutcome.ReconciliationRequired("STAGE_BEGIN_UNCONFIRMED", false),
            beginResult.outcome)
        assertNull(beginResult.graph)
        assertNull(beginResult.hostAcceptedArtifactIds)
        assertNotNull(begin.active) // The CAS may already have committed.

        val deniedFinish = FakeHost().apply { refuseFinish = true }
        val deniedFinishResult = execute(deniedFinish, FakeModel(), FakeTools(deniedFinish))
        assertEquals(DeveloperStageOutcome.ReconciliationRequired("STALE_STAGE_LEASE", true),
            deniedFinishResult.outcome)
        assertNull(deniedFinishResult.graph)
        assertNull(deniedFinishResult.hostAcceptedArtifactIds)
        assertNotNull(deniedFinish.active)

        val lostResponse = FakeHost().apply { throwAfterFinish = true }
        val lostResult = execute(lostResponse, FakeModel(), FakeTools(lostResponse))
        assertEquals(DeveloperStageOutcome.ReconciliationRequired("STAGE_FINISH_UNCONFIRMED", true),
            lostResult.outcome)
        assertNull(lostResult.graph)
        assertNull(lostResult.hostAcceptedArtifactIds)
        assertEquals(StageState.ACCEPTED, lostResponse.graph.stages.last().state)
        // Even an actual committed host success is *unknown* to this caller.

        val cannotAbort = FakeHost().apply {
            invalidLease = true
            refuseAbort = true
        }
        val failedAbort = execute(cannotAbort, FakeModel(), FakeTools(cannotAbort))
        assertEquals(DeveloperStageOutcome.ReconciliationRequired("STAGE_ABORT_UNCONFIRMED", false),
            failedAbort.outcome)
        assertNull(failedAbort.graph)
        assertNull(failedAbort.hostAcceptedArtifactIds)
        assertNotNull(cannotAbort.active)
    }

    @Test fun developerRejectsAllInvalidTargetShapesWithoutEffects() = runBlocking {
        val invalidTargets = listOf(
            List(8) { DeveloperFileTarget("src/main/New$it.kt") },
            listOf(DeveloperFileTarget(source, sha(oldSource)),
                DeveloperFileTarget("src/main/feature.kt")),
            listOf(DeveloperFileTarget(manifestPath)),
            emptyList()
        )
        for (targets in invalidTargets) {
            val host = FakeHost()
            val fake = FakeModel()
            val tools = FakeTools(host)
            val result = execute(host, fake, tools, targets)
            assertEquals(DeveloperStageOutcome.Denied("INVALID_DEVELOPER_TARGETS"),
                result.outcome)
            assertEquals(0, tools.writes)
            assertTrue(fake.calls.isEmpty())
        }

        val prior = FakeHost()
        val existingArtifact = execute(prior, FakeModel(), FakeTools(prior),
            modify = { it.copy(manifestPath = paths.getValue(StageKind.SDD)) })
        assertEquals(DeveloperStageOutcome.Denied("INVALID_DEVELOPER_TARGETS"),
            existingArtifact.outcome)

        val duplicateArtifact = FakeHost()
        val duplicate = execute(duplicateArtifact, FakeModel(), FakeTools(duplicateArtifact),
            modify = { it.copy(artifactId = artifactIds.getValue(StageKind.TDD)) })
        assertEquals(DeveloperStageOutcome.Denied("INVALID_DEVELOPER_TARGETS"),
            duplicate.outcome)

        val createConflict = FakeHost().apply { files[extra] = bytes("already here") }
        val conflictModel = FakeModel()
        val conflictTools = FakeTools(createConflict)
        val conflict = execute(createConflict, conflictModel, conflictTools)
        assertEquals(DeveloperStageOutcome.Denied("CREATE_TARGET_EXISTS"), conflict.outcome)
        assertTrue(conflictModel.calls.isEmpty())
        assertEquals(0, conflictTools.writes)
    }

    @Test fun missingOrExcessiveModelUsageIsDeniedBeforeWrite() = runBlocking {
        val invalid = listOf(
            StageRoleUsage(2, 100, 30),
            StageRoleUsage(1, null, 10),
            StageRoleUsage(1, 10, null),
            StageRoleUsage(1, -1, 10),
            StageRoleUsage(1, 10, -1),
            StageRoleUsage(1, 10_001, 1),
            StageRoleUsage(1, 500, 513),
            StageRoleUsage(1, 9_999, 100)
        )
        for (usage in invalid) {
            val host = FakeHost()
            val fake = FakeModel().apply { this.usage = usage }
            val tools = FakeTools(host)
            val result = execute(host, fake, tools)
            assertEquals(DeveloperStageOutcome.Failed("INVALID_DEVELOPER_USAGE", false),
                result.outcome, usage.toString())
            assertEquals(0, tools.writes)
            assertEquals(StageState.FAILED, host.graph.stages.last().state)
        }
    }

    @Test fun timedOutDeveloperIsNeverApprovedBeforeOrAfterRoleInvocation() = runBlocking {
        val entry = FakeHost()
        val noModel = FakeModel()
        val before = execute(entry, noModel, FakeTools(entry),
            modify = { it.copy(deadline = Instant.now().minusSeconds(1)) })
        assertEquals(DeveloperStageOutcome.Denied("ROLE_TIMED_OUT"), before.outcome)
        assertTrue(noModel.calls.isEmpty())

        val host = FakeHost()
        val signal = Cancellation()
        val fake = FakeModel().apply { onExecute = { signal.expire() } }
        val after = execute(host, fake, FakeTools(host),
            modify = { it.copy(cancellation = signal) })
        assertEquals(DeveloperStageOutcome.Failed("ROLE_TIMED_OUT", false), after.outcome)
        assertEquals(StageState.CANCELLED, host.graph.stages.last().state)
        assertFalse(ArtifactId("implementation-76") in host.accepted)
    }


    @Test fun preflightDenialPriorityIsIndependentOfPathAndTargetOrder() = runBlocking {
        val targetOrder = listOf(DeveloperFileTarget(source, sha(oldSource)),
            DeveloperFileTarget(extra))
        for (targets in listOf(targetOrder, targetOrder.reversed())) {
            // Both targets are dirty. A stale edit is always preferred to a create conflict.
            val host = FakeHost().apply {
                files[source] = bytes(newSource)
                files[extra] = bytes(newExtra)
            }
            val model = FakeModel()
            val result = execute(host, model, FakeTools(host), targets = targets)
            assertEquals(DeveloperStageOutcome.Denied("STALE_TARGET_BASELINE"), result.outcome)
            assertTrue(model.calls.isEmpty())
            assertNull(host.active)
            assertEquals(0, host.generation)
        }

        val host = FakeHost().apply {
            files[paths.getValue(StageKind.ATDD)] = bytes("changed")
            files.remove(paths.getValue(StageKind.SDD))
            contractReadFailure = extra to java.io.IOException("unavailable target")
        }
        val result = execute(host, FakeModel(), FakeTools(host))
        assertEquals(DeveloperStageOutcome.Denied("ARTIFACT_MISSING"), result.outcome)
        assertNull(host.active)
        assertEquals(0, host.generation)
    }

    @Test fun developerPromptPreservesHostSelectedTargetOrder() = runBlocking {
        val targetOrder = listOf(DeveloperFileTarget(source, sha(oldSource)),
            DeveloperFileTarget(extra))
        for (targets in listOf(targetOrder, targetOrder.reversed())) {
            val host = FakeHost()
            val model = FakeModel()
            val result = execute(host, model, FakeTools(host), targets = targets)
            assertIs<DeveloperStageOutcome.Accepted>(result.outcome)
            val instruction = model.calls.single().instruction
            val first = instruction.indexOf("<target path=\"" + targets[0].path + "\"")
            val second = instruction.indexOf("<target path=\"" + targets[1].path + "\"")
            assertTrue(first >= 0 && second > first, "model target order changed")
        }
    }

    @Test fun hostLocalRoleCancellationRespectsDeveloperCommandStopSignals() = runBlocking {
        for ((stop, code) in listOf("deadline" to "ROLE_TIMED_OUT",
            "cancel" to "ROLE_CANCELLED")) {
            val host = FakeHost()
            val signal = Cancellation()
            val model = FakeModel().apply {
                onExecute = {
                    if (stop == "deadline") signal.expire() else signal.cancel()
                    throw kotlinx.coroutines.CancellationException("role adapter stopped")
                }
            }
            val tools = FakeTools(host)
            val result = execute(host, model, tools,
                modify = { it.copy(cancellation = signal) })
            assertEquals(DeveloperStageOutcome.Failed(code, false), result.outcome, stop)
            assertEquals(StageState.CANCELLED, host.graph.stages.last().state, stop)
            assertEquals(0, tools.writes)
            assertNull(host.active)
        }
    }

    @Test fun dirtyAfterFailedCheckRequiresHostReconciliationBeforeRetry() = runBlocking {
        val host = FakeHost()
        val tools = FakeTools(host).apply { failCheck = true }
        val result = execute(host, FakeModel(), tools)
        val failed = assertIs<DeveloperStageOutcome.Failed>(result.outcome)
        assertEquals("CHECK_FAILED", failed.code)
        assertTrue(failed.workspaceMayBeDirty)
        assertEquals(newSource, String(host.files.getValue(source)))
        assertEquals(newExtra, String(host.files.getValue(extra)))
        assertTrue(manifestPath in host.files)
        assertEquals(artifactIds.values.toSet(), host.accepted)

        // A new, independently planned attempt cannot reuse dirty preimages.
        val newAttempt = FakeHost()
        newAttempt.files.putAll(host.files)
        val retryModel = FakeModel()
        val refused = execute(newAttempt, retryModel, FakeTools(newAttempt))
        assertEquals(DeveloperStageOutcome.Denied("STALE_TARGET_BASELINE"), refused.outcome)
        assertTrue(retryModel.calls.isEmpty())
    }
}
