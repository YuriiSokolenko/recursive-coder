package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
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
        put("causationId", if (sequence == 1) JsonNull else JsonPrimitive("evt-sess-one-1"))
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
}
