package ai.yuriilab.recursivecoder

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Use this fixture from a real host adapter's tests, not from production code. */
data class PreparationHostFixture(
    val host: PreparationStageHost,
    val graph: StageGraph,
    val stageRunId: StageRunId,
    /** Configure this fixture's trusted host approval policy for a single attempt. */
    val setApproval: (PreparationApproval) -> Unit
)

/**
 * Contract shared by in-memory and future physical host implementations.
 * Each test uses a freshly initialized authoritative store. No caller-graph
 * echo implementation can satisfy the generation, CAS and pending tests.
 */
abstract class PreparationStageHostContract {
    protected abstract fun fixtureForHostContract(): PreparationHostFixture

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

        val other = completed.copy(stages = completed.stages.map {
            if (it.correlation.stageRunId != f.stageRunId)
                it.copy(state = StageState.CANCELLED) else it
        })
        assertFalse(f.host.finishStage(lease, other, emptySet()))
        assertTrue(f.host.current(lease))

        // Even a globally valid foreign manifest remains outside the leased
        // stage's write scope. This proves the CAS checks more than validate().
        val foreignStage = completed.stages.single { it.kind == StageKind.ATDD }
        val foreignId = ArtifactId("foreign-acceptance-1")
        val foreign = StageArtifactManifest(
            foreignId, StageArtifactKind.ACCEPTANCE_TESTS,
            "tests/acceptance/foreign-acceptance-1.feature", "a".repeat(64),
            EngineeringRole.ATDD, foreignStage.correlation.stageRunId, 1L,
            ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE
        )
        val foreignGraph = completed.copy(
            stages = completed.stages.map {
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
        val f = fixtureForHostContract()
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        val stage = lease.graph.stages.single { it.correlation.stageRunId == f.stageRunId }
        val id = ArtifactId("host-contract-accepted-1")
        val draft = StageArtifactManifest(
            id, requireNotNull(stage.kind.output), "specs/host-contract-accepted-1.md",
            "a".repeat(64), stage.kind.role, f.stageRunId, 1L,
            ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE
        )
        val evidence = StageValidationEvidence(
            stage.correlation, requireNotNull(stage.agentId), listOf(
                StageCheckEvidence("git_diff_check", "receipt-1", "baseline", "policy", "manifest"),
                StageCheckEvidence("offline_check", "receipt-2", "baseline", "policy", "manifest")
            ), true
        )
        val usage = StageRoleUsage(1, 12, 6)
        val accepted = lease.graph.copy(
            stages = lease.graph.stages.map {
                if (it.correlation.stageRunId == f.stageRunId)
                    it.copy(state = StageState.ACCEPTED, outputArtifacts = setOf(id)) else it
            },
            artifacts = lease.graph.artifacts + draft.copy(
                approval = ArtifactApproval.ACCEPTED, freeze = ArtifactFreeze.FROZEN
            )
        )
        assertFalse(f.host.finishStage(lease, accepted, setOf(id)))
        assertTrue(f.host.current(lease))

        f.setApproval(PreparationApproval.REJECT)
        assertEquals(PreparationApproval.REJECT, f.host.approve(draft, evidence, usage))
        assertFalse(f.host.finishStage(lease, accepted, setOf(id)))
        assertTrue(f.host.current(lease))

        f.setApproval(PreparationApproval.ACCEPT)
        val wrongDigest = draft.copy(sha256 = "b".repeat(64))
        assertEquals(PreparationApproval.ACCEPT,
            f.host.approve(wrongDigest, evidence, usage))
        assertFalse(f.host.finishStage(lease, accepted, setOf(id)))
        assertTrue(f.host.current(lease))

        assertEquals(PreparationApproval.ACCEPT, f.host.approve(draft, evidence, usage))
        assertFalse(f.host.finishStage(lease, accepted, emptySet()))
        assertFalse(f.host.finishStage(lease, accepted,
            setOf(id, ArtifactId("extra-accepted"))))
        assertTrue(f.host.current(lease))
        assertTrue(f.host.finishStage(lease, accepted, setOf(id)))
    }

    @Test fun finishRequiresPendingEvidenceExactlyForRunningDrafts() {
        val f = fixtureForHostContract()
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        val stage = lease.graph.stages.single { it.correlation.stageRunId == f.stageRunId }
        val id = ArtifactId("host-contract-pending-1")
        val draft = StageArtifactManifest(
            id, requireNotNull(stage.kind.output), "specs/host-contract-pending-1.md",
            "a".repeat(64), stage.kind.role, f.stageRunId, 1L,
            ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE
        )
        val evidence = StageValidationEvidence(
            stage.correlation, requireNotNull(stage.agentId), listOf(
                StageCheckEvidence("git_diff_check", "receipt-1", "baseline", "policy", "manifest"),
                StageCheckEvidence("offline_check", "receipt-2", "baseline", "policy", "manifest")
            ), true
        )
        val pending = PreparationPendingApproval(evidence, StageRoleUsage(1, 12, 6))
        val draftGraph = lease.graph.copy(
            stages = lease.graph.stages.map {
                if (it.correlation.stageRunId == f.stageRunId)
                    it.copy(outputArtifacts = setOf(id)) else it
            },
            artifacts = lease.graph.artifacts + draft
        )
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
        assertTrue(f.host.finishStage(lease, draftGraph, emptySet(), pending))
    }

    @Test fun humanDraftRoundTripPersistsEvidenceAndFencesContinuation() {
        val f = fixtureForHostContract()
        val lease = assertNotNull(f.host.beginStage(f.graph, f.stageRunId, emptySet()))
        val stage = lease.graph.stages.single { it.correlation.stageRunId == f.stageRunId }
        val id = ArtifactId("host-contract-draft-1")
        val candidate = StageArtifactManifest(
            id, requireNotNull(stage.kind.output), "specs/host-contract-draft-1.md",
            "a".repeat(64), stage.kind.role, f.stageRunId, 1L,
            ArtifactApproval.DRAFT, ArtifactFreeze.EDITABLE
        )
        val evidence = StageValidationEvidence(
            stage.correlation, requireNotNull(stage.agentId),
            listOf(
                StageCheckEvidence("git_diff_check", "receipt-1", "baseline", "policy", "manifest"),
                StageCheckEvidence("offline_check", "receipt-2", "baseline", "policy", "manifest")
            ), true
        )
        val pending = PreparationPendingApproval(evidence, StageRoleUsage(1, 12, 6))
        val draftGraph = lease.graph.copy(
            stages = lease.graph.stages.map {
                if (it.correlation.stageRunId == f.stageRunId)
                    it.copy(outputArtifacts = setOf(id)) else it
            },
            artifacts = lease.graph.artifacts + candidate
        )
        f.setApproval(PreparationApproval.HUMAN_REVIEW_REQUIRED)
        assertEquals(PreparationApproval.HUMAN_REVIEW_REQUIRED,
            f.host.approve(candidate, evidence, pending.usage))
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
