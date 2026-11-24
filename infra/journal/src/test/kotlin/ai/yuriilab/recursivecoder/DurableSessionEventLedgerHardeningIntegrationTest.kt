package ai.yuriilab.recursivecoder

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * Non-resume fault evidence for #109. Verifying a physically present record
 * cannot establish acknowledgement or permission to replay side effects.
 */
@Tag("integration")
@OptIn(LedgerTestFaultInjection::class)
class DurableSessionEventLedgerHardeningIntegrationTest {
    @TempDir lateinit var temp: Path

    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private fun workspace() = temp.resolve("workspace").also { Files.createDirectories(it) }
    private fun ledger(name: String) = temp.resolve("$name.ledger")

    private fun event(sequence: Int, metadata: JsonObject? = null): JsonObject =
        SessionEventJournal.frame(
            sessionId = "sess-one", traceId = "trace-one", requestId = "request-one",
            sequence = sequence, timestamp = now, type = "agent.started",
            status = "started", extra = metadata
        )

    private fun journal(sink: EventSink) = SessionEventJournal(
        "sess-one", "trace-one", "request-one", Clock.fixed(now, ZoneOffset.UTC), sink
    )

    /** A failed append must permanently prevent SessionEventJournal acknowledgement. */
    @Test fun physicalWriteFaultsNeverAdvanceJournalOrPublishChecksum() {
        for (phase in listOf("before-record-write", "mid-record-write", "record-written")) {
            val path = ledger(phase)
            val writer = DurableSessionEventLedger.create(path, workspace())
            val sink = object : EventSink by writer {
                override fun append(event: JsonObject) {
                    writer.appendWithFault(event) { at ->
                        if (at == phase) throw IOException("injected-$at")
                    }
                }
            }
            val session = journal(sink)
            val failure = assertFailsWith<EventSinkFailure> {
                session.append("agent.started", "started")
            }
            assertEquals("LEDGER_WRITE_UNCONFIRMED",
                (failure.cause as SessionLedgerUnconfirmed).code, phase)
            assertEquals("injected-$phase", failure.cause?.cause?.message, phase)
            assertTrue(failure.cause?.cause is IOException, phase)
            assertEquals(0, session.sequence, phase)
            assertSame(failure, assertFailsWith<EventSinkFailure> { session.finish() })
            assertFailsWith<IllegalStateException> { writer.sha256() }
            if (phase == "mid-record-write") {
                assertEquals("LEDGER_TRUNCATED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        DurableSessionEventLedger.inspect(path)
                    }.code)
            } else {
                // An unacknowledged complete frame can look identical to a
                // committed frame; inspection intentionally only reports bytes.
                val physicallyPresent = if (phase == "record-written") 1 else 0
                assertEquals(physicallyPresent,
                    DurableSessionEventLedger.inspect(path).lastSequence, phase)
            }
        }
    }

    @Test fun forceFailuresBeforeAndAfterFsyncNeverAcknowledgeJournal() {
        for (phase in listOf("before-force", "forced-before-ack")) {
            val path = ledger(phase)
            val writer = DurableSessionEventLedger.create(path, workspace())
            val sink = object : EventSink by writer {
                override fun flush() {
                    writer.flushWithFault { at ->
                        if (at == phase) throw IOException("injected-$at")
                    }
                }
            }
            val session = journal(sink)
            val failure = assertFailsWith<EventSinkFailure> {
                session.append("agent.started", "started")
            }
            assertEquals("LEDGER_SYNC_UNCONFIRMED",
                (failure.cause as SessionLedgerUnconfirmed).code, phase)
            assertEquals("injected-$phase", failure.cause?.cause?.message, phase)
            assertTrue(failure.cause?.cause is IOException, phase)
            assertEquals(0, session.sequence, phase)
            assertSame(failure, assertFailsWith<EventSinkFailure> { session.finish() })
            assertFailsWith<IllegalStateException> { writer.sha256() }
            // Complete physical record != caller acknowledgement.
            assertEquals(1, DurableSessionEventLedger.inspect(path).lastSequence)
        }
    }

    /**
     * Assert the observable phase contract and the acknowledgement boundary.
     * A successful force is between callbacks in forcePending; this test
     * guards callback order, not durability across physical power loss.
     */
    @Test fun forceHooksPreservePhaseOrderAndDoNotPublishBeforeReturn() {
        val path = ledger("force-hook-order")
        val writer = DurableSessionEventLedger.create(path, workspace())
        val seen = mutableListOf<String>()
        writer.append(event(1))
        writer.flushWithFault { phase ->
            seen += phase
            assertEquals(
                listOf("before-force", "forced-before-ack").take(seen.size),
                seen,
                "Hook order changed"
            )
            // Neither callback is the ledger's commit acknowledgement.
            assertFailsWith<IllegalStateException> { writer.sha256() }
        }
        assertEquals(listOf("before-force", "forced-before-ack"), seen)
        writer.close()
        val verified = DurableSessionEventLedger.inspect(path)
        assertEquals(1, verified.lastSequence)
        assertEquals(verified.fileSha256, writer.sha256())
    }

    @Test fun headerCreationFaultsReleaseLeaseAndCannotClaimSuccessfulCreation() {
        for (phase in listOf("before-header-write", "header-written",
                "before-header-force", "header-forced", "parent-forced")) {
            val path = ledger(phase)
            val failure = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.createWithFault(path, workspace()) { at ->
                    if (at == phase) throw IOException("injected-$at")
                }
            }
            assertEquals("LEDGER_CREATE_UNCONFIRMED", failure.code, phase)
            assertTrue(failure.cause is IOException, phase)
            assertEquals("injected-$phase", failure.cause?.message, phase)
            val inspection = runCatching { DurableSessionEventLedger.inspect(path) }
            if (phase == "before-header-write") {
                assertEquals("LEDGER_SIZE_INVALID",
                    (inspection.exceptionOrNull() as SessionLedgerUnconfirmed).code)
            } else {
                assertEquals(0, inspection.getOrThrow().lastSequence)
            }
            // The failed creator can leave a valid, empty file and lock behind,
            // but a second CREATE_NEW must report existence, never a leaked lease.
            assertEquals("LEDGER_ALREADY_EXISTS",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.create(path, workspace())
                }.code, phase)
        }
    }

    @Test fun replayFailureSurvivesInjectedLockCleanupFailure() {
        val path = ledger("cleanup")
        DurableSessionEventLedger.create(path, workspace()).use {
            it.append(event(1)); it.flush()
        }
        val original = Files.readAllBytes(path)
        val damaged = original.copyOf()
        damaged[11] = (damaged[11].toInt() xor 1).toByte() // hash byte
        Files.write(path, damaged)
        val failure = assertFailsWith<SessionLedgerUnconfirmed> {
            DurableSessionEventLedger.inspectWithFault(path) {
                error("injected-lock-cleanup")
            }
        }
        assertEquals("LEDGER_HASH_MISMATCH", failure.code)
        assertEquals(1, failure.suppressed.size)
        assertEquals("injected-lock-cleanup", failure.suppressed.single().message)
        Files.write(path, original)
        assertEquals(1, DurableSessionEventLedger.inspect(path).lastSequence,
            "The cleanup fault must not leak the OS lock or JVM reservation")
    }

    @Test fun allowlistTracksActualSessionEventFrameAndRefusesUnknownKeys() {
        val metadata = buildJsonObject {
            put("inputTokens", Long.MAX_VALUE)
            put("bytes", 0)
            put("physicalCompleted", false)
            put("schedulerChargeRecorded", true)
            put("sha256", "a".repeat(64))
            put("artifactSha256", "b".repeat(64))
            put("receiptId", "receipt-001")
            put("graphId", "graph-one")
            put("roleEvent", "stage.started")
            put("reasonCode", "x".repeat(256))
        }
        val completeFrame = SessionEventJournal.frame(
            sessionId = "sess-one", traceId = "trace-one", requestId = "request-one",
            sequence = 1, timestamp = now, type = "agent.started", status = "started",
            transition = "created" to "accepted", started = now,
            extra = metadata, actor = SessionEventActor("agent-sess-one"),
            correlationId = "request-one"
        )
        assertEquals(SessionEventJournal.emittedFields, completeFrame.keys,
            "New root event fields require a deliberate ledger allowlist review")
        val path = ledger("allowlist")
        DurableSessionEventLedger.create(path, workspace()).use { writer ->
            val rejected = listOf(
                "LEDGER_UNSUPPORTED_METADATA" to buildJsonObject { put("inputTokens",
                    Json.parseToJsonElement("9223372036854775808")) },
                "LEDGER_UNSUPPORTED_METADATA" to buildJsonObject {
                    put("reasonCode", "x".repeat(257))
                },
                "LEDGER_SENSITIVE_METADATA" to buildJsonObject {
                    put("newEventKey", "unexpected")
                },
                "LEDGER_UNSUPPORTED_METADATA" to buildJsonObject { put("bytes", -1) }
            )
            for ((expectedCode, invalid) in rejected) {
                assertEquals(expectedCode,
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.append(event(1, invalid))
                    }.code)
            }
            assertEquals("LEDGER_UNSUPPORTED_FIELDS",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.append(JsonObject(completeFrame + ("surprise" to JsonPrimitive("data"))))
                }.code)
            writer.append(completeFrame)
            writer.flush()
        }
        assertEquals(metadata,
            DurableSessionEventLedger.inspect(path).events.single()["metadata"])
    }

    @Test fun replayRejectsMalformedLengthsVersionAndEncodingWithoutOversizedAllocation() {
        val path = ledger("length")
        DurableSessionEventLedger.create(path, workspace()).use {
            it.append(event(1)); it.flush()
        }
        val original = Files.readAllBytes(path)
        for (length in listOf(-1, 0, 16_385, Int.MAX_VALUE)) {
            val damaged = original.copyOf()
            ByteBuffer.wrap(damaged, 7, 4).putInt(length)
            Files.write(path, damaged)
            assertEquals("LEDGER_RECORD_SIZE_INVALID",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.inspect(path)
                }.code)
        }
        Files.write(path, original.copyOfRange(0, 9)) // half of size header
        assertEquals("LEDGER_TRUNCATED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)
        Files.write(path, original.copyOfRange(0, 7) + byteArrayOf(0, 0, 64, 0))
        assertEquals("LEDGER_TRUNCATED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)
        val unknownMagic = original.copyOf().also { it[5] = '9'.code.toByte() }
        Files.write(path, unknownMagic)
        assertEquals("LEDGER_VERSION_UNSUPPORTED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)

        val unsupportedVersion = JsonObject(event(1) + ("version" to JsonPrimitive("99")))
        Files.write(path, "RCLDG1\n".toByteArray(Charsets.US_ASCII) + framed(unsupportedVersion.toString().toByteArray()))
        assertEquals("LEDGER_EVENT_VERSION_OR_SEQUENCE",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)

        val utf8 = event(1).toString().toByteArray()
        val pos = utf8.indexOf('a'.code.toByte())
        assertTrue(pos >= 0)
        utf8[pos] = 0xff.toByte()
        Files.write(path, "RCLDG1\n".toByteArray(Charsets.US_ASCII) + framed(utf8))
        assertEquals("LEDGER_EVENT_INVALID_JSON",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)
    }

    @Test fun replayRejectsNumericSessionIdEvenWhenRootActorAndChainMatch() {
        val path = ledger("numeric-session-id")
        val valid = SessionEventJournal.frame(
            sessionId = "1", traceId = "trace-one", requestId = "request-one",
            sequence = 1, timestamp = now, type = "session.transition",
            status = "completed", transition = "accepted" to "running",
            actor = SessionEventActor("agent-1"),
            extra = buildJsonObject { put("graphId", "graph-root") }
        )
        val invalid = JsonObject(valid + ("sessionId" to JsonPrimitive(1)))
        // Replay validates physical framing, not lifecycle or execution authority.
        // Use an independent chain hash and change only the JSON value type.
        fun writeFrame(event: JsonObject) {
            Files.write(path, "RCLDG1\n".toByteArray(Charsets.US_ASCII) +
                framed(event.toString().toByteArray(Charsets.UTF_8)))
        }
        writeFrame(valid)
        assertEquals(valid, DurableSessionEventLedger.inspect(path).events.single())
        writeFrame(invalid)
        assertEquals("LEDGER_EVENT_INVALID",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)
    }

    @Test fun replayEventCeilingRefusesExcessEvenWithWellFormedFrames() {
        val path = ledger("replay-cap")
        DurableSessionEventLedger.create(path, workspace()).use {
            it.append(event(1)); it.flush()
            it.append(event(2)); it.flush()
        }
        // The same physical replay code runs with a lowered opt-in test
        // threshold; avoid 100,001 fsyncs and 100k allocated JSON objects.
        assertEquals("LEDGER_EVENT_LIMIT_EXCEEDED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspectWithEventLimitForTest(path, 1)
            }.code)
        assertEquals(2, DurableSessionEventLedger.inspect(path).lastSequence)
    }

    @Test fun sparseOversizedLedgerAndOverflowedOffsetsFailBeforeReplay() {
        val path = ledger("sparse")
        DurableSessionEventLedger.create(path, workspace()).close()
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            // Sparse extension: no 32 MiB heap allocation or 100k-event fsync loop.
            channel.position(32L * 1024 * 1024)
            channel.write(ByteBuffer.wrap(byteArrayOf(1)))
        }
        assertEquals("LEDGER_SIZE_INVALID",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.inspect(path)
            }.code)

        val cap = 32L * 1024 * 1024
        DurableSessionEventLedger.requireAppendCapacity(99_999, cap - 36 - 100, 100)
        assertEquals("LEDGER_RECORD_SIZE_INVALID",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireAppendCapacity(
                    99_999, cap - 36 - 100 + 1, 100)
            }.code)
        val live = DurableSessionEventLedger.MAX_LIVE_CHECKPOINT_LEDGER_BYTES
        DurableSessionEventLedger.requireReopenCheckpointCapacity(live - 36 - 100, 100)
        assertEquals("LEDGER_REOPEN_CHECKPOINT_CAPACITY",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireReopenCheckpointCapacity(
                    live - 36 - 100 + 1, 100)
            }.code)
        assertEquals("LEDGER_RECORD_SIZE_INVALID",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireAppendCapacity(0, Long.MAX_VALUE, 100)
            }.code)
        assertEquals("LEDGER_EVENT_LIMIT_EXCEEDED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireAppendCapacity(100_000, 7, 1)
            }.code)
        assertEquals("LEDGER_REOPEN_CHECKPOINT_CAPACITY",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireReopenCheckpointCapacity(Long.MAX_VALUE, 100)
            }.code)
    }

    /** Exact wire frame, independently produced: tests the physical prefix only. */
    private fun framed(payload: ByteArray, previous: ByteArray = ByteArray(32)): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(previous + payload)
        return ByteBuffer.allocate(4 + 32 + payload.size)
            .putInt(payload.size).put(hash).put(payload).array()
    }

    /**
     * CI/Gradle supplies the exact test runtime classpath. Standalone IDE
     * runs can use their own java.class.path; never guess transitive jars
     * from a handpicked set of class locations.
     */
    private fun forkClasspath(): String {
        val fromGradle = System.getProperty("recursivecoder.fork.classpath")
            ?.takeIf { it.isNotBlank() }
        if (fromGradle != null) return fromGradle
        check(!System.getenv("CI").equals("true", ignoreCase = true)) {
            "Gradle must provide recursivecoder.fork.classpath on CI"
        }
        // A direct IDE launch can use its local runtime classpath.
        return System.getProperty("java.class.path")
            ?.takeIf { it.isNotBlank() }
            ?: error("No test-runtime classpath available for LedgerCrashProcess")
    }

    /**
     * The child calls the actual ledger create/append/flush APIs and holds
     * its OS lease until killed. No synthetic FileChannel writer or compilation
     * on the runner is involved.
     */
    private fun killWriter(path: Path, mode: String) {
        val output = temp.resolve("$mode.stdout")
        val errors = temp.resolve("$mode.stderr")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-cp", forkClasspath(), LedgerCrashProcess::class.java.name,
            path.toString(), workspace().toString(), mode
        ).redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
            while (!Files.exists(output) ||
                !Files.readString(output).contains("READY:$mode")) {
                val diagnostics = if (Files.exists(errors)) Files.readString(errors) else ""
                assertTrue(process.isAlive, "Child JVM exited before READY: $diagnostics")
                assertTrue(System.nanoTime() < deadline,
                    "Child JVM did not reach ledger boundary: $diagnostics")
                Thread.sleep(25)
            }
            assertTrue(process.isAlive, "Child JVM ended unexpectedly")
            assertEquals("LEDGER_LEASE_HELD",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.inspect(path)
                }.code, "The actual ledger writer must hold its OS lease until killed")
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child JVM failed to die")
            assertNotEquals(0, process.exitValue(), "The child must be killed, not closed")
        } finally {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test fun killedJvmsClassifyPhysicalPrefixesOnly() {
        val hashes = mutableMapOf<String, String>()
        for (mode in listOf("acknowledged", "fully-present-unacknowledged", "torn")) {
            val path = ledger(mode)
            killWriter(path, mode)
            if (mode == "torn") {
                assertEquals("LEDGER_TRUNCATED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        DurableSessionEventLedger.inspect(path)
                    }.code)
            } else {
                val evidence = DurableSessionEventLedger.inspect(path)
                assertEquals(2, evidence.lastSequence)
                assertEquals(Files.size(path), evidence.lastTrustedOffset)
                assertEquals(listOf("agent.started", "agent.started"),
                    evidence.events.map { it["type"]!!.jsonPrimitive.content })
                // A checksum is evidence of bytes, not a terminal outcome,
                // a successful side effect, or safe resume authority.
                assertTrue(evidence.events.none {
                    it["type"]?.jsonPrimitive?.content == "agent.completed"
                })
                hashes[mode] = evidence.fileSha256
            }
        }
        // Killing a process preserves the OS page cache. No amount of
        // read-only replay can distinguish these byte-identical outcomes.
        assertEquals(hashes["acknowledged"], hashes["fully-present-unacknowledged"])
    }
}

/**
 * Invoked by an independent java process in the integration fixture above.
 * Uses only real ledger APIs. The parent kills this process without close().
 */
@OptIn(LedgerTestFaultInjection::class)
object LedgerCrashProcess {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3)
        val path = Path.of(args[0])
        val workspace = Path.of(args[1])
        val mode = args[2]
        val now = Instant.parse("2026-11-15T00:00:00Z")
        fun event(sequence: Int) = SessionEventJournal.frame(
            sessionId = "sess-one", traceId = "trace-one", requestId = "request-one",
            sequence = sequence, timestamp = now,
            type = "agent.started", status = "started"
        )
        val writer = DurableSessionEventLedger.create(path, workspace)
        writer.append(event(1))
        writer.flush()
        when (mode) {
            "acknowledged" -> {
                writer.append(event(2))
                writer.flush()
            }
            "fully-present-unacknowledged" -> writer.append(event(2))
            "torn" -> {
                val failure = runCatching {
                    writer.appendWithFault(event(2)) { phase ->
                        if (phase == "mid-record-write") throw IOException("torn-record")
                    }
                }.exceptionOrNull() as? SessionLedgerUnconfirmed
                check(failure?.code == "LEDGER_WRITE_UNCONFIRMED")
            }
            else -> error("Unknown crash mode $mode")
        }
        println("READY:$mode")
        System.out.flush()
        Thread.sleep(TimeUnit.MINUTES.toMillis(1))
    }
}
