package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Session-core tests use only contract interfaces. Concrete model and workspace
 * adapters are exercised by their own modules and the app:cli integration suite.
 */
@Tag("integration")
class SessionControllerUnitTest {
    @TempDir lateinit var temp: Path
    // A distinct sink belongs to each controller, even when a test starts more than one.
    private var activeEventSink = InMemoryEventSink()
    private var nextEventSink = activeEventSink
    private val eventSink get() = activeEventSink
    private val eventSinkFactory = EventSinkFactory {
        nextEventSink.also { sink ->
            activeEventSink = sink
            nextEventSink = InMemoryEventSink()
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fixture(text: String = "Read the isolated task"): SessionRequest {
        val root = temp.resolve("workspace")
        Files.createDirectories(root)
        val task = root.resolve("task.md")
        Files.writeString(task, text)
        val out = temp.resolve("out")
        val raw = buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "req-unit"); put("sessionId", "session-unit"); put("traceId", "trace-unit")
            put("invocation", buildJsonObject {
                put("source", "cli"); put("invocationId", "invoke-unit"); put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-unit"); put("rootPath", root.toString())
                put("baselineSha", "a".repeat(40)); put("headSha", "a".repeat(40))
                put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", "task-unit")
                put("taskInputRef", buildJsonObject {
                    put("path", task.toString()); put("sha256", sha256(text))
                })
            })
            put("model", buildJsonObject {
                put("provider", "fake"); put("modelId", "fake-v1")
                put("baseUrl", "http://fake.invalid/v1"); put("credentialRef", "env:FAKE_TOKEN")
                put("contextTokens", 8192); put("maxOutputTokens", 128)
                put("features", buildJsonArray { add("tool_calls") })
            })
            put("toolPolicy", buildJsonObject {
                put("mode", "read-only")
                put("allowedTools", buildJsonArray { add("read") })
                put("deniedTools", buildJsonArray { add("safe_edit") })
                put("protectedPaths", buildJsonArray { add(".git/**") })
                put("writablePaths", buildJsonArray {})
                put("policySha256", "b".repeat(64))
            })
            put("budgets", buildJsonObject {
                put("deadlineAt", Instant.now().plusSeconds(30).toString())
                put("maxWallTimeMs", 12000); put("maxTotalTokens", 10000)
                put("maxToolCalls", 1); put("maxChildAgents", 0); put("maxDepth", 0)
                put("maxModelRequests", 2)
            })
            put("outputs", buildJsonObject {
                put("rootDir", out.toString())
                put("resultPath", out.resolve("result.json").toString())
                put("eventsPath", out.resolve("events.jsonl").toString())
                put("checkpointDir", out.resolve("checkpoints").toString())
                put("diagnosticsPath", out.resolve("diagnostics.json").toString())
            })
            put("resume", buildJsonObject {
                put("mode", "fresh"); put("fenceGeneration", 1); put("checkpoint", JsonNull)
            })
            put("requiredCapabilities", buildJsonArray {
                add("session-events-v1"); add("fake-model-v1"); add("read-only-tools-v1")
            })
        }
        return SessionRequest.parse(raw.toString())
    }

    private fun withField(request: SessionRequest, key: String, value: JsonElement): SessionRequest =
        SessionRequest.parse(JsonObject(request.raw + (key to value)).toString())

    private fun withBudget(request: SessionRequest, key: String, value: Long): SessionRequest =
        withField(request, "budgets",
            JsonObject(request.budgets + (key to JsonPrimitive(value))))

    private class StubWorkspace(
        private val request: SessionRequest,
        private val taskText: String
    ) : WorkspaceSession {
        var verificationCount = 0
        var readCount = 0
        var closed = false
        var denyRead = false
        var failClose = false
        private var onAudit: (ToolAudit) -> Unit = {}
        private var onStart: (String, String, Instant) -> Unit = { _, _, _ -> }

        fun attach(
            audit: (ToolAudit) -> Unit,
            start: (String, String, Instant) -> Unit
        ): StubWorkspace {
            onAudit = audit
            onStart = start
            return this
        }

        override fun verifyTask(path: Path, expectedSha: String) {
            verificationCount++
            if (path != request.taskPath || expectedSha != digest(taskText))
                throw Refused("TASK_DIGEST_MISMATCH", "policy", "Task digest differs")
        }

        override fun taskRelativePath(path: Path): String {
            if (path != request.taskPath)
                throw Refused("INVALID_TASK_PATH", "policy", "Unexpected task path")
            return "task.md"
        }

        override fun execute(tool: WorkspaceTool, claimedExecutionId: String?): ToolOutcome {
            val read = tool as? WorkspaceTool.Read
                ?: throw IllegalArgumentException("Only read is supported by this stub")
            if (read.path != "task.md") throw IllegalArgumentException("Unexpected read path")
            val receipt = "tool-unit-" + (++readCount)
            val started = Instant.now()
            onStart(receipt, tool.name, started)
            val data = if (denyRead) null else ToolData.FileText(
                "task.md", taskText, taskText.toByteArray(Charsets.UTF_8).size, digest(taskText))
            val code = if (denyRead) "TEST_READ_DENIED" else null
            onAudit(ToolAudit(receipt, tool.name, started, code == null, code, data?.bytes ?: 0,
                data?.sha256))
            return ToolOutcome(receipt, tool.name, data, code)
        }

        override fun close() {
            closed = true
            if (failClose) error("Injected workspace close failure")
        }

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private class StubGateway(
        private val respond: (Path, Pair<Int, String>?, Boolean) -> ModelTurn = { task, seen, _ ->
            if (seen == null) ModelTurn(ModelDecision.Read(task), ModelUsage(3, 2))
            else ModelTurn(ModelDecision.Done(seen.first, seen.second), ModelUsage(4, 1))
        }
    ) : ModelGateway {
        var negotiations = 0
        var turns = 0
        val retryPermissions = mutableListOf<Boolean>()

        override fun negotiate(
            config: ModelConfig, cancellation: Cancellation, deadline: Instant
        ): GatewayCapabilities {
            negotiations++
            return GatewayCapabilities(setOf("tool_calls"), config.contextTokens, config.maxOutputTokens)
        }

        override fun next(
            config: ModelConfig, task: Path, observed: Pair<Int, String>?,
            cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
        ): ModelTurn {
            turns++
            retryPermissions += allowRetry
            return respond(task, observed, allowRetry)
        }
    }

    private fun run(request: SessionRequest, model: StubGateway, workspace: StubWorkspace) =
        SessionController(request, model, workspaceOpener = WorkspaceOpener { _, audit, start ->
            workspace.attach(audit, start)
        }, eventSinkFactory = eventSinkFactory).run()

    private fun events(request: SessionRequest): List<JsonObject> {
        // The Session layer can observe event semantics without a physical journal.
        assertFalse(Files.exists(request.output("eventsPath")))
        return eventSink.events.toList()
    }

    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content

    @Test fun eachControllerReceivesItsOwnMemoryEventSink() {
        val first = eventSinkFactory.open(temp.resolve("first.jsonl"))
        val second = eventSinkFactory.open(temp.resolve("second.jsonl"))
        assertNotSame(first, second)
        first.append(buildJsonObject { put("type", "first") })
        assertTrue((second as InMemoryEventSink).events.isEmpty())
        first.close()
        second.close()
    }

    @Test fun successfulReadUsesOnlyInjectedPortsAndPreservesEventOrder() {
        val request = fixture()
        val model = StubGateway()
        val workspace = StubWorkspace(request, "Read the isolated task")
        val result = run(request, model, workspace)

        assertEquals("succeeded", result.value("status"))
        assertEquals(JsonNull, result["error"])
        assertEquals(1, workspace.verificationCount)
        assertEquals(1, workspace.readCount)
        assertTrue(workspace.closed)
        assertEquals(1, model.negotiations)
        assertEquals(2, model.turns)
        assertEquals(listOf(false, false), model.retryPermissions)
        assertEquals(10, result.getValue("usage").jsonObject.getValue("totalTokens").jsonPrimitive.int)

        val entries = events(request)
        assertEquals((1..entries.size).toList(), entries.map {
            it.getValue("sequence").jsonPrimitive.int
        })
        assertEquals(entries.size, result.getValue("lastEventSeq").jsonPrimitive.int)
        assertEquals(1, entries.count { it.value("type") == "tool.started" })
        assertEquals(1, entries.count { it.value("type") == "tool.completed" })
        assertEquals(2, entries.count { it.value("type") == "llm.response" })
    }

    @Test fun unsupportedProtocolIsRejectedBeforeOpeningWorkspaceOrCallingModel() {
        val request = withField(fixture(), "version", JsonPrimitive("1.1"))
        val model = StubGateway()
        val forbiddenOpener = WorkspaceOpener { _, _, _ ->
            throw AssertionError("WorkspaceOpener must not be called for unsupported protocol")
        }

        val result = SessionController(
            request, model, workspaceOpener = forbiddenOpener,
            eventSinkFactory = eventSinkFactory
        ).run()

        assertEquals("rejected", result.value("status"))
        assertEquals("UNSUPPORTED_PROTOCOL", result.getValue("error").jsonObject.value("code"))
        assertEquals(0, model.negotiations)
        assertEquals(0, model.turns)
    }

    @Test fun digestMismatchClosesAdmittedWorkspaceBeforeNegotiation() {
        val request = fixture()
        val model = StubGateway()
        val workspace = StubWorkspace(request, "Changed after request construction")

        val result = run(request, model, workspace)

        assertEquals("rejected", result.value("status"))
        assertEquals("TASK_DIGEST_MISMATCH", result.getValue("error").jsonObject.value("code"))
        assertEquals(1, workspace.verificationCount)
        assertTrue(workspace.closed)
        assertEquals(0, model.negotiations)
    }

    @Test fun deniedReadFailsClosedWithoutInvokingConfirmationTurn() {
        val request = fixture()
        val model = StubGateway()
        val workspace = StubWorkspace(request, "Read the isolated task").apply { denyRead = true }

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("TEST_READ_DENIED", result.getValue("error").jsonObject.value("code"))
        assertEquals(1, workspace.readCount)
        assertEquals(1, model.turns)
        assertTrue(workspace.closed)
        assertEquals(1, events(request).count { it.value("type") == "policy.denied" })
    }

    @Test fun modelCompletionMustMatchTrustedReadObservation() {
        val request = fixture()
        val model = StubGateway { task, seen, _ ->
            if (seen == null) ModelTurn(ModelDecision.Read(task))
            else ModelTurn(ModelDecision.Done(seen.first + 1, seen.second))
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("MODEL_INVALID_COMPLETION", result.getValue("error").jsonObject.value("code"))
        assertEquals(1, workspace.readCount)
        assertEquals(2, model.turns)
    }

    @Test fun retryCannotConsumeRequestReservedForConfirmation() {
        val request = fixture()
        val model = StubGateway { task, _, _ ->
            ModelTurn(ModelDecision.Read(task), ModelUsage(1, 1), attempts = 2)
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("MODEL_REQUEST_BUDGET_EXCEEDED", result.getValue("error").jsonObject.value("code"))
        assertEquals(listOf(false), model.retryPermissions)
        assertEquals(0, workspace.readCount)
    }

    @Test fun spareRequestAllowsOneRetryWithoutUsingFinalTurnBudget() {
        val request = withBudget(fixture(), "maxModelRequests", 3L)
        val model = StubGateway { task, seen, _ ->
            if (seen == null) ModelTurn(ModelDecision.Read(task), ModelUsage(2, 1), attempts = 2)
            else ModelTurn(ModelDecision.Done(seen.first, seen.second), ModelUsage(2, 1))
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("succeeded", result.value("status"))
        assertEquals(listOf(true, false), model.retryPermissions)
        assertEquals(1, workspace.readCount)
        val responses = events(request).filter { it.value("type") == "llm.response" }
        assertEquals(2, responses.first().getValue("metadata").jsonObject.getValue("attempts").jsonPrimitive.int)
        assertEquals(1, responses.last().getValue("metadata").jsonObject.getValue("attempts").jsonPrimitive.int)
    }

    @Test fun journalAppendFailureNeverPublishesSuccessResult() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task")
        eventSink.failAppend = true

        assertFailsWith<EventSinkFailure> { run(request, StubGateway(), workspace) }
        assertFalse(Files.exists(request.output("resultPath")))
        assertTrue(workspace.closed)
        assertTrue(eventSink.closed)
    }

    @Test fun journalFlushFailureNeverPublishesSuccessResult() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task")
        eventSink.failFlush = true

        assertFailsWith<EventSinkFailure> { run(request, StubGateway(), workspace) }
        assertFalse(Files.exists(request.output("resultPath")))
        // append reached the in-memory buffer before flush failed; it is not committed.
        assertEquals(1, eventSink.events.size)
        assertEquals(1, eventSink.events.single().getValue("sequence").jsonPrimitive.int)
        assertTrue(workspace.closed)
    }

    @Test fun journalErrorSurvivesAnotherFailureDuringCleanup() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task").apply { failClose = true }
        eventSink.failFlush = true

        val original = assertFailsWith<EventSinkFailure> {
            run(request, StubGateway(), workspace)
        }
        assertEquals("Injected flush failure", original.cause?.message)
        assertEquals(1, original.suppressed.size)
        assertEquals("Injected workspace close failure", original.suppressed.single().message)
        assertTrue(workspace.closed)
        assertTrue(eventSink.closed)
        assertFalse(Files.exists(request.output("resultPath")))
    }

    @Test fun journalCloseFailureNeverPublishesSuccessResult() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task")
        eventSink.failClose = true

        assertFailsWith<EventSinkFailure> { run(request, StubGateway(), workspace) }
        assertFalse(Files.exists(request.output("resultPath")))
        assertTrue(eventSink.events.isNotEmpty())
        assertTrue(workspace.closed)
    }

    @Test fun excessiveTokenUsageIsRejectedBeforeAnyToolSideEffect() {
        val request = withBudget(fixture(), "maxTotalTokens", 3L)
        val model = StubGateway { task, _, _ ->
            ModelTurn(ModelDecision.Read(task), ModelUsage(4, 1))
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("TOKEN_BUDGET_EXCEEDED", result.getValue("error").jsonObject.value("code"))
        assertEquals(0, workspace.readCount)
        assertTrue(workspace.closed)
    }

    @Test fun runSessionInterfaceExecutesWithInjectedPorts() {
        val request = fixture()
        val gateway = StubGateway()
        val workspace = StubWorkspace(request, "Read the isolated task")
        val useCase: RunSessionUseCase = DefaultRunSessionUseCase(
            gateway, WorkspaceOpener { _, audit, start -> workspace.attach(audit, start) },
            eventSinkFactory
        )
        val result = useCase(request, Cancellation())
        assertEquals(SessionOutcome.SUCCEEDED, result.outcome)
        assertEquals(Files.readString(request.output("resultPath")), result.protocol.toString())
        assertEquals(1, workspace.readCount)
        assertEquals(2, gateway.turns)
        assertTrue(workspace.closed)
    }

    @Test fun useCaseRejectsBeforeOpeningWorkspace() {
        val invalid = withField(fixture(), "version", JsonPrimitive("1.1"))
        val gateway = StubGateway()
        val useCase: RunSessionUseCase = DefaultRunSessionUseCase(
            gateway, WorkspaceOpener { _, _, _ ->
                throw AssertionError("Admission failure must precede opening a workspace")
            }, eventSinkFactory
        )
        val result = useCase(invalid, Cancellation())
        assertEquals(SessionOutcome.REJECTED, result.outcome)
        assertEquals(0, gateway.negotiations)
    }

    @Test fun compatibilityFacadeAcceptsSubstitutedUseCaseWithoutReencoding() {
        val request = fixture()
        val expected = buildJsonObject { put("status", "succeeded") }
        var invoked = false
        val substitute = object : RunSessionUseCase {
            override fun invoke(requestArg: SessionRequest, cancellation: Cancellation): SessionExecutionResult {
                assertSame(request, requestArg)
                invoked = true
                return SessionExecutionResult(SessionOutcome.SUCCEEDED, expected)
            }
        }
        val controller = SessionController(request, Cancellation(), substitute)
        assertSame(expected, controller.run())
        assertTrue(invoked)
        assertTrue(controller.awaitFinished(10))
    }
}
