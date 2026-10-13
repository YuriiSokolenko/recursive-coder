package ai.yuriilab.recursivecoder

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.*

/** Pure value types for the terminal Session decision, before protocol serialization. */
internal data class SessionFailure(val code: String, val category: String, val safeMessage: String)
internal data class SessionArtifact(val path: Path, val sha256: String)
internal data class SessionRunResult(
    val outcome: SessionOutcome,
    val startedAt: Instant,
    val finishedAt: Instant,
    val lastEventSeq: Long,
    val eventLog: SessionArtifact,
    val diagnostics: SessionArtifact,
    val failure: SessionFailure?,
    val usage: SessionBudgetState
)

/** Versioned JSON projection of a finished Session; never used by lifecycle/budget decisions. */
internal object SessionResultMapper {
    /** Digest the exact payload before any output file is created. */
    fun artifact(path: Path, content: String): SessionArtifact {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return SessionArtifact(path, sha256)
    }

    fun diagnostics(sessionId: String, outcome: SessionOutcome, sequence: Long): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("status", outcome.wire)
        put("events", sequence)
        put("summary", when (outcome) {
            SessionOutcome.SUCCEEDED -> "Read-only model session completed"
            SessionOutcome.CANCELLED -> "Session cancelled"
            SessionOutcome.TIMED_OUT -> "Session deadline exceeded"
            SessionOutcome.REJECTED -> "Session rejected before model invocation"
            SessionOutcome.FAILED -> "Session failed"
        })
    }

    fun encode(result: SessionRunResult, request: SessionRequest): JsonObject {
        // These checks apply after policy and before a terminal result can be written.
        // Messages intentionally contain no untrusted request, path or model text.
        require(result.lastEventSeq >= 0) { "Invalid session event sequence" }
        require((result.outcome == SessionOutcome.SUCCEEDED) == (result.failure == null)) {
            "Inconsistent session terminal result"
        }
        val digestPattern = Regex("[a-f0-9]{64}")
        require(digestPattern.matches(result.eventLog.sha256) &&
            digestPattern.matches(result.diagnostics.sha256)) { "Invalid session artifact digest" }

        return buildJsonObject {
            put("kind", "session-result"); put("version", SessionProtocol.VERSION)
            put("sessionId", request.sessionId); put("traceId", request.traceId)
            put("requestId", request.requestId); put("status", result.outcome.wire)
            put("startedAt", result.startedAt.toString()); put("finishedAt", result.finishedAt.toString())
            put("durationMs", Duration.between(result.startedAt, result.finishedAt).toMillis().coerceAtLeast(0))
            put("lastEventSeq", result.lastEventSeq)
            put("provenance", buildJsonObject {
                put("workspaceId", request.workspace.textAt("workspaceId"))
                put("baselineSha", request.workspace.textAt("baselineSha"))
                put("policySha256", request.policy.textAt("policySha256"))
                put("fenceGeneration", request.raw.objectAt("resume").longAt("fenceGeneration"))
            })
            put("outputs", buildJsonObject {
                put("eventLog", buildJsonObject {
                    put("path", result.eventLog.path.toString())
                    put("sha256", result.eventLog.sha256)
                })
                put("diagnostics", buildJsonObject {
                    put("path", result.diagnostics.path.toString())
                    put("sha256", result.diagnostics.sha256)
                })
                put("terminalReceipt", JsonNull); put("changeManifest", JsonNull)
                put("checkpoint", JsonNull)
            })
            put("error", result.failure?.let { failure ->
                buildJsonObject {
                    put("code", failure.code)
                    put("category", failure.category)
                    put("retryable", false)
                    put("safeMessage", failure.safeMessage)
                }
            } ?: JsonNull)
            put("usage", buildJsonObject {
                put("inputTokens", result.usage.inputTokens)
                put("outputTokens", result.usage.outputTokens)
                put("cacheReadTokens", 0); put("cacheWriteTokens", 0)
                put("totalTokens", result.usage.inputTokens + result.usage.outputTokens)
                put("completeness", if (result.usage.completeUsage) "complete" else "unknown")
            })
            // This validated DTO is copied unchanged to preserve field order and v1 wire form.
            put("invocation", request.invocation)
        }
    }
}


/** Compatibility transport projection; not part of the Use Case contract. */
internal fun SessionRunResult.toSessionProtocolJson(request: SessionRequest): JsonObject =
    SessionResultMapper.encode(this, request)
