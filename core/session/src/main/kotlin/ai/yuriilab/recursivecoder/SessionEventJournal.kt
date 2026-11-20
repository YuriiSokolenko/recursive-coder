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
    companion object {
        /** Exact root field set written by this emitter; not the entire Protocol v1 schema. */
        val emittedFields: Set<String> = setOf(
            "kind", "version", "sessionId", "traceId", "eventId", "sequence",
            "timestamp", "type", "status", "correlationId", "causationId",
            "actor", "transition", "timing", "redaction", "metadata"
        )

        fun eventId(sessionId: String, sequence: Int): String =
            "evt-" + sessionId + "-" + sequence

        /**
         * Canonical Session Protocol v1 event frame. Shared by the runtime
         * event journal and the trusted C1 -> C2 metadata-only emitter.
         * This is a pure serializer, never authority to append or resume.
         * Sequence continuity is independently verified by the sink.
         */
        fun frame(
            sessionId: String,
            traceId: String,
            requestId: String,
            sequence: Int,
            timestamp: Instant,
            type: String,
            status: String,
            transition: Pair<String, String>? = null,
            toolId: String? = null,
            started: Instant? = null,
            extra: JsonObject? = null,
            actor: SessionEventActor? = null,
            correlationId: String? = null
        ): JsonObject {
            require(sequence > 0)
            require(correlationId == null ||
                ToolAuthorizationPolicy.validHostIdentifier(correlationId))
            return buildJsonObject {
                put("kind", "session-event"); put("version", SessionProtocol.VERSION)
                put("sessionId", sessionId); put("traceId", traceId)
                put("eventId", eventId(sessionId, sequence))
                put("sequence", sequence)
                put("timestamp", timestamp.toString())
                put("type", type); put("status", status)
                put("correlationId", correlationId ?: requestId)
                put("causationId", if (sequence == 1) JsonNull else
                    JsonPrimitive(eventId(sessionId, sequence - 1)))
                put("actor", buildJsonObject {
                    put("agentId", actor?.agentId ?: "agent-" + sessionId)
                    put("parentAgentId",
                        actor?.parentAgentId?.let(::JsonPrimitive) ?: JsonNull)
                    put("childAgentId",
                        actor?.childAgentId?.let(::JsonPrimitive) ?: JsonNull)
                    put("toolExecutionId",
                        (actor?.toolExecutionId ?: toolId)?.let(::JsonPrimitive) ?: JsonNull)
                    put("childSessionId",
                        actor?.childSessionId?.let(::JsonPrimitive) ?: JsonNull)
                })
                transition?.let { (from, to) -> put("transition", buildJsonObject {
                    put("from", from); put("to", to)
                }) }
                started?.let { put("timing", buildJsonObject {
                    put("startedAt", it.toString())
                    put("durationMs", Duration.between(started, timestamp)
                        .toMillis().coerceAtLeast(0))
                }) }
                put("redaction", buildJsonObject {
                    put("mode", "metadata-only")
                    put("removedFields", buildJsonArray {
                        listOf("rawPrompt", "rawResponse", "rawToolOutput",
                            "credentialRef", "authorizationHeader").forEach { add(it) }
                    })
                })
                extra?.let { put("metadata", it) }
            }
        }
    }

    @Volatile var sequence: Int = 0
        private set
    private var previous: String? = null
    private var closed = false
    private var writeFailure: EventSinkFailure? = null

    @Synchronized fun append(type: String, status: String, transition: Pair<String, String>? = null,
               toolId: String? = null, started: Instant? = null, extra: JsonObject? = null,
               actor: SessionEventActor? = null, correlationId: String? = null) {
        check(!closed) { "Session event journal is already closed" }
        writeFailure?.let { throw it }
        // frame() performs the canonical correlationId validation.
        val next = Math.addExact(sequence, 1)
        val eventId = eventId(sessionId, next)
        val now = clock.instant()
        check(previous == if (next == 1) null else eventId(sessionId, next - 1))
        val event = frame(
            sessionId, traceId, requestId, next, now, type, status,
            transition, toolId, started, extra, actor, correlationId)
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
    @Synchronized fun finish(): String {
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
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { sink.close() } catch (e: Exception) { throw EventSinkFailure(e) }
        }
    }
}
