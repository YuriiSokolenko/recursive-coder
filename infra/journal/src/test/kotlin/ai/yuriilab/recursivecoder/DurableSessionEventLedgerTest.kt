package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

@Tag("integration")
class DurableSessionEventLedgerTest {
    @TempDir lateinit var temp: Path

    private fun workspace(): Path = temp.resolve("workspace").also {
        Files.createDirectories(it)
    }

    private fun file(): Path = temp.resolve("events.ledger")

    private fun event(sequence: Int, extra: JsonObject? = null): JsonObject = buildJsonObject {
        put("kind", "session-event")
        put("version", SessionProtocol.VERSION)
        put("sessionId", "sess-one")
        put("traceId", "trace-one")
        put("eventId", "evt-sess-one-" + sequence)
        put("sequence", sequence)
        put("timestamp", "2026-11-15T00:00:00Z")
        put("type", "agent.started")
        put("status", "started")
        put("correlationId", "request-one")
        if (sequence == 1) put("causationId", JsonNull)
        else put("causationId", "evt-sess-one-" + (sequence - 1))
        put("actor", buildJsonObject { put("agentId", "agent-sess-one") })
        put("redaction", buildJsonObject { put("mode", "metadata-only") })
        extra?.let { put("metadata", it) }
    }

    private fun committedLedger(): Path {
        val path = file()
        DurableSessionEventLedger.create(path, workspace()).use { ledger ->
            ledger.append(event(1))
            ledger.flush()
            ledger.append(event(2))
            ledger.flush()
        }
        return path
    }

    @Test fun sessionJournalAcknowledgesFsyncedEventsAndVerifiedReplay() {
        val path = file()
        val sink = DurableSessionEventLedger.create(path, workspace())
        val journal = SessionEventJournal("sess-one", "trace-one", "request-one",
            Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC), sink)
        journal.append("agent.started", "started")
        journal.append("agent.completed", "completed")
        val verifiedDigest = journal.finish()

        val replay = DurableSessionEventLedger.inspect(path)
        assertEquals(2, replay.lastSequence)
        assertEquals(2, replay.events.size)
        assertEquals("evt-sess-one-1", replay.events[0]["eventId"]!!.toString().trim('"'))
        assertEquals("evt-sess-one-1", replay.events[1]["causationId"]!!.toString().trim('"'))
        assertEquals(verifiedDigest, replay.fileSha256)
        assertEquals(64, replay.headSha256.length)
        assertEquals(64, replay.fileSha256.length)
    }

    @Test fun writerAndVerifiedReaderCannotOverlapLiveOwner() {
        val path = file()
        val writer = DurableSessionEventLedger.create(path, workspace())
        try {
            val exception = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }
            assertEquals("LEDGER_LEASE_HELD", exception.code)
            assertEquals("LEDGER_LEASE_HELD",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.create(path, workspace())
                }.code)
        } finally {
            writer.close()
        }
        assertEquals(0, DurableSessionEventLedger.inspect(path).lastSequence)
    }

    @Test fun rejectedMetadataNeverAdvancesCommittedSequence() {
        val path = file()
        val writer = DurableSessionEventLedger.create(path, workspace())
        try {
            val bad = event(1, buildJsonObject { put("rawPrompt", "secret prompt") })
            val ex = assertFailsWith<SessionLedgerUnconfirmed> { writer.append(bad) }
            assertEquals("LEDGER_SENSITIVE_METADATA", ex.code)
            writer.append(event(1, buildJsonObject { put("inputTokens", 5) }))
            writer.flush()
        } finally {
            writer.close()
        }
        assertEquals(1, DurableSessionEventLedger.inspect(path).lastSequence)
    }

    @Test fun incompleteTailAndTamperedBytesRefuseVerifiedReplay() {
        val path = committedLedger()
        val original = Files.readAllBytes(path)
        Files.write(path, original.copyOf(original.size - 1))
        val tail = assertFailsWith<SessionLedgerUnconfirmed> {
            DurableSessionEventLedger.inspect(path)
        }
        assertEquals("LEDGER_TRUNCATED", tail.code)

        val mutated = original.copyOf()
        mutated[mutated.lastIndex] = (mutated.last().toInt() xor 1).toByte()
        Files.write(path, mutated)
        val tampered = assertFailsWith<SessionLedgerUnconfirmed> {
            DurableSessionEventLedger.inspect(path)
        }
        assertEquals("LEDGER_HASH_MISMATCH", tampered.code)
    }

    @Test fun wrongOrderVersionAndOversizedMetadataAreDeniedBeforeWrite() {
        val path = file()
        val writer = DurableSessionEventLedger.create(path, workspace())
        try {
            assertEquals("LEDGER_EVENT_VERSION_OR_SEQUENCE",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.append(event(2))
                }.code)
            assertEquals("LEDGER_UNSUPPORTED_METADATA",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.append(event(1, buildJsonObject {
                        put("someMetadata", "x".repeat(513))
                    }))
                }.code)
            assertEquals("LEDGER_RECORD_SIZE_INVALID",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.append(event(1, buildJsonObject {
                        repeat(40) { put("safeValue" + it, "x".repeat(500)) }
                    }))
                }.code)
            writer.append(event(1))
            writer.flush()
            assertEquals("LEDGER_EVENT_VERSION_OR_SEQUENCE",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.append(event(1)) }.code)
        } finally {
            writer.close()
        }
        assertEquals(1, DurableSessionEventLedger.inspect(path).lastSequence)
    }

    @Test fun unflushedAppendCannotPublishChecksum() {
        val path = file()
        val writer = DurableSessionEventLedger.create(path, workspace())
        writer.append(event(1))
        assertEquals("LEDGER_UNACKNOWLEDGED_APPEND",
            assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
        assertFailsWith<IllegalStateException> { writer.sha256() }
    }

    @Test fun workspaceAndSymlinkDestinationsAreRefused() {
        val root = workspace()
        val inside = root.resolve("events.ledger")
        assertEquals("LEDGER_INSIDE_WORKSPACE",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.create(inside, root)
            }.code)
        val real = temp.resolve("real.ledger")
        Files.writeString(real, "private")
        val symlink = temp.resolve("link.ledger")
        Files.createSymbolicLink(symlink, real)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> {
            DurableSessionEventLedger.create(symlink, root)
        }
        assertEquals("private", Files.readString(real))
    }
}
