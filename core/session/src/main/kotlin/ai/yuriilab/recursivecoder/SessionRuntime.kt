package ai.yuriilab.recursivecoder

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Resolve an output destination's existing ancestor before creating any directories. */
private fun canonicalDestination(path: Path): Path {
    val missing = ArrayDeque<Path>()
    var ancestor = path
    while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
        val name = ancestor.fileName ?: refuse("Invalid output path")
        missing.addFirst(name)
        ancestor = ancestor.parent ?: refuse("Invalid output path")
    }
    val realAncestor = try { ancestor.toRealPath() } catch (_: Exception) {
        throw Refused("UNSAFE_OUTPUT_PATH", "policy", "Cannot resolve output directory")
    }
    return missing.fold(realAncestor) { parent, part -> parent.resolve(part) }
}

private fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun digestFile(path: Path): String = digest(Files.readAllBytes(path))
private fun artifact(path: Path): JsonObject = buildJsonObject {
    put("path", path.toString())
    put("sha256", digestFile(path))
}
private fun JsonObject.stringOrNull(key: String): String? =
    if (this[key] == null || this[key] == JsonNull) null else textAt(key)

private fun SessionRequest.admit(workspaceOpener: WorkspaceOpener,
              onAudit: (ToolAudit) -> Unit = {},
              onStart: (String, String, Instant) -> Unit = { _, _, _ -> }): WorkspaceSession {
        if (!SessionProtocol.supports(raw.textAt("version")))
            throw Refused("UNSUPPORTED_PROTOCOL", "protocol", "Unsupported protocol version")
        val source = invocation.textAt("source")
        val attempt = invocation.longAt("attempt")
        if (source !in setOf("cli", "scheduler") || (source == "cli" && attempt != 1L))
            throw Refused("UNSUPPORTED_INVOCATION", "protocol", "Unsupported invocation source or attempt")
        if (raw.objectAt("resume").textAt("mode") != "fresh" ||
            raw.objectAt("resume").longAt("fenceGeneration") != 1L)
            throw Refused("UNSUPPORTED_RESUME", "checkpoint", "Resume is not supported")
        val supported = setOf("session-events-v1", "fake-model-v1", "model-gateway-v1", "read-only-tools-v1")
        if (!supported.containsAll(raw.stringsAt("requiredCapabilities")) ||
            "session-events-v1" !in raw.stringsAt("requiredCapabilities"))
            throw Refused("UNSUPPORTED_CAPABILITY", "protocol", "Required capability is unavailable")
        val provider = model.textAt("provider")
        if (provider !in setOf("fake", "openai-compatible") ||
            (provider == "fake" && ("fake-model-v1" !in raw.stringsAt("requiredCapabilities") ||
                "model-gateway-v1" in raw.stringsAt("requiredCapabilities") ||
                model.textAt("modelId") != "fake-v1" ||
                model.stringsAt("features").toSet() != setOf("tool_calls"))) ||
            (provider == "openai-compatible" && ("model-gateway-v1" !in raw.stringsAt("requiredCapabilities") ||
                "fake-model-v1" in raw.stringsAt("requiredCapabilities"))))
            throw Refused("UNSUPPORTED_MODEL", "provider", "Unsupported model provider or configuration")
        if (policy.textAt("mode") != "read-only" ||
            ("read" !in policy.stringsAt("allowedTools") || "read" in policy.stringsAt("deniedTools")) ||
            policy.stringsAt("writablePaths").isNotEmpty() ||
            (policy["immutableInputs"] as? JsonArray)?.isNotEmpty() == true)
            throw Refused("UNSUPPORTED_TOOL_POLICY", "policy", "Only read-only task access is supported")
        if (budgets.longAt("maxToolCalls") < 1 || (budgets["maxModelRequests"]?.let {
                budgets.longAt("maxModelRequests")
            } ?: Long.MAX_VALUE) < 2)
            throw Refused("INSUFFICIENT_BUDGET", "policy", "Two model turns and one read are required")
        val manager = workspaceOpener.open(this, onAudit, onStart)
        try {
            manager.verifyTask(taskPath, task.objectAt("taskInputRef").textAt("sha256"))
            return manager
        } catch (e: Exception) {
            manager.close()
            throw e
        }
    }

private class EventJournal(private val request: SessionRequest) : AutoCloseable {
    private val writer = Files.newBufferedWriter(request.output("eventsPath"),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    var sequence = 0
        private set
    private var previous: String? = null
    fun append(type: String, status: String, transition: Pair<String, String>? = null,
               toolId: String? = null, started: Instant? = null, extra: JsonObject? = null) {
        sequence++
        val eventId = "evt-" + request.sessionId + "-" + sequence
        val now = Instant.now()
        val event = buildJsonObject {
            put("kind", "session-event"); put("version", SessionProtocol.VERSION)
            put("sessionId", request.sessionId); put("traceId", request.traceId)
            put("eventId", eventId); put("sequence", sequence)
            put("timestamp", now.toString()); put("type", type); put("status", status)
            put("correlationId", request.requestId)
            put("causationId", previous?.let(::JsonPrimitive) ?: JsonNull)
            put("actor", buildJsonObject {
                put("agentId", JsonPrimitive("agent-" + request.sessionId))
                put("parentAgentId", JsonNull); put("childAgentId", JsonNull)
                put("toolExecutionId", toolId?.let(::JsonPrimitive) ?: JsonNull)
                put("childSessionId", JsonNull)
            })
            transition?.let { (from, to) -> put("transition", buildJsonObject {
                put("from", from); put("to", to)
            }) }
            started?.let { put("timing", buildJsonObject {
                put("startedAt", it.toString())
                put("durationMs", Duration.between(it, now).toMillis().coerceAtLeast(0))
            }) }
            put("redaction", buildJsonObject {
                put("mode", "metadata-only")
                put("removedFields", buildJsonArray {
                    listOf("rawPrompt", "rawResponse", "rawToolOutput", "credentialRef", "authorizationHeader")
                        .forEach { add(it) }
                })
            })
            extra?.let { put("metadata", it) }
        }
        writer.write(event.toString()); writer.newLine(); writer.flush()
        previous = eventId
    }
    override fun close() { writer.close() }
}

class SessionController(
    private val request: SessionRequest,
    private val model: ModelGateway,
    val cancellation: Cancellation = Cancellation(),
    private val workspaceOpener: WorkspaceOpener
) {
    private val finished = CountDownLatch(1)
    fun awaitFinished(timeoutMs: Long) = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun run(): JsonObject {
        cancellation.bind()
        val started = Instant.now()
        // Never open or truncate existing output files. A later recovery milestone owns replay.
        val outputRoot = request.outputRoot
        val workspaceCanonical = try { request.rootPath.toRealPath() } catch (_: Exception) {
            throw Refused("INVALID_WORKSPACE", "policy", "Cannot resolve workspace")
        }
        if (canonicalDestination(outputRoot).startsWith(workspaceCanonical) ||
            Files.isSymbolicLink(outputRoot))
            throw Refused("UNSAFE_OUTPUT_PATH", "policy", "Session output must be outside the workspace")
        Files.createDirectories(outputRoot)
        val targetPaths = listOf("eventsPath", "resultPath", "diagnosticsPath").map { request.output(it) }
        if (targetPaths.any { !it.startsWith(outputRoot) || Files.exists(it, LinkOption.NOFOLLOW_LINKS) })
            throw Refused("OUTPUT_ALREADY_EXISTS", "policy", "Output paths are unavailable")
        targetPaths.forEach { target ->
            if (target.parent != outputRoot) throw Refused("UNSAFE_OUTPUT_PATH", "policy", "Nested outputs are unsupported")
        }
        val journal = EventJournal(request)
        var state = "created"
        var status = "succeeded"
        var error: JsonElement = JsonNull
        val maxWall = request.budgets.longAt("maxWallTimeMs")
        val deadline = minOf(request.deadline, started.plusMillis(maxWall))
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "session-deadline").apply { isDaemon = true }
        }
        watchdog.schedule({ cancellation.expire() },
            Duration.between(Instant.now(), deadline).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
        fun wireState(state: String): String = when (state) {
            "admitted" -> "accepted"
            "cancelling" -> "cancelled"
            "completed" -> "succeeded"
            else -> state
        }
        fun transition(to: String, eventStatus: String = "completed") {
            journal.append("session.transition", eventStatus, wireState(state) to wireState(to))
            state = to
        }
        var workspaceManager: WorkspaceSession? = null
        val config = ModelConfig.from(request.model)
        var inputTokens = 0L
        var outputTokens = 0L
        var completeUsage = true
        try {
            workspaceManager = request.admit(workspaceOpener,
                onAudit = { audit ->
                    journal.append(if (audit.permitted) "tool.completed" else "policy.denied",
                        if (audit.permitted) "completed" else "denied",
                        toolId = audit.receiptId, started = audit.started,
                        extra = buildJsonObject {
                            put("tool", audit.tool)
                            put("receiptId", audit.receiptId)
                            put("bytes", audit.outputBytes)
                            audit.code?.let { put("deniedCode", it) }
                            audit.sha256?.let { put("sha256", it) }
                        })
                },
                onStart = { receipt, tool, startedAt ->
                    journal.append("tool.started", "started", toolId = receipt, started = startedAt,
                        extra = buildJsonObject { put("tool", tool); put("receiptId", receipt) })
                }
            )
            cancellation.check(deadline)
            val capabilities = model.negotiate(config, cancellation, deadline)
            journal.append("llm.capabilities", "completed", extra = buildJsonObject {
                put("contextTokens", capabilities.contextTokens)
                put("maxOutputTokens", capabilities.maxOutputTokens)
                put("features", capabilities.features.sorted().joinToString(","))
            })
            transition("admitted")
            transition("running")
            journal.append("agent.started", "started")
            var observed: Pair<Int, String>? = null
            // maxModelRequests counts actual Chat Completion HTTP POSTs, including retries;
            // capability-discovery GETs are not inference requests.
            val maxModelRequests = request.budgets["maxModelRequests"]?.let {
                request.budgets.longAt("maxModelRequests")
            } ?: Long.MAX_VALUE
            var modelRequestsMade = 0L
            repeat(2) { turn ->
                cancellation.check(deadline)
                val requestsRemaining = maxModelRequests - modelRequestsMade
                val turnsRemaining = (2 - turn).toLong()
                if (requestsRemaining < turnsRemaining)
                    throw Refused("MODEL_REQUEST_BUDGET_EXCEEDED", "policy", "Model request budget exhausted")
                // Reserve one request for the final confirmation before allowing a first-turn retry.
                val allowPreToolRetry = turn == 0 && requestsRemaining > turnsRemaining
                val modelStart = Instant.now()
                journal.append("llm.request", "started", started = modelStart,
                    extra = buildJsonObject { put("provider", config.provider); put("turn", turn + 1) })
                val response = try {
                    model.next(config, request.taskPath, observed, cancellation, deadline, allowPreToolRetry)
                } catch (_: InterruptedException) { cancellation.check(deadline); throw Stopped(false) }
                cancellation.check(deadline)
                // Reject faulty provider adapters before any tool side effects occur.
                if (response.attempts !in 1..(if (allowPreToolRetry) 2 else 1) ||
                    response.attempts.toLong() > requestsRemaining)
                    throw Refused("MODEL_REQUEST_BUDGET_EXCEEDED", "policy", "Model request budget exceeded")
                modelRequestsMade += response.attempts
                response.usage?.let {
                    val maxTokens = request.budgets.longAt("maxTotalTokens")
                    val alreadyUsed = inputTokens + outputTokens
                    if (it.input > maxTokens - alreadyUsed ||
                        it.output > maxTokens - alreadyUsed - it.input)
                        throw Refused("TOKEN_BUDGET_EXCEEDED", "policy", "Model token budget exceeded")
                    inputTokens += it.input
                    outputTokens += it.output
                } ?: run { completeUsage = false }
                journal.append("llm.response", "completed", started = modelStart,
                    extra = buildJsonObject {
                        put("provider", config.provider)
                        put("turn", turn + 1)
                        put("attempts", response.attempts)
                        response.statusCode?.let { put("httpStatus", it) }
                        response.usage?.let {
                            put("inputTokens", it.input)
                            put("outputTokens", it.output)
                        }
                    })
                when (val decision = response.decision) {
                    is ModelDecision.Read -> {
                        if (turn != 0 || decision.path != request.taskPath) refuse("Unexpected fake tool call")
                        cancellation.check(deadline)
                        val relative = workspaceManager!!.taskRelativePath(decision.path)
                        val outcome = workspaceManager.execute(WorkspaceTool.Read(relative))
                        if (!outcome.permitted)
                            throw Refused(outcome.deniedCode!!, "policy", "Read-only tool denied")
                        val read = outcome.data as? ToolData.FileText
                            ?: throw Refused("TOOL_RESULT_INVALID", "policy", "Unexpected tool result")
                        if (read.sha256 != request.task.objectAt("taskInputRef").textAt("sha256"))
                            throw Refused("TASK_CHANGED", "policy", "Task changed during execution")
                        observed = read.bytes to read.sha256
                    }
                    is ModelDecision.Done -> {
                        if (turn != 1 || observed != (decision.bytes to decision.sha256))
                            throw Refused("MODEL_INVALID_COMPLETION", "provider", "Model completion did not match trusted observation")
                    }
                    ModelDecision.Refusal ->
                        throw Refused("MODEL_REFUSAL", "provider", "Model declined the request")
                }
            }
            cancellation.check(deadline)
            journal.append("agent.completed", "completed")
            transition("completed")
        } catch (e: Refused) {
            status = if (state == "created") "rejected" else "failed"
            error = sessionError(e.code, e.category, e.explanation)
            journal.append(if (state == "created") "policy.denied" else "session.error", "denied")
            transition(if (state == "created") "rejected" else "failed", "denied")
        } catch (e: Stopped) {
            status = if (e.timedOut) "timed_out" else "cancelled"
            error = sessionError(if (e.timedOut) "SESSION_DEADLINE_EXCEEDED" else "SESSION_CANCELLED",
                if (e.timedOut) "timeout" else "cancellation",
                if (e.timedOut) "Session deadline exceeded" else "Session cancelled")
            if (!e.timedOut && state == "running") transition("cancelling", "cancelled")
            journal.append("session.error", if (e.timedOut) "timed_out" else "cancelled")
            transition(if (state == "created") "rejected" else "failed",
                if (e.timedOut) "timed_out" else "cancelled")
        } catch (_: Exception) {
            status = "failed"
            error = sessionError("SESSION_EXECUTION_FAILED", "infrastructure", "Session execution failed")
            journal.append("session.error", "failed")
            transition("failed", "failed")
        } finally {
            workspaceManager?.close()
            watchdog.shutdownNow()
            journal.close()
        }
        try {
            val finishedAt = Instant.now()
            val diagnostics = buildJsonObject {
                put("sessionId", request.sessionId); put("status", status)
                put("events", journal.sequence)
                put("summary", when (status) {
                    "succeeded" -> "Read-only model session completed"
                    "cancelled" -> "Session cancelled"
                    "timed_out" -> "Session deadline exceeded"
                    "rejected" -> "Session rejected before model invocation"
                    else -> "Session failed"
                })
            }
            Files.writeString(request.output("diagnosticsPath"), diagnostics.toString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val result = buildJsonObject {
                put("kind", "session-result"); put("version", SessionProtocol.VERSION)
                put("sessionId", request.sessionId); put("traceId", request.traceId)
                put("requestId", request.requestId); put("status", status)
                put("startedAt", started.toString()); put("finishedAt", finishedAt.toString())
                put("durationMs", Duration.between(started, finishedAt).toMillis().coerceAtLeast(0))
                put("lastEventSeq", journal.sequence)
                put("provenance", buildJsonObject {
                    put("workspaceId", request.workspace.textAt("workspaceId"))
                    put("baselineSha", request.workspace.textAt("baselineSha"))
                    put("policySha256", request.policy.textAt("policySha256"))
                    put("fenceGeneration", request.raw.objectAt("resume").longAt("fenceGeneration"))
                })
                put("outputs", buildJsonObject {
                    put("eventLog", artifact(request.output("eventsPath")))
                    put("diagnostics", artifact(request.output("diagnosticsPath")))
                    put("terminalReceipt", JsonNull); put("changeManifest", JsonNull)
                    put("checkpoint", JsonNull)
                })
                put("error", error)
                put("usage", buildJsonObject {
                    put("inputTokens", inputTokens); put("outputTokens", outputTokens)
                    put("cacheReadTokens", 0); put("cacheWriteTokens", 0)
                    put("totalTokens", inputTokens + outputTokens)
                    put("completeness", if (completeUsage) "complete" else "unknown")
                })
                put("invocation", request.invocation)
            }
            Files.writeString(request.output("resultPath"), result.toString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            return result
        } finally {
            finished.countDown()
        }
    }
}

private fun sessionError(code: String, category: String, message: String): JsonObject = buildJsonObject {
    put("code", code); put("category", category); put("retryable", false)
    put("safeMessage", message)
}
