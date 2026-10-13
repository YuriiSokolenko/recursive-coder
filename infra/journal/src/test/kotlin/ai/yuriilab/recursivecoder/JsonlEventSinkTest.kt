package ai.yuriilab.recursivecoder

import java.io.BufferedWriter
import java.io.IOException
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

@Tag("integration")
class JsonlEventSinkTest {
    @TempDir lateinit var temp: Path
    private val instant = Instant.parse("2026-10-13T10:00:00Z")

    private fun journal(sink: EventSink) =
        SessionEventJournal("sess-1", "trace-1", "req-1",
            Clock.fixed(instant, ZoneOffset.UTC), sink)

    private fun physicalWriter(path: Path): BufferedWriter =
        Files.newBufferedWriter(path, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

    @Test fun persistsExactJsonlBytesAndDigestAfterClose() {
        val path = temp.resolve("events.jsonl")
        val sink = JsonlEventSink.open(path)
        val journal = journal(sink)
        journal.append("agent.started", "started")
        journal.append("session.transition", "completed", "running" to "succeeded")
        val sha = journal.finish()

        val bytes = Files.readAllBytes(path)
        assertEquals(sha, MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) })
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        assertEquals(2, lines.size)
        assertEquals(listOf(1, 2), lines.map {
            Json.parseToJsonElement(it).jsonObject.getValue("sequence").jsonPrimitive.int
        })
        assertEquals((lines.joinToString(System.lineSeparator()) + System.lineSeparator()),
            String(bytes, Charsets.UTF_8))
        assertEquals("evt-sess-1-1",
            Json.parseToJsonElement(lines[1]).jsonObject.getValue("causationId").jsonPrimitive.content)
        assertFailsWith<IllegalStateException> { sink.append(buildJsonObject {}) }
    }

    @Test fun neverOverwritesAnExistingEventLog() {
        val path = temp.resolve("events.jsonl")
        Files.writeString(path, "do-not-overwrite")
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { JsonlEventSink.open(path) }
        assertEquals("do-not-overwrite", Files.readString(path))
    }

    @Test fun partialWriteIsNeverAcknowledgedAsAnEvent() {
        val path = temp.resolve("partial.jsonl")
        val file = physicalWriter(path)
        val broken = object : Writer() {
            override fun write(chars: CharArray, off: Int, len: Int) {
                file.write(chars, off, minOf(17, len))
                file.flush()
                throw IOException("Injected partial write")
            }
            override fun flush() = file.flush()
            override fun close() = file.close()
        }
        val journal = journal(JsonlEventSink(path, BufferedWriter(broken, 1)))

        assertFailsWith<EventSinkFailure> { journal.append("llm.request", "started") }
        assertEquals(0, journal.sequence)
        journal.close()
        val partial = Files.readString(path)
        assertTrue(partial.isNotEmpty())
        assertFalse(partial.endsWith(System.lineSeparator()))
        assertFailsWith<Exception> { Json.parseToJsonElement(partial) }
    }

    @Test fun failedFlushIsNotCountedAsCommittedEvent() {
        val path = temp.resolve("flush.jsonl")
        val file = physicalWriter(path)
        val broken = object : Writer() {
            override fun write(chars: CharArray, off: Int, len: Int) = file.write(chars, off, len)
            override fun flush(): Unit = throw IOException("Injected flush failure")
            override fun close() = file.close()
        }
        val journal = journal(JsonlEventSink(path, BufferedWriter(broken)))
        assertFailsWith<EventSinkFailure> { journal.append("agent.started", "started") }
        assertEquals(0, journal.sequence)
        journal.close()
    }

    @Test fun failedCloseCannotReturnVerifiedChecksum() {
        val path = temp.resolve("close.jsonl")
        val file = physicalWriter(path)
        val broken = object : Writer() {
            override fun write(chars: CharArray, off: Int, len: Int) = file.write(chars, off, len)
            override fun flush() = file.flush()
            override fun close() {
                file.close()
                throw IOException("Injected close failure")
            }
        }
        val sink = JsonlEventSink(path, BufferedWriter(broken))
        val journal = journal(sink)
        journal.append("agent.started", "started")
        assertFailsWith<EventSinkFailure> { journal.finish() }
        assertFailsWith<IllegalStateException> { sink.sha256() }
    }
}
