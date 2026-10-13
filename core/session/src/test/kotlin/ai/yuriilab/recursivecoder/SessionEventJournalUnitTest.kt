package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Pure in-memory journal adapter shared with Session orchestration tests. */
internal class InMemoryEventSink : EventSink {
    val events = mutableListOf<JsonObject>()
    var closed = false
        private set
    var failAppend = false
    var failFlush = false
    var failClose = false

    override fun append(event: JsonObject) {
        check(!closed) { "Sink already closed" }
        if (failAppend) error("Injected append failure")
        events += event
    }

    override fun flush() {
        check(!closed) { "Sink already closed" }
        if (failFlush) error("Injected flush failure")
    }

    override fun close() {
        if (closed) return
        closed = true
        if (failClose) error("Injected close failure")
    }

    override fun sha256(): String {
        check(closed && !failClose) { "Sink was not closed successfully" }
        val serialized = events.joinToString("") { it.toString() + System.lineSeparator() }
        return MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@Tag("unit")
class SessionEventJournalUnitTest {
    private val instant = Instant.parse("2026-10-13T10:00:00Z")

    private fun journal(sink: EventSink) =
        SessionEventJournal("sess-1", "trace-1", "req-1",
            Clock.fixed(instant, ZoneOffset.UTC), sink)

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun eventEnvelopesPreserveIdentitySequenceCorrelationTimingAndRedaction() {
        val sink = InMemoryEventSink()
        val journal = journal(sink)
        journal.append("session.transition", "completed", "created" to "accepted")
        journal.append("tool.started", "started", toolId = "receipt-1",
            started = instant.minusMillis(25),
            extra = buildJsonObject { put("tool", "read"); put("receiptId", "receipt-1") })
        val sha = journal.finish()

        assertTrue(sink.closed)
        assertEquals(2, journal.sequence)
        assertEquals(64, sha.length)
        val (first, second) = sink.events
        assertEquals(listOf("session-event", "session-event"), sink.events.map { it.text("kind") })
        assertEquals(SessionProtocol.VERSION, first.text("version"))
        assertEquals(listOf(1, 2), sink.events.map { it.getValue("sequence").jsonPrimitive.int })
        assertEquals(listOf("evt-sess-1-1", "evt-sess-1-2"), sink.events.map { it.text("eventId") })
        assertEquals(listOf("req-1", "req-1"), sink.events.map { it.text("correlationId") })
        assertEquals(JsonNull, first["causationId"])
        assertEquals("evt-sess-1-1", second.text("causationId"))
        assertEquals(instant.toString(), first.text("timestamp"))
        assertEquals("sess-1", first.text("sessionId"))
        assertEquals("trace-1", first.text("traceId"))
        assertEquals("accepted", first.getValue("transition").jsonObject.text("to"))
        assertEquals("receipt-1", second.getValue("actor").jsonObject.text("toolExecutionId"))
        assertEquals(25L, second.getValue("timing").jsonObject.getValue("durationMs").jsonPrimitive.long)
        assertEquals("metadata-only", second.getValue("redaction").jsonObject.text("mode"))
        assertEquals(listOf("rawPrompt", "rawResponse", "rawToolOutput", "credentialRef", "authorizationHeader"),
            second.getValue("redaction").jsonObject.getValue("removedFields").jsonArray.map {
                it.jsonPrimitive.content
            })
        assertEquals(setOf("tool", "receiptId"), second.getValue("metadata").jsonObject.keys)
        assertFailsWith<IllegalStateException> { journal.append("agent.completed", "completed") }
    }

    @Test fun failedAppendOrFlushNeverAdvancesAcknowledgedSequence() {
        for (failure in listOf("append", "flush")) {
            val sink = InMemoryEventSink().apply {
                failAppend = failure == "append"
                failFlush = failure == "flush"
            }
            val journal = journal(sink)
            assertFailsWith<EventSinkFailure> { journal.append("llm.request", "started") }
            assertEquals(0, journal.sequence, failure)
            // The in-memory fake models a partial line on flush failure, but does not
            // let it acquire an acknowledged sequence number.
            assertEquals(if (failure == "flush") 1 else 0, sink.events.size)
            journal.close()
            assertTrue(sink.closed)
        }
    }

    @Test fun failedWriteCannotLaterProduceAVerifiedJournal() {
        val sink = InMemoryEventSink().apply { failFlush = true }
        val journal = journal(sink)
        val original = assertFailsWith<EventSinkFailure> {
            journal.append("llm.request", "started")
        }
        sink.failFlush = false
        assertSame(original, assertFailsWith<EventSinkFailure> {
            journal.append("llm.response", "completed")
        })
        assertSame(original, assertFailsWith<EventSinkFailure> { journal.finish() })
        assertTrue(sink.closed)
        assertEquals(0, journal.sequence)
    }

    @Test fun closeFailureCannotProduceACommitChecksum() {
        val sink = InMemoryEventSink().apply { failClose = true }
        val journal = journal(sink)
        journal.append("agent.started", "started")
        assertFailsWith<EventSinkFailure> { journal.finish() }
        assertEquals(1, journal.sequence)
        assertTrue(sink.closed)
    }
}
