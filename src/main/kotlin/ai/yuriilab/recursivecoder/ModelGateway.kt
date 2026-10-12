package ai.yuriilab.recursivecoder

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.*

/** The gateway has no authority to execute tools: it may only propose a decision. */
interface ModelGateway {
    fun negotiate(config: ModelConfig, cancellation: Cancellation, deadline: Instant): GatewayCapabilities
    fun next(
        config: ModelConfig,
        task: Path,
        observed: Pair<Int, String>?,
        cancellation: Cancellation,
        deadline: Instant,
        allowRetry: Boolean
    ): ModelTurn
}

data class GatewayCapabilities(val features: Set<String>, val contextTokens: Long, val maxOutputTokens: Long)
data class ModelUsage(val input: Long, val output: Long)
data class ModelTurn(
    val decision: ModelDecision,
    val usage: ModelUsage? = null,
    val attempts: Int = 1,
    val statusCode: Int? = null
)
sealed interface ModelDecision {
    data class Read(val path: Path) : ModelDecision
    data class Done(val bytes: Int, val sha256: String) : ModelDecision
    data object Refusal : ModelDecision
}

/**
 * Only references to credentials are accepted in the request. Neither credentials nor
 * response bodies become diagnostics, exceptions, or event metadata.
 */
data class ModelConfig(
    val provider: String,
    val modelId: String,
    val baseUrl: String,
    val credentialRef: String,
    val contextTokens: Long,
    val maxOutputTokens: Long,
    val features: Set<String>,
    val temperature: Double?,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long
) {
    companion object {
        fun from(model: JsonObject) = ModelConfig(
            provider = model.getValue("provider").jsonPrimitive.content,
            modelId = model.getValue("modelId").jsonPrimitive.content,
            baseUrl = model.getValue("baseUrl").jsonPrimitive.content,
            credentialRef = model.getValue("credentialRef").jsonPrimitive.content,
            contextTokens = model.getValue("contextTokens").jsonPrimitive.long,
            maxOutputTokens = model.getValue("maxOutputTokens").jsonPrimitive.long,
            features = model.getValue("features").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            temperature = model["temperature"]?.jsonPrimitive?.double,
            connectTimeoutMs = model["connectTimeoutMs"]?.jsonPrimitive?.long ?: 3000L,
            requestTimeoutMs = model["requestTimeoutMs"]?.jsonPrimitive?.long ?: 30000L
        )
    }
}

/** Injection seam for the CLI, Koog agents, and deterministic offline tests. */
object ModelGateways {
    fun create(provider: String, fakeDelayMs: Long = 0): ModelGateway = when (provider) {
        "fake" -> FakeModel(fakeDelayMs)
        "openai-compatible" -> OpenAICompatibleGateway()
        else -> object : ModelGateway {
            override fun negotiate(
                config: ModelConfig, cancellation: Cancellation, deadline: Instant
            ): GatewayCapabilities =
                throw Refused("UNSUPPORTED_MODEL", "provider", "Unknown model provider")
            override fun next(
                config: ModelConfig, task: Path, observed: Pair<Int, String>?,
                cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
            ): ModelTurn =
                throw Refused("UNSUPPORTED_MODEL", "provider", "Unknown model provider")
        }
    }
}

/** The bootstrap fake remains network-free and produces the same trusted observations. */
class FakeModel(private val delayMs: Long = 0) : ModelGateway {
    override fun negotiate(config: ModelConfig, cancellation: Cancellation, deadline: Instant): GatewayCapabilities {
        if (config.provider != "fake" || config.modelId != "fake-v1" ||
            config.features != setOf("tool_calls"))
            throw Refused("UNSUPPORTED_MODEL", "provider", "Unsupported fake model configuration")
        cancellation.check(deadline)
        return GatewayCapabilities(setOf("tool_calls"), config.contextTokens, config.maxOutputTokens)
    }

    override fun next(
        config: ModelConfig, task: Path, observed: Pair<Int, String>?,
        cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
    ): ModelTurn {
        if (delayMs > 0) Thread.sleep(delayMs)
        cancellation.check(deadline)
        return ModelTurn(if (observed == null) ModelDecision.Read(task)
            else ModelDecision.Done(observed.first, observed.second))
    }
}

/**
 * Strict OpenAI Chat Completions adapter.
 *
 * The OpenAI-compatible /models/{id} response must advertise an explicit
 * 'capabilities' object (boolean feature keys), 'context_length' and
 * 'max_output_tokens'. Unlike guessed capabilities, missing fields fail closed.
 * This extension is intentionally documented because OpenAI-compatible servers
 * do not have a universal capability-discovery schema.
 */
class OpenAICompatibleGateway : ModelGateway {
    private val supported = setOf("tool_calls", "tool_choice", "streaming",
        "structured_outputs", "usage_metadata", "error_metadata")
    private val limitBytes = 1_048_576

    override fun negotiate(config: ModelConfig, cancellation: Cancellation, deadline: Instant): GatewayCapabilities {
        if (config.provider != "openai-compatible" || "tool_calls" !in config.features ||
            !supported.containsAll(config.features) || config.maxOutputTokens >= config.contextTokens)
            throw Refused("UNSUPPORTED_MODEL", "provider", "Unsupported model feature selection")
        val client = client(config)
        val response = send(client, request(config, "models/" + encode(config.modelId), "GET", null),
            config, cancellation, deadline, retryable = true)
        val record = parseObject(response.body)
        if (record["id"]?.jsonPrimitive?.content != config.modelId)
            throw unsupported()
        val featureObject = record["capabilities"] as? JsonObject ?: throw unsupported()
        val advertised = supported.filter {
            (featureObject[it] as? JsonPrimitive)?.booleanOrNull == true
        }.toSet()
        val context = record["context_length"]?.jsonPrimitive?.longOrNull ?: throw unsupported()
        val output = record["max_output_tokens"]?.jsonPrimitive?.longOrNull ?: throw unsupported()
        if (context <= 0 || output <= 0 || context < config.contextTokens ||
            output < config.maxOutputTokens || !advertised.containsAll(config.features))
            throw unsupported()
        cancellation.check(deadline)
        return GatewayCapabilities(advertised, context, output)
    }

    override fun next(
        config: ModelConfig, task: Path, observed: Pair<Int, String>?,
        cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
    ): ModelTurn {
        val stream = "streaming" in config.features
        val body = completionRequest(config, task, observed, stream)
        val response = send(client(config), request(config, "chat/completions", "POST", body),
            config, cancellation, deadline, retryable = allowRetry)
        val parsed = if (stream) parseStream(response.body) else parseCompletion(response.body)
        val decision = parseDecision(parsed, task, observed)
        if ("usage_metadata" in config.features && parsed.usage == null) throw unsupported()
        cancellation.check(deadline)
        return ModelTurn(decision, parsed.usage, response.attempts, response.status)
    }

    private fun unsupported() =
        Refused("MODEL_CAPABILITY_UNSUPPORTED", "provider", "Required model capability is unavailable")

    private fun client(config: ModelConfig) = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.connectTimeoutMs))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")

    private fun uri(config: ModelConfig, suffix: String): URI {
        val root = try { URI(config.baseUrl.trimEnd('/') + "/") } catch (_: Exception) { throw unsupported() }
        if (root.userInfo != null || root.rawQuery != null || root.fragment != null ||
            root.host == null || root.scheme !in setOf("http", "https"))
            throw unsupported()
        if (root.scheme == "http" && root.host !in setOf("localhost", "127.0.0.1", "::1", "[::1]"))
            throw Refused("MODEL_INSECURE_ENDPOINT", "policy", "Non-loopback model endpoints require HTTPS")
        return root.resolve(suffix)
    }

    private fun request(config: ModelConfig, path: String, method: String, body: String?): HttpRequest {
        val destination = uri(config, path)
        val builder = HttpRequest.newBuilder(destination)
            .timeout(Duration.ofMillis(config.requestTimeoutMs))
            .header("Accept", "application/json, text/event-stream")
        val envKey = config.credentialRef.removePrefix("env:")
        val secret = System.getenv(envKey)?.takeIf { it.isNotBlank() }
        if (secret == null && destination.scheme == "https")
            throw Refused("MODEL_CREDENTIAL_UNAVAILABLE", "provider", "Model credential is unavailable")
        if (secret != null) builder.header("Authorization", "Bearer $secret")
        return if (method == "GET") builder.GET().build()
        else builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body ?: "")).build()
    }

    private data class Reply(val body: String, val attempts: Int, val status: Int)

    private fun send(
        client: HttpClient, request: HttpRequest, config: ModelConfig,
        cancellation: Cancellation, deadline: Instant, retryable: Boolean
    ): Reply {
        val maxAttempts = if (retryable) 2 else 1
        for (attempt in 1..maxAttempts) {
            cancellation.check(deadline)
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (_: InterruptedException) {
                cancellation.check(deadline)
                Thread.currentThread().interrupt()
                throw Stopped(false)
            } catch (_: Exception) {
                cancellation.check(deadline)
                throw Refused("MODEL_TRANSPORT_FAILED", "provider", "Model transport failed")
            }
            var retryDelay: Long? = null
            response.body().use { input ->
                val code = response.statusCode()
                if (code == 401 || code == 403)
                    throw Refused("MODEL_AUTH_FAILED", "provider", "Model authentication failed")
                if (code in 300..399)
                    throw Refused("MODEL_REDIRECT_DENIED", "provider", "Model redirect denied")
                if (code == 429 || code in 500..599) {
                    if (attempt == maxAttempts)
                        throw Refused("MODEL_RETRY_EXHAUSTED", "provider", "Model temporarily unavailable")
                    val seconds = response.headers().firstValue("Retry-After").orElse("0")
                        .toLongOrNull()?.coerceIn(0, 1) ?: 0L
                    retryDelay = if (seconds == 0L) 100L else seconds * 1000L
                } else {
                    if (code !in 200..299)
                        throw Refused("MODEL_HTTP_ERROR", "provider", "Model request rejected")
                    val bytes = try { input.readNBytes(limitBytes + 1) } catch (_: Exception) {
                        cancellation.check(deadline)
                        throw Refused("MODEL_STREAM_INCOMPLETE", "provider", "Model response interrupted")
                    }
                    cancellation.check(deadline)
                    if (bytes.size > limitBytes)
                        throw Refused("MODEL_RESPONSE_TOO_LARGE", "provider", "Model response exceeds size limit")
                    return Reply(String(bytes, StandardCharsets.UTF_8), attempt, code)
                }
            }
            val delay = retryDelay ?: break
            try { Thread.sleep(delay) } catch (_: InterruptedException) {
                cancellation.check(deadline)
                throw Stopped(false)
            }
        }
        throw Refused("MODEL_RETRY_EXHAUSTED", "provider", "Model temporarily unavailable")
    }

    private fun completionRequest(
        config: ModelConfig, task: Path, observed: Pair<Int, String>?, streaming: Boolean
    ): String = buildJsonObject {
        put("model", config.modelId)
        put("max_tokens", config.maxOutputTokens)
        config.temperature?.let { put("temperature", it) }
        put("stream", streaming)
        if (streaming && "usage_metadata" in config.features)
            put("stream_options", buildJsonObject { put("include_usage", true) })
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", if (observed == null)
                    "Call read_task exactly once for the supplied path. Do not invent tool results."
                else "Return only a JSON object with bytes and sha256 exactly matching the trusted observation.")
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", if (observed == null) task.toString()
                    else "Trusted read: bytes=${observed.first}, sha256=${observed.second}")
            })
        })
        if (observed == null) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "read_task")
                        put("description", "Request a trusted read of the task file")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("required", buildJsonArray { add("path") })
                            put("properties", buildJsonObject {
                                put("path", buildJsonObject { put("type", "string") })
                            })
                        })
                    })
                })
            })
            if ("tool_choice" in config.features) put("tool_choice", "required")
        } else if ("structured_outputs" in config.features) {
            put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", "read_confirmation")
                    put("strict", true)
                    put("schema", buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", false)
                        put("required", buildJsonArray { add("bytes"); add("sha256") })
                        put("properties", buildJsonObject {
                            put("bytes", buildJsonObject { put("type", "integer") })
                            put("sha256", buildJsonObject { put("type", "string") })
                        })
                    })
                })
            })
        }
    }.toString()

    private data class Completion(
        val finish: String, val content: String, val toolName: String?,
        val toolArguments: String?, val refusal: Boolean, val usage: ModelUsage?
    )

    private fun parseObject(raw: String): JsonObject = try {
        Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException()
    } catch (_: Exception) {
        throw Refused("MODEL_MALFORMED_RESPONSE", "provider", "Malformed model response")
    }

    private fun usage(obj: JsonObject): ModelUsage? {
        val item = obj["usage"] as? JsonObject ?: return null
        val input = item["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: return null
        val output = item["completion_tokens"]?.jsonPrimitive?.longOrNull ?: return null
        if (input < 0 || output < 0) throw unsupported()
        return ModelUsage(input, output)
    }

    private fun parseCompletion(raw: String): Completion {
        val obj = parseObject(raw)
        val choice = (obj["choices"] as? JsonArray)?.singleOrNull() as? JsonObject ?: throw unsupported()
        val message = choice["message"] as? JsonObject ?: throw unsupported()
        val calls = message["tool_calls"] as? JsonArray
        if (calls != null && calls.size != 1) throw unsupported()
        val function = (calls?.singleOrNull() as? JsonObject)?.get("function") as? JsonObject
        return Completion(
            choice["finish_reason"]?.jsonPrimitive?.content ?: throw unsupported(),
            (message["content"] as? JsonPrimitive)?.contentOrNull ?: "",
            function?.get("name")?.jsonPrimitive?.contentOrNull,
            function?.get("arguments")?.jsonPrimitive?.contentOrNull,
            message["refusal"] != null && message["refusal"] != JsonNull,
            usage(obj)
        )
    }

    private fun parseStream(raw: String): Completion {
        val content = StringBuilder()
        val arguments = StringBuilder()
        var name: String? = null
        var finish: String? = null
        var refused = false
        var reportedUsage: ModelUsage? = null
        var done = false
        val events = raw.replace("\r\n", "\n").split("\n\n")
        for (event in events) {
            val data = event.lines().filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (data.isEmpty()) continue
            if (data == "[DONE]") { done = true; break }
            val obj = parseObject(data)
            usage(obj)?.let { reportedUsage = it }
            val choices = obj["choices"] as? JsonArray ?: throw unsupported()
            if (choices.isEmpty()) continue
            val choice = choices.singleOrNull() as? JsonObject ?: throw unsupported()
            choice["finish_reason"]?.takeUnless { it == JsonNull }?.let {
                finish = it.jsonPrimitive.content
            }
            val delta = choice["delta"] as? JsonObject ?: throw unsupported()
            val text = delta["content"] as? JsonPrimitive
            if (text != null && text != JsonNull) content.append(text.content)
            if (delta["refusal"] != null && delta["refusal"] != JsonNull) refused = true
            val calls = delta["tool_calls"] as? JsonArray
            calls?.forEach { element ->
                val call = element as? JsonObject ?: throw unsupported()
                if (call["index"]?.jsonPrimitive?.intOrNull != 0) throw unsupported()
                val function = call["function"] as? JsonObject ?: return@forEach
                function["name"]?.jsonPrimitive?.contentOrNull?.let { name = it }
                function["arguments"]?.jsonPrimitive?.contentOrNull?.let { arguments.append(it) }
            }
        }
        if (!done || finish == null)
            throw Refused("MODEL_STREAM_INCOMPLETE", "provider", "Model stream ended before completion")
        return Completion(finish!!, content.toString(), name,
            if (name != null) arguments.toString() else null, refused, reportedUsage)
    }

    private fun parseDecision(parsed: Completion, task: Path, observed: Pair<Int, String>?): ModelDecision {
        if (parsed.refusal) return ModelDecision.Refusal
        if (observed == null) {
            if (parsed.finish != "tool_calls" || parsed.toolName != "read_task" ||
                parsed.toolArguments == null) throw unsupported()
            val args = parseObject(parsed.toolArguments)
            if (args.keys != setOf("path")) throw unsupported()
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: throw unsupported()
            val parsedPath = try { Path.of(path) } catch (_: Exception) { throw unsupported() }
            if (parsedPath != task) throw unsupported()
            return ModelDecision.Read(parsedPath)
        }
        if (parsed.finish != "stop" || parsed.toolName != null) throw unsupported()
        val result = parseObject(parsed.content)
        if (result.keys != setOf("bytes", "sha256")) throw unsupported()
        val bytes = result["bytes"]?.jsonPrimitive?.intOrNull ?: throw unsupported()
        val sha = result["sha256"]?.jsonPrimitive?.contentOrNull ?: throw unsupported()
        if (bytes < 0 || !Regex("[a-f0-9]{64}").matches(sha)) throw unsupported()
        return ModelDecision.Done(bytes, sha)
    }
}
