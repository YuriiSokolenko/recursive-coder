package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Typed stage tool paths; the test tier verifier classifies Path references as integration. */
private object Stage75PathValues {
    val root = java.nio.file.Path.of("/offline/unused")
    val executable = java.nio.file.Path.of("/bin/true")
}

/** Deterministic stateful host and fake role/tool adapters; no live model or disk access. */
@Tag("integration")
@PreparationHostConformance("ai.yuriilab.recursivecoder.RunPreparationStageIntegrationTest\$FakeHost")
class RunPreparationStageIntegrationTest : PreparationStageHostContract() {
    override fun createHostFixture(): PreparationHostFixture {
        val graph = initial()
        val host = FakeHost().apply { this.graph = graph }
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
            host.unavailableReadPath = if (state == PreparationReadState.UNAVAILABLE) path else null
            host.cancelledReadPath = if (state == PreparationReadState.HOST_CANCELLED) path else null
        }
        return PreparationHostFixture(host, graph, ids.getValue(StageKind.SDD), read,
            prepareOutput = { lease, output, content ->
                host.files[output] = bytes(content)
                fakePreparationEvidence(lease)
            }, setApproval = { host.decision = it })
    }

    private val graphId = GraphId("prep-75")
    private val ids = mapOf(
        StageKind.SDD to StageRunId("sdd-75"),
        StageKind.ATDD to StageRunId("atdd-75"),
        StageKind.TDD to StageRunId("tdd-75")
    )
    private val artifactIds = mapOf(
        StageKind.SDD to ArtifactId("requirements-75"),
        StageKind.ATDD to ArtifactId("acceptance-75"),
        StageKind.TDD to ArtifactId("unit-tests-75")
    )
    private val paths = mapOf(
        StageKind.SDD to "specs/feature-75.md",
        StageKind.ATDD to "tests/acceptance/feature-75.feature",
        StageKind.TDD to "src/test/kotlin/Feature75Test.kt"
    )
    private val proposals = mapOf(
        StageKind.SDD to "Requirement: users can enqueue a task.",
        StageKind.ATDD to "Scenario: enqueue succeeds given a valid task.",
        StageKind.TDD to "class Feature75Test { /* offline fixture */ }"
    )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun initial(): StageGraph {
        val stages = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD).map { kind ->
            StageNode(
                StageCorrelation("session-75", graphId, ids.getValue(kind),
                    "request-" + kind.name.lowercase(), "corr-" + kind.name.lowercase()),
                kind, StageState.CREATED, AgentId("actor-" + kind.name.lowercase()),
                dependencies = when (kind) {
                    StageKind.SDD -> emptySet()
                    StageKind.ATDD -> setOf(ids.getValue(StageKind.SDD))
                    else -> setOf(ids.getValue(StageKind.SDD), ids.getValue(StageKind.ATDD))
                }
            )
        }
        return StageGraph(GraphCorrelation("session-75", graphId, "workflow-75"),
            StageGraphState.RUNNING, stages, emptyList(), StageBudget(2_000, 10_000, 0, 0))
    }

    private fun bind(graph: StageGraph, kind: StageKind): StageGraph {
        val inputs = when (kind) {
            StageKind.SDD -> emptySet()
            StageKind.ATDD -> setOf(artifactIds.getValue(StageKind.SDD))
            StageKind.TDD -> setOf(artifactIds.getValue(StageKind.SDD),
                artifactIds.getValue(StageKind.ATDD))
            else -> error("not preparation")
        }
        return graph.copy(stages = graph.stages.map {
            if (it.kind == kind) it.copy(inputArtifacts = inputs) else it
        })
    }

    private inner class FakeHost : PreparationStageHost {
        val files = mutableMapOf<String, ByteArray>()
        var graph: StageGraph? = null
        var accepted: Set<ArtifactId> = emptySet()
        private var leaseGeneration: String? = null
        private var generation = 0
        private var pending: PreparationPendingApproval? = null
        var abortCalls = 0
        var invalidBeginLease = false
        var throwOnApprove = false
        var throwAfterBegin = false
        var throwAfterHumanBegin = false
        var throwAfterFinish = false
        var beforeCurrent: (() -> Unit)? = null
        var beforeRead: (() -> Unit)? = null
        var beforeReadPath: ((String) -> Unit)? = null
        var unavailableReadPath: String? = null
        var cancelledReadPath: String? = null
        var beforeApprove: (() -> Unit)? = null
        var beforeFinish: (() -> Unit)? = null
        @Volatile var activeLease: String? = null
        val approvals = mutableListOf<StageArtifactManifest>()
        var decision = PreparationApproval.ACCEPT
        private var leaseApproval: Pair<StageArtifactManifest, PreparationApproval>? = null
        var corruptOutput = false
        var revokeOnApprove = false
        var mutateOnApprove: String? = null
        var staleAuthority = false
        var missingGitCheck = false
        var missingIndependentCheck = false
        var authorityReads = 0

        @Synchronized fun plan(kind: StageKind) {
            assertNull(leaseGeneration)
            graph = bind(requireNotNull(graph), kind)
        }
        @Synchronized fun revoke() { leaseGeneration = null }
        override fun readBytes(path: String): ByteArray? {
            beforeRead?.invoke()
            beforeReadPath?.invoke(path)
            if (path == cancelledReadPath)
                throw kotlinx.coroutines.CancellationException("host read cancelled")
            if (path == unavailableReadPath) throw java.io.IOException("temporary host read failure")
            return files[path]?.clone()
        }

        private fun makeLease(stageId: StageRunId, running: StageGraph,
                              savedPending: PreparationPendingApproval? = null): PreparationStageLease {
            generation++
            val token = "host-lease-$generation"
            leaseGeneration = token
            activeLease = token
            leaseApproval = null
            authorityReads = 0
            val stage = running.stages.single { it.correlation.stageRunId == stageId }
            val requiredChecks = when {
                missingGitCheck -> setOf("offline_check")
                missingIndependentCheck -> setOf("git_diff_check")
                else -> setOf("git_diff_check", "offline_check")
            }
            val snapshot = StageToolHostSnapshot(
                running, stageId, stage.correlation.requestId, accepted,
                Path.of("/offline/unused"), "a".repeat(40), "dev",
                checks = mapOf("offline_check" to StageIsolatedCheck(Path.of("/bin/true"))),
                requiredChecks = requiredChecks
            )
            val source = StageToolAuthoritySource {
                synchronized(this) {
                    authorityReads++
                    if (leaseGeneration != token || graph != running ||
                        (staleAuthority && authorityReads > 3))
                        null else snapshot
                }
            }
            return PreparationStageLease(token, stageId,
                if (invalidBeginLease) running.copy(state = StageGraphState.FAILED) else running,
                accepted, source, savedPending)
        }
        @Synchronized override fun beginStage(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): PreparationStageLease? {
            if (graph == null) graph = expectedGraph
            if (leaseGeneration != null || graph != expectedGraph || accepted != expectedAcceptedIds)
                return null
            val current = graph ?: return null
            val stage = current.stages.singleOrNull {
                it.correlation.stageRunId == stageRunId
            } ?: return null
            val ready = if (stage.state == StageState.CREATED)
                current.copy(stages = current.stages.map {
                    if (it.correlation.stageRunId == stageRunId)
                        it.copy(state = StageState.READY) else it
                }) else current
            if (StageGraphPolicy.admitStage(ready, stageRunId, accepted) != StageGateDecision.Permit)
                return null
            val running = ready.copy(stages = ready.stages.map {
                if (it.correlation.stageRunId == stageRunId)
                    it.copy(state = StageState.RUNNING) else it
            })
            graph = running
            val issued = makeLease(stageRunId, running)
            if (throwAfterBegin) error("host CAS committed but reply lost")
            return issued
        }
        @Synchronized override fun beginHumanApproval(
            expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>
        ): PreparationStageLease? {
            if (leaseGeneration != null || graph != expectedGraph ||
                accepted != expectedAcceptedIds || pending == null) return null
            val stage = expectedGraph.stages.singleOrNull {
                it.correlation.stageRunId == stageRunId
            } ?: return null
            if (stage.state != StageState.RUNNING || stage.outputArtifacts.size != 1)
                return null
            val issued = makeLease(stageRunId, expectedGraph, pending)
            if (throwAfterHumanBegin) error("human lease issued but reply lost")
            return issued
        }
        @Synchronized override fun current(lease: PreparationStageLease): Boolean {
            beforeCurrent?.invoke()
            return leaseGeneration == lease.generation && graph == lease.graph &&
                accepted == lease.acceptedIds
        }
        @Synchronized override fun finishStage(
            lease: PreparationStageLease, updatedGraph: StageGraph,
            updatedAcceptedIds: Set<ArtifactId>,
            pending: PreparationPendingApproval?
        ): Boolean {
            beforeFinish?.invoke()
            if (!current(lease)) return false
            if (!PreparationStageFinishPolicy.permits(
                    lease, requireNotNull(graph), accepted,
                    updatedGraph, updatedAcceptedIds, pending,
                    leaseApproval?.second, leaseApproval?.first
                )) return false
            graph = updatedGraph
            accepted = updatedAcceptedIds.toSet()
            leaseGeneration = null
            activeLease = null
            leaseApproval = null
            this.pending = pending
            if (throwAfterFinish) error("host commit succeeded but reply lost")
            return true
        }
        @Synchronized override fun abortStage(
            lease: PreparationStageLease, terminal: StageState
        ): PreparationStageSnapshot? {
            if (leaseGeneration != lease.generation ||
                terminal !in setOf(StageState.FAILED, StageState.CANCELLED)) return null
            val authoritative = graph ?: return null
            val stage = authoritative.stages.singleOrNull {
                it.correlation.stageRunId == lease.stageRunId
            } ?: return null
            if (stage.state != StageState.RUNNING) return null
            val updated = authoritative.copy(stages = authoritative.stages.map {
                if (it.correlation.stageRunId == lease.stageRunId)
                    it.copy(state = terminal) else it
            })
            if (StageGraphPolicy.validate(updated) != StageGateDecision.Permit) return null
            graph = updated
            leaseGeneration = null
            activeLease = null
            leaseApproval = null
            abortCalls++
            pending = null
            return PreparationStageSnapshot(updated, accepted)
        }
        @Synchronized override fun approve(
            candidate: StageArtifactManifest, evidence: StageValidationEvidence,
            usage: StageRoleUsage
        ): PreparationApproval {
            beforeApprove?.invoke()
            if (throwOnApprove) error("Approval host failed")
            approvals += candidate
            assertTrue(evidence.successful)
            leaseApproval = candidate to decision
            if (mutateOnApprove != null)
                files[mutateOnApprove!!] = bytes("altered after approval")
            if (revokeOnApprove) revoke()
            return decision
        }
    }

    private inner class FakeRoles : StageRoleExecutor {
        val calls = mutableListOf<StageRoleRequest>()
        var outcome: StageRoleOutcome? = null
        var mismatch = false
        var onExecute: (() -> Unit)? = null
        override suspend fun execute(
            request: StageRoleRequest, events: StageRoleEventSink
        ): StageRoleResult {
            calls += request
            onExecute?.invoke()
            val stage = request.graph.stages.single {
                it.correlation.stageRunId == request.stageRunId
            }
            val answer = outcome ?: StageRoleOutcome.Proposed(proposals.getValue(stage.kind))
            return StageRoleResult(stage.correlation, request.expectedRole,
                if (mismatch) AgentId("wrong-actor") else stage.agentId,
                answer, StageRoleUsage(1, 22, 12))
        }
    }

    private inner class FakeTools(private val host: FakeHost) : StageToolSessionOpener {
        val roles = mutableListOf<EngineeringRole>()
        val writes = mutableListOf<String>()
        var closeCount = 0
        var denyOpen = false
        var denyWrite = false
        var failCheck = false
        var cancelDuringCheck: (() -> Unit)? = null
        override fun open(authority: StageToolAuthoritySource): StageToolOpenResult {
            if (denyOpen) return StageToolOpenResult.Denied("TOOL_OPEN_DENIED")
            val snap = authority.current() ?: return StageToolOpenResult.Denied("STALE_STAGE_AUTHORITY")
            val stage = snap.graph.stages.single {
                it.correlation.stageRunId == snap.stageRunId
            }
            roles += stage.kind.role
            return StageToolOpenResult.Granted(object : StageToolSession {
                override fun write(operations: List<StageWrite>): StageWriteEvidence {
                    if (denyWrite) return StageWriteEvidence(null, "WRITE_DENIED")
                    val op = operations.single() as? StageWrite.Create
                        ?: return StageWriteEvidence(null, "CREATE_ONLY")
                    val denied = ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                        stage.kind.role, WriteOperationKind.CREATE, op.path,
                        immutableArtifactPaths = snap.graph.artifacts
                            .filter { it.freeze == ArtifactFreeze.FROZEN }
                            .map { it.declaredPath }.toSet()
                    ))
                    if (denied is ToolAuthorizationDecision.Deny)
                        return StageWriteEvidence(null, denied.deniedCode)
                    if (op.path in host.files) return StageWriteEvidence(null, "CREATE_ONLY")
                    writes += op.path
                    host.files[op.path] = bytes(if (host.corruptOutput)
                        "changed by untrusted writer" else op.content)
                    return StageWriteEvidence("write-receipt")
                }
                override fun runCheck(checkId: String): StageCheckEvidence {
                    cancelDuringCheck?.invoke()
                    return if (failCheck) StageCheckEvidence(checkId, null,
                        deniedCode = "CHECK_FAILED")
                    else StageCheckEvidence(checkId, "receipt-$checkId", "baseline", "policy", "manifest")
                }
                override fun verify() = StageValidationEvidence(
                    stage.correlation, requireNotNull(stage.agentId),
                    snap.requiredChecks.sorted().map { check ->
                        StageCheckEvidence(check, "receipt-$check", "baseline", "policy", "manifest")
                    }, true
                )
                override fun close() { closeCount++ }
            })
        }
    }

    private val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
        "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000)

    private suspend fun run(
        host: FakeHost, roles: FakeRoles, tools: FakeTools, graph: StageGraph,
        kind: StageKind, accepted: Set<ArtifactId> = host.accepted,
        modify: (PreparationStageCommand) -> PreparationStageCommand = { it },
        events: MutableList<PreparationStageEvent> = mutableListOf()
    ): PreparationStageResult =
        RunPreparationStageUseCase(roles, tools, host).execute(modify(
            PreparationStageCommand(graph, accepted, ids.getValue(kind),
                artifactIds.getValue(kind), paths.getValue(kind), "Propose " + kind.name,
                model, Instant.now().plusSeconds(60), Cancellation())),
            StageRoleEventSink { }, PreparationStageEventSink { events += it })

    @Test fun threeSeparateStagesProduceFrozenHostAcceptedManifests() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles()
        val tools = FakeTools(host)
        val events = mutableListOf<PreparationStageEvent>()
        val sdd = run(host, roles, tools, initial(), StageKind.SDD, events = events)
        assertIs<PreparationStageOutcome.Accepted>(sdd.outcome)
        host.plan(StageKind.ATDD)
        val atdd = run(host, roles, tools, requireNotNull(host.graph),
            StageKind.ATDD, events = events)
        assertIs<PreparationStageOutcome.Accepted>(atdd.outcome)
        host.plan(StageKind.TDD)
        val tdd = run(host, roles, tools, requireNotNull(host.graph),
            StageKind.TDD, events = events)
        assertIs<PreparationStageOutcome.Accepted>(tdd.outcome)

        assertEquals(listOf(EngineeringRole.SDD, EngineeringRole.ATDD, EngineeringRole.TDD),
            tools.roles)
        assertEquals(paths.values.toList(), tools.writes)
        assertEquals(3, tools.closeCount)
        assertEquals(3, roles.calls.size)
        assertEquals(3, roles.calls.map { it.expectedRole }.toSet().size)
        assertEquals(3, roles.calls.map { it.graph.stages.single { s ->
            s.correlation.stageRunId == it.stageRunId }.agentId }.toSet().size)
        assertTrue(roles.calls.all { it.requestedTools.isEmpty() })
        assertTrue(roles.calls[1].instruction.contains(proposals.getValue(StageKind.SDD)))
        assertTrue(roles.calls[2].instruction.contains(proposals.getValue(StageKind.ATDD)))
        assertEquals(3, requireNotNull(tdd.graph).artifacts.size)
        assertEquals(artifactIds.values.toSet(), tdd.hostAcceptedArtifactIds)
        assertTrue(requireNotNull(tdd.graph).artifacts.all { it.approval == ArtifactApproval.ACCEPTED &&
            it.freeze == ArtifactFreeze.FROZEN &&
            it.sha256 == sha(host.files.getValue(it.declaredPath)) })
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(requireNotNull(tdd.graph)))
        assertEquals(3, events.count { it.state == StageState.ACCEPTED })
        assertEquals(3, events.count { it.state == StageState.RUNNING })
        assertTrue(events.filter { it.state == StageState.ACCEPTED }.all {
            it.approval == ArtifactApproval.ACCEPTED && it.sha256 != null
        })
    }

    @Test fun stageOrderAndAtomicBeginCannotBeBypassed() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles()
        val tools = FakeTools(host)
        val tdd = run(host, roles, tools, initial(), StageKind.TDD)
        assertEquals(PreparationStageOutcome.Denied("PREREQUISITE_STAGE_NOT_ACCEPTED"), tdd.outcome)
        assertEquals(initial(), tdd.graph) // denial never promotes CREATED to READY
        assertNull(host.graph)
        val first = requireNotNull(host.beginStage(initial(), ids.getValue(StageKind.SDD), emptySet()))
        assertNull(host.beginStage(initial(), ids.getValue(StageKind.SDD), emptySet()))
        val second = run(host, roles, tools, initial(), StageKind.SDD)
        assertEquals(PreparationStageOutcome.Denied("STAGE_ADMISSION_REVOKED"), second.outcome)
        assertTrue(roles.calls.isEmpty())
        assertTrue(tools.writes.isEmpty())
        assertTrue(host.current(first))
    }

    @Test fun exactDigestOfOriginalBytesRejectsCorruptionAndNormalization() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles()
        val tools = FakeTools(host)
        val sdd = run(host, roles, tools, initial(), StageKind.SDD)
        assertIs<PreparationStageOutcome.Accepted>(sdd.outcome)
        host.plan(StageKind.ATDD)
        val original = host.files.getValue(paths.getValue(StageKind.SDD))
        host.files[paths.getValue(StageKind.SDD)] = bytes(String(original) + "\r\n")
        val denied = run(host, roles, tools, requireNotNull(host.graph), StageKind.ATDD)
        assertEquals(PreparationStageOutcome.Denied("ARTIFACT_DIGEST_MISMATCH"), denied.outcome)
        assertEquals(1, roles.calls.size)
    }

    @Test fun deniedStageCrossRoleAndFrozenTestsHaveSpecificCodes() {
        for ((kind, path) in paths) {
            for (actor in EngineeringRole.entries) {
                val plain = ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    actor, WriteOperationKind.EDIT, path
                ))
                assertEquals(if (actor == kind.role) ToolAuthorizationDecision.Permit
                    else ToolAuthorizationDecision.Deny("ROLE_NOT_AUTHORIZED"), plain)
                val frozen = ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    actor, WriteOperationKind.EDIT, path,
                    immutableArtifactPaths = setOf(path)
                ))
                assertEquals(ToolAuthorizationDecision.Deny(
                    if (actor == kind.role) "APPROVED_ARTIFACT_IMMUTABLE"
                    else "ROLE_NOT_AUTHORIZED"), frozen)
            }
        }
    }

    @Test fun rejectionPendingAndHumanContinuationUseSeparateCas() = runBlocking {
        for (decision in listOf(PreparationApproval.REJECT, PreparationApproval.HUMAN_REVIEW_REQUIRED)) {
            val host = FakeHost().apply { this.decision = decision }
            val roles = FakeRoles()
            val tools = FakeTools(host)
            val result = run(host, roles, tools, initial(), StageKind.SDD)
            assertEquals(emptySet(), result.hostAcceptedArtifactIds)
            if (decision == PreparationApproval.REJECT) {
                assertEquals(PreparationStageOutcome.Denied("HOST_REJECTED"), result.outcome)
                assertEquals(StageState.REJECTED, requireNotNull(host.graph).stages.first().state)
            } else {
                assertIs<PreparationStageOutcome.AwaitingHuman>(result.outcome)
                assertEquals(ArtifactApproval.DRAFT, requireNotNull(host.graph).artifacts.single().approval)
                host.decision = PreparationApproval.ACCEPT
                val completion = RunPreparationStageUseCase(roles, tools, host)
                    .completeHumanApproval(requireNotNull(host.graph), host.accepted,
                        ids.getValue(StageKind.SDD), Instant.now().plusSeconds(60),
                        Cancellation(), PreparationStageEventSink { })
                assertIs<PreparationStageOutcome.Accepted>(completion.outcome)
                assertEquals(ArtifactFreeze.FROZEN, requireNotNull(completion.graph).artifacts.single().freeze)
                assertEquals(1, roles.calls.size)
                assertEquals(1, tools.writes.size)
                val again = RunPreparationStageUseCase(roles, tools, host)
                    .completeHumanApproval(requireNotNull(result.graph), emptySet(),
                        ids.getValue(StageKind.SDD), Instant.now().plusSeconds(60),
                        Cancellation(), PreparationStageEventSink { })
                assertEquals(PreparationStageOutcome.Denied("STAGE_ADMISSION_REVOKED"), again.outcome)
            }
        }
    }

    @Test fun deterministicFailuresHaveExactCodesAndCloseSessions() = runBlocking {
        for ((scenario, expected) in mapOf(
            "corrupt" to "ARTIFACT_DIGEST_MISMATCH",
            "check" to "CHECK_FAILED",
            "stale" to "STALE_STAGE_AUTHORITY",
            "missing-git" to "STAGE_CHECKS_REQUIRED",
            "missing-independent" to "STAGE_CHECKS_REQUIRED",
            "write" to "WRITE_DENIED",
            "open" to "TOOL_OPEN_DENIED",
            "blank" to "MODEL_INVALID_RESPONSE",
            "oversize" to "MODEL_INVALID_RESPONSE",
            "identity" to "ROLE_RESULT_IDENTITY_MISMATCH"
        )) {
            val host = FakeHost().apply {
                corruptOutput = scenario == "corrupt"
                staleAuthority = scenario == "stale"
                missingGitCheck = scenario == "missing-git"
                missingIndependentCheck = scenario == "missing-independent"
            }
            val roles = FakeRoles().apply {
                mismatch = scenario == "identity"
                if (scenario == "blank") outcome = StageRoleOutcome.Proposed(" ")
                if (scenario == "oversize") outcome = StageRoleOutcome.Proposed(
                    "x".repeat(StageRoleLimits.MAX_PROPOSAL_BYTES + 1))
            }
            val tools = FakeTools(host).apply {
                failCheck = scenario == "check"
                denyWrite = scenario == "write"
                denyOpen = scenario == "open"
            }
            val result = run(host, roles, tools, initial(), StageKind.SDD)
            assertEquals(PreparationStageOutcome.Failed(expected), result.outcome, scenario)
            assertEquals(StageState.FAILED, requireNotNull(host.graph).stages.first().state, scenario)
            assertTrue(host.accepted.isEmpty(), scenario)
            assertTrue(host.approvals.isEmpty(), scenario)
            assertEquals(if (scenario in setOf("corrupt", "check", "stale", "write")) 1 else
                if (scenario == "open") 0 else
                if (scenario in setOf("missing-git", "missing-independent", "blank",
                        "oversize", "identity")) 0 else 1, tools.closeCount, scenario)
        }
    }

    @Test fun cancellationTimeoutAndModelFailuresCommitTerminalStates() = runBlocking {
        for ((outcome, state, code) in listOf(
            Triple(StageRoleOutcome.Denied("ROLE_DENIED"), StageState.FAILED, "ROLE_DENIED"),
            Triple(StageRoleOutcome.Failed("PROVIDER_DOWN"), StageState.FAILED, "PROVIDER_DOWN"),
            Triple(StageRoleOutcome.Cancelled, StageState.CANCELLED, "ROLE_CANCELLED"),
            Triple(StageRoleOutcome.TimedOut, StageState.CANCELLED, "ROLE_TIMED_OUT")
        )) {
            val host = FakeHost()
            val roles = FakeRoles().apply { this.outcome = outcome }
            val result = run(host, roles, FakeTools(host), initial(), StageKind.SDD)
            assertEquals(state, requireNotNull(host.graph).stages.first().state)
            assertEquals(if (outcome is StageRoleOutcome.Denied)
                PreparationStageOutcome.Denied(code) else
                PreparationStageOutcome.Failed(code), result.outcome)
        }
        val host = FakeHost()
        val cancellation = Cancellation()
        val tools = FakeTools(host).apply { cancelDuringCheck = { cancellation.cancel() } }
        val result = run(host, FakeRoles(), tools, initial(), StageKind.SDD,
            modify = { it.copy(cancellation = cancellation) })
        assertEquals(PreparationStageOutcome.Failed("ROLE_CANCELLED"), result.outcome)
        assertEquals(StageState.CANCELLED, requireNotNull(host.graph).stages.first().state)
        assertEquals(1, tools.closeCount)
    }

    @Test fun postApprovalMutationsAreRefusedAndNeverFrozen() = runBlocking {
        for (change in listOf("output", "authority", "input")) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            var graph = initial()
            if (change == "input") {
                val sdd = run(host, roles, tools, graph, StageKind.SDD)
                assertIs<PreparationStageOutcome.Accepted>(sdd.outcome)
                host.plan(StageKind.ATDD)
                graph = requireNotNull(host.graph)
                host.mutateOnApprove = paths.getValue(StageKind.SDD)
            } else if (change == "output") {
                host.mutateOnApprove = paths.getValue(StageKind.SDD)
            } else {
                host.revokeOnApprove = true
            }
            val result = run(host, roles, tools, graph,
                if (change == "input") StageKind.ATDD else StageKind.SDD)
            assertEquals(if (change == "authority")
                PreparationStageOutcome.ReconciliationRequired("STALE_STAGE_LEASE")
                else PreparationStageOutcome.Failed("STALE_ARTIFACT_APPROVAL"),
                result.outcome, change)
            if (change == "authority") {
                assertNull(result.graph)
                assertNull(result.hostAcceptedArtifactIds)
            }
            if (change != "authority") assertFalse(
                requireNotNull(result.hostAcceptedArtifactIds).contains(
                    artifactIds.getValue(if (change == "input") StageKind.ATDD else StageKind.SDD)))
        }
    }


    @Test fun eventSinkFailuresNeverChangeCommittedHostResult() = runBlocking {
        for (failAt in listOf(StageState.RUNNING, StageState.ACCEPTED)) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            val result = RunPreparationStageUseCase(roles, tools, host).execute(
                PreparationStageCommand(initial(), emptySet(), ids.getValue(StageKind.SDD),
                    artifactIds.getValue(StageKind.SDD), paths.getValue(StageKind.SDD),
                    "Generate spec", model, Instant.now().plusSeconds(30), Cancellation()),
                StageRoleEventSink { },
                PreparationStageEventSink {
                    if (it.state == failAt) throw IllegalStateException("failed event delivery")
                }
            )
            assertIs<PreparationStageOutcome.Accepted>(result.outcome)
            assertEquals(StageState.ACCEPTED, requireNotNull(host.graph).stages.first().state)
            assertEquals(requireNotNull(host.graph), result.graph)
            assertEquals(host.accepted, result.hostAcceptedArtifactIds)
            assertEquals(1, tools.closeCount)
        }
    }

    @Test fun invalidLeaseIsAbortedInsteadOfStrandingRunningStage() = runBlocking {
        val host = FakeHost().apply { invalidBeginLease = true }
        val roles = FakeRoles()
        val result = run(host, roles, FakeTools(host), initial(), StageKind.SDD)
        assertEquals(PreparationStageOutcome.Failed("INVALID_STAGE_LEASE"), result.outcome)
        assertEquals(StageState.FAILED, requireNotNull(host.graph).stages.first().state)
        assertEquals(1, host.abortCalls)
        assertTrue(roles.calls.isEmpty())
        assertNull(host.beginStage(initial(), ids.getValue(StageKind.SDD), emptySet()))
    }

    @Test fun modelCancellationExceptionAbortsAndRethrows() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles().apply {
            onExecute = { throw kotlinx.coroutines.CancellationException("caller cancelled") }
        }
        val tools = FakeTools(host)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            run(host, roles, tools, initial(), StageKind.SDD)
        }
        assertEquals(StageState.CANCELLED, requireNotNull(host.graph).stages.first().state)
    }

    @Test fun unreadableAndInvalidUtf8InputsFailBeforeModelInvocation() = runBlocking {
        for ((scenario, code) in listOf(
            "missing" to "ARTIFACT_MISSING",
            "invalid-utf8" to "ARTIFACT_ENCODING_INVALID"
        )) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            val first = run(host, roles, tools, initial(), StageKind.SDD)
            assertIs<PreparationStageOutcome.Accepted>(first.outcome)
            host.plan(StageKind.ATDD)
            val path = paths.getValue(StageKind.SDD)
            if (scenario == "missing") {
                host.files.remove(path)
            } else {
                val malformed = byteArrayOf(0xc3.toByte(), 0x28)
                host.files[path] = malformed
                // The bytes are host-pinned but cannot be decoded as UTF-8.
                host.graph = requireNotNull(host.graph).copy(
                    artifacts = requireNotNull(host.graph).artifacts.map { manifest ->
                        if (manifest.declaredPath == path)
                            manifest.copy(sha256 = sha(malformed)) else manifest
                    }
                )
            }
            val result = run(host, roles, tools, requireNotNull(host.graph), StageKind.ATDD)
            assertEquals(PreparationStageOutcome.Denied(code), result.outcome)
            assertEquals(1, roles.calls.size)
        }
    }

    @Test fun frozenInputReadFailureBeforeAdmissionDoesNotClaimOrExecuteStage() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles()
        val tools = FakeTools(host)
        assertIs<PreparationStageOutcome.Accepted>(
            run(host, roles, tools, initial(), StageKind.SDD).outcome)
        host.plan(StageKind.ATDD)
        val graph = requireNotNull(host.graph)
        val accepted = host.accepted.toSet()
        val files = host.files.mapValues { it.value.clone() }
        host.unavailableReadPath = paths.getValue(StageKind.SDD)
        val events = mutableListOf<PreparationStageEvent>()
        val result = run(host, roles, tools, graph, StageKind.ATDD, events = events)
        assertEquals(PreparationStageOutcome.Denied("INPUT_READ_UNAVAILABLE"), result.outcome)
        assertEquals(graph, result.graph)
        assertEquals(graph, host.graph)
        assertEquals(accepted, host.accepted)
        assertEquals(accepted, result.hostAcceptedArtifactIds)
        assertNull(host.activeLease)
        assertEquals(1, roles.calls.size)
        assertEquals(1, tools.writes.size)
        assertEquals(1, host.approvals.size)
        assertTrue(events.isEmpty())
        assertEquals(files.keys, host.files.keys)
        files.forEach { (path, bytes) -> assertContentEquals(bytes, host.files[path]) }
        host.unavailableReadPath = null
        assertIs<PreparationStageOutcome.Accepted>(
            run(host, roles, tools, graph, StageKind.ATDD).outcome)
    }

    @Test fun frozenInputReadFailureBeforeOrAfterApprovalNeverAcceptsOutput() = runBlocking {
        for (afterApproval in listOf(false, true)) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            assertIs<PreparationStageOutcome.Accepted>(
                run(host, roles, tools, initial(), StageKind.SDD).outcome)
            host.plan(StageKind.ATDD)
            val before = requireNotNull(host.graph)
            val accepted = host.accepted.toSet()
            val inputPath = paths.getValue(StageKind.SDD)
            val frozen = requireNotNull(host.files[inputPath]).clone()
            val outputPath = paths.getValue(StageKind.ATDD)
            if (afterApproval) {
                host.beforeApprove = { host.unavailableReadPath = inputPath }
            } else {
                // The initial frozen-input read succeeds; after the first
                // proposal starts, this specific frozen path becomes unreadable.
                roles.onExecute = { host.unavailableReadPath = inputPath }
            }
            val events = mutableListOf<PreparationStageEvent>()
            val result = run(host, roles, tools, before, StageKind.ATDD, events = events)
            assertEquals(PreparationStageOutcome.Failed("INPUT_READ_UNAVAILABLE"),
                result.outcome, "afterApproval=$afterApproval")
            val persisted = requireNotNull(host.graph)
            assertEquals(result.graph, persisted)
            assertEquals(StageState.FAILED, persisted.stages.single {
                it.kind == StageKind.ATDD
            }.state)
            assertEquals(before.artifacts, persisted.artifacts)
            assertEquals(accepted, host.accepted)
            assertEquals(accepted, result.hostAcceptedArtifactIds)
            assertNull(host.activeLease)
            assertEquals(2, roles.calls.size)
            assertEquals(2, tools.writes.size)
            assertEquals(2, tools.closeCount)
            assertEquals(if (afterApproval) 2 else 1, host.approvals.size)
            assertEquals("INPUT_READ_UNAVAILABLE", events.last().code)
            assertEquals(StageState.FAILED, events.last().state)
            assertContentEquals(frozen, host.files[inputPath])
            // Physical output may exist from the trusted write, but there is
            // no accepted/frozen manifest or permission to repeat that effect.
            assertTrue(outputPath in host.files)
            assertFalse(artifactIds.getValue(StageKind.ATDD) in host.accepted)
            host.unavailableReadPath = null
            host.beforeApprove = null
            roles.onExecute = null
            val retry = run(host, roles, tools, persisted, StageKind.ATDD)
            assertEquals(PreparationStageOutcome.Denied("STAGE_NOT_READY"), retry.outcome)
            assertEquals(2, roles.calls.size)
            assertEquals(2, tools.writes.size)
            assertEquals(persisted, host.graph)
        }
    }

    @Test fun artifactReadCallerCancellationPropagatesAndOnlyActiveAttemptIsCancelled() = runBlocking {
        for ((readKind, occurrence) in listOf(
            "input" to 1, "input" to 2, "input" to 3,
            "output" to 1, "output" to 2
        )) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            assertIs<PreparationStageOutcome.Accepted>(
                run(host, roles, tools, initial(), StageKind.SDD).outcome)
            host.plan(StageKind.ATDD)
            val graph = requireNotNull(host.graph)
            val accepted = host.accepted.toSet()
            val path = paths.getValue(if (readKind == "input") StageKind.SDD else StageKind.ATDD)
            val preflight = readKind == "input" && occurrence == 1
            val at = "$readKind/$occurrence"
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var reads = 0
            host.beforeReadPath = { currentPath ->
                if (path == currentPath && ++reads == occurrence) {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val job = async(kotlinx.coroutines.Dispatchers.Default) {
                run(host, roles, tools, graph, StageKind.ATDD)
            }
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS), at)
                if (preflight) assertNull(host.activeLease, at)
                else assertNotNull(host.activeLease, at)
                job.cancel()
            } finally {
                release.countDown()
            }
            assertFailsWith<kotlinx.coroutines.CancellationException>(at) { job.await() }
            assertNull(host.activeLease, at)
            assertEquals(accepted, host.accepted, at)
            val persisted = requireNotNull(host.graph)
            assertEquals(graph.artifacts, persisted.artifacts, at)
            if (preflight) {
                assertEquals(graph, persisted, at)
                assertEquals(1, roles.calls.size)
                assertEquals(1, tools.writes.size)
            } else {
                assertEquals(StageState.CANCELLED, persisted.stages.single {
                    it.kind == StageKind.ATDD
                }.state, at)
                assertEquals(2, tools.closeCount)
            }
        }
    }

    @Test fun hostThrownReadCancellationOnActiveCallerIsInputUnavailable() = runBlocking {
        for (occurrence in 1..3) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            assertIs<PreparationStageOutcome.Accepted>(
                run(host, roles, tools, initial(), StageKind.SDD).outcome)
            host.plan(StageKind.ATDD)
            val graph = requireNotNull(host.graph)
            val accepted = host.accepted.toSet()
            val path = paths.getValue(StageKind.SDD)
            var reads = 0
            host.beforeReadPath = { currentPath ->
                if (path == currentPath && ++reads == occurrence)
                    host.cancelledReadPath = path
            }
            val result = run(host, roles, tools, graph, StageKind.ATDD)
            assertEquals(if (occurrence == 1)
                PreparationStageOutcome.Denied("INPUT_READ_UNAVAILABLE")
            else PreparationStageOutcome.Failed("INPUT_READ_UNAVAILABLE"), result.outcome)
            assertNull(host.activeLease)
            assertEquals(accepted, host.accepted)
            val persisted = requireNotNull(host.graph)
            assertEquals(graph.artifacts, persisted.artifacts)
            if (occurrence == 1) {
                assertEquals(graph, persisted)
                assertEquals(1, roles.calls.size)
                assertEquals(1, tools.writes.size)
            } else {
                assertEquals(StageState.FAILED, persisted.stages.single {
                    it.kind == StageKind.ATDD
                }.state)
                assertEquals(2, tools.closeCount)
            }
        }
    }

    @Test fun outputReadsDistinguishUnavailableFromMissingOrChangedBytes() = runBlocking {
        for (afterApproval in listOf(false, true)) {
            for (failure in listOf("io", "host-cancel", "missing", "changed")) {
                val host = FakeHost()
                val roles = FakeRoles()
                val tools = FakeTools(host)
                val graph = initial()
                val path = paths.getValue(StageKind.SDD)
                var reads = 0
                host.beforeReadPath = { currentPath ->
                    if (currentPath == path && ++reads == (if (afterApproval) 2 else 1)) {
                        when (failure) {
                            "io" -> host.unavailableReadPath = path
                            "host-cancel" -> host.cancelledReadPath = path
                            "missing" -> host.files.remove(path)
                            "changed" -> host.files[path] = bytes("changed")
                            else -> error("Unknown read scenario")
                        }
                    }
                }
                val code = when {
                    failure == "io" || failure == "host-cancel" -> "OUTPUT_READ_UNAVAILABLE"
                    afterApproval -> "STALE_ARTIFACT_APPROVAL"
                    failure == "missing" -> "ARTIFACT_MISSING"
                    else -> "ARTIFACT_DIGEST_MISMATCH"
                }
                val at = "$failure/afterApproval=$afterApproval"
                val events = mutableListOf<PreparationStageEvent>()
                val result = run(host, roles, tools, graph, StageKind.SDD, events = events)
                assertEquals(PreparationStageOutcome.Failed(code), result.outcome, at)
                val persisted = requireNotNull(host.graph)
                assertEquals(StageState.FAILED, persisted.stages.first().state, at)
                assertEquals(graph.artifacts, persisted.artifacts, at)
                assertTrue(host.accepted.isEmpty(), at)
                assertEquals(host.accepted, result.hostAcceptedArtifactIds, at)
                assertEquals(persisted, result.graph, at)
                assertNull(host.activeLease, at)
                assertEquals(1, roles.calls.size, at)
                assertEquals(1, tools.writes.size, at)
                assertEquals(1, tools.closeCount, at)
                assertEquals(if (afterApproval) 1 else 0, host.approvals.size, at)
                assertEquals(code, events.last().code, at)
                assertEquals(StageState.FAILED, events.last().state, at)
            }
        }
    }

    @Test fun outputReadUnavailableDoesNotDowngradeRevokedApprovalAuthority() = runBlocking {
        val host = FakeHost()
        val path = paths.getValue(StageKind.SDD)
        var reads = 0
        host.beforeReadPath = { currentPath ->
            if (currentPath == path && ++reads == 2) {
                host.revoke()
                host.unavailableReadPath = path
            }
        }
        val result = run(host, FakeRoles(), FakeTools(host), initial(), StageKind.SDD)
        assertEquals(PreparationStageOutcome.ReconciliationRequired("STALE_STAGE_LEASE"),
            result.outcome)
        assertNull(result.graph)
        assertNull(result.hostAcceptedArtifactIds)
        assertTrue(host.accepted.isEmpty())
        assertTrue(requireNotNull(host.graph).artifacts.isEmpty())
    }

    @Test fun failedFrozenReadDoesNotMaskUnconfirmedHostFinish() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles()
        val tools = FakeTools(host)
        assertIs<PreparationStageOutcome.Accepted>(
            run(host, roles, tools, initial(), StageKind.SDD).outcome)
        host.plan(StageKind.ATDD)
        val graph = requireNotNull(host.graph)
        val accepted = host.accepted.toSet()
        host.beforeApprove = {
            host.unavailableReadPath = paths.getValue(StageKind.SDD)
        }
        host.throwAfterFinish = true
        val result = run(host, roles, tools, graph, StageKind.ATDD)
        assertEquals(PreparationStageOutcome.ReconciliationRequired("STAGE_FINISH_UNCONFIRMED"),
            result.outcome)
        assertNull(result.graph)
        assertNull(result.hostAcceptedArtifactIds)
        assertEquals(accepted, host.accepted)
        assertEquals(graph.artifacts, requireNotNull(host.graph).artifacts)
        assertEquals(StageState.FAILED, requireNotNull(host.graph).stages.single {
            it.kind == StageKind.ATDD
        }.state)
        assertNull(host.activeLease)
    }

    @Test fun missingOrChangedFrozenInputAfterWriteRetainsDistinctDefiniteFailure() = runBlocking {
        for (missing in listOf(false, true)) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            assertIs<PreparationStageOutcome.Accepted>(
                run(host, roles, tools, initial(), StageKind.SDD).outcome)
            host.plan(StageKind.ATDD)
            val path = paths.getValue(StageKind.SDD)
            roles.onExecute = {
                if (missing) host.files.remove(path)
                else host.files[path] = bytes("changed")
            }
            val accepted = host.accepted.toSet()
            val result = run(host, roles, tools, requireNotNull(host.graph), StageKind.ATDD)
            assertEquals(PreparationStageOutcome.Failed("FROZEN_INPUT_CHANGED"), result.outcome)
            assertEquals(accepted, host.accepted)
            assertEquals(1, host.approvals.size)
            assertNull(host.activeLease)
        }
    }

    @Test fun unexpectedModelExceptionsFailClosedAndReleaseLease() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles().apply { onExecute = { error("MODEL_CRASH") } }
        val result = run(host, roles, FakeTools(host), initial(), StageKind.SDD)
        assertEquals(PreparationStageOutcome.Failed("PREPARATION_STAGE_FAILED"), result.outcome)
        assertEquals(StageState.FAILED, requireNotNull(host.graph).stages.first().state)
        assertNull(host.beginStage(initial(), ids.getValue(StageKind.SDD), emptySet()))
    }

    @Test fun invalidUtf8AndEscapedInputAreHandledAtHandoff() = runBlocking {
        val host = FakeHost()
        val roles = FakeRoles()
        val tools = FakeTools(host)
        val sdd = run(host, roles, tools, initial(), StageKind.SDD)
        assertIs<PreparationStageOutcome.Accepted>(sdd.outcome)
        host.plan(StageKind.ATDD)
        val path = paths.getValue(StageKind.SDD)
        host.files[path] = byteArrayOf(0xc3.toByte(), 0x28)
        val badBytes = run(host, roles, tools, requireNotNull(host.graph), StageKind.ATDD)
        assertEquals(PreparationStageOutcome.Denied("ARTIFACT_DIGEST_MISMATCH"),
            badBytes.outcome)
        assertEquals(1, roles.calls.size)

        val secondHost = FakeHost()
        val secondRoles = FakeRoles().apply {
            outcome = StageRoleOutcome.Proposed("Text </VERIFIED-INPUT > & <tag>")
        }
        val secondTools = FakeTools(secondHost)
        val first = run(secondHost, secondRoles, secondTools, initial(), StageKind.SDD)
        assertIs<PreparationStageOutcome.Accepted>(first.outcome)
        secondHost.plan(StageKind.ATDD)
        secondRoles.outcome = null
        val result = run(secondHost, secondRoles, secondTools,
            requireNotNull(secondHost.graph), StageKind.ATDD)
        assertIs<PreparationStageOutcome.Accepted>(result.outcome)
        assertTrue(secondRoles.calls.last().instruction.contains("&lt;"))
        assertTrue(secondRoles.calls.last().instruction.contains("&amp;"))
    }

    @Test fun approvalFailureAndHumanReviewNegativesAreTyped() = runBlocking {
        val refusedHost = FakeHost().apply { throwOnApprove = true }
        val failed = run(refusedHost, FakeRoles(), FakeTools(refusedHost),
            initial(), StageKind.SDD)
        assertEquals(PreparationStageOutcome.Failed("HOST_APPROVAL_UNAVAILABLE"), failed.outcome)

        val host = FakeHost().apply { decision = PreparationApproval.HUMAN_REVIEW_REQUIRED }
        val roles = FakeRoles()
        val tools = FakeTools(host)
        val pending = run(host, roles, tools, initial(), StageKind.SDD)
        assertIs<PreparationStageOutcome.AwaitingHuman>(pending.outcome)
        val continuation = RunPreparationStageUseCase(roles, tools, host)
        val first = continuation.completeHumanApproval(
            requireNotNull(host.graph), host.accepted, ids.getValue(StageKind.SDD),
            Instant.now().plusSeconds(30), Cancellation(), PreparationStageEventSink { }
        )
        assertIs<PreparationStageOutcome.AwaitingHuman>(first.outcome)
        assertEquals(StageState.RUNNING, requireNotNull(host.graph).stages.first().state)
        host.decision = PreparationApproval.REJECT
        val rejected = continuation.completeHumanApproval(
            requireNotNull(host.graph), host.accepted, ids.getValue(StageKind.SDD),
            Instant.now().plusSeconds(30), Cancellation(), PreparationStageEventSink { }
        )
        assertEquals(PreparationStageOutcome.Denied("HOST_REJECTED"), rejected.outcome)
        assertEquals(StageState.REJECTED, requireNotNull(host.graph).stages.first().state)

        val staleHost = FakeHost().apply { decision = PreparationApproval.HUMAN_REVIEW_REQUIRED }
        val stale = run(staleHost, FakeRoles(), FakeTools(staleHost), initial(), StageKind.SDD)
        assertIs<PreparationStageOutcome.AwaitingHuman>(stale.outcome)
        staleHost.files[paths.getValue(StageKind.SDD)] = bytes("tampered")
        val staleFinish = RunPreparationStageUseCase(FakeRoles(), FakeTools(staleHost), staleHost)
            .completeHumanApproval(requireNotNull(staleHost.graph), staleHost.accepted,
                ids.getValue(StageKind.SDD), Instant.now().plusSeconds(30),
                Cancellation(), PreparationStageEventSink { })
        assertEquals(PreparationStageOutcome.Failed("STALE_HUMAN_APPROVAL"), staleFinish.outcome)
        assertEquals(StageState.FAILED, requireNotNull(staleHost.graph).stages.first().state)
        assertNull(staleHost.activeLease)
    }


    @Test fun beginAndFinishExceptionsDemandHostReconciliation() = runBlocking {
        val unknownBegin = FakeHost().apply { throwAfterBegin = true }
        val first = run(unknownBegin, FakeRoles(), FakeTools(unknownBegin),
            initial(), StageKind.SDD)
        assertEquals(PreparationStageOutcome.ReconciliationRequired(
            "STAGE_BEGIN_UNCONFIRMED"), first.outcome)
        assertNull(first.graph)
        assertNull(first.hostAcceptedArtifactIds)
        assertEquals(StageState.RUNNING, requireNotNull(unknownBegin.graph).stages.first().state)
        assertNotNull(unknownBegin.activeLease)

        val unknownFinish = FakeHost().apply { throwAfterFinish = true }
        val second = run(unknownFinish, FakeRoles(), FakeTools(unknownFinish),
            initial(), StageKind.SDD)
        assertEquals(PreparationStageOutcome.ReconciliationRequired(
            "STAGE_FINISH_UNCONFIRMED"), second.outcome)
        assertNull(second.graph)
        assertNull(second.hostAcceptedArtifactIds)
        assertEquals(StageState.ACCEPTED, requireNotNull(unknownFinish.graph).stages.first().state)
        assertNull(unknownFinish.activeLease)

        val pendingHost = FakeHost().apply { decision = PreparationApproval.HUMAN_REVIEW_REQUIRED }
        assertIs<PreparationStageOutcome.AwaitingHuman>(
            run(pendingHost, FakeRoles(), FakeTools(pendingHost), initial(), StageKind.SDD).outcome)
        pendingHost.throwAfterHumanBegin = true
        val resumed = RunPreparationStageUseCase(FakeRoles(), FakeTools(pendingHost), pendingHost)
            .completeHumanApproval(requireNotNull(pendingHost.graph), pendingHost.accepted,
                ids.getValue(StageKind.SDD), Instant.now().plusSeconds(30),
                Cancellation(), PreparationStageEventSink { })
        assertEquals(PreparationStageOutcome.ReconciliationRequired(
            "HUMAN_APPROVAL_BEGIN_UNCONFIRMED"), resumed.outcome)
        assertNull(resumed.graph)
    }

    @Test fun humanApprovalTransientReadFailuresRetainDraftAndPermitRetry() = runBlocking {
        for (failure in listOf("draft-output", "frozen-input", "post-approval-read", "post-approval-input", "approval")) {
            for (hostCancellation in listOf(false, true)) {
            val host = FakeHost().apply { decision = PreparationApproval.HUMAN_REVIEW_REQUIRED }
            val roles = FakeRoles()
            val tools = FakeTools(host)
            val kind = if (failure in setOf("frozen-input", "post-approval-input")) StageKind.ATDD else StageKind.SDD
            if (kind == StageKind.ATDD) {
                host.decision = PreparationApproval.ACCEPT
                assertIs<PreparationStageOutcome.Accepted>(
                    run(host, roles, tools, initial(), StageKind.SDD).outcome)
                host.plan(StageKind.ATDD)
                host.decision = PreparationApproval.HUMAN_REVIEW_REQUIRED
            }
            assertIs<PreparationStageOutcome.AwaitingHuman>(
                run(host, roles, tools, host.graph ?: initial(), kind).outcome)
            val pendingGraph = requireNotNull(host.graph)
            val unreadable = if (kind == StageKind.ATDD)
                paths.getValue(StageKind.SDD) else paths.getValue(kind)
            host.decision = PreparationApproval.ACCEPT
            val injectReadFailure = {
                if (hostCancellation) host.cancelledReadPath = unreadable
                else host.unavailableReadPath = unreadable
            }
            when (failure) {
                "approval" -> host.beforeApprove = {
                    if (hostCancellation) throw kotlinx.coroutines.CancellationException("host approval cancelled")
                    else throw java.io.IOException("approval unavailable")
                }
                "post-approval-read", "post-approval-input" -> host.beforeApprove = injectReadFailure
                else -> injectReadFailure()
            }
            val continuation = RunPreparationStageUseCase(roles, tools, host)
            val failed = continuation.completeHumanApproval(pendingGraph, host.accepted,
                ids.getValue(kind), Instant.now().plusSeconds(30),
                Cancellation(), PreparationStageEventSink { })
            assertEquals(PreparationStageOutcome.Failed(
                if (failure == "approval") "HOST_APPROVAL_UNAVAILABLE"
                else "HUMAN_APPROVAL_READ_UNAVAILABLE"), failed.outcome, failure)
            assertEquals(pendingGraph, host.graph, failure)
            assertNull(host.activeLease, failure)
            assertEquals(StageState.RUNNING, requireNotNull(host.graph).stages.single {
                it.correlation.stageRunId == ids.getValue(kind)
            }.state)
            host.beforeApprove = null
            host.unavailableReadPath = null
            host.cancelledReadPath = null
            val accepted = continuation.completeHumanApproval(requireNotNull(host.graph),
                host.accepted, ids.getValue(kind), Instant.now().plusSeconds(30),
                Cancellation(), PreparationStageEventSink { })
            assertIs<PreparationStageOutcome.Accepted>(accepted.outcome, failure)
            assertNull(host.activeLease, failure)
            }
        }
    }

    @Test fun humanApprovalDeadlineReturnsResultNotCallerCancellation() = runBlocking {
        val host = FakeHost().apply { decision = PreparationApproval.HUMAN_REVIEW_REQUIRED }
        assertIs<PreparationStageOutcome.AwaitingHuman>(
            run(host, FakeRoles(), FakeTools(host), initial(), StageKind.SDD).outcome)
        val start = Instant.now()
        val observed = java.util.concurrent.atomic.AtomicReference(start)
        val clock = object : java.time.Clock() {
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant(): Instant = observed.get()
        }
        // The fake host deliberately returns after the modeled deadline,
        // without sleeping or relying on cold-runner startup latency.
        host.beforeApprove = { observed.set(start.plusSeconds(31)) }
        val result = RunPreparationStageUseCase(FakeRoles(), FakeTools(host), host,
            clock = clock).completeHumanApproval(requireNotNull(host.graph), host.accepted,
                ids.getValue(StageKind.SDD), start.plusSeconds(30),
                Cancellation(), PreparationStageEventSink { })
        assertEquals(PreparationStageOutcome.Failed(
            "HUMAN_APPROVAL_TIMED_OUT"), result.outcome)
        assertEquals(StageState.RUNNING, requireNotNull(host.graph).stages.first().state)
        assertNull(host.activeLease)
    }

    @Test fun realCoroutineCancellationAtHostCurrentReadAndApprovalClosesLease() = runBlocking {
        for (at in listOf("current", "output-read", "approve")) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val gate = {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            when (at) {
                "current" -> {
                    var reads = 0
                    host.beforeCurrent = {
                        reads++
                        if (reads == 2) gate()
                    }
                }
                "output-read" -> host.beforeRead = { gate() }
                "approve" -> host.beforeApprove = gate
            }
            val job = async(kotlinx.coroutines.Dispatchers.Default) {
                run(host, roles, tools, initial(), StageKind.SDD)
            }
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS), at)
                assertNotNull(host.activeLease, at)
                job.cancel()
            } finally {
                release.countDown()
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
            assertEquals(StageState.CANCELLED, requireNotNull(host.graph).stages.first().state, at)
            assertNull(host.activeLease, at)
        }
    }

    @Test fun cancellationDuringFinishNeverStrandsGeneration() = runBlocking {
        val host = FakeHost()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        host.beforeFinish = {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        val job = async(kotlinx.coroutines.Dispatchers.Default) {
            run(host, FakeRoles(), FakeTools(host), initial(), StageKind.SDD)
        }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
        // The CAS may already have linearized; its authoritative result wins.
        assertNull(host.activeLease)
        assertTrue(requireNotNull(host.graph).stages.first().state in
            setOf(StageState.CANCELLED, StageState.ACCEPTED))
    }

    @Test fun preflightDenialsAndInvalidProposalNeverStartWrites() = runBlocking {
        for ((scenario, code) in listOf(
            "blank-instruction" to "EMPTY_STAGE_INSTRUCTION",
            "duplicate-id" to "DUPLICATE_ARTIFACT_ID_OR_PATH",
            "duplicate-path" to "DUPLICATE_ARTIFACT_ID_OR_PATH",
            "large-input" to "STAGE_INPUT_TOO_LARGE"
        )) {
            val host = FakeHost()
            val roles = FakeRoles()
            val tools = FakeTools(host)
            val base = initial()
            val result = run(host, roles, tools, base, StageKind.SDD, modify = {
                when (scenario) {
                    "blank-instruction" -> it.copy(instruction = "")
                    "duplicate-id" -> it.copy(graph = base.copy(artifacts = listOf(
                        StageArtifactManifest(artifactIds.getValue(StageKind.SDD),
                            StageArtifactKind.REQUIREMENTS, paths.getValue(StageKind.SDD),
                            "a".repeat(64), EngineeringRole.SDD, ids.getValue(StageKind.SDD),
                            1, ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE))))
                    "duplicate-path" -> it.copy(graph = base.copy(artifacts = listOf(
                        StageArtifactManifest(ArtifactId("different-id"),
                            StageArtifactKind.REQUIREMENTS, paths.getValue(StageKind.SDD),
                            "a".repeat(64), EngineeringRole.SDD, ids.getValue(StageKind.SDD),
                            1, ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE))))
                    else -> it.copy(instruction = "x".repeat(
                        StageRoleLimits.MAX_INSTRUCTION_BYTES + 1))
                }
            })
            assertEquals(PreparationStageOutcome.Denied(code), result.outcome, scenario)
            assertTrue(roles.calls.isEmpty(), scenario)
            assertTrue(tools.writes.isEmpty(), scenario)
        }
    }
}

