package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir

private val sessionTestWorkspaceOpener = WorkspaceOpener { request, audit, start ->
    WorkspaceManager.open(request, audit, start)
}

@Tag("integration")
class SessionRuntimeTest {
    @TempDir lateinit var temp: Path

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }
    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fixture(secret: String = "Read the example task"): JsonObject {
        val root = temp.resolve("workspace")
        Files.createDirectories(root)
        git(root, "init", "-b", "dev")
        git(root, "config", "user.email", "test@example.invalid")
        git(root, "config", "user.name", "Test")
        val task = root.resolve("task.md")
        Files.writeString(task, secret)
        git(root, "add", "task.md")
        git(root, "commit", "-m", "fixture")
        val head = git(root, "rev-parse", "HEAD")
        val out = temp.resolve("out")
        return buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "req-4"); put("sessionId", "sess-4"); put("traceId", "trace-4")
            put("invocation", buildJsonObject {
                put("source", "cli"); put("invocationId", "invoke-4"); put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-4"); put("rootPath", root.toString())
                put("baselineSha", head); put("headSha", head); put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", "task-4")
                put("taskInputRef", buildJsonObject {
                    put("path", task.toString()); put("sha256", hash(secret.toByteArray()))
                })
                put("parentAgentId", JsonNull)
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
                put("deniedTools", buildJsonArray { add("safe_edit"); add("git_push") })
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
    }
    private fun JsonObject.change(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun JsonObject.changeChild(key: String, child: String, value: JsonElement) =
        change(key, (this[key] as JsonObject).change(child, value))
    private fun run(request: JsonObject, model: FakeModel = FakeModel()) =
        SessionController(SessionRequest.parse(request.toString()), model, workspaceOpener = sessionTestWorkspaceOpener).run()
    private fun status(result: JsonObject) = result["status"]!!.jsonPrimitive.content
    private fun events() = Files.readAllLines(temp.resolve("out/events.jsonl"))
        .map { Json.parseToJsonElement(it).jsonObject }

    @Test fun successEmitsModelAndToolEventsAndOneTerminalTransition() {
        val result = run(fixture())
        assertEquals("succeeded", status(result))
        val events = events()
        assertEquals(2, events.count { it["type"]!!.jsonPrimitive.content == "llm.request" })
        assertEquals(2, events.count { it["type"]!!.jsonPrimitive.content == "llm.response" })
        assertEquals(1, events.count { it["type"]!!.jsonPrimitive.content == "tool.started" })
        assertEquals(1, events.count { it["type"]!!.jsonPrimitive.content == "tool.completed" })
        assertEquals(1, events.count { it["transition"]?.jsonObject?.get("to")?.jsonPrimitive?.content == "succeeded" })
        assertEquals((1..events.size).toList(), events.map { it["sequence"]!!.jsonPrimitive.int })
        assertEquals(events.size, result["lastEventSeq"]!!.jsonPrimitive.int)
        assertTrue(events.all { it["traceId"]!!.jsonPrimitive.content == "trace-4" })
        assertEquals("unknown", result["usage"]!!.jsonObject["completeness"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, result["error"])
    }

    @Test fun unsupportedVersionFailsBeforeModelInvocation() {
        assertEquals("rejected", status(run(fixture().change("version", JsonPrimitive("1.1")))))
        assertTrue(events().none { it["type"]!!.jsonPrimitive.content == "llm.request" })
    }

    @Test fun unavailableModelOrToolCapabilitiesFailClosed() {
        val request = fixture().change("requiredCapabilities", buildJsonArray { add("unavailable-capability") })
        assertEquals("rejected", status(run(request)))
        assertTrue(events().none { it["type"]!!.jsonPrimitive.content == "llm.request" })
    }

    @Test fun corruptInputFailsClosed() {
        assertFailsWith<RuntimeException> { SessionRequest.parse("{") }
        assertFailsWith<RuntimeException> { SessionRequest.parse("{}") }
        assertFailsWith<RuntimeException> {
            SessionRequest.parse(fixture().change("unknown", JsonPrimitive("value")).toString())
        }
    }

    @Test fun deadlineInterruptsBlockingFakeModel() {
        val request = fixture().changeChild("budgets", "maxWallTimeMs", JsonPrimitive(100))
        val result = run(request, FakeModel(5000))
        assertEquals("timed_out", status(result))
        assertEquals("SESSION_DEADLINE_EXCEEDED", result["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun cancellationEmitsOneTerminalResult() {
        val controller = SessionController(SessionRequest.parse(fixture().toString()), FakeModel(5000), workspaceOpener = sessionTestWorkspaceOpener)
        val result = AtomicReference<JsonObject>()
        val worker = thread { result.set(controller.run()) }
        Thread.sleep(150)
        controller.cancellation.cancel()
        worker.join(4000)
        assertFalse(worker.isAlive)
        assertEquals("cancelled", status(result.get()))
        assertEquals(1, events().count {
            it["transition"]?.jsonObject?.get("to")?.jsonPrimitive?.content == "failed"
        })
        assertTrue(events().any {
            it["transition"]?.jsonObject?.get("to")?.jsonPrimitive?.content == "cancelled"
        })
    }

    @Test fun eventRedactionNeverLeaksTaskOrCredentialReference() {
        val result = run(fixture("TOP_SECRET_TEST_PAYLOAD_987"))
        assertEquals("succeeded", status(result))
        listOf("events.jsonl", "result.json", "diagnostics.json").forEach { name ->
            val text = Files.readString(temp.resolve("out").resolve(name))
            assertFalse(text.contains("TOP_SECRET_TEST_PAYLOAD_987"))
            assertFalse(text.contains("FAKE_TOKEN"))
        }
    }

    @Test fun previousOutputCannotBeOverwritten() {
        val request = fixture()
        run(request)
        val previous = Files.readString(temp.resolve("out/result.json"))
        assertFailsWith<RuntimeException> { run(request) }
        assertEquals(previous, Files.readString(temp.resolve("out/result.json")))
    }

    @Test fun workspaceBaselineMismatchIsRejected() {
        val request = fixture().changeChild("workspace", "baselineSha", JsonPrimitive("a".repeat(40)))
        assertEquals("rejected", status(run(request)))
        assertTrue(events().none { it["type"]!!.jsonPrimitive.content == "llm.request" })
    }

    @Test fun writeToolRequestIsRejectedBeforeFirstInvocation() {
        val request = fixture().changeChild("toolPolicy", "allowedTools",
            buildJsonArray { add("read"); add("safe_edit") })
        assertEquals("rejected", status(run(request)))
        assertTrue(events().none { it["type"]!!.jsonPrimitive.content == "llm.request" })
    }
    @Test fun openaiGatewayRunsThroughTrustedReadAndRedactsModelPayloads() {
        val taskText = "PRIVATE_TASK_CONTENT_NEVER_IN_DIAGNOSTICS"
        val request = fixture(taskText)
        val taskPath = request["task"]!!.jsonObject["taskInputRef"]!!.jsonObject["path"]!!.jsonPrimitive.content
        val taskHash = hash(taskText.toByteArray())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0)
        fun answer(exchange: com.sun.net.httpserver.HttpExchange, text: String) {
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.createContext("/v1/models/demo") { exchange ->
            answer(exchange, """{"id":"demo","context_length":8192,"max_output_tokens":512,"capabilities":{"tool_calls":true,"usage_metadata":true}}""")
        }
        http.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            val index = calls.incrementAndGet()
            val content = if (index == 1) {
                buildJsonObject {
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("finish_reason", "tool_calls")
                            put("message", buildJsonObject {
                                put("tool_calls", buildJsonArray {
                                    add(buildJsonObject {
                                        put("function", buildJsonObject {
                                            put("name", "read_task")
                                            put("arguments", buildJsonObject { put("path", taskPath) }.toString())
                                        })
                                    })
                                })
                            })
                        })
                    })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 11); put("completion_tokens", 5)
                    })
                }.toString()
            } else {
                buildJsonObject {
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("finish_reason", "stop")
                            put("message", buildJsonObject {
                                put("content", buildJsonObject {
                                    put("bytes", taskText.toByteArray().size)
                                    put("sha256", taskHash)
                                }.toString())
                            })
                        })
                    })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 19); put("completion_tokens", 6)
                    })
                }.toString()
            }
            answer(exchange, content)
        }
        http.start()
        try {
            val options = JsonObject(request["model"]!!.jsonObject +
                mapOf(
                    "provider" to JsonPrimitive("openai-compatible"),
                    "modelId" to JsonPrimitive("demo"),
                    "baseUrl" to JsonPrimitive("http://127.0.0.1:${http.address.port}/v1"),
                    "features" to buildJsonArray { add("tool_calls"); add("usage_metadata") }
                ))
            val live = request.change("model", options)
                .change("requiredCapabilities", buildJsonArray {
                    add("session-events-v1")
                    add("read-only-tools-v1")
                    add("model-gateway-v1")
                })
            val result = SessionController(SessionRequest.parse(live.toString()),
                OpenAICompatibleGateway(), workspaceOpener = sessionTestWorkspaceOpener).run()
            assertEquals("succeeded", status(result))
            assertEquals(2, calls.get())
            assertEquals(41, result["usage"]!!.jsonObject["totalTokens"]!!.jsonPrimitive.int)
            assertEquals("complete", result["usage"]!!.jsonObject["completeness"]!!.jsonPrimitive.content)
            assertTrue(events().any { it["type"]!!.jsonPrimitive.content == "llm.capabilities" })
            assertEquals(1, events().count { it["type"]!!.jsonPrimitive.content == "tool.completed" })
            listOf("events.jsonl", "diagnostics.json", "result.json").forEach { filename ->
                val text = Files.readString(temp.resolve("out").resolve(filename))
                assertFalse(text.contains(taskText))
                assertFalse(text.contains("FAKE_TOKEN"))
                assertFalse(text.contains(taskPath))
            }
        } finally {
            http.stop(0)
        }
    }

    private fun countedGateway(
        firstAttempts: Int,
        firstUsage: ModelUsage = ModelUsage(1L, 1L),
        onFirstRetryDecision: (Boolean) -> Unit = {}
    ): ModelGateway = object : ModelGateway {
        override fun negotiate(
            config: ModelConfig, cancellation: Cancellation, deadline: Instant
        ): GatewayCapabilities =
            GatewayCapabilities(setOf("tool_calls"), config.contextTokens, config.maxOutputTokens)

        override fun next(
            config: ModelConfig, task: Path, observed: Pair<Int, String>?,
            cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
        ): ModelTurn = if (observed == null) {
            onFirstRetryDecision(allowRetry)
            ModelTurn(ModelDecision.Read(task), firstUsage, attempts = firstAttempts)
        } else ModelTurn(
            ModelDecision.Done(observed.first, observed.second),
            ModelUsage(1L, 1L)
        )
    }

    @Test fun wireRetriesAreCountedAndFinalModelTurnIsReserved() {
        val request = fixture().changeChild("budgets", "maxModelRequests", JsonPrimitive(3))
        var retryAllowed = false
        val gateway = countedGateway(firstAttempts = 2) { retryAllowed = it }
        val result = SessionController(SessionRequest.parse(request.toString()), gateway, workspaceOpener = sessionTestWorkspaceOpener).run()
        assertEquals("succeeded", status(result))
        assertTrue(retryAllowed, "The initial turn may retry only if budget reserves the final turn")
        val modelEvents = events().filter { it["type"]!!.jsonPrimitive.content == "llm.response" }
        assertEquals(2, modelEvents.size)
        assertEquals(2, modelEvents.first()["metadata"]!!.jsonObject["attempts"]!!.jsonPrimitive.int)
        assertEquals(1, modelEvents.last()["metadata"]!!.jsonObject["attempts"]!!.jsonPrimitive.int)
        assertEquals(1, events().count { it["type"]!!.jsonPrimitive.content == "tool.completed" })
    }

    @Test fun exhaustedModelRequestBudgetRejectsUnreportedRetryBeforeTrustedTool() {
        val request = fixture() // maxModelRequests = 2, one POST for each turn
        var retryAllowed = true
        val gateway = countedGateway(firstAttempts = 2) { retryAllowed = it }
        val result = SessionController(SessionRequest.parse(request.toString()), gateway, workspaceOpener = sessionTestWorkspaceOpener).run()
        assertFalse(retryAllowed, "The final turn's reserved request must not fund a retry")
        assertEquals("failed", status(result))
        assertEquals("MODEL_REQUEST_BUDGET_EXCEEDED",
            result["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(0, events().count { it["type"]!!.jsonPrimitive.content == "tool.completed" })
    }

    @Test fun tokenBudgetExceededBeforeWorkspaceRead() {
        val request = fixture().changeChild("budgets", "maxTotalTokens", JsonPrimitive(2))
        val gateway = countedGateway(firstAttempts = 1, firstUsage = ModelUsage(20L, 10L))
        val result = SessionController(SessionRequest.parse(request.toString()), gateway, workspaceOpener = sessionTestWorkspaceOpener).run()
        assertEquals("failed", status(result))
        assertEquals("TOKEN_BUDGET_EXCEEDED",
            result["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(0, events().count { it["type"]!!.jsonPrimitive.content == "tool.completed" })
    }

}
