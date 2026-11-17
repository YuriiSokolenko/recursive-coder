package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.FileSystems
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.attribute.PosixFilePermission
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

@Tag("integration")
@OptIn(ResumeOperatorOnlyApi::class, LedgerTestFaultInjection::class)
class FencedLedgerReopenIntegrationTest : SessionCheckpointTestFixture() {
    /**
     * Build checkpoint-bound pre-execution prefixes near 4 MiB without
     * relying on stage/recursive metadata forbidden by the shared policy.
     * Large records use repeated schema-approved redaction markers only;
     * they are not lifecycle events, grants or effect receipts.
     */
    private fun redactedCheckpointEvent(
        sequence: Int, removedCount: Int, correlation: String
    ): JsonObject {
        val original = event(sequence, correlation, "completed",
            buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved")
        return JsonObject(original.toMutableMap().also {
            it["redaction"] = buildJsonObject {
                put("mode", "metadata-only")
                put("removedFields", buildJsonArray {
                    repeat(removedCount) { add("rawPrompt") }
                })
            }
        })
    }

    /** Exact frame sizing: each repeated redaction string contributes 12 bytes. */
    private fun sizedCheckpointEvent(sequence: Int, bytes: Int): JsonObject {
        val minimum = frameBytes(redactedCheckpointEvent(sequence, 0, "r"))
        require(bytes >= minimum) { "Checkpoint frame smaller than allowed minimum" }
        val estimate = (bytes - minimum) / 12
        for (count in maxOf(0, estimate - 14)..(estimate + 1)) {
            val candidate = redactedCheckpointEvent(sequence, count, "r")
            val correlationPadding = bytes - frameBytes(candidate)
            if (correlationPadding !in 0..159) continue
            val sized = redactedCheckpointEvent(sequence, count,
                "r".repeat(correlationPadding + 1))
            assertEquals(bytes, frameBytes(sized))
            return sized
        }
        error("Cannot size a strict checkpoint.saved event to " + bytes)
    }

    private fun saveDensePreExecutionPrefix(targetBytes: Long): Pair<VerifiedSessionCheckpoint, Int> {
        val next = DurableSessionEventLedger.create(ledger(), workspace()).use { writer ->
            writer.append(event(1, "req-one", "completed",
                buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
            writer.flush()
            var sequence = 2
            while (true) {
                val full = sizedCheckpointEvent(sequence, 15_000)
                val following = sizedCheckpointEvent(sequence + 1, 15_000)
                if (targetBytes - Files.size(ledger()) <
                    frameBytes(full).toLong() + frameBytes(following)) break
                writer.append(full)
                writer.flush()
                sequence++
            }
            val remaining = (targetBytes - Files.size(ledger())).toInt()
            val first = sizedCheckpointEvent(sequence, remaining / 2)
            writer.append(first)
            writer.flush()
            val second = sizedCheckpointEvent(sequence + 1, remaining - remaining / 2)
            writer.append(second)
            writer.flush()
            assertEquals(targetBytes, Files.size(ledger()))
            sequence + 2
        }
        val checkpoint = snapshot().copy(
            phase = "created", taskAttempt = 0, taskState = "created",
            children = listOf(CheckpointChild("graph-child", "graph-root", "ready")),
            acceptedArtifacts = emptyList(), hostAssertedUsage = emptyList(),
            pendingEffects = emptyList()
        )
        return save(checkpoint) to next
    }

    @Test fun fencedReopenRefusesOverFourMiBCheckpointBoundPrefix() {
        val limit = DurableSessionEventLedger.MAX_LIVE_CHECKPOINT_LEDGER_BYTES
        val checkpoint = saveDensePreExecutionPrefix(limit + 1)
        assertEquals(limit + 1, checkpoint.first.snapshot.lastTrustedJournalOffset)
        val bytes = Files.readAllBytes(ledger())
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val denied = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), context(), lease)
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_CAPACITY", denied.code)
            assertContentEquals(bytes, Files.readAllBytes(ledger()))
            assertEquals(checkpoint.first.sha256, inspect().sha256)
            lease.assertCurrent()
        }
    }

    @Test fun fencedAppendRefusesCrossingFourMiBWithoutPoisonOrExtraBytes() {
        val limit = DurableSessionEventLedger.MAX_LIVE_CHECKPOINT_LEDGER_BYTES
        val checkpoint = saveDensePreExecutionPrefix(limit - 1)
        val bytes = Files.readAllBytes(ledger())
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                assertEquals("LEDGER_REOPEN_CHECKPOINT_CAPACITY",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(event(checkpoint.second, "req-boundary",
                            "completed", buildJsonObject { put("graphId", "graph-root") },
                            "checkpoint.saved"))
                    }.code)
                assertContentEquals(bytes, Files.readAllBytes(ledger()))
                val anchor = writer.withCommittedCheckpointAnchor { it }
                assertEquals(limit - 1, anchor.lastTrustedOffset)
                // The rejection occurred before write, so the verified writer
                // remains healthy and a clean close needs no new checkpoint.
            }
            lease.assertCurrent()
        }
        assertContentEquals(bytes, Files.readAllBytes(ledger()))
    }

    @Test fun fencedCloseReportsMissingOrFailedCheckpointImmediately() {
        val first = createPreExecutionCheckpoint()
        val baseline = Files.readAllBytes(ledger())
        val checkpointFile = store().resolve("000000000001.rcp")
        val checkpointBytes = Files.readAllBytes(checkpointFile)
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            writer.appendFenced(event(2, "req-two", "completed",
                buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
            val evidence = writer.withCommittedCheckpointAnchor { it }
            val next = first.snapshot.copy(
                sequence = 2L, previousSha256 = first.sha256,
                journalSequence = evidence.lastSequence,
                journalHeadSha256 = evidence.headSha256,
                journalFileSha256 = evidence.fileSha256,
                lastTrustedJournalOffset = evidence.lastTrustedOffset)
            assertEquals("CHECKPOINT_STALE_GENERATION",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFenced(
                        lease, store(), context(), writer, next,
                        "0".repeat(64))
                }.code)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            val actual = Files.readAllBytes(ledger())
            assertTrue(actual.size > baseline.size)
            assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
            assertContentEquals(checkpointBytes, Files.readAllBytes(checkpointFile))
            assertEquals("CHECKPOINT_JOURNAL_MISMATCH",
                assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
            lease.assertCurrent()
        }
    }

    @Test fun fencedSaveAfterRenameFailureReportsUnconfirmedNotMissingCheckpoint() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            writer.appendFenced(event(2, "req-two", "completed",
                buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
            val evidence = writer.withCommittedCheckpointAnchor { it }
            val second = first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256,
                journalSequence = evidence.lastSequence,
                journalHeadSha256 = evidence.headSha256,
                journalFileSha256 = evidence.fileSha256,
                lastTrustedJournalOffset = evidence.lastTrustedOffset)
            val failure = assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.saveFromOpenLedgerFencedWithFault(
                    lease, store(), context(), writer, second, first.sha256) { phase ->
                    if (phase == "renamed")
                        throw IllegalStateException("directory fsync not acknowledged")
                }
            }
            assertEquals("CHECKPOINT_WRITE_UNCONFIRMED", failure.code)
            assertEquals("CHECKPOINT_WRITE_UNCONFIRMED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFenced(
                        lease, store(), context(), writer, second, first.sha256)
                }.code)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            // C2 did become visible, but the writer must never turn that
            // observation into a claim that the failed fsync was acknowledged.
            assertEquals(second, inspect().snapshot)
            assertEquals("CHECKPOINT_STALE_GENERATION",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFenced(
                        lease, store(), context(), ledger(), second, first.sha256)
                }.code)
        }
    }

    @Test fun metadataOnlyFencedSaveFailureStillMakesCloseUnconfirmed() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            // No appended event: the generation publication itself can still
            // be uncertain and must not be silently treated as a clean close.
            val second = first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256)
            assertEquals("CHECKPOINT_WRITE_UNCONFIRMED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFencedWithFault(
                        lease, store(), context(), writer, second, first.sha256) { phase ->
                        if (phase == "renamed")
                            throw IllegalStateException("post-rename power loss seam")
                    }
                }.code)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            assertEquals(2L, inspect().snapshot.sequence)
            lease.assertCurrent()
        }
    }


    @Test fun lostLeaseAndAmbiguousCheckpointPreserveBothCloseDiagnostics() {
        val first = createPreExecutionCheckpoint()
        val lease = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation())
        val writer = DurableSessionEventLedger.reopenForAppend(
            ledger(), workspace(), store(), context(), lease)
        val second = first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256)
        try {
            assertEquals("CHECKPOINT_WRITE_UNCONFIRMED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFencedWithFault(
                        lease, store(), context(), writer, second, first.sha256) { phase ->
                        if (phase == "renamed")
                            throw IllegalStateException("post-rename uncertainty")
                    }
                }.code)
            lease.close()
            assertEquals("RESUME_FENCE_STALE_WRITER",
                assertFailsWith<SessionResumeUnconfirmed> {
                    writer.appendFenced(event(2, "req-two", "completed",
                        buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                }.code)
            val close = assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED", close.code)
            assertTrue(close.suppressed.any {
                (it as? SessionLedgerUnconfirmed)?.code == "LEDGER_REOPEN_WRITER_POISONED"
            }, "Loss of the lease must remain visible alongside ambiguous C2")
        } finally {
            try { writer.close() } catch (_: Throwable) { }
            lease.close()
        }
        assertEquals(2L, inspect().snapshot.sequence)
    }

    @Test fun fencedSaveBeforeRenameFailureAlsoRequiresExplicitReconciliation() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            writer.appendFenced(event(2, "req-two", "completed",
                buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
            val evidence = writer.withCommittedCheckpointAnchor { it }
            val second = first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256,
                journalSequence = evidence.lastSequence,
                journalHeadSha256 = evidence.headSha256,
                journalFileSha256 = evidence.fileSha256,
                lastTrustedJournalOffset = evidence.lastTrustedOffset)
            assertEquals("CHECKPOINT_WRITE_UNCONFIRMED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFencedWithFault(
                        lease, store(), context(), writer, second, first.sha256) { phase ->
                        if (phase == "temp-synced")
                            throw IllegalStateException("rename not attempted")
                    }
                }.code)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            assertFalse(Files.exists(store().resolve("000000000002.rcp")))
            assertEquals("CHECKPOINT_JOURNAL_MISMATCH",
                assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        }
    }

    @Test fun fencedCheckpointRejectsWrongAnchorBeforeDurablePublication() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val intended = first.snapshot.copy(
                    sequence = 2L, previousSha256 = first.sha256,
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset)
                val wrong = intended.copy(journalHeadSha256 = "0".repeat(64))
                val refusal = assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFenced(
                        lease, store(), context(), writer, wrong, first.sha256)
                }
                assertEquals("CHECKPOINT_JOURNAL_UNCONFIRMED", refusal.code)
                assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH",
                    (refusal.cause as? SessionLedgerUnconfirmed)?.code)
                assertFalse(Files.exists(store().resolve("000000000002.rcp")))
                // The failed precondition never creates or poisons a new
                // checkpoint generation. A correct save can still publish.
                val saved = SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, store(), context(), writer, intended, first.sha256)
                assertEquals(2L, saved.snapshot.sequence)
                assertTrue(Files.exists(store().resolve("000000000002.rcp")))
            }
        }
    }


    @Test fun fencedSaveRejectsPrewriteContextErrorsWithoutPoisoningWriter() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                writer.appendFenced(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val correct = first.snapshot.copy(
                    sequence = 2L, previousSha256 = first.sha256,
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset)
                val original = Files.readAllBytes(ledger())
                for (wrong in listOf(
                    correct.copy(sessionId = "wrong-session"),
                    correct.copy(traceId = "wrong-trace"),
                    correct.copy(binding = correct.binding.copy(policySha256 = "0".repeat(64)))
                )) {
                    assertEquals("RESUME_FENCE_CONTEXT_CHANGED",
                        assertFailsWith<SessionResumeUnconfirmed> {
                            SessionCheckpointStore.saveFromOpenLedgerFenced(
                                lease, store(), context(), writer, wrong, first.sha256)
                        }.code)
                    assertFalse(Files.exists(store().resolve("000000000002.rcp")))
                    assertContentEquals(original, Files.readAllBytes(ledger()))
                    assertEquals(anchor.lastSequence,
                        writer.withCommittedCheckpointAnchor { it.lastSequence })
                }
                // The same writer can still publish its acknowledged prefix.
                val saved = SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, store(), context(), writer, correct, first.sha256)
                assertEquals(2L, saved.snapshot.sequence)
            }
        }
        assertEquals(2L, inspect().snapshot.sequence)
    }


    @Test fun fencedAppendRejectsForeignGraphOrExecutionMetadataWithoutWriting() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                val prefix = Files.readAllBytes(ledger())
                val baseline = writer.withCommittedCheckpointAnchor { it }
                val forbidden = listOf(
                    event(2, "req-two", "completed",
                        buildJsonObject { put("graphId", "other") }, "checkpoint.saved"),
                    event(2, "req-two", "completed",
                        buildJsonObject {
                            put("graphId", "graph-root")
                            put("parentGraphId", "other")
                        }, "checkpoint.saved"),
                    event(2, "req-two", "completed",
                        buildJsonObject {
                            put("graphId", "graph-root")
                            put("childGraphId", "other")
                        }, "checkpoint.saved"),
                    event(2, "req-two", "completed",
                        buildJsonObject {
                            put("graphId", "graph-root")
                            put("recursiveEvent", "SUCCEEDED")
                            put("stageRunId", "stage-one")
                            put("stageState", "completed")
                            put("receiptId", "receipt-one")
                        }, "checkpoint.saved"),
                    JsonObject(fencedTransition(2, "created", "accepted").toMutableMap().also {
                        it["metadata"] = buildJsonObject { put("graphId", "other") }
                    })
                )
                for (invalid in forbidden) {
                    assertEquals("LEDGER_REOPEN_EVENT_REFUSED",
                        assertFailsWith<SessionLedgerUnconfirmed> {
                            writer.appendFenced(invalid)
                        }.code)
                    assertContentEquals(prefix, Files.readAllBytes(ledger()))
                    assertEquals(baseline.headSha256,
                        writer.withCommittedCheckpointAnchor { it.headSha256 })
                }
                writer.appendFenced(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val correct = first.snapshot.copy(
                    sequence = 2L, previousSha256 = first.sha256,
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset)
                assertEquals(2L, SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, store(), context(), writer, correct, first.sha256).snapshot.sequence)
            }
        }
        assertEquals(2L, inspect().snapshot.sequence)
    }

    @Test fun ambiguousFencedCheckpointPreservesPrimaryDiagnosticAfterAppendAttempt() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            val second = first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256)
            assertEquals("CHECKPOINT_WRITE_UNCONFIRMED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFencedWithFault(
                        lease, store(), context(), writer, second, first.sha256) { phase ->
                        if (phase == "renamed")
                            throw IllegalStateException("simulated failure after rename")
                    }
                }.code)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.appendFenced(event(2, "req-two", "completed",
                        buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                }.code)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.withCommittedCheckpointAnchor { it }
                }.code)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            assertEquals(2L, inspect().snapshot.sequence)
        }
    }

    @Test fun fencePreflightRefusesDifferentLeaseIdentityWithoutPublication() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { active ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), active).use { writer ->
                val head = writer.withCommittedCheckpointAnchor { it }
                val extraLock = temp.resolve("unrelated-resume.lock")
                java.nio.channels.FileChannel.open(extraLock,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { owner ->
                    val lock = owner.tryLock() ?: error("Test-only lock unavailable")
                    val other = SessionResumeLease(
                        active.generation, active.admittedCheckpointSha256,
                        active.toolAuthoritySha256, store(), ledger(),
                        "0".repeat(64), "0".repeat(64), "unrelated-file-key",
                        extraLock, owner, lock, {})
                    try {
                        val candidate = first.snapshot
                        assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH",
                            assertFailsWith<SessionLedgerUnconfirmed> {
                                writer.requireFencedCheckpointPublication(
                                    other, head, candidate)
                            }.code)
                        // The real lease and writer are still usable. This is
                        // a prepublication reference-identity denial.
                        writer.requireFencedCheckpointPublication(active, head, candidate)
                    } finally {
                        other.close()
                    }
                }
                assertFalse(Files.exists(store().resolve("000000000002.rcp")))
            }
        }
    }

    @Test fun checkpointLockContentionAtReopenIsRetryableBusyNotEvidenceMismatch() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val lockPath = store().resolve("checkpoint.lock")
            java.nio.channels.FileChannel.open(lockPath, StandardOpenOption.WRITE).use { holder ->
                holder.tryLock().use { exclusive ->
                    assertNotNull(exclusive)
                    val refusal = assertFailsWith<SessionLedgerUnconfirmed> {
                        DurableSessionEventLedger.reopenForAppend(
                            ledger(), workspace(), store(), context(), lease)
                    }
                    assertEquals("LEDGER_REOPEN_BUSY", refusal.code)
                    assertEquals("CHECKPOINT_LEASE_HELD",
                        (refusal.cause as? CheckpointUnconfirmed)?.code)
                }
            }
            // Contention is transient and must not poison the resume lease.
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use {
                lease.assertCurrent()
            }
        }
    }

    @Test fun reopenContextMismatchGetsSpecificRefusalBeforeAnyMutation() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val wrongWorkspace = temp.resolve("wrong-workspace")
            Files.createDirectories(wrongWorkspace)
            val before = Files.readAllBytes(ledger())
            assertEquals("LEDGER_REOPEN_CONTEXT_MISMATCH",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.reopenForAppend(
                        ledger(), wrongWorkspace, store(), context(), lease)
                }.code)
            assertContentEquals(before, Files.readAllBytes(ledger()))
            lease.assertCurrent()
        }
    }

    @Test fun errorAfterForceStillReportsUnconfirmedPhysicalWrite() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            assertEquals("LEDGER_SYNC_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.appendFencedWithFault(event(2, "req-two", "completed",
                        buildJsonObject { put("graphId", "graph-root") },
                        "checkpoint.saved")) { phase ->
                        if (phase == "forced-before-ack")
                            throw AssertionError("Crash-like Error after force")
                    }
                }.code)
            assertEquals("LEDGER_UNACKNOWLEDGED_APPEND",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
        }
    }

    @Test fun fencedTransitionAcceptsOnlyCreatedToAcceptedAndKeepsPhaseAcrossEvents() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val missing = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), context(), lease).use { writer ->
                val before = Files.readAllBytes(ledger())
                for (forbidden in listOf(
                    fencedTransition(2, "created", "running"),
                    fencedTransition(2, "created", "succeeded"),
                    fencedTransition(2, "accepted", "running"),
                    event(2, "req-two", "completed",
                        buildJsonObject { put("graphId", "graph-root") }, "session.transition")
                )) {
                    assertEquals("LEDGER_REOPEN_EVENT_REFUSED",
                        assertFailsWith<SessionLedgerUnconfirmed> {
                            writer.appendFenced(forbidden)
                        }.code)
                    assertContentEquals(before, Files.readAllBytes(ledger()))
                }
                writer.appendFenced(fencedTransition(2, "created", "accepted"))
                val afterAdmission = Files.readAllBytes(ledger())
                assertEquals(2, writer.withCommittedCheckpointAnchor { it.lastSequence })
                for (forbidden in listOf(
                    fencedTransition(3, "created", "accepted"),
                    fencedTransition(3, "accepted", "running"),
                    fencedTransition(3, "accepted", "succeeded")
                )) {
                    assertEquals("LEDGER_REOPEN_EVENT_REFUSED",
                        assertFailsWith<SessionLedgerUnconfirmed> {
                            writer.appendFenced(forbidden)
                        }.code)
                    assertContentEquals(afterAdmission, Files.readAllBytes(ledger()))
                }
                val withSneakyTransition = JsonObject(event(3, "req-three", "completed",
                    buildJsonObject { put("graphId", "graph-root") },
                    "checkpoint.saved").toMutableMap().also {
                    it["transition"] = buildJsonObject {
                        put("from", "accepted"); put("to", "running")
                    }
                })
                assertEquals("LEDGER_REOPEN_EVENT_REFUSED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(withSneakyTransition)
                    }.code)
                writer.appendFenced(event(3, "req-three", "completed",
                    buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                }
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED", missing.code)
        }
        val replay = DurableSessionEventLedger.inspect(ledger())
        assertEquals(3, replay.lastSequence)
        assertEquals("session.transition", replay.events[1]["type"]?.jsonPrimitive?.content)
        assertEquals("checkpoint.saved", replay.events[2]["type"]?.jsonPrimitive?.content)
    }

    @Test fun fencedReopenRefusesSecondWriterInSameJvmAndRetiredLease() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use {
                assertEquals("LEDGER_LEASE_HELD",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        DurableSessionEventLedger.reopenForAppend(
                            ledger(), workspace(), store(), context(), lease)
                    }.code)
            }
        }
    }

    @Test fun fencedSaveRejectsMismatchedPhaseAndUnsafeShapeBeforePublishing() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            writer.appendFenced(fencedTransition(2, "created", "accepted"))
            val anchor = writer.withCommittedCheckpointAnchor { it }
            val candidate = first.snapshot.copy(
                sequence = 2L, previousSha256 = first.sha256,
                journalSequence = anchor.lastSequence,
                journalHeadSha256 = anchor.headSha256,
                journalFileSha256 = anchor.fileSha256,
                lastTrustedJournalOffset = anchor.lastTrustedOffset)
            val cases = listOf(
                candidate, // phase still created, but the event history is accepted
                candidate.copy(phase = "running", taskState = "running"),
                candidate.copy(phase = "accepted", taskState = "ready", taskAttempt = 1),
                candidate.copy(phase = "accepted", taskState = "ready",
                    children = listOf(CheckpointChild("graph-child", "graph-root", "running"))))
            try {
                for (unsafe in cases) {
                    val refusal = assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointStore.saveFromOpenLedgerFenced(
                            lease, store(), context(), writer, unsafe, first.sha256)
                    }
                    assertEquals("CHECKPOINT_JOURNAL_UNCONFIRMED", refusal.code)
                    assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH",
                        (refusal.cause as? SessionLedgerUnconfirmed)?.code)
                    assertFalse(Files.exists(store().resolve("000000000002.rcp")))
                }
                assertTrue(Files.exists(store().resolve("000000000001.rcp")))
            } finally {
                // Rejected preflight cannot clear the successful append's
                // still-uncovered checkpoint obligation.
                val close = assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.close()
                }
                assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED", close.code)
            }
        }
    }

    @Test fun fencedReopenSupportsTwoCompleteResumeGenerations() {
        val first = createPreExecutionCheckpoint()
        val second = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation()).use { lease ->
            assertEquals(1L, lease.generation)
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                writer.appendFenced(fencedTransition(2, "created", "accepted"))
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val next = first.snapshot.copy(
                    sequence = 2L, previousSha256 = first.sha256,
                    phase = "accepted", taskState = "ready",
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset)
                SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, store(), context(), writer, next, first.sha256)
            }
        }
        assertEquals("accepted", second.snapshot.phase)
        assertEquals(2L, second.snapshot.sequence)
        assertEquals(2, second.snapshot.journalSequence)
        val third = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation(expected = 1L)).use { lease ->
            assertEquals(2L, lease.generation)
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                writer.appendFenced(event(3, "req-three", "completed",
                    buildJsonObject { put("graphId", "graph-root") },
                    "checkpoint.saved"))
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val next = second.snapshot.copy(
                    sequence = 3L, previousSha256 = second.sha256,
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset)
                SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, store(), context(), writer, next, second.sha256)
            }
        }
        assertEquals("accepted", third.snapshot.phase)
        assertEquals(3, third.snapshot.journalSequence)
        assertEquals(2L, SessionResumeAdmission.currentGeneration(
            store(), context(), ledger()))
        val replay = DurableSessionEventLedger.inspect(ledger())
        assertEquals(3, replay.lastSequence)
        assertEquals(replay.fileSha256, third.snapshot.journalFileSha256)
        assertEquals(replay.headSha256, third.snapshot.journalHeadSha256)
        assertEquals(third.sha256, inspect().sha256)
    }

    @Test fun fencedReopenContinuesHashChainAndPublishesBoundCheckpoint() {
        val first = createPreExecutionCheckpoint()
        val before = DurableSessionEventLedger.inspect(ledger())
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                assertEquals("LEDGER_FENCED_API_REQUIRED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.append(event(2, "req-two", "completed",
                            buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                    }.code)
                assertEquals("LEDGER_FENCED_API_REQUIRED",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.flush() }.code)
                assertEquals(before.lastTrustedOffset, Files.size(ledger()))
                writer.appendFenced(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                val evidence = writer.withCommittedCheckpointAnchor { it }
                assertEquals(2, evidence.lastSequence)
                assertTrue(evidence.lastTrustedOffset > before.lastTrustedOffset)
                assertNotEquals(before.headSha256, evidence.headSha256)
                val next = first.snapshot.copy(
                    sequence = 2L, previousSha256 = first.sha256,
                    journalSequence = evidence.lastSequence,
                    journalHeadSha256 = evidence.headSha256,
                    journalFileSha256 = evidence.fileSha256,
                    lastTrustedJournalOffset = evidence.lastTrustedOffset)
                val persisted = SessionCheckpointStore.saveFromOpenLedgerFenced(
                    lease, store(), context(), writer, next, first.sha256)
                assertEquals(2L, persisted.snapshot.sequence)
                lease.assertCurrent()
            }
            // Once a newer checkpoint has been published the same admission
            // SHA cannot reopen the ledger again, even after writer close.
            val refused = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), context(), lease)
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH", refused.code)
            assertEquals("CHECKPOINT_REOPEN_UNCONFIRMED",
                (refused.cause as? CheckpointUnconfirmed)?.code)
        }
        val replay = DurableSessionEventLedger.inspect(ledger())
        assertEquals(2, replay.lastSequence)
        assertEquals("checkpoint.saved", replay.events.last()["type"]?.jsonPrimitive?.content)
        val current = inspect()
        assertEquals(replay.headSha256, current.snapshot.journalHeadSha256)
        assertEquals(replay.fileSha256, current.snapshot.journalFileSha256)
        assertEquals(replay.lastTrustedOffset, current.snapshot.lastTrustedJournalOffset)
    }

    @Test fun fencedAppendInvalidSequenceAndCausationLeaveWriterUsable() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val missing = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), context(), lease).use { writer ->
                val before = Files.readAllBytes(ledger())
                assertEquals("LEDGER_EVENT_VERSION_OR_SEQUENCE",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(event(3, "req-three", "completed",
                            buildJsonObject { put("graphId", "graph-root") },
                            "checkpoint.saved"))
                    }.code)
                val invalidChain = JsonObject(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") },
                    "checkpoint.saved").toMutableMap().also {
                    it["causationId"] = JsonPrimitive("evt-not-the-predecessor")
                })
                assertEquals("LEDGER_EVENT_CHAIN_INVALID",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(invalidChain)
                    }.code)
                assertContentEquals(before, Files.readAllBytes(ledger()))
                writer.appendFenced(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
                }
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED", missing.code)
        }
        assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
    }

    @Test fun fencedReopenRefusesForbiddenEventsWithoutWritingBytes() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            val original = Files.readAllBytes(ledger())
            try {
                assertEquals("LEDGER_REOPEN_EVENT_REFUSED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(event(2, "req-two", "started",
                            buildJsonObject { put("graphId", "graph-root") }, "tool.started"))
                    }.code)
                assertContentEquals(original, Files.readAllBytes(ledger()))
                // Bad input is rejected before any physical write, but it
                // must not poison a healthy writer or make close uncertain.
                writer.appendFenced(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
            } finally {
                assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            }
        }
        assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
    }

    @Test fun foreignAppendBetweenFencedOperationsPoisonsWriterWithoutAcknowledgement() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val original = Files.readAllBytes(ledger())
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            // Simulate a non-cooperating same-UID writer bypassing advisory locks.
            // The current writer cursor must not silently skip these foreign bytes.
            val tamper = byteArrayOf(0x41, 0x42, 0x43)
            Files.write(ledger(), tamper, StandardOpenOption.APPEND)
            val changed = Files.readAllBytes(ledger())
            try {
                assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(event(2, "req-two", "completed",
                            buildJsonObject { put("graphId", "graph-root") },
                            "checkpoint.saved"))
                    }.code)
                assertContentEquals(changed, Files.readAllBytes(ledger()))
            } finally {
                assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            }
            // This is a test-only fixture reset, never a recovery path.
            Files.write(ledger(), original)
            assertEquals(1, DurableSessionEventLedger.inspect(ledger()).lastSequence)
            lease.assertCurrent()
        }
    }

    @Test fun fencedReopenRefusesValidUnacknowledgedTailAtCheckpointOffset() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val before = DurableSessionEventLedger.inspect(ledger())
            val event = event(2, "req-two", "completed",
                buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved")
            val payload = event.toString().toByteArray(StandardCharsets.UTF_8)
            val last = before.headSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(last + payload)
            val frame = ByteBuffer.allocate(4 + 32 + payload.size)
                .putInt(payload.size).put(digest).put(payload).array()
            Files.write(ledger(), frame, StandardOpenOption.APPEND)
            val originalSize = Files.size(ledger())
            assertEquals("LEDGER_REOPEN_TAIL_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.reopenForAppend(
                        ledger(), workspace(), store(), context(), lease)
                }.code)
            assertEquals(originalSize, Files.size(ledger()))
            assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        }
    }

    @Test @Timeout(120) fun independentJvmLedgerLockPreventsFencedReopen() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val source = temp.resolve("FencedReopenLockHolder.java")
            Files.writeString(source, """
                import java.nio.file.*;
                import java.nio.channels.*;
                class FencedReopenLockHolder {
                    public static void main(String[] args) throws Exception {
                        try (FileChannel channel = FileChannel.open(
                                Path.of(args[0]), StandardOpenOption.WRITE)) {
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
            val output = temp.resolve("fenced-lock-holder.log")
            val child = ProcessBuilder(javaBin, source.toString(),
                ledger().resolveSibling("events.ledger.lock").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                awaitChildLockSignal(child, output, "ACQUIRED")
                assertEquals("LEDGER_LEASE_HELD",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        DurableSessionEventLedger.reopenForAppend(
                            ledger(), workspace(), store(), context(), lease)
                    }.code)
            } finally {
                child.destroyForcibly()
                assertTrue(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
            }
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use {
                lease.assertCurrent()
            }
        }
    }

    @Test fun retiredLeasePoisonsAlreadyOpenReopenedWriterWithoutAcknowledgement() {
        createPreExecutionCheckpoint()
        val lease = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation())
        val writer = DurableSessionEventLedger.reopenForAppend(
            ledger(), workspace(), store(), context(), lease)
        val bytes = Files.readAllBytes(ledger())
        lease.close()
        assertEquals("RESUME_FENCE_STALE_WRITER",
            assertFailsWith<SessionResumeUnconfirmed> {
                writer.appendFenced(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") }, "checkpoint.saved"))
            }.code)
        assertContentEquals(bytes, Files.readAllBytes(ledger()))
        // Authority loss revokes the writer, but never implies a partial write.
        assertEquals("LEDGER_REOPEN_WRITER_POISONED",
            assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
        assertEquals(1, DurableSessionEventLedger.inspect(ledger()).lastSequence)
    }

    @Test fun faultDuringReopenReleasesLedgerLeaseWithoutChangingEvidence() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val baseline = Files.readAllBytes(ledger())
            for (phase in listOf("ledger-lock-acquired", "ledger-prefix-verified")) {
                val failure = assertFailsWith<IllegalStateException> {
                    DurableSessionEventLedger.reopenForAppendWithFault(
                        ledger(), workspace(), store(), context(), lease) { observed ->
                        if (observed == phase) throw IllegalStateException(phase)
                    }
                }
                assertEquals(phase, failure.message)
                assertContentEquals(baseline, Files.readAllBytes(ledger()))
                // Reopening after a pre-publication fault proves the lock channel
                // and the in-JVM canonical-path reservation were both released.
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), context(), lease).use {
                    lease.assertCurrent()
                }
            }
        }
        assertEquals(1, DurableSessionEventLedger.inspect(ledger()).lastSequence)
    }

    @Test fun interruptedFencedAppendBeforeForceLeavesUnconfirmedTail() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val initialSize = Files.size(ledger())
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            try {
                assertEquals("record-written", assertFailsWith<IllegalStateException> {
                    writer.appendFencedWithFault(event(2, "req-two", "completed",
                        buildJsonObject { put("graphId", "graph-root") },
                        "checkpoint.saved")) { phase ->
                        if (phase == "record-written")
                            throw IllegalStateException(phase)
                    }
                }.message)
            } finally {
                assertEquals("LEDGER_UNACKNOWLEDGED_APPEND",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            }
            assertTrue(Files.size(ledger()) > initialSize)
            assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
            assertEquals("LEDGER_REOPEN_TAIL_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.reopenForAppend(
                        ledger(), workspace(), store(), context(), lease)
                }.code)
        }
    }

    @Test fun interruptedFencedAppendAfterForceNeverAcknowledgesThatTail() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val initialSize = Files.size(ledger())
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            try {
                val failed = assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.appendFencedWithFault(event(2, "req-two", "completed",
                        buildJsonObject { put("graphId", "graph-root") },
                        "checkpoint.saved")) { phase ->
                        if (phase == "forced-before-ack")
                            throw IllegalStateException("simulated kill before acknowledgement")
                    }
                }
                assertEquals("LEDGER_SYNC_UNCONFIRMED", failed.code)
                assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(event(3, "req-three", "completed",
                            buildJsonObject { put("graphId", "graph-root") },
                            "checkpoint.saved"))
                    }.code)
            } finally {
                assertEquals("LEDGER_UNACKNOWLEDGED_APPEND",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            }
            assertTrue(Files.size(ledger()) > initialSize)
            assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
            assertEquals("LEDGER_REOPEN_TAIL_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.reopenForAppend(
                        ledger(), workspace(), store(), context(), lease)
                }.code)
        }
    }

    @Test fun retiredLeaseWaitsForInFlightFencedAppendThenRejectsLateCallback() {
        createPreExecutionCheckpoint()
        val lease = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation())
        val writer = DurableSessionEventLedger.reopenForAppend(
            ledger(), workspace(), store(), context(), lease)
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val closerStarted = java.util.concurrent.CountDownLatch(1)
        val closerFinished = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val publishing = Thread {
            try {
                writer.appendFencedWithFault(event(2, "req-two", "completed",
                    buildJsonObject { put("graphId", "graph-root") },
                    "checkpoint.saved")) { phase ->
                    if (phase == "record-written") {
                        entered.countDown()
                        check(resume.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            } catch (error: Throwable) { errors.set(error) }
        }
        val closing = Thread {
            closerStarted.countDown()
            try { lease.close() }
            catch (error: Throwable) { errors.set(error) }
            finally { closerFinished.countDown() }
        }
        try {
            publishing.start()
            assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS))
            closing.start()
            assertTrue(closerStarted.await(15, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(closerFinished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS),
                "close() must not overtake an in-flight publishIfCurrent")
            resume.countDown()
            publishing.join(15000)
            closing.join(15000)
            assertFalse(publishing.isAlive)
            assertFalse(closing.isAlive)
            errors.get()?.let { throw AssertionError("Fenced publication race", it) }
            assertEquals("RESUME_FENCE_STALE_WRITER",
                assertFailsWith<SessionResumeUnconfirmed> {
                    writer.appendFenced(event(3, "req-three", "completed",
                        buildJsonObject { put("graphId", "graph-root") },
                        "checkpoint.saved"))
                }.code)
        } finally {
            resume.countDown()
            publishing.join(15000)
            closing.join(15000)
            val close = assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }
            assertEquals("LEDGER_REOPEN_WRITER_POISONED", close.code)
            assertTrue(close.suppressed.any {
                (it as? SessionLedgerUnconfirmed)?.code ==
                    "LEDGER_REOPEN_CHECKPOINT_REQUIRED"
            }, "A revoked writer must retain missing-checkpoint diagnostics")
        }
        assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
    }

    @Test fun fencedReopenRejectsShortenedCheckpointPrefixWithoutTruncatingAgain() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val bytes = Files.readAllBytes(ledger())
            Files.write(ledger(), bytes.copyOf(bytes.size - 1))
            val shortened = Files.readAllBytes(ledger())
            try {
                assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        DurableSessionEventLedger.reopenForAppend(
                            ledger(), workspace(), store(), context(), lease)
                    }.code)
                assertContentEquals(shortened, Files.readAllBytes(ledger()))
            } finally {
                Files.write(ledger(), bytes)
            }
            lease.assertCurrent()
        }
    }

    @Test fun fencedReopenRejectsChangedPolicyContextBeforeAcquiringLedger() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val bytes = Files.readAllBytes(ledger())
            val wrongPolicy = context().copy(policySha256 = "f".repeat(64))
            val error = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), wrongPolicy, lease)
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH", error.code)
            assertEquals("CHECKPOINT_REOPEN_UNCONFIRMED",
                (error.cause as? CheckpointUnconfirmed)?.code)
            assertContentEquals(bytes, Files.readAllBytes(ledger()))
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use {
                lease.assertCurrent()
            }
        }
    }

    @Test fun fencedReopenRejectsDamagedPrefixWithoutAnyNewWrite() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val original = Files.readAllBytes(ledger())
            val changed = original.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            Files.write(ledger(), changed)
            try {
                val refusal = assertFailsWith<SessionLedgerUnconfirmed> {
                    DurableSessionEventLedger.reopenForAppend(
                        ledger(), workspace(), store(), context(), lease)
                }
                assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH", refusal.code)
                assertEquals("LEDGER_HASH_MISMATCH",
                    (refusal.cause as? SessionLedgerUnconfirmed)?.code)
                assertContentEquals(changed, Files.readAllBytes(ledger()))
            } finally {
                Files.write(ledger(), original)
            }
            lease.assertCurrent()
        }
    }

    @Test fun fencedReopenRejectsRetiredLeaseAndLedgerInodeSwap() {
        createPreExecutionCheckpoint()
        val old = SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())
        old.close()
        assertEquals("RESUME_FENCE_STALE_WRITER",
            assertFailsWith<SessionResumeUnconfirmed> {
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), context(), old)
            }.code)
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation(1)).use { lease ->
            val backup = ledger().resolveSibling("original.ledger")
            Files.move(ledger(), backup)
            Files.copy(backup, ledger(), StandardCopyOption.COPY_ATTRIBUTES)
            try {
                assertEquals("RESUME_LEDGER_PATH_CHANGED",
                    assertFailsWith<SessionResumeUnconfirmed> {
                        DurableSessionEventLedger.reopenForAppend(
                            ledger(), workspace(), store(), context(), lease)
                    }.code)
            } finally {
                Files.delete(ledger())
                Files.move(backup, ledger())
            }
            lease.assertCurrent()
        }
    }

    @Test fun fencedReopenRechecksCheckpointAfterLedgerLockAcrossC1ToC2Race() {
        val first = createPreExecutionCheckpoint()
        val baseline = Files.readAllBytes(ledger())
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            var publicationCount = 0
            val denial = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.reopenForAppendWithFault(
                    ledger(), workspace(), store(), context(), lease) { phase ->
                    if (phase == "checkpoint-inspected") {
                        publicationCount++
                        val next = first.snapshot.copy(
                            sequence = 2L, previousSha256 = first.sha256)
                        val published = SessionCheckpointStore.saveFenced(
                            lease, store(), context(), ledger(), next, first.sha256)
                        assertEquals(2L, published.snapshot.sequence)
                        assertNotEquals(first.sha256, published.sha256)
                        // The checkpoint head changed without writing any
                        // additional ledger bytes in the admission-to-lock gap.
                        assertContentEquals(baseline, Files.readAllBytes(ledger()))
                    }
                }
            }
            assertEquals(1, publicationCount)
            assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH", denial.code)
            assertEquals("CHECKPOINT_REOPEN_UNCONFIRMED",
                (denial.cause as? CheckpointUnconfirmed)?.code)
            assertEquals(2L, inspect().snapshot.sequence)
            assertContentEquals(baseline, Files.readAllBytes(ledger()))
            // Refusal must release both the OS lock and the JVM lock-path
            // reservation; an independent read-only inspection remains usable.
            assertEquals(1, DurableSessionEventLedger.inspect(ledger()).lastSequence)
            lease.assertCurrent()
        }
    }

    @Test fun authorityExceptionWithPolicyCodePoisonsWriterBeforeAnyWrite() {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val baseline = Files.readAllBytes(ledger())
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            try {
                // This is NOT the private policy sentinel. A future lease or
                // fence helper may coincidentally use the same public string.
                assertEquals("RESUME_UNSAFE_BOUNDARY",
                    assertFailsWith<SessionResumeUnconfirmed> {
                        writer.appendFencedWithFault(event(
                            2, "req-two", "completed",
                            buildJsonObject { put("graphId", "graph-root") },
                            "checkpoint.saved")) { phase ->
                            if (phase == "before-record-write")
                                throw SessionResumeUnconfirmed("RESUME_UNSAFE_BOUNDARY")
                        }
                    }.code)
                assertContentEquals(baseline, Files.readAllBytes(ledger()))
                assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendFenced(event(
                            2, "req-two", "completed",
                            buildJsonObject { put("graphId", "graph-root") },
                            "checkpoint.saved"))
                    }.code)
            } finally {
                assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.withCommittedCheckpointAnchor { it }
                    }.code)
                assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            }
            assertContentEquals(baseline, Files.readAllBytes(ledger()))
        }
    }

    @Test fun fencedReopenRejectsCheckpointDriftFromAdmittedSha() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use { lease ->
            val next = first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256)
            SessionCheckpointStore.saveFenced(lease, store(), context(), ledger(), next,
                first.sha256)
            val denial = assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.reopenForAppend(
                    ledger(), workspace(), store(), context(), lease)
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_MISMATCH", denial.code)
            assertEquals("CHECKPOINT_REOPEN_UNCONFIRMED",
                (denial.cause as? CheckpointUnconfirmed)?.code)
        }
    }

    /**
     * Child JVM classpath from the test worker's actual class loaders and code
     * sources; java.class.path alone can omit the forked Gradle test classes.
     * Keep the fallback even on JDKs without URLClassLoader app loaders.
     */
    private fun childJavaClasspath(): String {
        val cp = linkedSetOf<String>()
        cp.addAll(System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() })
        val visited = mutableSetOf<ClassLoader>()
        for (root in listOf(javaClass.classLoader,
                Thread.currentThread().contextClassLoader)) {
            var loader: ClassLoader? = root
            while (loader != null && visited.add(loader)) {
                if (loader is java.net.URLClassLoader) {
                    for (url in loader.urLs) {
                        if (url.protocol == "file")
                            cp.add(Path.of(url.toURI()).toString())
                    }
                }
                loader = loader.parent
            }
        }
        for (type in listOf(FencedLedgerReopenIntegrationTest::class.java,
                FencedReopenCrashWorker::class.java,
                SessionResumeAdmission::class.java, SessionCheckpointStore::class.java,
                DurableSessionEventLedger::class.java, CheckpointHostContext::class.java,
                kotlin.Unit::class.java, kotlinx.serialization.json.JsonObject::class.java)) {
            type.protectionDomain?.codeSource?.location?.let { url ->
                if (url.protocol == "file") cp.add(Path.of(url.toURI()).toString())
            }
        }
        return cp.joinToString(java.io.File.pathSeparator)
    }

    private fun assertActualKillRestartRefusesTail(phase: String) {
        createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val checkpointFile = store().resolve("000000000001.rcp")
        val checkpointBytes = Files.readAllBytes(checkpointFile)
        val bin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classPath = childJavaClasspath()
        val marker = temp.resolve("actual-kill-" + phase + "-marker")
        val output = temp.resolve("actual-kill-" + phase + "-output.log")
        val arguments = arrayOf(store().toString(), workspace().toString(),
            ledger().toString(), marker.toString())
        val worker = ProcessBuilder(
            bin, "-cp", classPath, FencedReopenCrashWorker::class.java.name,
            phase, *arguments).redirectErrorStream(true)
            .redirectOutput(output.toFile()).start()
        try {
            val deadline = System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS.toNanos(45)
            while (!Files.exists(marker)) {
                assertTrue(worker.isAlive,
                    "Child died before crash boundary: " + Files.readString(output))
                assertTrue(System.nanoTime() < deadline,
                    "Timed out before crash boundary: " + Files.readString(output))
                Thread.sleep(20L)
            }
            assertTrue(worker.isAlive, "The child must actually be alive before SIGKILL")
            assertTrue(Files.size(ledger()) > original.size)
            // Still held by the child; a competing JVM cannot take its lease.
            assertEquals("RESUME_LEASE_HELD",
                assertFailsWith<SessionResumeUnconfirmed> {
                    SessionResumeAdmission.admit(store(), context(), ledger(),
                        resumeAttestation(1))
                }.code)
            worker.destroyForcibly()
            assertTrue(worker.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
            assertNotEquals(0, worker.exitValue(), "The child must die by force, not return")
            val tailAfterKill = Files.readAllBytes(ledger())
            assertTrue(tailAfterKill.size > original.size)
            assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
            val restartOutput = temp.resolve("actual-restart-" + phase + "-output.log")
            val restarted = ProcessBuilder(
                bin, "-cp", classPath, FencedReopenCrashWorker::class.java.name,
                "restart", *arguments).redirectErrorStream(true)
                .redirectOutput(restartOutput.toFile()).start()
            try {
                assertTrue(restarted.waitFor(45, java.util.concurrent.TimeUnit.SECONDS),
                    "Restart JVM hung: " + Files.readString(restartOutput))
                assertEquals(0, restarted.exitValue(), Files.readString(restartOutput))
                assertEquals("DENIED:CHECKPOINT_JOURNAL_MISMATCH",
                    Files.readString(restartOutput).trim())
            } finally {
                if (restarted.isAlive) restarted.destroyForcibly()
                restarted.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertContentEquals(tailAfterKill, Files.readAllBytes(ledger()))
            assertContentEquals(checkpointBytes, Files.readAllBytes(checkpointFile))
            // Exactly one durable fence generation was published before the crash.
            assertTrue(Files.exists(store().resolve("resume-000000000001.rcf")))
            assertFalse(Files.exists(store().resolve("resume-000000000002.rcf")))
        } finally {
            if (worker.isAlive) worker.destroyForcibly()
            worker.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test @Timeout(120) fun sigkillBeforeForceLeavesUnconfirmedTailAfterJvmRestart() {
        assertActualKillRestartRefusesTail("record-written")
    }

    @Test @Timeout(120) fun sigkillAfterForceBeforeAckLeavesUnconfirmedTailAfterJvmRestart() {
        assertActualKillRestartRefusesTail("forced-before-ack")
    }

    @Test @Timeout(120) fun sigkillAfterWriterCloseBeforeCheckpointRefusesJvmRestart() {
        assertActualKillRestartRefusesTail("after-close")
    }

    @Test @Timeout(120) fun independentJvmsRaceSameExpectedGenerationAtAdmission() {
        createPreExecutionCheckpoint()
        val source = temp.resolve("ResumeCasRaceChild.java")
        Files.writeString(source, """
            import ai.yuriilab.recursivecoder.*;
            import java.nio.file.*;
            class ResumeCasRaceChild {
                public static void main(String[] args) throws Exception {
                    var store = Path.of(args[0]);
                    var workspace = Path.of(args[1]);
                    var ledger = Path.of(args[2]);
                    var ready = Path.of(args[3]);
                    var gate = Path.of(args[4]);
                    var ctx = new CheckpointHostContext(workspace,
                        "a".repeat(64), "b".repeat(40), "refs/heads/dev",
                        "c".repeat(64), "graph-root", 5L, "d".repeat(64));
                    var policy = new ReadPolicyFacts("read-only",
                        ToolAuthorizationPolicy.INSTANCE.getKnownReadTools(), false, false);
                    var attestation = new ResumeHostAttestation(0L, policy, true);
                    Files.writeString(ready, "ready");
                    while (!Files.exists(gate)) Thread.sleep(5L);
                    try (var lease = SessionResumeAdmission.INSTANCE.admit(
                            store, ctx, ledger, attestation)) {
                        try (var writer = DurableSessionEventLedger.Companion.reopenForAppend(
                                ledger, workspace, store, ctx, lease)) {
                            System.out.println("SUCCESS:" + lease.getGeneration());
                            System.out.flush();
                            Thread.sleep(200L);
                        }
                    } catch (SessionResumeUnconfirmed denial) {
                        System.out.println("DENIED:" + denial.getCode());
                    } catch (SessionLedgerUnconfirmed denial) {
                        System.out.println("DENIED:" + denial.getCode());
                    }
                }
            }
        """.trimIndent())

        val classPath = childJavaClasspath()
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val gate = temp.resolve("cas-race-start")
        val children = (1..2).map { id ->
            val ready = temp.resolve("race-$id-ready")
            val output = temp.resolve("race-$id-output.log")
            val process = ProcessBuilder(javaBin, "-cp", classPath,
                source.toString(), store().toString(), workspace().toString(),
                ledger().toString(), ready.toString(), gate.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            Triple(process, ready, output)
        }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
            while (children.any { !Files.exists(it.second) }) {
                assertTrue(System.nanoTime() < deadline,
                    "Child JVM startup timed out: " + children.map {
                        Files.readString(it.third)
                    })
                assertTrue(children.all { it.first.isAlive },
                    "Child JVM failed before admission: " + children.map {
                        Files.readString(it.third)
                    })
                Thread.sleep(20L)
            }
            Files.writeString(gate, "go")
            for ((process, _, output) in children) {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
                    "Child JVM timed out: " + Files.readString(output))
                assertEquals(0, process.exitValue(), Files.readString(output))
            }
            val results = children.map { Files.readString(it.third).trim() }
            assertEquals(1, results.count { it == "SUCCESS:1" }, results.toString())
            assertEquals(1, results.count {
                it == "DENIED:RESUME_LEASE_HELD" ||
                    it == "DENIED:RESUME_FENCE_CAS_FAILED"
            }, results.toString())
            assertEquals(1L,
                SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
            assertEquals(1, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        } finally {
            children.forEach { (process, _, _) ->
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

}

/**
 * Runs in a physically separate JVM during the crash/restart tests above.
 * Its main thread is deliberately parked at a known durability boundary until
 * the parent delivers a real SIGKILL. No finally block or writer close executes.
 *
 * Test-only; this class is never referenced by the production host or CLI.
 */
@OptIn(LedgerTestFaultInjection::class)
internal object FencedReopenCrashWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5)
        val phase = args[0]
        val directory = Path.of(args[1])
        val workspace = Path.of(args[2])
        val ledger = Path.of(args[3])
        val marker = Path.of(args[4])
        val context = CheckpointHostContext(workspace, "a".repeat(64),
            "b".repeat(40), "refs/heads/dev", "c".repeat(64),
            "graph-root", 5L, "d".repeat(64))
        val policy = ReadPolicyFacts("read-only",
            ToolAuthorizationPolicy.knownReadTools, false, false)
        val expected = if (phase == "restart") 1L else 0L
        val attestation = ResumeHostAttestation(expected, policy,
            externalActionsReconciled = true)

        if (phase == "restart") {
            check(DurableSessionEventLedger.inspect(ledger).lastSequence == 2)
            try {
                SessionResumeAdmission.admit(directory, context, ledger, attestation).use {
                    throw IllegalStateException("Unsafe admission succeeded after killed writer")
                }
            } catch (error: CheckpointUnconfirmed) {
                check(error.code == "CHECKPOINT_JOURNAL_MISMATCH")
                println("DENIED:" + error.code)
            }
            return
        }

        require(phase in setOf("record-written", "forced-before-ack", "after-close"))
        val lease = SessionResumeAdmission.admit(directory, context, ledger, attestation)
        val writer = DurableSessionEventLedger.reopenForAppend(
            ledger, workspace, directory, context, lease)
        val event = buildJsonObject {
            put("kind", "session-event")
            put("version", SessionProtocol.VERSION)
            put("sessionId", "sess-one")
            put("traceId", "trace-one")
            put("eventId", "evt-sess-one-2")
            put("sequence", 2)
            put("timestamp", "2026-11-15T00:00:00Z")
            put("type", "checkpoint.saved")
            put("status", "completed")
            put("correlationId", "req-two")
            put("causationId", "evt-sess-one-1")
            put("actor", buildJsonObject { put("agentId", "agent-sess-one") })
            put("redaction", buildJsonObject {
                put("mode", "metadata-only")
                put("removedFields", buildJsonArray { })
            })
            put("metadata", buildJsonObject { put("graphId", "graph-root") })
        }

        fun signalBoundaryAndPark() {
            Files.writeString(marker, phase, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)
            // The parent must kill the process to proceed. Holding the lease
            // here proves the OS releases it only when the process actually dies.
            while (true) Thread.sleep(1000L)
        }

        if (phase == "after-close") {
            writer.appendFenced(event)
            check(runCatching { writer.close() }.exceptionOrNull()
                .let { it is SessionLedgerUnconfirmed &&
                    it.code == "LEDGER_REOPEN_CHECKPOINT_REQUIRED" })
            signalBoundaryAndPark()
        } else {
            writer.appendFencedWithFault(event) { observed ->
                if (observed == phase) signalBoundaryAndPark()
            }
        }
        throw IllegalStateException("Crash boundary was never reached: " + phase)
    }
}
