package ai.yuriilab.recursivecoder

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real Koog AIAgent with a deterministic offline fake PromptExecutor. */
@Tag("integration")
class KoogStageRoleExecutorTest {
    private val graphId = GraphId("graph-73")
    private val sddId = StageRunId("sdd-73")
    private val atddId = StageRunId("atdd-73")
    private val artifactId = ArtifactId("requirements-73")

    private fun graph(kind: StageKind = StageKind.SDD): StageGraph {
        val sdd = StageNode(
            StageCorrelation("session-73", graphId, sddId, "request-sdd", "corr-sdd"),
            StageKind.SDD,
            state = if (kind == StageKind.SDD) StageState.READY else StageState.ACCEPTED,
            agentId = AgentId("sdd-agent"),
            outputArtifacts = if (kind == StageKind.SDD) emptySet() else setOf(artifactId)
        )
        val atdd = StageNode(
            StageCorrelation("session-73", graphId, atddId, "request-atdd", "corr-atdd"),
            StageKind.ATDD, state = StageState.READY, agentId = AgentId("atdd-agent"),
            dependencies = setOf(sddId), inputArtifacts = setOf(artifactId)
        )
        val manifest = StageArtifactManifest(
            artifactId, StageArtifactKind.REQUIREMENTS, "specs/Requirements.md",
            "a".repeat(64), EngineeringRole.SDD, sddId, 1,
            ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN
        )
        return StageGraph(
            GraphCorrelation("session-73", graphId, "workflow-73"),
            StageGraphState.RUNNING,
            stages = if (kind == StageKind.SDD) listOf(sdd) else listOf(sdd, atdd),
            artifacts = if (kind == StageKind.SDD) emptyList() else listOf(manifest),
            budget = StageBudget(2_000, 10_000, 0, 0)
        )
    }

    private fun request(
        kind: StageKind = StageKind.SDD,
        accepted: Set<ArtifactId> = if (kind == StageKind.SDD) emptySet() else setOf(artifactId)
    ): StageRoleRequest = StageRoleRequest(
        graph = graph(kind),
        stageRunId = if (kind == StageKind.SDD) sddId else atddId,
        expectedRole = kind.role,
        hostAcceptedArtifactIds = accepted,
        model = ModelConfig("fake", "fake-v1", "http://localhost:12345/v1",
            "env:OFFLINE_TEST_ONLY", 4096, 512, setOf("tool_calls"), null, 1000, 1000),
        instruction = "Propose a short specification.",
        deadline = Instant.now().plusSeconds(30),
        cancellation = Cancellation()
    )

    private class OfflineGateway : ModelGateway {
        var negotiations = 0
        override fun negotiate(config: ModelConfig, cancellation: Cancellation, deadline: Instant): GatewayCapabilities {
            negotiations++
            cancellation.check(deadline)
            return GatewayCapabilities(setOf("tool_calls"), 4096, 512)
        }
        override fun next(
            config: ModelConfig, task: Path, observed: Pair<Int, String>?,
            cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
        ): ModelTurn = error("Legacy read_task gateway must NOT generate role text")
    }

    private class RecordingFake(private val delegated: PromptExecutor) : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()
        val toolsSeen = mutableListOf<List<String>>()
        var closes = 0
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts += prompt
            toolsSeen += tools.map { it.name }
            return delegated.execute(prompt, model, tools)
        }
        override fun executeStreaming(
            prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = error("Streaming is not supported")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("Moderation is not supported")
        override fun close() { closes++; delegated.close() }
    }

    private fun fake(response: String, metered: Boolean = true): RecordingFake =
        RecordingFake(getMockExecutor(
            tokenizer = if (metered) SimpleRegexBasedTokenizer() else null
        ) { mockLLMAnswer(response).asDefaultResponse })

    @Test fun independentRoleContextsUseKoogAndNeverShareTools() = runBlocking {
        val gateway = OfflineGateway()
        val constructed = mutableListOf<Pair<EngineeringRole, RecordingFake>>()
        val runner = KoogStageRoleExecutor(gateway, KoogPromptBridge { _, role ->
            val testExecutor = fake("untrusted " + role.name + " proposal")
            constructed += role to testExecutor
            KoogPromptBinding(testExecutor, OpenAIModels.Chat.GPT4o)
        })
        val events = mutableListOf<StageRoleEvent>()
        val sink = StageRoleEventSink { events.add(it) }
        val first = runner.execute(request(), sink)
        val second = runner.execute(request(StageKind.ATDD), sink)

        assertEquals(2, gateway.negotiations)
        assertEquals(2, constructed.size)
        assertNotSame(constructed[0].second, constructed[1].second)
        assertEquals(listOf(EngineeringRole.SDD, EngineeringRole.ATDD), constructed.map { it.first })
        assertIs<StageRoleOutcome.Proposed>(first.outcome)
        assertIs<StageRoleOutcome.Proposed>(second.outcome)
        assertEquals(1, first.usage.modelRequests)
        assertEquals(1, second.usage.modelRequests)
        assertTrue(first.usage.inputTokens!! > 0)
        assertTrue(second.usage.outputTokens!! > 0)
        constructed.forEach { (role, model) ->
            assertEquals(1, model.prompts.size)
            assertTrue(model.prompts.single().messages.any {
                it is Message.System && it.textContent().contains("Role: " + role.name)
            })
            assertEquals(listOf(emptyList()), model.toolsSeen)
            assertEquals(1, model.closes)
        }
        assertEquals(listOf("request-sdd", "request-sdd", "request-atdd", "request-atdd"),
            events.map { it.correlation.requestId })
        assertEquals(listOf(StageRoleEventKind.STARTED, StageRoleEventKind.PROPOSED,
            StageRoleEventKind.STARTED, StageRoleEventKind.PROPOSED), events.map { it.kind })
        assertTrue(events.all { it.code == null && it.usage.modelRequests in 0..1 })
        assertEquals(StageState.READY, request().graph.stages.single().state)
    }

    @Test fun identityToolEscalationAndUnacceptedInputsFailBeforeModelAccess() = runBlocking {
        var bridgeCalls = 0
        val gateway = OfflineGateway()
        val runner = KoogStageRoleExecutor(gateway, KoogPromptBridge { _, _ ->
            bridgeCalls++
            KoogPromptBinding(fake("nothing"), OpenAIModels.Chat.GPT4o)
        })
        val events = StageRoleEventSink { }
        val spoofed = runner.execute(request().copy(expectedRole = EngineeringRole.DEVELOPER), events)
        assertEquals(StageRoleOutcome.Denied("ROLE_IDENTITY_MISMATCH"), spoofed.outcome)
        val tool = runner.execute(request().copy(requestedTools = setOf("read")), events)
        assertEquals(StageRoleOutcome.Denied("ROLE_TOOL_SURFACE_UNAVAILABLE"), tool.outcome)
        val rawWrite = runner.execute(request().copy(requestedTools = setOf("write")), events)
        assertEquals(StageRoleOutcome.Denied("ROLE_TOOL_SURFACE_UNAVAILABLE"), rawWrite.outcome)
        val noAcceptance = runner.execute(request(StageKind.ATDD, emptySet()), events)
        assertEquals(StageRoleOutcome.Denied("ARTIFACT_NOT_ACCEPTED"), noAcceptance.outcome)
        assertEquals(0, bridgeCalls)
        assertEquals(0, gateway.negotiations)
    }

    @Test fun missingUsageAndTokenBudgetReturnTypedFailuresWithoutProposals() = runBlocking {
        val noUsage = KoogStageRoleExecutor(OfflineGateway(), KoogPromptBridge { _, _ ->
            KoogPromptBinding(fake("no usage metadata", metered = false), OpenAIModels.Chat.GPT4o)
        }).execute(request(), StageRoleEventSink { })
        assertEquals(StageRoleOutcome.Failed("MODEL_USAGE_MISSING"), noUsage.outcome)
        assertEquals(1, noUsage.usage.modelRequests)
        val tokens = KoogStageRoleExecutor(OfflineGateway(), KoogPromptBridge { _, _ ->
            KoogPromptBinding(fake("too many tokens"), OpenAIModels.Chat.GPT4o)
        }).execute(request().copy(graph = graph().copy(
            budget = StageBudget(1, 10_000, 0, 0))), StageRoleEventSink { })
        assertEquals(StageRoleOutcome.Failed("TOKEN_BUDGET_EXCEEDED"), tokens.outcome)
        assertEquals(1, tokens.usage.modelRequests)
    }

    @Test fun unsupportedProviderAndProviderErrorsFailClosedWithSanitizedEvents() = runBlocking {
        val gateway = OfflineGateway()
        val sinkEvents = mutableListOf<StageRoleEvent>()
        val sink = StageRoleEventSink { sinkEvents.add(it) }
        val runner = KoogStageRoleExecutor(gateway, KoogPromptBridge { _, _ ->
            throw IllegalStateException("sensitive provider token: SECRET_123")
        })
        val unsupported = runner.execute(request().copy(model = request().model.copy(
            provider = "openai-compatible")), sink)
        assertEquals(StageRoleOutcome.Denied("MODEL_BRIDGE_UNSUPPORTED"), unsupported.outcome)
        val failure = runner.execute(request(), sink)
        assertEquals(StageRoleOutcome.Failed("ROLE_EXECUTION_FAILED"), failure.outcome)
        assertEquals(0, failure.usage.modelRequests)
        assertEquals(1, gateway.negotiations)
        assertTrue(sinkEvents.none { it.toString().contains("SECRET_123") })
        assertEquals(StageRoleEventKind.FAILED, sinkEvents.last().kind)
    }

    @Test fun cancellationDeadlineAndJournalFailuresNeverClaimSuccess() = runBlocking {
        val gateway = OfflineGateway()
        var opened = 0
        val runner = KoogStageRoleExecutor(gateway, KoogPromptBridge { _, _ ->
            opened++
            KoogPromptBinding(fake("should never run"), OpenAIModels.Chat.GPT4o)
        })
        val cancelled = request().also { it.cancellation.cancel() }
        assertEquals(StageRoleOutcome.Cancelled, runner.execute(cancelled, StageRoleEventSink { }).outcome)
        val expired = request().copy(deadline = Instant.now().minusSeconds(1))
        assertEquals(StageRoleOutcome.TimedOut, runner.execute(expired, StageRoleEventSink { }).outcome)
        assertEquals(0, opened)
        val failure = runner.execute(request(), StageRoleEventSink { error("event sink unavailable") })
        assertEquals(StageRoleOutcome.Failed("EVENT_SINK_FAILED"), failure.outcome)
        assertEquals(0, opened)
    }
}
