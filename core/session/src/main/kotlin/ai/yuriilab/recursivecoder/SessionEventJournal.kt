package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.*

/**
 * Session-owned output port. append and flush are distinct so the coordinator
 * never acknowledges an event until the sink has flushed its buffered data.
 * sha256 is available only after a successful close and describes the emitted bytes.
 * Durability across a process or machine crash is not promised by this port.
 */
interface EventSink : AutoCloseable {
    fun append(event: JsonObject)
    fun flush()
    fun sha256(): String
    override fun close()
}

fun interface EventSinkFactory {
    fun open(path: Path): EventSink
}

/** Never turn an unverified event log into a successful Session result. */
class EventSinkFailure(cause: Throwable) :
    IllegalStateException("Session event journal failed", cause)

/**
 * Host-derived stage identity for correlated recursive events. The default
 * actor remains unchanged for Session Protocol v1 read-only callers.
 */
data class SessionEventActor(
    val agentId: String,
    val parentAgentId: String? = null,
    val childAgentId: String? = null,
    val childSessionId: String? = null,
    val toolExecutionId: String? = null
) {
    init {
        require(listOfNotNull(agentId, parentAgentId, childAgentId,
            childSessionId, toolExecutionId).all(ToolAuthorizationPolicy::validHostIdentifier))
    }
}

/**
 * Pure event identity, correlation, sequence, timestamp and redaction policy.
 * No file operations or event serialization to physical storage belong here.
 */
class SessionEventJournal(
    private val sessionId: String,
    private val traceId: String,
    private val requestId: String,
    private val clock: Clock,
    private val sink: EventSink
) : AutoCloseable {
    var sequence: Int = 0
        private set
    private var previous: String? = null
    private var closed = false
    private var writeFailure: EventSinkFailure? = null

    fun append(type: String, status: String, transition: Pair<String, String>? = null,
               toolId: String? = null, started: Instant? = null, extra: JsonObject? = null,
               actor: SessionEventActor? = null, correlationId: String? = null) {
        check(!closed) { "Session event journal is already closed" }
        writeFailure?.let { throw it }
        require(correlationId == null || ToolAuthorizationPolicy.validHostIdentifier(correlationId))
        val next = Math.addExact(sequence, 1)
        val eventId = "evt-" + sessionId + "-" + next
        val now = clock.instant()
        val event = buildJsonObject {
            put("kind", "session-event"); put("version", SessionProtocol.VERSION)
            put("sessionId", sessionId); put("traceId", traceId)
            put("eventId", eventId); put("sequence", next)
            put("timestamp", now.toString()); put("type", type); put("status", status)
            put("correlationId", correlationId ?: requestId)
            put("causationId", previous?.let(::JsonPrimitive) ?: JsonNull)
            put("actor", buildJsonObject {
                put("agentId", actor?.agentId ?: "agent-" + sessionId)
                put("parentAgentId", actor?.parentAgentId?.let(::JsonPrimitive) ?: JsonNull)
                put("childAgentId", actor?.childAgentId?.let(::JsonPrimitive) ?: JsonNull)
                put("toolExecutionId", (actor?.toolExecutionId ?: toolId)?.let(::JsonPrimitive) ?: JsonNull)
                put("childSessionId", actor?.childSessionId?.let(::JsonPrimitive) ?: JsonNull)
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
        try {
            sink.append(event)
            sink.flush()
        } catch (e: Exception) {
            throw EventSinkFailure(e).also { writeFailure = it }
        }
        // Never advance the correlation chain before the sink acknowledges the write.
        sequence = next
        previous = eventId
    }

    /** Close and verify the recorded bytes before the result can be published. */
    fun finish(): String {
        check(!closed) { "Session event journal is already closed" }
        closed = true
        writeFailure?.let { original ->
            // An unacknowledged partial write is never a verified journal.
            try { sink.close() } catch (e: Exception) { original.addSuppressed(e) }
            throw original
        }
        return try {
            sink.close()
            sink.sha256().also { hash ->
                check(Regex("[a-f0-9]{64}").matches(hash)) { "Invalid journal checksum" }
            }
        } catch (e: Exception) {
            throw EventSinkFailure(e)
        }
    }

    /** Abort path: clean up a sink without publishing a checksum. */
    override fun close() {
        if (!closed) {
            closed = true
            try { sink.close() } catch (e: Exception) { throw EventSinkFailure(e) }
        }
    }
}
