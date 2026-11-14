package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pure fake ports plus a test-only host snapshot; no Linux writes or network. */
@Tag("integration")
class InstalledChildRuntimePortsTest {
    private val start = Instant.parse("2026-11-14T15:00:00Z")
    private val now = AtomicReference(start)
    private val clock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now.get()
    }
    private val graphId = GraphId("meter-ports-graph")
    private val stageId = StageRunId("meter-ports-stage")
    private val correlation = StageCorrelation("meter-ports-session", graphId,
        stageId, "meter-ports-request", "meter-ports-correlation")
    private val actor = AgentId("meter-ports-agent")
    private val stage = StageNode(correlation, StageKind.SDD, StageState.READY, actor)
    private val graph = StageGraph(GraphCorrelation("meter-ports-session",
        graphId, "meter-ports-graph-correlation"), StageGraphState.RUNNING,
        listOf(stage), emptyList(), StageBudget(10000, 60000, 0, 0))
    private val config = ModelConfig("fake", "fake-v1", "http://localhost/unused",
        "env:FAKE_UNUSED", 2048, 512, emptySet(), null, 1000, 1000)
    private val request = StageRoleRequest(graph, stageId, correlation.requestId,
        StageKind.SDD.role, emptySet(), config, "Write a spec",
        start.plusSeconds(600), Cancellation())
    private fun meter(tokens: Long = 150L) = InstalledChildRuntimeMeter(
        RecursiveNode(GraphId("meter-ports-child"), stageId, graphId, stageId,
            "meter-ports-child-request", StageKind.SDD, "meter-ports-task",
            emptySet(), 1, setOf("specs/Feature.md"), emptySet(), emptySet(),
            RecursiveUsage(tokens, 3), deadline = start.plusSeconds(60),
            state = RecursiveNodeState.RUNNING), clock)

    @Test fun wrapperBoundsRoleOutputAndDeadlineAndPreservesDenialIdentity() =
        runBlocking {
        val ledger = meter()
        var invocations = 0
        val outputCaps = mutableListOf<Long>()
        val executor = MeteredChildRoleExecutor(object : StageRoleExecutor {
            override suspend fun execute(bounded: StageRoleRequest,
                events: StageRoleEventSink): StageRoleResult {
                invocations++
                outputCaps.add(bounded.model.maxOutputTokens)
                assertEquals(start.plusSeconds(60), bounded.deadline)
                return StageRoleResult(correlation, StageKind.SDD.role, actor,
                    StageRoleOutcome.Proposed("proposal"), StageRoleUsage(1, 100, 30))
            }
        }, ledger)
        val first = executor.execute(request, StageRoleEventSink { })
        assertIs<StageRoleOutcome.Proposed>(first.outcome)
        val second = executor.execute(request, StageRoleEventSink { })
        assertEquals(StageRoleOutcome.Denied("TOKEN_BUDGET_EXHAUSTED"),
            second.outcome)
        assertEquals(correlation, second.correlation)
        assertEquals(actor, second.agentId)
        val third = executor.execute(request, StageRoleEventSink { })
        assertEquals(StageRoleOutcome.Denied("TOKEN_BUDGET_EXHAUSTED"),
            third.outcome)
        assertEquals(correlation, third.correlation)
        assertEquals(actor, third.agentId)
        assertEquals(listOf(150L, 20L), outputCaps)
        assertEquals(2, invocations)
        assertEquals(RecursiveUsage(260, 0), ledger.observed())
        assertEquals(RecursiveUsage(150, 3), ledger.accounted())
    }

    @Test fun unknownUsageNeverDispatchesAnotherRole() = runBlocking {
        val ledger = meter()
        assertEquals("CHILD_USAGE_UNAVAILABLE",
            ledger.recordRole(StageRoleUsage(1, null, null), false))
        var called = false
        val executor = MeteredChildRoleExecutor(object : StageRoleExecutor {
            override suspend fun execute(request: StageRoleRequest,
                events: StageRoleEventSink): StageRoleResult {
                called = true
                error("Poisoned child must never call its model delegate")
            }
        }, ledger)
        val result = executor.execute(request, StageRoleEventSink { })
        assertEquals(StageRoleOutcome.Denied("CHILD_USAGE_UNCONFIRMED"),
            result.outcome)
        assertEquals(correlation, result.correlation)
        assertEquals(actor, result.agentId)
        assertFalse(called)
    }

    private fun authority() = StageToolAuthoritySource {
        StageToolHostSnapshot(graph, stageId, correlation.requestId, emptySet(),
            Path.of("/tmp/installed-meter-ports"), "0123456789012345678901234567890123456789",
            "dev")
    }

    @Test fun verifyCrossingDeadlineReturnsTypedDenialInsteadOfAcceptance() {
        val ledger = meter()
        var verified = 0
        val tools = MeteredChildTools(StageToolSessionOpener {
            StageToolOpenResult.Granted(object : StageToolSession {
                override fun write(operations: List<StageWrite>) =
                    StageWriteEvidence("write")
                override fun runCheck(checkId: String) =
                    StageCheckEvidence(checkId, "check",
                        policyHash = "policy", manifestHash = "manifest")
                override fun verify(): StageValidationEvidence {
                    verified++
                    now.set(start.plusSeconds(61))
                    return StageValidationEvidence(correlation, actor,
                        emptyList(), true)
                }
                override fun close() {}
            })
        }, ledger)
        val opened = tools.open(authority())
        assertIs<StageToolOpenResult.Granted>(opened)
        opened.session.use { session ->
            val decision = session.verify()
            assertFalse(decision.successful)
            assertEquals("STAGE_DEADLINE", decision.deniedCode)
            assertEquals(correlation, decision.correlation)
            assertEquals(actor, decision.actor)
        }
        assertEquals(1, verified)
    }

    @Test fun expiredVerifyWithoutHostIdentityIsUnconfirmedNotAnApproval() {
        now.set(start.plusSeconds(61))
        val ledger = meter()
        val tools = MeteredChildTools(StageToolSessionOpener {
            error("Expired child must not open a tool session")
        }, ledger)
        assertEquals(StageToolOpenResult.Denied("STAGE_DEADLINE"),
            tools.open(authority()))

        now.set(start)
        val active = MeteredChildTools(StageToolSessionOpener {
            StageToolOpenResult.Granted(object : StageToolSession {
                override fun write(operations: List<StageWrite>) = StageWriteEvidence("write")
                override fun runCheck(checkId: String) = StageCheckEvidence(checkId, "check")
                override fun verify() = error("Must never verify after expiration")
                override fun close() {}
            })
        }, ledger)
        val opened = active.open(StageToolAuthoritySource { null })
        assertIs<StageToolOpenResult.Granted>(opened)
        now.set(start.plusSeconds(61))
        assertFailsWith<IllegalStateException> {
            opened.session.verify()
        }
    }
}
