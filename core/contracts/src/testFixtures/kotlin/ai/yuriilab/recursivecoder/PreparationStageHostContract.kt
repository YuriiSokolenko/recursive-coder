package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Binary JVM class name of the exact concrete host exercised by this suite. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreparationHostConformance(val adapter: String)

enum class PreparationReadState { PRESENT, ABSENT, UNAVAILABLE, HOST_CANCELLED }

/** Configure the underlying fixture, not a wrapper around the port under test. */
data class PreparationReadFixture(
    val path: String,
    val expectedBytes: ByteArray,
    val configure: (PreparationReadState) -> Unit
)

/** Use this fixture from a real host adapter's tests, not from production code. */
data class PreparationHostFixture(
    val host: PreparationStageHost,
    val graph: StageGraph,
    val stageRunId: StageRunId,
    val read: PreparationReadFixture,
    /** Prepare actual host-owned evidence for this leased output. */
    val prepareOutput: (PreparationStageLease, String, String) -> StageValidationEvidence,
    /** Configure this fixture's trusted host approval policy for a single attempt. */
    val setApproval: (PreparationApproval) -> Unit
)

/** Only for the in-memory fake fixtures; physical adapters must obtain actual evidence. */
fun fakePreparationEvidence(lease: PreparationStageLease): StageValidationEvidence {
    val stage = lease.graph.stages.single { it.correlation.stageRunId == lease.stageRunId }
    val snapshot = assertNotNull(lease.authority.current())
    return StageValidationEvidence(
        stage.correlation, requireNotNull(stage.agentId),
        snapshot.requiredChecks.sorted().map { check ->
            StageCheckEvidence(check, "receipt-$check", "baseline", "policy", "manifest")
        }, true
    )
}

/**
 * Contract shared by in-memory and physical host implementations.
 * Every test constructs a fresh authoritative store and checks the exact
 * registered runtime adapter, not a substitute fake or a policy-only test.
 */
abstract class PreparationStageHostContract {
    protected abstract fun createHostFixture(): PreparationHostFixture

    private fun fixtureForHostContract(): PreparationHostFixture {
        val registration = assertNotNull(javaClass.getDeclaredAnnotation(
            PreparationHostConformance::class.java))
        val fixture = createHostFixture()
        assertEquals(registration.adapter, fixture.host.javaClass.name)
        return fixture
    }

    // Existing in-process Linux host explicitly refuses pending continuation.
    // No new adapter (including a durable one) can opt out of the full contract.
    private fun supportsPending(f: PreparationHostFixture) =
        f.host.javaClass.name != "ai.yuriilab.recursivecoder.LinuxFiveRoleWorkflowHost"

    @Test fun readReturnsExactPresentBytes() {
        val f = fixtureForHostContract()
        f.read.configure(PreparationReadState.PRESENT)
        assertTrue(f.read.expectedBytes.isNotEmpty())
        assertContentEquals(f.read.expectedBytes, assertNotNull(f.host.readBytes(f.read.path)))
    }

    @Test fun readReturnsNullForConfirmedAbsence() {
        val f = fixtureForHostContract()
        f.read.configure(PreparationReadState.PRESENT)
        assertContentEquals(f.read.expectedBytes, assertNotNull(f.host.readBytes(f.read.path)))
        f.read.configure(PreparationReadState.ABSENT)
        assertNull(f.host.readBytes(f.read.path))
        f.read.configure(PreparationReadState.PRESENT)
        assertContentEquals(f.read.expectedBytes, assertNotNull(f.host.readBytes(f.read.path)))
    }

    @Test fun unavailableReadThrowsInsteadOfReportingAbsence() {
        val f = fixtureForHostContract()
        f.read.configure(PreparationReadState.PRESENT)
        assertContentEquals(f.read.expectedBytes, assertNotNull(f.host.readBytes(f.read.path)))
        f.read.configure(PreparationReadState.UNAVAILABLE)
        val failure = assertFailsWith<Exception> { f.host.readBytes(f.read.path) }
        assertFalse(failure is CancellationException)
        f.read.configure(PreparationReadState.PRESENT)
        assertContentEquals(f.read.expectedBytes, assertNotNull(f.host.readBytes(f.read.path)))
    }

    @Test fun hostLocalCancellationThrowsInsteadOfReportingAbsence() {
        val f = fixtureForHostContract()
        f.read.configure(PreparationReadState.PRESENT)
        assertContentEquals(f.read.expectedBytes, assertNotNull(f.host.readBytes(f.read.path)))
        f.read.configure(PreparationReadState.HOST_CANCELLED)
        assertFailsWith<CancellationException> { f.host.readBytes(f.read.path) }
        f.read.configure(PreparationReadState.PRESENT)
        assertContentEquals(f.read.expectedBytes, assertNotNull(f.host.readBytes(f.read.path)))
    }

    private fun candidate(f: PreparationHostFixture, lease: PreparationStageLease,
                          name: String): Pair<StageArtifactManifest, StageValidationEvidence> {
        val stage = lease.graph.stages.single { it.correlation.stageRunId == f.stageRunId }
        val path = "specs/$name.md"
        val content = "Host contract output: $name\n"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val manifest = StageArtifactManifest(
            ArtifactId(name), requireNotNull(stage.kind.output), path, digest,
            stage.kind.role, f.stageRunId, 1L, ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE
        )
        val evidence = f.prepareOutput(lease, path, content)
        assertTrue(evidence.successful, evidence.deniedCode)
        assertEquals(stage.correlation, evidence.correlation)
        return manifest to evidence
    }

    private fun withOutput(lease: PreparationStageLease, manifest: StageArtifactManifest,
                           state: StageState): StageGraph = lease.graph.copy(
        stages = lease.graph.stages.map {
            if (it.correlation.stageRunId == lease.stageRunId)
                it.copy(state = state, outputArtifacts = setOf(manifest.id)) else it
        },
        artifacts = lease.graph.artifacts + manifest
    )

    @Test fun twoConcurrentBeginsHaveExactlyOneWinner() {
        val fixture = fixtureForHostContract()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..2).map {
                pool.submit(Callable {
                    start.await()
                    fixture.host.beginStage(fixture.graph, fixture.stageRunId, emptySet())
                })
            }
            start.countDown()
            val leases = tasks.map { it.get(10, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, leases.size)
            assertTrue(fixture.host.current(leases.single()))
            assertNotNull(leases.single().authority.current())
            assertNull(fixture.host.beginStage(fixture.graph, fixture.stageRunId, emptySet()))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun casRejectsMismatchedGraphAndAcceptedIds() {
        val f = fixtureForHostContract()
        val wrongGraph = f.graph.copy(state = StageGraphState.FAILED)
        assertNull(f.host.beginStage(wrongGraph, f.stageRunId, emptySet()))
        assertNull(f.host.beginStage(f.graph, f.stageRunId, setOf(ArtifactId("wrong-input"))))
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        assertTrue(f.host.current(lease))
    }

    @Test fun committedGenerationCannotBeReplayedAndRevokesAuthority() {
        val f = fixtureForHostContract()
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        val completed = lease.graph.copy(stages = lease.graph.stages.map {
            if (it.correlation.stageRunId == f.stageRunId)
                it.copy(state = StageState.FAILED) else it
        })
        assertTrue(f.host.finishStage(lease, completed, emptySet()))
        assertFalse(f.host.current(lease))
        assertNull(lease.authority.current())
        assertFalse(f.host.finishStage(lease, completed, emptySet()))
        assertNull(f.host.abortStage(lease, StageState.FAILED))
    }

    @Test fun abortOnlyAcceptsTerminalFailureAndConsumesGeneration() {
        for (terminal in listOf(StageState.FAILED, StageState.CANCELLED)) {
            val f = fixtureForHostContract()
            val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
            assertNull(f.host.abortStage(lease, StageState.ACCEPTED))
            assertNull(f.host.abortStage(lease, StageState.RUNNING))
            assertTrue(f.host.current(lease))
            val committed = assertNotNull(f.host.abortStage(lease, terminal))
            assertEquals(terminal, committed.graph.stages.single {
                it.correlation.stageRunId == f.stageRunId
            }.state)
            assertFalse(f.host.current(lease))
            assertNull(lease.authority.current())
            assertFalse(f.host.finishStage(lease, lease.graph, emptySet()))
            assertNull(f.host.abortStage(lease, StageState.CANCELLED))
        }
    }

    @Test fun finishRejectsInjectedAcceptedIdsAndChangesToOtherStages() {
        val f = fixtureForHostContract()
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        val completed = lease.graph.copy(stages = lease.graph.stages.map {
            if (it.correlation.stageRunId == f.stageRunId)
                it.copy(state = StageState.FAILED) else it
        })
        assertFalse(f.host.finishStage(lease, completed,
            setOf(ArtifactId("unauthorized-accepted"))))
        assertTrue(f.host.current(lease))

        val leased = completed.stages.single { it.correlation.stageRunId == f.stageRunId }
        val foreignStage = completed.stages.singleOrNull { it.kind == StageKind.ATDD }
            ?: StageNode(leased.correlation.copy(stageRunId = StageRunId("foreign-atdd"),
                requestId = "foreign-request", correlationId = "foreign-correlation"),
                StageKind.ATDD, StageState.CREATED, AgentId("foreign-actor"),
                dependencies = setOf(f.stageRunId))
        val withForeign = if (foreignStage in completed.stages) completed.stages
            else completed.stages + foreignStage
        val other = completed.copy(stages = withForeign.map {
            if (it.correlation.stageRunId != f.stageRunId)
                it.copy(state = StageState.CANCELLED) else it
        })
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(other))
        assertFalse(f.host.finishStage(lease, other, emptySet()))
        assertTrue(f.host.current(lease))

        // Even a globally valid foreign manifest remains outside the leased
        // stage's write scope. This proves the CAS checks more than validate().
        val foreignId = ArtifactId("foreign-acceptance-1")
        val foreign = StageArtifactManifest(
            foreignId, StageArtifactKind.ACCEPTANCE_TESTS,
            "tests/acceptance/foreign-acceptance-1.feature", "a".repeat(64),
            EngineeringRole.ATDD, foreignStage.correlation.stageRunId, 1L,
            ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE
        )
        val foreignGraph = completed.copy(
            stages = withForeign.map {
                if (it.correlation.stageRunId == foreignStage.correlation.stageRunId)
                    it.copy(outputArtifacts = setOf(foreignId)) else it
            },
            artifacts = completed.artifacts + foreign
        )
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(foreignGraph))
        assertFalse(f.host.finishStage(lease, foreignGraph, emptySet()))
        assertTrue(f.host.current(lease))

        assertFalse(f.host.finishStage(lease,
            completed.copy(budget = completed.budget.copy(maxTokens = 1)), emptySet()))
        assertTrue(f.host.current(lease))
        assertTrue(f.host.finishStage(lease, completed, emptySet()))
    }

    @Test fun hostApprovalForExactOutputIsMandatoryForFrozenAcceptance() {
        // A physical host may allow only one approval decision per generation.
        // Exercise each negative and positive case on a fresh real lease.
        for (scenario in listOf("missing", "rejected", "wrong-digest", "accepted")) {
            val f = fixtureForHostContract()
            val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
            val (draft, evidence) = candidate(f, lease, "host-contract-accepted-1")
            val usage = StageRoleUsage(1, 12, 6)
            val accepted = withOutput(lease, draft.copy(
                approval = ArtifactApproval.ACCEPTED, freeze = ArtifactFreeze.FROZEN
            ), StageState.ACCEPTED)
            val ids = setOf(draft.id)
            assertFalse(f.host.finishStage(lease, accepted, ids))
            assertTrue(f.host.current(lease))
            when (scenario) {
                "missing" -> Unit
                "rejected" -> {
                    f.setApproval(PreparationApproval.REJECT)
                    assertEquals(PreparationApproval.REJECT, f.host.approve(draft, evidence, usage))
                }
                "wrong-digest" -> {
                    f.setApproval(PreparationApproval.ACCEPT)
                    // A stricter host can reject this before consulting approval.
                    f.host.approve(draft.copy(sha256 = "b".repeat(64)), evidence, usage)
                }
                "accepted" -> {
                    f.setApproval(PreparationApproval.ACCEPT)
                    assertEquals(PreparationApproval.ACCEPT, f.host.approve(draft, evidence, usage))
                    assertFalse(f.host.finishStage(lease, accepted, emptySet()))
                    assertFalse(f.host.finishStage(lease, accepted, ids + ArtifactId("extra-accepted")))
                    assertTrue(f.host.current(lease))
                    assertTrue(f.host.finishStage(lease, accepted, ids))
                    assertFalse(f.host.current(lease))
                    assertNull(lease.authority.current())
                    continue
                }
            }
            assertFalse(f.host.finishStage(lease, accepted, ids))
            assertTrue(f.host.current(lease))
            assertNotNull(f.host.abortStage(lease, StageState.FAILED))
        }
    }

    @Test fun finishRequiresPendingEvidenceExactlyForRunningDrafts() {
        val f = fixtureForHostContract()
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        val (draft, evidence) = candidate(f, lease, "host-contract-pending-1")
        val pending = PreparationPendingApproval(evidence, StageRoleUsage(1, 12, 6))
        val draftGraph = withOutput(lease, draft, StageState.RUNNING)
        val failed = lease.graph.copy(stages = lease.graph.stages.map {
            if (it.correlation.stageRunId == f.stageRunId)
                it.copy(state = StageState.FAILED) else it
        })
        f.setApproval(PreparationApproval.HUMAN_REVIEW_REQUIRED)
        assertEquals(PreparationApproval.HUMAN_REVIEW_REQUIRED,
            f.host.approve(draft, evidence, pending.usage))
        assertFalse(f.host.finishStage(lease, draftGraph, emptySet()))
        assertFalse(f.host.finishStage(lease, failed, emptySet(), pending))
        assertTrue(f.host.current(lease))
        if (supportsPending(f)) {
            assertTrue(f.host.finishStage(lease, draftGraph, emptySet(), pending))
        } else {
            assertFalse(f.host.finishStage(lease, draftGraph, emptySet(), pending))
            assertTrue(f.host.current(lease))
            assertNotNull(f.host.abortStage(lease, StageState.FAILED))
            assertNull(lease.authority.current())
        }
    }

    @Test fun humanDraftRoundTripPersistsEvidenceAndFencesContinuation() {
        val f = fixtureForHostContract()
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        val (candidate, evidence) = candidate(f, lease, "host-contract-draft-1")
        val pending = PreparationPendingApproval(evidence, StageRoleUsage(1, 12, 6))
        val draftGraph = withOutput(lease, candidate, StageState.RUNNING)
        f.setApproval(PreparationApproval.HUMAN_REVIEW_REQUIRED)
        assertEquals(PreparationApproval.HUMAN_REVIEW_REQUIRED,
            f.host.approve(candidate, evidence, pending.usage))
        if (!supportsPending(f)) {
            assertFalse(f.host.finishStage(lease, draftGraph, emptySet(), pending))
            assertTrue(f.host.current(lease))
            assertNull(f.host.beginHumanApproval(draftGraph, f.stageRunId, emptySet()))
            assertNotNull(f.host.abortStage(lease, StageState.FAILED))
            assertFalse(f.host.current(lease))
            assertNull(lease.authority.current())
            assertNull(f.host.beginHumanApproval(draftGraph, f.stageRunId, emptySet()))
            return
        }
        assertTrue(f.host.finishStage(lease, draftGraph, emptySet(), pending))
        assertNull(lease.authority.current())
        assertFalse(f.host.current(lease))
        assertNull(f.host.beginHumanApproval(draftGraph, f.stageRunId,
            setOf(ArtifactId("wrong-input"))))
        val resume = assertNotNull(f.host.beginHumanApproval(
            draftGraph, f.stageRunId, emptySet()))
        assertEquals(pending, resume.pendingApproval)
        assertNull(f.host.beginHumanApproval(draftGraph, f.stageRunId, emptySet()))
        assertTrue(f.host.current(resume))
        assertNotNull(resume.authority.current())
        assertNotNull(f.host.abortStage(resume, StageState.FAILED))
        assertFalse(f.host.current(resume))
        assertNull(resume.authority.current())
        assertNull(f.host.beginHumanApproval(draftGraph, f.stageRunId, emptySet()))
    }
}
