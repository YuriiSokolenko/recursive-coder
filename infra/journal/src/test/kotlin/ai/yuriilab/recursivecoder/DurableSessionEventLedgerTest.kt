package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
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
        put("redaction", buildJsonObject {
            put("mode", "metadata-only")
            put("removedFields", JsonArray(emptyList()))
        })
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

    @Test fun unfencedCreateNewWriterRejectsFencedAppendWithoutPoisoning() {
        DurableSessionEventLedger.create(file(), workspace()).use { ledger ->
            val before = Files.readAllBytes(file())
            assertEquals("LEDGER_NOT_FENCED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    ledger.appendFenced(event(1))
                }.code)
            assertContentEquals(before, Files.readAllBytes(file()))
            ledger.append(event(1))
            ledger.flush()
        }
        assertEquals(1, DurableSessionEventLedger.inspect(file()).lastSequence)
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
        assertEquals("evt-sess-one-1", replay.events[0]["eventId"]!!.jsonPrimitive.content)
        assertEquals("evt-sess-one-1", replay.events[1]["causationId"]!!.jsonPrimitive.content)
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
            assertEquals("LEDGER_SENSITIVE_METADATA",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.append(event(1, buildJsonObject {
                        put("someMetadata", "x".repeat(513))
                    }))
                }.code)
            assertEquals("LEDGER_UNSUPPORTED_METADATA",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.append(event(1, buildJsonObject {
                        put("inputTokens", "not-a-number")
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

    @Test fun mixedSessionIdentityIsRejectedEvenWithCorrectCausation() {
        val path = file()
        val writer = DurableSessionEventLedger.create(path, workspace())
        try {
            writer.append(event(1))
            writer.flush()
            val foreign = JsonObject(event(2) + mapOf(
                "sessionId" to JsonPrimitive("foreign"),
                "eventId" to JsonPrimitive("evt-foreign-2")
            ))
            assertEquals("LEDGER_IDENTITY_CHANGED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.append(foreign)
                }.code)
            writer.append(event(2))
            writer.flush()
        } finally {
            writer.close()
        }
        assertEquals(2, DurableSessionEventLedger.inspect(path).lastSequence)
    }

    @Test fun incompatibleLedgerVersionDoesNotReplay() {
        val path = committedLedger()
        val bytes = Files.readAllBytes(path)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        Files.write(path, bytes)
        assertEquals("LEDGER_VERSION_UNSUPPORTED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)
    }


    /** Rewrite record two with a fresh valid chain hash: replay must inspect policy. */
    private fun rewriteSecond(path: Path, replacement: JsonObject) {
        val bytes = Files.readAllBytes(path)
        val magic = 7
        val firstSize = ByteBuffer.wrap(bytes, magic, 4).int
        val secondOffset = magic + 4 + 32 + firstSize
        val firstDigest = bytes.copyOfRange(magic + 4, magic + 4 + 32)
        val payload = replacement.toString().toByteArray(Charsets.UTF_8)
        val newDigest = MessageDigest.getInstance("SHA-256").digest(firstDigest + payload)
        val frame = ByteBuffer.allocate(4 + 32 + payload.size).putInt(payload.size)
            .put(newDigest).put(payload).array()
        Files.write(path, bytes.copyOfRange(0, secondOffset) + frame)
    }

    @Test fun validHashCannotHideReplaySequenceCausationOrIdentityDrift() {
        val cases = listOf(
            "sequence" to JsonObject(event(2) + ("sequence" to JsonPrimitive(3))),
            "causation" to JsonObject(event(2) + ("causationId" to JsonPrimitive("evt-forged-1"))),
            "identity" to JsonObject(event(2) + mapOf(
                "sessionId" to JsonPrimitive("foreign"),
                "eventId" to JsonPrimitive("evt-foreign-2"),
                "traceId" to JsonPrimitive("other-trace")
            ))
        )
        for ((case, replacement) in cases) {
            val path = temp.resolve(case + ".ledger")
            DurableSessionEventLedger.create(path, workspace()).use { writer ->
                writer.append(event(1)); writer.flush()
                writer.append(event(2)); writer.flush()
            }
            rewriteSecond(path, replacement)
            val expected = when (case) {
                "sequence" -> "LEDGER_EVENT_VERSION_OR_SEQUENCE"
                "causation" -> "LEDGER_EVENT_CHAIN_INVALID"
                else -> "LEDGER_IDENTITY_CHANGED"
            }
            assertEquals(expected,
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.inspect(path)
                }.code, case)
        }
    }

    private fun probeExclusiveLease(lockFile: Path): String {
        val javaSource = temp.resolve("LeaseProbe.java")
        Files.writeString(javaSource, """
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
public class LeaseProbe {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                System.out.println(lock == null ? "HELD" : "AVAILABLE");
            }
        }
    }
}
""".trimIndent())
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, javaSource.toString(), lockFile.toString())
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Lease probe did not exit")
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun failedSameJvmLeaseAttemptDoesNotReleaseOsLockForAnotherProcess() {
        val path = file()
        val writer = DurableSessionEventLedger.create(path, workspace())
        val lockFile = temp.resolve("events.ledger.lock")
        try {
            assertEquals("LEDGER_LEASE_HELD",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.inspect(path)
                }.code)
            assertEquals("LEDGER_LEASE_HELD",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.create(path, workspace())
                }.code)
            assertEquals("HELD", probeExclusiveLease(lockFile),
                "A rejected in-process channel must never drop the writer's OS lock")
        } finally {
            writer.close()
        }
        assertEquals("AVAILABLE", probeExclusiveLease(lockFile))
    }

    @Test fun inspectCannotCreateMissingLockFile() {
        val path = committedLedger()
        val lockPath = path.resolveSibling(path.fileName.toString() + ".lock")
        Files.delete(lockPath)

        assertEquals("LEDGER_LOCK_MISSING",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)
        assertFalse(Files.exists(lockPath), "Read-only inspection must not recreate the lock")
        assertTrue(Files.isRegularFile(path), "Ledger bytes remain in place")

        val missingLedger = temp.resolve("never-created.ledger")
        assertEquals("INVALID_LEDGER_PATH",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(missingLedger)
            }.code)
        assertFalse(Files.exists(missingLedger.resolveSibling("never-created.ledger.lock")))
    }

    @Test fun ledgerRejectsSymlinkedLockFileWithStableCode() {
        val target = temp.resolve("other.lock")
        Files.writeString(target, "")
        Files.createSymbolicLink(temp.resolve("events.ledger.lock"), target)
        assertEquals("UNSAFE_LEDGER_LOCK",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.create(file(), workspace())
            }.code)
    }

    @Test fun doubleCloseAndChangedAfterCloseAreDetected() {
        val path = file()
        val writer = DurableSessionEventLedger.create(path, workspace())
        writer.append(event(1)); writer.flush()
        writer.close()
        writer.close()
        val digest = writer.sha256()
        assertEquals(64, digest.length)
        val bytes = Files.readAllBytes(path)
        Files.write(path, bytes + byteArrayOf(1))
        assertFailsWith<SessionLedgerUnconfirmed> { writer.sha256() }
    }


    @Test fun replayRejectsZeroOrOversizedFrameLength() {
        val path = committedLedger()
        val original = Files.readAllBytes(path)
        for (invalidLength in listOf(0, 16_385)) {
            val bytes = original.copyOf()
            ByteBuffer.wrap(bytes, 7, 4).putInt(invalidLength)
            Files.write(path, bytes)
            assertEquals("LEDGER_RECORD_SIZE_INVALID",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.inspect(path)
                }.code)
        }
    }

    @Test fun nonMetadataFieldsAlsoFailClosedOnUntrustedContent() {
        val path = file()
        DurableSessionEventLedger.create(path, workspace()).use { writer ->
            val badActor = JsonObject(event(1) + ("actor" to buildJsonObject {
                put("agentId", "agent-sess-one")
                put("rawOutput", "leaked tool data")
            }))
            assertEquals("LEDGER_SENSITIVE_ACTOR",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.append(badActor) }.code)

            val badTransition = JsonObject(event(1) + ("transition" to buildJsonObject {
                put("from", "running")
                put("to", "succeeded")
                put("privateData", "leaked")
            }))
            assertEquals("LEDGER_SENSITIVE_TRANSITION",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.append(badTransition) }.code)

            val badTiming = JsonObject(event(1) + ("timing" to buildJsonObject {
                put("startedAt", "2026-11-15T00:00:00Z")
                put("durationMs", 10)
                put("extra", "secret")
            }))
            assertEquals("LEDGER_SENSITIVE_TIMING",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.append(badTiming) }.code)

            val badRedaction = JsonObject(event(1) + ("redaction" to buildJsonObject {
                put("mode", "metadata-only")
                put("removedFields", JsonArray(listOf(JsonPrimitive("customCredentials"))))
            }))
            assertEquals("LEDGER_REDACTION_REQUIRED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.append(badRedaction) }.code)

            writer.append(event(1))
            writer.flush()
        }
        assertEquals(1, DurableSessionEventLedger.inspect(path).lastSequence)
    }

    @Test fun workspaceBoundFactoryDoesNotNeedCallerToProvideWorkspaceRoot() {
        val factory = DurableSessionEventLedger.factory(workspace())
        factory.open(file()).use { sink ->
            sink.append(event(1))
            sink.flush()
        }
        assertEquals(1, DurableSessionEventLedger.inspect(file()).lastSequence)
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
        assertEquals("LEDGER_ALREADY_EXISTS",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.create(symlink, root)
            }.code)
        assertEquals("private", Files.readString(real))
    }
}
