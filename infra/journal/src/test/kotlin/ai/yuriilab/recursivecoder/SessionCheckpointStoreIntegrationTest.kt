package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.FileSystems
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.attribute.PosixFilePermission
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

@Tag("integration")
class SessionCheckpointStoreIntegrationTest {
    @TempDir lateinit var temp: Path

    @BeforeEach fun requiresLinuxCheckpointFilesystem() {
        val supported = System.getProperty("os.name") == "Linux" &&
            FileSystems.getDefault().supportedFileAttributeViews().contains("unix") &&
            LinuxCheckpointOwner.effectiveUid() != null
        val reason = "Checkpoint ownership requires Linux procfs and unix file attributes"
        // Unsupported developer platforms may skip; CI must fail rather than
        // report a green build after silently skipping the whole suite.
        if (System.getenv("CI") != null) {
            assertTrue(supported, reason)
        } else {
            assumeTrue(supported, reason)
        }
    }
    private val artifactDigest = "f".repeat(64)

    private fun workspace(): Path = temp.resolve("workspace").also { Files.createDirectories(it) }
    private fun store(): Path = temp.resolve("checkpoints").also {
        if (!Files.exists(it)) {
            Files.createDirectories(it)
            Files.setPosixFilePermissions(it, setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE))
        }
    }
    private fun ledger(): Path = temp.resolve("events.ledger")

    private fun context() = CheckpointHostContext(workspace(), "a".repeat(64),
        "b".repeat(40), "refs/heads/dev", "c".repeat(64),
        "graph-root", 5, "d".repeat(64))

    private fun event(sequence: Int, correlation: String, status: String,
                      metadata: JsonObject, kind: String = if (sequence == 1) "agent.started" else "tool.completed"
    ): JsonObject = buildJsonObject {
        put("kind", "session-event")
        put("version", SessionProtocol.VERSION)
        put("sessionId", "sess-one")
        put("traceId", "trace-one")
        put("eventId", "evt-sess-one-" + sequence)
        put("sequence", sequence)
        put("timestamp", "2026-11-15T00:00:00Z")
        put("type", kind)
        put("status", status)
        put("correlationId", correlation)
        put("causationId", if (sequence == 1) JsonNull else
            JsonPrimitive("evt-sess-one-" + (sequence - 1)))
        put("actor", buildJsonObject { put("agentId", "agent-sess-one") })
        put("redaction", buildJsonObject {
            put("mode", "metadata-only")
            put("removedFields", buildJsonArray { })
        })
        put("metadata", metadata)
    }

    private fun createLedger() {
        DurableSessionEventLedger.create(ledger(), workspace()).use { writer ->
            writer.append(event(1, "req-one", "started", buildJsonObject {
                put("graphId", "graph-root")
            }))
            writer.flush()
            writer.append(event(2, "req-two", "completed", buildJsonObject {
                put("graphId", "graph-root")
                put("childGraphId", "graph-child")
                put("artifactId", "artifact-one")
                put("artifactSha256", artifactDigest)
            }))
            writer.flush()
        }
    }

    private fun snapshot(sequence: Long = 1, previous: String? = null,
                         unknown: Boolean = false,
                         providedAnchor: VerifiedSessionEventLedger? = null): SessionCheckpoint {
        val verified = providedAnchor ?: DurableSessionEventLedger.inspect(ledger())
        return SessionCheckpoint(
            sequence, previous, context().binding(),
            "sess-one", "trace-one", "running", "task-one", 1, "running",
            listOf(CheckpointChild("graph-child", "graph-root", "succeeded")),
            listOf(CheckpointArtifact("artifact-one", "graph-child", artifactDigest)),
            listOf(
                HostAssertedCheckpointUsage("task-one", "model", if (unknown) "unknown" else "known",
                    if (unknown) null else 42L),
                HostAssertedCheckpointUsage("task-one", "tool", "known", 1L)
            ),
            if (unknown) listOf(CheckpointEffect("effect-one", "req-two", "unknown"))
                else emptyList(),
            verified.lastSequence, verified.headSha256, verified.fileSha256,
            verified.lastTrustedOffset
        )
    }

    private fun save(s: SessionCheckpoint, previous: String? = null) =
        SessionCheckpointStore.save(store(), context(), ledger(), s, previous)

    private fun inspect() = SessionCheckpointStore.inspect(store(), context(), ledger())

    // Dense but protocol-valid metadata keeps the real 4 MiB boundary tests
    // to roughly a thousand fsynced records rather than tens of thousands.
    private val paddingKeys = listOf("tool", "provider", "features", "deniedCode",
        "recursiveEvent", "roleEvent", "origin", "role", "kind", "stageState",
        "approval", "verdict", "reasonCode", "scope", "code", "childResourceAudit",
        "recursiveStopCode")

    private fun frameBytes(value: JsonObject): Int =
        36 + value.toString().toByteArray(StandardCharsets.UTF_8).size

    private fun maximumPaddingEvent(sequence: Int): JsonObject =
        event(sequence, "req-padding", "started", buildJsonObject {
            paddingKeys.forEach { put(it, "x".repeat(256)) }
        }, "agent.started")

    private fun preciselySizedEvent(sequence: Int, frameSize: Int): JsonObject {
        for (count in 1..paddingKeys.size) {
            val selected = paddingKeys.take(count)
            val minimum = frameBytes(event(sequence, "req-padding", "started",
                buildJsonObject { selected.forEach { put(it, "x") } }, "agent.started"))
            val spare = frameSize - minimum
            if (spare !in 0..(255 * count)) continue
            var remaining = spare
            val payload = buildJsonObject {
                for (key in selected) {
                    val extra = minOf(255, remaining)
                    put(key, "x".repeat(1 + extra))
                    remaining -= extra
                }
            }
            val result = event(sequence, "req-padding", "started", payload, "agent.started")
            assertEquals(frameSize, frameBytes(result))
            return result
        }
        error("Cannot produce valid event frame with length " + frameSize)
    }

    private fun fillLiveLedgerTo(writer: DurableSessionEventLedger, targetBytes: Long): Int {
        writer.append(event(1, "req-one", "started", buildJsonObject {
            put("graphId", "graph-root")
        }))
        writer.flush()
        writer.append(event(2, "req-two", "completed", buildJsonObject {
            put("graphId", "graph-root")
            put("childGraphId", "graph-child")
            put("artifactId", "artifact-one")
            put("artifactSha256", artifactDigest)
        }))
        writer.flush()
        var next = 3
        while (true) {
            val full = maximumPaddingEvent(next)
            val nextFull = maximumPaddingEvent(next + 1)
            if (targetBytes - Files.size(ledger()) <
                frameBytes(full).toLong() + frameBytes(nextFull)) break
            writer.append(full)
            writer.flush()
            next++
        }
        val missing = (targetBytes - Files.size(ledger())).toInt()
        val first = preciselySizedEvent(next, missing / 2)
        writer.append(first)
        writer.flush()
        val second = preciselySizedEvent(next + 1, missing - missing / 2)
        writer.append(second)
        writer.flush()
        assertEquals(targetBytes, Files.size(ledger()))
        return next + 2
    }

    @Test fun directoryOwnerUsesEffectiveUidNotMutableUserName() {
        assertEquals(0L, LinuxCheckpointOwner.parseEffectiveUid(
            "Name:\tjava\nUid:\t1000\t0\t0\t0\n"))
        assertEquals(1000L, LinuxCheckpointOwner.parseEffectiveUid(
            "Uid:\t0\t1000\t0\t0\n"))
        assertNull(LinuxCheckpointOwner.parseEffectiveUid("Uid:\t1000\t0\t0\n"))
        assertNull(LinuxCheckpointOwner.parseEffectiveUid(
            "Uid:\t1000\t0\t0\t0\nUid:\t1000\t1000\t1000\t1000\n"))
        createLedger()
        val ownerUid = (Files.getAttribute(store(), "unix:uid",
            LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        assertEquals(ownerUid, LinuxCheckpointOwner.effectiveUid())
        val original = System.getProperty("user.name")
        try {
            System.setProperty("user.name", "forged-checkpoint-owner")
            val saved = save(snapshot())
            assertEquals(saved, inspect())
        } finally {
            if (original == null) System.clearProperty("user.name")
            else System.setProperty("user.name", original)
        }
        assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
            assertFailsWith<CheckpointUnconfirmed> {
                LinuxCheckpointOwner.requireMatchingUid(ownerUid, ownerUid + 1)
            }.code)
        assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
            assertFailsWith<CheckpointUnconfirmed> {
                LinuxCheckpointOwner.requireMatchingUid(ownerUid, null)
            }.code)
    }

    @Test fun ownerMismatchAndMissingUidRefuseSaveAndInspectWithoutWriting() {
        createLedger()
        val dir = store()
        val actualUid = (Files.getAttribute(dir, "unix:uid",
            LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        val baseline = snapshot()
        for (pretended in listOf<Long?>(actualUid + 1, null)) {
            val denied = assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.save(dir, context(), ledger(), baseline, null) {
                    pretended
                }
            }
            assertEquals("CHECKPOINT_UNSAFE_DIRECTORY", denied.code)
            assertFalse(Files.exists(dir.resolve("checkpoint.lock")))
            assertFalse(Files.exists(dir.resolve("000000000001.rcp")))
        }
        val saved = save(baseline)
        val entries = Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        }
        for (pretended in listOf<Long?>(actualUid + 1, null)) {
            assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.inspect(dir, context(), ledger()) { pretended }
                }.code)
            assertEquals(entries, Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }.sorted().toList()
            })
        }
        assertEquals(saved, inspect())
    }

    @Test fun liveSaveRefusesForeignOrUnknownUidWithoutCreatingFiles() {
        DurableSessionEventLedger.create(ledger(), workspace()).use { writer ->
            writer.append(event(1, "req-one", "started", buildJsonObject {
                put("graphId", "graph-root")
            }))
            writer.flush()
            writer.append(event(2, "req-two", "completed", buildJsonObject {
                put("graphId", "graph-root")
                put("childGraphId", "graph-child")
                put("artifactId", "artifact-one")
                put("artifactSha256", artifactDigest)
            }))
            writer.flush()
            val anchor = writer.withCommittedCheckpointAnchor { it }
            val dir = store()
            val actualUid = (Files.getAttribute(dir, "unix:uid",
                LinkOption.NOFOLLOW_LINKS) as Number).toLong()
            val baseline = snapshot(providedAnchor = anchor)
            for (pretended in listOf<Long?>(actualUid + 1, null)) {
                assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
                    assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointStore.saveFromOpenLedger(
                            dir, context(), writer, baseline, null) { pretended }
                    }.code)
                assertFalse(Files.exists(dir.resolve("checkpoint.lock")))
                assertFalse(Files.exists(dir.resolve("000000000001.rcp")))
            }
            val saved = SessionCheckpointStore.saveFromOpenLedger(
                dir, context(), writer, baseline, null)
            assertEquals(1, saved.snapshot.sequence)
        }
    }

    @Test fun liveCheckpointAcceptsLedgerJustBelowSizeLimit() {
        val limit = DurableSessionEventLedger.MAX_LIVE_CHECKPOINT_LEDGER_BYTES
        DurableSessionEventLedger.create(ledger(), workspace()).use { writer ->
            fillLiveLedgerTo(writer, limit - 1)
            val anchor = writer.withCommittedCheckpointAnchor { it }
            val saved = SessionCheckpointStore.saveFromOpenLedger(store(), context(),
                writer, snapshot(providedAnchor = anchor), null)
            assertEquals(limit - 1, saved.snapshot.lastTrustedJournalOffset)
            assertTrue(Files.exists(store().resolve("000000000001.rcp")))
        }
    }

    @Test fun exactLiveLimitSucceedsAndOversizeRefusesBeforeReplayWithoutPoisoningWriter() {
        val limit = DurableSessionEventLedger.MAX_LIVE_CHECKPOINT_LEDGER_BYTES
        val finalSequence = DurableSessionEventLedger.create(ledger(), workspace()).use { writer ->
            val next = fillLiveLedgerTo(writer, limit)
            val anchor = writer.withCommittedCheckpointAnchor { it }
            val first = SessionCheckpointStore.saveFromOpenLedger(store(), context(),
                writer, snapshot(providedAnchor = anchor), null)
            assertEquals(limit, first.snapshot.lastTrustedJournalOffset)

            writer.append(event(next, "req-after-limit", "started",
                buildJsonObject {}, "agent.started"))
            writer.flush()
            assertTrue(Files.size(ledger()) > limit)
            val failed = assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.saveFromOpenLedger(store(), context(), writer,
                    snapshot(sequence = 2, previous = first.sha256,
                        providedAnchor = anchor), first.sha256)
            }
            assertEquals("CHECKPOINT_LIVE_LEDGER_TOO_LARGE", failed.code)
            assertEquals(DurableSessionEventLedger.LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE,
                (failed.cause as SessionLedgerUnconfirmed).code)
            assertFalse(Files.exists(store().resolve("000000000002.rcp")))
            // Verify a subsequent fsynced append actually grows the ledger.
            val before = Files.size(ledger())
            writer.append(event(next + 1, "req-after-refusal", "started",
                buildJsonObject {}, "agent.started"))
            writer.flush()
            assertTrue(Files.size(ledger()) > before)
            assertFalse(Files.exists(store().resolve("000000000002.rcp")))
            assertTrue(Files.exists(store().resolve("000000000001.rcp")))
            next + 1
        }
        // Closing and replaying the full ledger proves the writer was not poisoned.
        val verified = DurableSessionEventLedger.inspect(ledger())
        assertEquals(finalSequence, verified.lastSequence)
        assertEquals(Files.size(ledger()), verified.lastTrustedOffset)
        assertEquals("evt-sess-one-" + finalSequence,
            verified.events.last()["eventId"]!!.jsonPrimitive.content)
    }

    @Test fun openLedgerCheckpointRequiresAcknowledgedPrefixAndBlocksConcurrentInspection() {
        val writer = DurableSessionEventLedger.create(ledger(), workspace())
        try {
            writer.append(event(1, "req-one", "started", buildJsonObject {
                put("graphId", "graph-root")
            }))
            writer.flush()
            val anchorAtOne = writer.withCommittedCheckpointAnchor { it }
            writer.append(event(2, "req-two", "completed", buildJsonObject {
                put("graphId", "graph-root")
                put("childGraphId", "graph-child")
                put("artifactId", "artifact-one")
                put("artifactSha256", artifactDigest)
            }))
            assertEquals("CHECKPOINT_JOURNAL_UNCONFIRMED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedger(store(), context(), writer,
                        snapshot(providedAnchor = anchorAtOne), null)
                }.code)
            writer.flush()
            val acknowledged = writer.withCommittedCheckpointAnchor { it }
            val saved = SessionCheckpointStore.saveFromOpenLedger(store(), context(), writer,
                snapshot(providedAnchor = acknowledged), null)
            assertEquals(2, saved.snapshot.journalSequence)
            assertEquals("LEDGER_LEASE_HELD",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.inspect(ledger())
                }.code)
            assertTrue(Files.exists(store().resolve("000000000001.rcp")))
        } finally {
            writer.close()
        }
        assertEquals(2, inspect().snapshot.journalSequence)
    }

    @Test fun atomicRoundTripBindsRealJournalAndDeniesResume() {
        createLedger()
        val first = save(snapshot())
        assertEquals(1, first.snapshot.sequence)
        assertEquals(64, first.sha256.length)
        assertFalse(first.reconciliationRequired)
        assertFalse(first.usageLedgerVerified)
        assertFalse(first.resumeAllowed)
        assertEquals(first, inspect())
        assertTrue(Files.exists(store().resolve("000000000001.rcp")))
        assertEquals("CHECKPOINT_RESUME_DENIED",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.requireResumeAdmission(first)
            }.code)
    }

    @Test fun unknownUsageAndSideEffectsStayExplicitlyUnconfirmed() {
        createLedger()
        val written = save(snapshot(unknown = true))
        assertTrue(written.reconciliationRequired)
        val reopened = inspect()
        assertTrue(reopened.reconciliationRequired)
        assertNull(reopened.snapshot.hostAssertedUsage.single { it.kind == "model" }.units)
        assertEquals("unknown", reopened.snapshot.pendingEffects.single().state)
        assertFalse(reopened.resumeAllowed)
    }

    @Test fun modelAndToolUsageCannotBeSilentlyOmitted() {
        createLedger()
        val incomplete = snapshot().copy(hostAssertedUsage = listOf(
            HostAssertedCheckpointUsage("task-one", "model", "known", 42)))
        assertEquals("CHECKPOINT_USAGE_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> { save(incomplete) }.code)
    }

    @Test fun unmatchedToolStartsRequireExplicitPendingEffects() {
        val writer = DurableSessionEventLedger.create(ledger(), workspace())
        writer.use {
            it.append(event(1, "req-one", "started", buildJsonObject {
                put("graphId", "graph-root")
            }))
            it.flush()
            it.append(event(2, "req-two", "started", buildJsonObject {
                put("graphId", "graph-root")
            }, kind = "tool.started"))
            it.flush()
        }
        val proposed = snapshot().copy(acceptedArtifacts = emptyList(),
            pendingEffects = emptyList())
        assertEquals("CHECKPOINT_SIDE_EFFECT_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> { save(proposed) }.code)
        val quarantined = proposed.copy(pendingEffects =
            listOf(CheckpointEffect("effect-tool", "req-two", "unknown")))
        assertTrue(save(quarantined).reconciliationRequired)
        assertFalse(inspect().resumeAllowed)
    }

    @Test fun tempSyncFailureDoesNotReplaceLastCompleteGeneration() {
        createLedger()
        val first = save(snapshot())
        val second = snapshot(sequence = 2, previous = first.sha256)
        val error = assertFailsWith<CheckpointUnconfirmed> {
            SessionCheckpointStore.saveWithFault(store(), context(), ledger(),
                second, first.sha256) { phase ->
                if (phase == "temp-synced") throw IllegalStateException("simulated process stop")
            }
        }
        assertEquals("CHECKPOINT_WRITE_UNCONFIRMED", error.code)
        assertEquals(first, inspect())
        assertFalse(Files.exists(store().resolve("000000000002.rcp")))
        val orphan = store().resolve(".pending-" + UUID.randomUUID().toString() + ".tmp")
        Files.writeString(orphan, "incomplete")
        assertEquals(first, inspect(), "An incomplete temp is not a committed snapshot")
    }

    @Test fun renameWithoutAcknowledgementMustNotBeRetriedAsStaleGeneration() {
        createLedger()
        val first = save(snapshot())
        val second = snapshot(sequence = 2, previous = first.sha256)
        val error = assertFailsWith<CheckpointUnconfirmed> {
            SessionCheckpointStore.saveWithFault(store(), context(), ledger(),
                second, first.sha256) { phase ->
                if (phase == "renamed") throw IllegalStateException("directory sync interrupted")
            }
        }
        assertEquals("CHECKPOINT_WRITE_UNCONFIRMED", error.code)
        val durable = inspect()
        assertEquals(2, durable.snapshot.sequence)
        assertEquals(second, durable.snapshot)
        assertEquals("CHECKPOINT_STALE_GENERATION",
            assertFailsWith<CheckpointUnconfirmed> {
                save(second, first.sha256)
            }.code)
    }

    @Test fun staleOrForeignGenerationsCannotBeOverwritten() {
        createLedger()
        val first = save(snapshot())
        assertEquals("CHECKPOINT_STALE_GENERATION",
            assertFailsWith<CheckpointUnconfirmed> {
                save(snapshot())
            }.code)
        val newer = store().resolve("000000000003.rcp")
        Files.writeString(newer, "foreign-generation")
        val failure = assertFailsWith<CheckpointUnconfirmed> {
            save(snapshot(2, first.sha256), first.sha256)
        }
        assertTrue(failure.code.startsWith("CHECKPOINT_"))
        assertEquals("foreign-generation", Files.readString(newer))
        assertTrue(Files.exists(store().resolve("000000000001.rcp")))
    }

    @Test fun publishedTruncationAndHashTamperingNeverFallBackToPriorSnapshot() {
        createLedger()
        val first = save(snapshot())
        save(snapshot(2, first.sha256), first.sha256)
        val path = store().resolve("000000000002.rcp")
        val original = Files.readAllBytes(path)
        Files.write(path, original.copyOf(original.size - 1))
        assertEquals("CHECKPOINT_TRUNCATED",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        val changed = original.copyOf()
        changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        Files.write(path, changed)
        assertEquals("CHECKPOINT_HASH_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        Files.write(path, original)
        assertEquals(2, inspect().snapshot.sequence)
    }

    @Test fun changedPolicyHeadAndFrozenInputAreRejected() {
        createLedger()
        save(snapshot())
        val old = context()
        val changed = listOf(
            old.copy(policySha256 = "e".repeat(64)),
            old.copy(gitHead = "e".repeat(40)),
            old.copy(gitBranch = "refs/heads/other"),
            old.copy(gitBaselineSha256 = "e".repeat(64)),
            old.copy(graphGeneration = 6),
            old.copy(frozenInputsSha256 = "e".repeat(64))
        )
        changed.forEach {
            assertEquals("CHECKPOINT_BASELINE_CHANGED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.inspect(store(), it, ledger())
                }.code)
        }
    }

    @Test fun changedLedgerAndCorrelationsAreDenied() {
        createLedger()
        val first = save(snapshot())
        val original = Files.readAllBytes(ledger())
        val tampered = original.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        Files.write(ledger(), tampered)
        assertEquals("CHECKPOINT_JOURNAL_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        Files.write(ledger(), original)
        val badEffect = snapshot(2, first.sha256, unknown = true).copy(
            pendingEffects = listOf(CheckpointEffect("effect-two", "not-in-ledger", "unknown")))
        assertEquals("CHECKPOINT_CORRELATION_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> { save(badEffect, first.sha256) }.code)
        val wrongOwner = snapshot(2, first.sha256).copy(acceptedArtifacts =
            listOf(CheckpointArtifact("artifact-one", "graph-root", artifactDigest)))
        assertEquals("CHECKPOINT_ARTIFACT_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> { save(wrongOwner, first.sha256) }.code)
        val badArtifact = snapshot(2, first.sha256).copy(acceptedArtifacts =
            listOf(CheckpointArtifact("artifact-one", "graph-child", "0".repeat(64))))
        assertEquals("CHECKPOINT_ARTIFACT_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> { save(badArtifact, first.sha256) }.code)
    }

    @Test fun mismatchedGraphIdentityAndHostilePathsAreDenied() {
        createLedger()
        assertEquals("CHECKPOINT_GRAPH_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> {
                save(snapshot().copy(children = emptyList(), acceptedArtifacts = emptyList(),
                    hostAssertedUsage = emptyList()))
            }.code)
        val real = store()
        val symlink = temp.resolve("linked-checkpoints")
        Files.createSymbolicLink(symlink, real)
        assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.save(symlink, context(), ledger(), snapshot(), null)
            }.code)
        val nested = workspace().resolve("checkpoints").also { Files.createDirectories(it) }
        assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.save(nested, context(), ledger(), snapshot(), null)
            }.code)
        val lock = store().resolve("checkpoint.lock")
        Files.deleteIfExists(lock)
        val target = temp.resolve("target-lock")
        Files.writeString(target, "")
        Files.createSymbolicLink(lock, target)
        assertEquals("CHECKPOINT_UNSAFE_LOCK",
            assertFailsWith<CheckpointUnconfirmed> { save(snapshot()) }.code)
        assertEquals("", Files.readString(target))
    }

    @Test fun rejectsMissingGenerationAndUnknownEntries() {
        createLedger()
        val dir = store()
        Files.writeString(dir.resolve("checkpoint.lock"), "")
        assertEquals("CHECKPOINT_MISSING",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        val unknownEntry = dir.resolve("unexpected.bin")
        Files.writeString(unknownEntry, "intrusion")
        assertEquals("CHECKPOINT_UNRECOGNIZED_ENTRY",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        Files.delete(unknownEntry)
        val first = save(snapshot())
        Files.copy(dir.resolve("000000000001.rcp"), dir.resolve("000000000002.rcp"))
        assertEquals("CHECKPOINT_CHAIN_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        assertTrue(Files.exists(dir.resolve("000000000001.rcp")))
        assertEquals(64, first.sha256.length)
    }

    @Test fun rejectsWorkspaceLedgerPathAndForeignSessionIdentity() {
        createLedger()
        val inside = workspace().resolve("events.ledger")
        assertEquals("CHECKPOINT_LEDGER_INSIDE_WORKSPACE",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.save(store(), context(), inside, snapshot(), null)
            }.code)

        val foreign = temp.resolve("other.ledger")
        DurableSessionEventLedger.create(foreign, workspace()).use { writer ->
            val event = JsonObject(event(1, "foreign-req", "started", buildJsonObject {
                put("graphId", "graph-root")
            }) + mapOf(
                "sessionId" to JsonPrimitive("foreign-session"),
                "traceId" to JsonPrimitive("foreign-trace"),
                "eventId" to JsonPrimitive("evt-foreign-session-1")
            ))
            writer.append(event)
            writer.flush()
        }
        val anchor = DurableSessionEventLedger.inspect(foreign)
        val forged = snapshot().copy(journalSequence = anchor.lastSequence,
            journalHeadSha256 = anchor.headSha256,
            journalFileSha256 = anchor.fileSha256,
            lastTrustedJournalOffset = anchor.lastTrustedOffset,
            acceptedArtifacts = emptyList(),
            hostAssertedUsage = emptyList())
        assertEquals("CHECKPOINT_SESSION_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.save(store(), context(), foreign, forged, null)
            }.code)
    }

    @Test fun nonPrivateCheckpointDirectoryFailsClosed() {
        createLedger()
        val dir = store()
        Files.setPosixFilePermissions(dir, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ))
        assertEquals("CHECKPOINT_UNSAFE_DIRECTORY",
            assertFailsWith<CheckpointUnconfirmed> { save(snapshot()) }.code)
        assertFalse(Files.exists(dir.resolve("checkpoint.lock")))
    }

    @Test fun inspectIsReadOnlyWhenLockHasDisappeared() {
        createLedger()
        save(snapshot())
        val lock = store().resolve("checkpoint.lock")
        Files.delete(lock)
        assertEquals("CHECKPOINT_LOCK_MISSING",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        assertFalse(Files.exists(lock))
        assertTrue(Files.exists(store().resolve("000000000001.rcp")))
    }

    private fun createPreExecutionCheckpoint(): VerifiedSessionCheckpoint {
        DurableSessionEventLedger.create(ledger(), workspace()).use { writer ->
            writer.append(event(1, "req-one", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "session.transition"))
            writer.flush()
        }
        val early = snapshot().copy(
            phase = "created",
            taskAttempt = 0,
            taskState = "created",
            children = listOf(CheckpointChild("graph-child", "graph-root", "ready")),
            acceptedArtifacts = emptyList(),
            hostAssertedUsage = emptyList(),
            pendingEffects = emptyList()
        )
        return save(early)
    }

    private fun resumeAttestation(
        expected: Long = 0L,
        allowed: Set<String> = ToolAuthorizationPolicy.knownReadTools
    ) = ResumeHostAttestation(expected,
        ReadPolicyFacts("read-only", allowed, writablePathsPresent = false,
            immutableInputsPresent = false), externalActionsReconciled = true)

    @Test fun generationLeaseFencesUnqualifiedCheckpointWritersAndOldCallbacks() {
        val first = createPreExecutionCheckpoint()
        assertEquals(0L,
            SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
        val retired = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation())
        assertEquals(1L, retired.generation)
        retired.assertCurrent()
        assertEquals("published", retired.publishIfCurrent { "published" })
        assertEquals(1L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))

        val secondSnapshot = first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256)
        val unqualified = assertFailsWith<CheckpointUnconfirmed> {
            save(secondSnapshot, first.sha256)
        }
        assertEquals("RESUME_FENCE_REQUIRED", unqualified.code)
        val saved = SessionCheckpointStore.saveFenced(retired, store(), context(),
            ledger(), secondSnapshot, first.sha256)
        assertEquals(2L, saved.snapshot.sequence)

        retired.close()
        var staleCallbackPublished = false
        assertEquals("RESUME_FENCE_STALE_WRITER",
            assertFailsWith<SessionResumeUnconfirmed> {
                retired.publishIfCurrent { staleCallbackPublished = true }
            }.code)
        assertFalse(staleCallbackPublished)
        assertEquals("RESUME_FENCE_STALE_WRITER",
            assertFailsWith<SessionResumeUnconfirmed> { retired.assertCurrent() }.code)
        assertEquals("RESUME_FENCE_STALE_WRITER",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionCheckpointStore.saveFenced(retired, store(), context(),
                    ledger(), saved.snapshot.copy(sequence = 3L, previousSha256 = saved.sha256),
                    saved.sha256)
            }.code)

        assertEquals("RESUME_FENCE_CAS_FAILED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation(0))
            }.code)

        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation(1)).use { newer ->
            assertEquals(2L, newer.generation)
            newer.assertCurrent()
            val third = SessionCheckpointStore.saveFenced(newer, store(), context(), ledger(),
                saved.snapshot.copy(sequence = 3L, previousSha256 = saved.sha256), saved.sha256)
            assertEquals(3L, third.snapshot.sequence)
        }
        assertEquals(2L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
    }

    @Test fun liveLeaseAndHostEvidenceFailuresDenyWithoutAdvancingFence() {
        createPreExecutionCheckpoint()
        val holder = SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())
        try {
            assertEquals("RESUME_LEASE_HELD",
                assertFailsWith<SessionResumeUnconfirmed> {
                    SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation(1))
                }.code)
        } finally {
            holder.close()
        }
        assertEquals("RESUME_TOOL_AUTHORITY_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(),
                    resumeAttestation(1).copy(readPolicyFacts = ReadPolicyFacts(
                        "writable", ToolAuthorizationPolicy.knownReadTools, false, false)))
            }.code)
        assertEquals("RESUME_TOOL_AUTHORITY_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(),
                    resumeAttestation(1).copy(readPolicyFacts = ReadPolicyFacts(
                        "read-only", setOf("unapproved-tool"), false, false)))
            }.code)
        assertEquals("RESUME_EXTERNAL_EFFECTS_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(),
                    resumeAttestation(1).copy(externalActionsReconciled = false))
            }.code)
        assertEquals("RESUME_FENCE_CONTEXT_CHANGED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(),
                    resumeAttestation(1, setOf("read")))
            }.code)
        assertEquals(1L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
    }

    @Test fun changedHeadPolicyFrozenInputsAndUnsafeBoundaryRequireReconciliation() {
        val checkpoint = createPreExecutionCheckpoint()
        val changed = listOf(
            context().copy(gitHead = "f".repeat(40)),
            context().copy(policySha256 = "f".repeat(64)),
            context().copy(frozenInputsSha256 = "f".repeat(64)),
            context().copy(gitBaselineSha256 = "f".repeat(64))
        )
        for (changedContext in changed) {
            assertEquals("CHECKPOINT_BASELINE_CHANGED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionResumeAdmission.admit(store(), changedContext, ledger(),
                        resumeAttestation())
                }.code)
        }
        // A valid checkpoint at a non-restartable point never becomes authority.
        val running = checkpoint.snapshot.copy(sequence = 2L, previousSha256 = checkpoint.sha256,
            phase = "running", taskAttempt = 1, taskState = "running")
        save(running, checkpoint.sha256)
        assertEquals("RESUME_UNSAFE_BOUNDARY",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())
            }.code)
        assertEquals(0L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
    }

    @Test fun damagedPublishedFenceOrUnknownFilesNeverResetTheGeneration() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).close()
        val fence = store().resolve("resume-000000000001.rcf")
        val original = Files.readAllBytes(fence)
        Files.write(fence, original.copyOf(original.size - 1))
        assertEquals("RESUME_FENCE_VERSION_UNSUPPORTED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.currentGeneration(store(), context(), ledger())
            }.code)
        Files.write(fence, original)
        Files.writeString(store().resolve("resume-000000000003.rcf"),
            Files.readString(fence))
        assertEquals("RESUME_FENCE_CHAIN_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.currentGeneration(store(), context(), ledger())
            }.code)
    }

    @Test fun durableSidecarAnchorDetectsFenceRollbackAndCleansOrphanTemp() {
        createPreExecutionCheckpoint()
        val orphan = store().resolve(".resume-pending-" + UUID.randomUUID() + ".tmp")
        Files.writeString(orphan, "orphan")
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).close()
        assertFalse(Files.exists(orphan))
        val previous = inspect()
        Files.delete(store().resolve("resume-000000000001.rcf"))
        assertEquals("RESUME_FENCE_ANCHOR_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.currentGeneration(store(), context(), ledger())
            }.code)
        assertEquals("CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        // The legacy writer must not escape its CheckpointUnconfirmed contract.
        assertEquals("CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> {
                save(previous.snapshot.copy(sequence = 2L, previousSha256 = previous.sha256),
                    previous.sha256)
            }.code)
    }

    @Test fun liveLedgerSavePathRejectsStaleLeaseAndUnfencedWriter() {
        createPreExecutionCheckpoint()
        val retired = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation())
        retired.close()
        DurableSessionEventLedger.create(temp.resolve("other-live.ledger"), workspace()).use { writer ->
            writer.append(event(1, "req-one", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "session.transition"))
            writer.flush()
            val current = inspect()
            val anchor = writer.withCommittedCheckpointAnchor { it }
            val next = current.snapshot.copy(
                sequence = 2L, previousSha256 = current.sha256,
                journalSequence = anchor.lastSequence, journalHeadSha256 = anchor.headSha256,
                journalFileSha256 = anchor.fileSha256,
                lastTrustedJournalOffset = anchor.lastTrustedOffset)
            assertEquals("RESUME_FENCE_STALE_WRITER",
                assertFailsWith<SessionResumeUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFenced(
                        retired, store(), context(), writer, next, current.sha256)
                }.code)
            assertEquals("RESUME_FENCE_REQUIRED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedger(
                        store(), context(), writer, next, current.sha256)
                }.code)
            SessionResumeAdmission.admit(store(), context(), ledger(),
                resumeAttestation(1)).use { currentLease ->
                assertEquals("RESUME_LEDGER_PATH_CHANGED",
                    assertFailsWith<SessionResumeUnconfirmed> {
                        SessionCheckpointStore.saveFromOpenLedgerFenced(
                            currentLease, store(), context(), writer, next, current.sha256)
                    }.code)
            }
        }
    }

    @Test fun interruptedAnchorPrepareNeedsExplicitOperatorReconciliation() {
        createPreExecutionCheckpoint()
        val interrupted = assertFailsWith<SessionResumeUnconfirmed> {
            SessionResumeAdmission.admitWithFault(store(), context(), ledger(),
                resumeAttestation()) {
                throw IllegalStateException("simulated kill after durable anchor, before fence")
            }
        }
        assertEquals("RESUME_ADMISSION_UNCONFIRMED", interrupted.code)
        assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        val preparedFile = ledger().resolveSibling("events.ledger.resume-anchor-000000000001.rca")
        assertTrue(Files.exists(preparedFile))
        val confirmedHash = Files.readString(preparedFile).split("\n")[2]
        assertEquals("RESUME_FENCE_ANCHOR_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(),
                    resumeAttestation())
            }.code)
        assertEquals("CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        val approved = PreparedFenceReconciliation(1L, confirmedHash, "operator-one", true)
        assertEquals("RESUME_FENCE_RECONCILIATION_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.reconcilePreparedAdmission(
                    store(), context(), ledger(), resumeAttestation(),
                    approved.copy(expectedPreparedFenceSha256 = "0".repeat(64)))
            }.code)
        assertEquals("RESUME_FENCE_RECONCILIATION_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.reconcilePreparedAdmission(
                    store(), context(), ledger(), resumeAttestation(),
                    approved.copy(operatorApproved = false))
            }.code)
        val orphanTemp = ledger().resolveSibling(
            ".events.ledger.resume-anchor-pending-" + UUID.randomUUID() + ".tmp")
        val unrelatedTemp = ledger().resolveSibling(
            ".other.ledger.resume-anchor-pending-" + UUID.randomUUID() + ".tmp")
        Files.writeString(orphanTemp, "orphaned")
        Files.writeString(unrelatedTemp, "another ledger's active temp")
        SessionResumeAdmission.reconcilePreparedAdmission(
            store(), context(), ledger(), resumeAttestation(), approved).use { repaired ->
            assertEquals(1L, repaired.generation)
            repaired.assertCurrent()
        }
        assertFalse(Files.exists(orphanTemp))
        assertTrue(Files.exists(unrelatedTemp))
        assertEquals(1L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation(1)).use {
            assertEquals(2L, it.generation)
        }
    }

    @Test fun operatorApprovalIsDurableAndRetryIsIdempotentAfterAuditFsync() {
        createPreExecutionCheckpoint()
        assertEquals("RESUME_ADMISSION_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admitWithFault(store(), context(), ledger(),
                    resumeAttestation()) { throw IllegalStateException("crash after anchor") }
            }.code)
        val prepared = ledger().resolveSibling("events.ledger.resume-anchor-000000000001.rca")
        val expectedFence = Files.readString(prepared).split("\n")[2]
        val approval = PreparedFenceReconciliation(1L, expectedFence, "reviewer-one", true)
        val audit = ledger().resolveSibling("events.ledger.resume-audit-000000000001.rcaudit")
        assertEquals("RESUME_ADMISSION_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.reconcilePreparedWithFault(store(), context(), ledger(),
                    resumeAttestation(), approval) {
                    throw IllegalStateException("crash after audit force, before fence")
                }
            }.code)
        assertTrue(Files.exists(audit))
        assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        val originalAudit = Files.readAllBytes(audit)
        val fields = Files.readString(audit).split("\n")
        assertEquals("RCAUD1", fields[0])
        assertEquals("1", fields[1])
        assertEquals(expectedFence, fields[2])
        assertEquals("reviewer-one", fields[6])
        assertEquals("true", fields[7])
        assertEquals("RESUME_FENCE_AUDIT_CONFLICT",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.reconcilePreparedAdmission(
                    store(), context(), ledger(), resumeAttestation(),
                    approval.copy(operatorId = "other-reviewer"))
            }.code)
        val orphan = ledger().resolveSibling(
            ".events.ledger.resume-audit-pending-" + UUID.randomUUID() + ".tmp")
        Files.writeString(orphan, "old partial approval")
        SessionResumeAdmission.reconcilePreparedAdmission(store(), context(), ledger(),
            resumeAttestation(), approval).use {
            assertEquals(1L, it.generation)
            it.assertCurrent()
        }
        assertFalse(Files.exists(orphan))
        assertContentEquals(originalAudit, Files.readAllBytes(audit))
        assertEquals(1L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
        val corrupted = Files.readString(audit).replace("reviewer-one", "reviewer-two")
        Files.writeString(audit, corrupted)
        assertEquals("RESUME_FENCE_AUDIT_UNCONFIRMED",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.currentGeneration(store(), context(), ledger())
            }.code)
    }

    @Test fun anchorParentRequiresPrivateOwnerModeAndIgnoresUnrelatedSiblings() {
        createPreExecutionCheckpoint()
        val unrelated = ledger().resolveSibling("events.ledger.resume-anchor-not-a-generation.rca")
        Files.writeString(unrelated, "unrelated")
        val originalMode = Files.getPosixFilePermissions(ledger().parent)
        try {
            Files.setPosixFilePermissions(ledger().parent, originalMode +
                PosixFilePermission.GROUP_READ)
            assertEquals("RESUME_FENCE_ANCHOR_UNSAFE_DIRECTORY",
                assertFailsWith<SessionResumeUnconfirmed> {
                    SessionResumeAdmission.admit(store(), context(), ledger(),
                        resumeAttestation())
                }.code)
        } finally {
            Files.setPosixFilePermissions(ledger().parent, originalMode)
        }
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use {
            it.assertCurrent()
        }
        assertEquals(1L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
    }

    @Test fun fencedLeaseRejectsReplacedLedgerAtSamePath() {
        createPreExecutionCheckpoint()
        val admitted = SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation())
        try {
            admitted.requireLedgerPath(ledger())
            val prior = temp.resolve("moved-old-events.ledger")
            Files.move(ledger(), prior)
            Files.copy(prior, ledger())
            assertEquals("RESUME_LEDGER_PATH_CHANGED",
                assertFailsWith<SessionResumeUnconfirmed> {
                    admitted.requireLedgerPath(ledger())
                }.code)
        } finally {
            admitted.close()
        }
    }

    @Test fun negativeFenceGenerationHasDedicatedDenial() {
        createPreExecutionCheckpoint()
        assertEquals("RESUME_FENCE_EXPECTED_GENERATION_INVALID",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store(), context(), ledger(),
                    resumeAttestation(-1))
            }.code)
    }

    @Test fun independentOsLockProcessBlocksAdmissionAndReleasesOnKill() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).close()
        val source = temp.resolve("ResumeLockHolder.java")
        Files.writeString(source, """
            import java.nio.file.*;
            import java.nio.channels.*;
            class ResumeLockHolder {
                public static void main(String[] args) throws Exception {
                    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
                        try (FileLock lock = channel.tryLock()) {
                            System.out.println(lock == null ? "HELD" : "ACQUIRED");
                            System.out.flush();
                            if (lock != null) Thread.sleep(60000);
                        }
                    }
                }
            }
        """.trimIndent())
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(javaBin, source.toString(),
            store().resolve("resume.lock").toString()).redirectErrorStream(true).start()
        try {
            assertEquals("ACQUIRED", child.inputStream.bufferedReader().readLine())
            assertEquals("RESUME_LEASE_HELD",
                assertFailsWith<SessionResumeUnconfirmed> {
                    SessionResumeAdmission.admit(store(), context(), ledger(),
                        resumeAttestation(1))
                }.code)
        } finally {
            child.destroyForcibly()
            assertTrue(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
        }
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation(1)).use {
            assertEquals(2L, it.generation)
        }
    }
}
