package ai.yuriilab.recursivecoder

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** No inference server, internet, credentials, or external model is used by these tests. */
class ModelGatewayTest {
    private lateinit var server: HttpServer
    private val posts = Collections.synchronizedList(mutableListOf<String>())
    private val authorizationHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private val getCount = AtomicInteger()
    private val postCount = AtomicInteger()
    private val postHandler = AtomicReference<(Int, JsonObject) -> MockReply> { index, _ ->
        if (index == 1) MockReply(200, toolCall()) else MockReply(200, confirmation())
    }
    private val getHandler = AtomicReference<() -> MockReply> { MockReply(200, metadata()) }

    private data class MockReply(
        val status: Int, val body: String,
        val contentType: String = "application/json",
        val retryAfter: String? = null,
        val headersReady: CountDownLatch? = null,
        val bodyGate: CountDownLatch? = null
    )

    @BeforeEach fun start() {
        posts.clear()
        authorizationHeaders.clear()
        postCount.set(0)
        getCount.set(0)
        postHandler.set { index, _ ->
            if (index == 1) MockReply(200, toolCall()) else MockReply(200, confirmation())
        }
        getHandler.set { MockReply(200, metadata()) }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models/demo") { exchange ->
            getCount.incrementAndGet()
            respond(exchange, getHandler.get()())
        }
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            posts.add(body)
            authorizationHeaders.add(exchange.requestHeaders.getFirst("Authorization"))
            val index = postCount.incrementAndGet()
            respond(exchange, postHandler.get()(index, Json.parseToJsonElement(body).jsonObject))
        }
        server.start()
    }

    @AfterEach fun stop() { server.stop(0) }

    private fun respond(exchange: HttpExchange, reply: MockReply) {
        exchange.responseHeaders.add("Content-Type", reply.contentType)
        reply.retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
        val bytes = reply.body.toByteArray()
        try {
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { stream ->
                reply.headersReady?.countDown()
                reply.bodyGate?.await(10, TimeUnit.SECONDS)
                stream.write(bytes)
            }
        } catch (_: Exception) {
            exchange.close()
        }
    }

    private fun config(vararg feature: String): ModelConfig = ModelConfig(
        provider = "openai-compatible", modelId = "demo",
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        credentialRef = "env:UNSET_TEST_MODEL_CREDENTIAL",
        contextTokens = 4096, maxOutputTokens = 256,
        features = (listOf("tool_calls") + feature).toSet(),
        temperature = null, connectTimeoutMs = 1000, requestTimeoutMs = 3000
    )

    private val task = Path.of("/safe/task.md")
    private val observation = 42 to "a".repeat(64)
    private fun gateway() = OpenAICompatibleGateway()
    private fun deadline() = Instant.now().plusSeconds(10)
    private fun metadata(
        context: Int = 8192, output: Int = 1024, features: String =
        "\"tool_calls\":true,\"tool_choice\":true,\"streaming\":true,\"structured_outputs\":true,\"usage_metadata\":true,\"error_metadata\":true"
    ) = """{"id":"demo","context_length":$context,"max_output_tokens":$output,"capabilities":{$features}}"""

    private fun toolCall() = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_task","arguments":"{\"path\":\"/safe/task.md\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
    private fun confirmation() = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"bytes\":42,\"sha256\":\"${"a".repeat(64)}\"}"}}],"usage":{"prompt_tokens":20,"completion_tokens":6}}"""
    private fun refusal() = """{"choices":[{"finish_reason":"stop","message":{"refusal":"No, do not execute","content":null}}]}"""
    private fun sse(vararg lines: String) =
        lines.joinToString("\n\n") { "data: $it" } + "\n\n"

    @Test fun capabilitiesMustBeExplicitAndMatchRequestedLimits() {
        val c = config("structured_outputs", "tool_choice", "streaming", "usage_metadata", "error_metadata")
        val capabilities = gateway().negotiate(c, Cancellation(), deadline())
        assertTrue(capabilities.features.containsAll(c.features))
        assertEquals(8192, capabilities.contextTokens)
        assertEquals(1, getCount.get())
        assertEquals(0, postCount.get())

        getHandler.set { MockReply(200, metadata(features = "\"tool_calls\":true")) }
        val error = assertFailsWith<Refused> {
            gateway().negotiate(c, Cancellation(), deadline())
        }
        assertEquals("MODEL_CAPABILITY_UNSUPPORTED", error.code)
        assertEquals(0, postCount.get())
    }

    @Test fun missingMetadataAndInsufficientContextFailBeforeInference() {
        getHandler.set { MockReply(200, """{"id":"demo"}""") }
        assertEquals("MODEL_CAPABILITY_UNSUPPORTED", assertFailsWith<Refused> {
            gateway().negotiate(config(), Cancellation(), deadline())
        }.code)
        getHandler.set { MockReply(200, metadata(context = 2048)) }
        assertEquals("MODEL_CAPABILITY_UNSUPPORTED", assertFailsWith<Refused> {
            gateway().negotiate(config(), Cancellation(), deadline())
        }.code)
        assertEquals(0, postCount.get())
    }

    @Test fun toolCallAndStructuredFinalReplyUseTrustedObservation() {
        val c = config("tool_choice", "structured_outputs", "usage_metadata")
        val model = gateway()
        model.negotiate(c, Cancellation(), deadline())
        val first = model.next(c, task, null, Cancellation(), deadline(), true)
        assertEquals(ModelDecision.Read(task), first.decision)
        assertEquals(ModelUsage(10, 5), first.usage)
        val second = model.next(c, task, observation, Cancellation(), deadline(), false)
        assertEquals(ModelDecision.Done(42, observation.second), second.decision)
        assertEquals(ModelUsage(20, 6), second.usage)
        val firstRequest = Json.parseToJsonElement(posts[0]).jsonObject
        assertEquals("required", firstRequest["tool_choice"]!!.jsonPrimitive.content)
        assertNotNull(firstRequest["tools"])
        val secondRequest = Json.parseToJsonElement(posts[1]).jsonObject
        assertEquals("json_schema",
            secondRequest["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2, postCount.get())
    }

    @Test fun partialSseFunctionArgumentsAndResponseAreAssembled() {
        val c = config("streaming", "usage_metadata")
        val first = sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"read_task","arguments":"{\"path\":"}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"/safe/task.md\"}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3}}""",
            "[DONE]"
        )
        val second = sse(
            """{"choices":[{"delta":{"content":"{\"bytes\":42,\"sha256\":\""},"finish_reason":null}]}""",
            """{"choices":[{"delta":{"content":"${"a".repeat(64)}\"}"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":15,"completion_tokens":7}}""",
            "[DONE]"
        )
        postHandler.set { index, _ ->
            MockReply(200, if (index == 1) first else second, "text/event-stream")
        }
        val model = gateway()
        val start = model.next(c, task, null, Cancellation(), deadline(), true)
        assertEquals(ModelDecision.Read(task), start.decision)
        assertEquals(ModelUsage(11, 3), start.usage)
        val end = model.next(c, task, observation, Cancellation(), deadline(), false)
        assertEquals(ModelDecision.Done(42, observation.second), end.decision)
        assertEquals(ModelUsage(15, 7), end.usage)
        assertTrue(posts.all { Json.parseToJsonElement(it).jsonObject["stream"]!!.jsonPrimitive.boolean })
    }

    @Test fun incompleteOrMalformedSseFailsWithoutToolExecution() {
        val c = config("streaming")
        postHandler.set { _, _ ->
            MockReply(200, sse("""{"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}"""),
                "text/event-stream")
        }
        assertEquals("MODEL_STREAM_INCOMPLETE", assertFailsWith<Refused> {
            gateway().next(c, task, null, Cancellation(), deadline(), true)
        }.code)
        postHandler.set { _, _ -> MockReply(200, sse("not-json", "[DONE]"), "text/event-stream") }
        assertEquals("MODEL_MALFORMED_RESPONSE", assertFailsWith<Refused> {
            gateway().next(c, task, null, Cancellation(), deadline(), true)
        }.code)
    }

    @Test fun refusalAndMalformedMessagesFailClosed() {
        postHandler.set { _, _ -> MockReply(200, refusal()) }
        assertEquals(ModelDecision.Refusal, gateway().next(
            config(), task, null, Cancellation(), deadline(), true).decision)
        postHandler.set { _, _ -> MockReply(200, "not json") }
        assertEquals("MODEL_MALFORMED_RESPONSE", assertFailsWith<Refused> {
            gateway().next(config(), task, null, Cancellation(), deadline(), true)
        }.code)
        postHandler.set { _, _ -> MockReply(200,
            toolCall().replace("/safe/task.md", "/outside/task.md")) }
        assertEquals("MODEL_INVALID_RESPONSE", assertFailsWith<Refused> {
            gateway().next(config(), task, null, Cancellation(), deadline(), true)
        }.code)
    }

    @Test fun authenticationAndSensitiveBodiesNeverAppearInErrors() {
        postHandler.set { _, _ -> MockReply(401, "SENSITIVE_PRIVATE_REASONING credential=TOP_SECRET") }
        val error = assertFailsWith<Refused> {
            gateway().next(config(), task, null, Cancellation(), deadline(), true)
        }
        assertEquals("MODEL_AUTH_FAILED", error.code)
        assertFalse(error.toString().contains("TOP_SECRET"))
        assertFalse(error.toString().contains("SENSITIVE_PRIVATE_REASONING"))
    }

    @Test fun retriesBeforeToolExecutionButNeverAfterObservedToolResult() {
        postHandler.set { index, _ ->
            if (index == 1) MockReply(429, "rate limited", retryAfter = "0")
            else MockReply(200, toolCall())
        }
        val c = config()
        val first = gateway().next(c, task, null, Cancellation(), deadline(), true)
        assertEquals(2, first.attempts)
        assertEquals(2, postCount.get())
        postHandler.set { _, _ -> MockReply(503, "unavailable", retryAfter = "0") }
        assertEquals("MODEL_RETRY_EXHAUSTED", assertFailsWith<Refused> {
            gateway().next(c, task, observation, Cancellation(), deadline(), true)
        }.code)
        assertEquals(3, postCount.get(), "An observed tool result must not be sent twice")
    }

    @Test fun cancellationInterruptsStalledSseBodyBeforeServerReleases() {
        // Headers have been delivered; the body cannot finish until we release the gate.
        // A completion-only cancellation check would hang for 10 seconds and fail this test.
        val headersReady = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        postHandler.set { _, _ -> MockReply(
            200, sse("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""", "[DONE]"),
            contentType = "text/event-stream", headersReady = headersReady, bodyGate = releaseBody
        ) }
        val cancellation = Cancellation()
        val failure = AtomicReference<Throwable>()
        val worker = thread {
            cancellation.bind()
            try {
                gateway().next(config("streaming"), task, null, cancellation, deadline(), true)
            } catch (e: Throwable) { failure.set(e) }
        }
        try {
            assertTrue(headersReady.await(3, TimeUnit.SECONDS), "The server must send headers before cancellation")
            val started = System.nanoTime()
            cancellation.cancel()
            worker.join(1000)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertFalse(worker.isAlive, "Blocked response body did not stop on interrupt")
            assertTrue(elapsedMs < 1000, "Cancellation must not wait for the server body")
            assertIs<Stopped>(failure.get())
        } finally {
            releaseBody.countDown()
            cancellation.cancel()
            worker.join(2000)
        }
    }

    @Test fun cancellationInterruptsStalledHeaders() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        postHandler.set { _, _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            MockReply(200, toolCall())
        }
        val cancellation = Cancellation()
        val failure = AtomicReference<Throwable>()
        val worker = thread {
            cancellation.bind()
            try { gateway().next(config(), task, null, cancellation, deadline(), true) }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            cancellation.cancel()
            worker.join(1000)
            assertFalse(worker.isAlive, "The blocked header request was not interrupted")
            assertIs<Stopped>(failure.get())
        } finally {
            release.countDown()
            cancellation.cancel()
            worker.join(2000)
        }
    }

    @Test fun invalidToolNameAndMissingUsageHaveDistinctCodes() {
        postHandler.set { _, _ -> MockReply(200, toolCall().replace("read_task", "wrong_tool")) }
        assertEquals("MODEL_INVALID_RESPONSE", assertFailsWith<Refused> {
            gateway().next(config(), task, null, Cancellation(), deadline(), false)
        }.code)
        postHandler.set { _, _ -> MockReply(200, toolCall().substringBefore(",\"usage\":") + "}") }
        assertEquals("MODEL_INVALID_RESPONSE", assertFailsWith<Refused> {
            gateway().next(config("usage_metadata"), task, null, Cancellation(), deadline(), false)
        }.code)
    }

    @Test fun credentialsAreInjectedAndNotPersistedInResponses() {
        val token = "TOP_SECRET_PROVIDER_TOKEN"
        val model = OpenAICompatibleGateway { key ->
            if (key == "TEST_PROVIDER_TOKEN") token else null
        }
        val config = config().copy(credentialRef = "env:TEST_PROVIDER_TOKEN")
        val turn = model.next(config, task, null, Cancellation(), deadline(), false)
        assertEquals(ModelDecision.Read(task), turn.decision)
        assertEquals(listOf("Bearer $token"), authorizationHeaders.toList())
        assertFalse(turn.toString().contains(token))
        assertFalse(turn.toString().contains("TEST_PROVIDER_TOKEN"))
    }

    @Test fun oversizedResponseFailsWithSafeError() {
        postHandler.set { _, _ -> MockReply(200, "X".repeat(1_048_577)) }
        assertEquals("MODEL_RESPONSE_TOO_LARGE", assertFailsWith<Refused> {
            gateway().next(config(), task, null, Cancellation(), deadline(), false)
        }.code)
    }

    @Test fun optionalOutputControlsAreAbsentWhenUnadvertised() {
        val c = config()
        gateway().next(c, task, null, Cancellation(), deadline(), false)
        gateway().next(c, task, observation, Cancellation(), deadline(), false)
        val first = Json.parseToJsonElement(posts[0]).jsonObject
        val second = Json.parseToJsonElement(posts[1]).jsonObject
        assertFalse("tool_choice" in first)
        assertFalse("response_format" in second)
    }

    @Test fun plaintextRemoteEndpointAndUnknownFeaturesFailClosed() {
        val c = config()
        assertEquals("MODEL_INSECURE_ENDPOINT", assertFailsWith<Refused> {
            gateway().negotiate(c.copy(baseUrl = "http://example.com/v1"), Cancellation(), deadline())
        }.code)
        assertEquals("UNSUPPORTED_MODEL", assertFailsWith<Refused> {
            gateway().negotiate(c.copy(features = setOf("tool_calls", "vendor_magic")),
                Cancellation(), deadline())
        }.code)
    }
}
