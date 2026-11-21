package ai.yuriilab.recursivecoder

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real local-fs, ledger and checkpoint code; no model, tools or network. */
@Tag("integration")
@OptIn(TrustedPreExecutionHandoff::class, TrustedRunningBoundaryApi::class,
    LedgerTestFaultInjection::class)
class DurableCreatedAcceptedHandoffIntegrationTest : SessionCheckpointTestFixture() {
    private fun publish(
        created: VerifiedSessionCheckpoint,
        transition: JsonObject = fencedTransition(2, "created", "accepted"),
        expectedFence: Long = 0L
    ) = DurableCreatedAcceptedHandoff.publish(
        store(), context(), ledger(), resumeAttestation(expected = expectedFence),
        created, transition
    )

    private fun assertNoFirstFenceOrLedgerMutation(
        original: ByteArray, created: VerifiedSessionCheckpoint
    ) {
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertEquals(created, inspect())
        assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
    }

    @Test fun acceptedIsDurableBeforeHandoffReturnsAndNextGenerationCanReopen() {
        val c1 = createPreExecutionCheckpoint()

        val c2 = publish(c1)

        assertEquals(2L, c2.snapshot.sequence)
        assertEquals(c1.sha256, c2.snapshot.previousSha256)
        assertEquals(c1.snapshot.taskId, c2.snapshot.taskId)
        assertEquals(c1.snapshot.children, c2.snapshot.children)
        assertEquals(SessionPhaseVocabulary.acceptedWire, c2.snapshot.phase)
        assertEquals("ready", c2.snapshot.taskState)
        assertEquals(c2, inspect(), "C2 must already be on disk at return")
        val verified = DurableSessionEventLedger.inspect(ledger())
        assertEquals(2, verified.lastSequence)
        assertEquals("session.transition", verified.events.last()["type"]!!.jsonPrimitive.content)
        assertEquals("accepted", PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            "accepted", verified.events))
        assertEquals(verified.lastTrustedOffset, c2.snapshot.lastTrustedJournalOffset)
        assertEquals(1L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))

        // No execution replay: a new generation can only reopen the verified
        // metadata-only accepted prefix, without appending runtime events.
        SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation(expected = 1L)
        ).use { nextLease ->
            assertEquals(2L, nextLease.generation)
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), nextLease
            ).use { reopened ->
                assertEquals(2, reopened.withCommittedCheckpointAnchor { it.lastSequence })
            }
        }
    }

    @Test fun invalidLifecycleEdgeRefusesBeforeFenceOrLedgerMutation() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val denial = assertFailsWith<SessionResumeUnconfirmed> {
            publish(c1, fencedTransition(2, "created", "running"))
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY", denial.code)
        assertNoFirstFenceOrLedgerMutation(original, c1)
        assertFalse(Files.exists(store().resolve("resume.lock")))
    }

    @Test fun preflightRejectsWrongSequenceOrGraphBeforeAllocatingGeneration() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val wrongGraph = JsonObject(fencedTransition(2, "created", "accepted")
            .toMutableMap().also {
                it["metadata"] = buildJsonObject { put("graphId", "wrong-graph") }
            })
        for (invalid in listOf(
            fencedTransition(3, "created", "accepted"),
            wrongGraph
        )) {
            val denied = assertFailsWith<SessionResumeUnconfirmed> {
                publish(c1, invalid)
            }
            assertEquals("RESUME_UNSAFE_BOUNDARY", denied.code)
            assertNoFirstFenceOrLedgerMutation(original, c1)
        }
        assertFalse(Files.exists(store().resolve("resume.lock")))
    }

    @Test fun forgedCheckpointWithCorrectHashCannotChangeTaskOrChildrenInC2() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val forged = c1.copy(snapshot = c1.snapshot.copy(
            taskId = "untrusted-task",
            children = emptyList()
        ))
        assertEquals(c1.sha256, forged.sha256, "Positive control: forged label matches C1")
        val refused = assertFailsWith<SessionResumeUnconfirmed> { publish(forged) }
        assertEquals("RESUME_CHECKPOINT_CHANGED", refused.code)
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertEquals(c1, inspect())
        assertFalse(Files.exists(store().resolve("000000000002.rcp")))
        // Admission already minted a fence. It must not be reused.
        assertEquals(1L, SessionResumeAdmission.currentGeneration(store(), context(), ledger()))
    }

    @Test fun uncertainOrAcceptedC1CannotEnterTheHandoffAgain() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val refused = assertFailsWith<SessionResumeUnconfirmed> {
            publish(c1.copy(reconciliationRequired = true))
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY", refused.code)
        assertNoFirstFenceOrLedgerMutation(original, c1)

        val c2 = publish(c1)
        val acceptedLedger = Files.readAllBytes(ledger())
        val again = assertFailsWith<SessionResumeUnconfirmed> {
            publish(c2, fencedTransition(3, "created", "accepted"), expectedFence = 1L)
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY", again.code)
        assertContentEquals(acceptedLedger, Files.readAllBytes(ledger()))
        assertEquals(c2, inspect())
        assertFalse(Files.exists(store().resolve("resume-000000000002.rcf")))
    }

    @Test fun wrongExpectedFenceGenerationDoesNotPublishC2() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val refused = assertFailsWith<SessionResumeUnconfirmed> {
            publish(c1, expectedFence = 7L)
        }
        assertEquals("RESUME_FENCE_CAS_FAILED", refused.code)
        assertNoFirstFenceOrLedgerMutation(original, c1)
        assertFalse(Files.exists(store().resolve("000000000002.rcp")))
    }

    @Test fun failedCheckpointAfterAcknowledgedAppendCannotBeAdmittedAgain() {
        val c1 = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation()
        ).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease
            )
            try {
                writer.appendFenced(fencedTransition(2, "created", "accepted"))
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val accepted = c1.snapshot.copy(
                    sequence = 2L, previousSha256 = c1.sha256,
                    phase = "accepted", taskState = "ready",
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset
                )
                val refused = assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFencedWithFault(
                        lease, store(), context(), writer, accepted, c1.sha256
                    ) { phase ->
                        if (phase == "temp-synced")
                            throw IllegalStateException("simulated crash before C2 publication")
                    }
                }
                assertEquals("CHECKPOINT_WRITE_UNCONFIRMED", refused.code)
            } finally {
                assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.close()
                    }.code)
            }
        }
        // An acknowledged event is not a published C2. The durable physical
        // tail is intentionally left untouched and must fail future admission.
        assertFalse(Files.exists(store().resolve("000000000002.rcp")))
        assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        val refusal = assertFailsWith<CheckpointUnconfirmed> {
            SessionResumeAdmission.admit(
                store(), context(), ledger(), resumeAttestation(expected = 1L)
            )
        }
        assertEquals("CHECKPOINT_JOURNAL_MISMATCH", refusal.code)
        assertFalse(Files.exists(store().resolve("resume-000000000002.rcf")))
    }
    @Test fun runningAppendWithoutC3LeavesUncoveredTailAndCannotResume() {
        val c2 = publish(createPreExecutionCheckpoint())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            writer.appendTrustedAcceptedRunning(
                trustedRunningTransition())
            val failedClose = assertFailsWith<SessionLedgerUnconfirmed> {
                writer.close()
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED", failedClose.code)
        }
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
        val physical = DurableSessionEventLedger.inspect(ledger())
        assertEquals(3, physical.lastSequence)
        assertEquals(SessionPhaseVocabulary.runningWire,
            TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory(
                SessionPhaseVocabulary.runningWire, physical.events),
            "Read-only physical evidence identifies the canonical running tail")
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            SessionPhaseVocabulary.acceptedWire, physical.events),
            "The same tail can never be accepted as resume evidence")
        val rejection = assertFailsWith<CheckpointUnconfirmed> { inspect() }
        assertEquals("CHECKPOINT_JOURNAL_MISMATCH", rejection.code)
        val readmission = assertFailsWith<CheckpointUnconfirmed> {
            SessionResumeAdmission.admit(store(), context(), ledger(),
                resumeAttestation(expected = 2L))
        }
        assertEquals("CHECKPOINT_JOURNAL_MISMATCH", readmission.code)
        assertFalse(Files.exists(store().resolve("resume-000000000003.rcf")))
        assertEquals(2L, c2.snapshot.sequence)
    }

    @Test fun runningCheckpointCannotChangeEvenReadyChildrenAtPublication() {
        val c2 = publish(createPreExecutionCheckpoint())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            try {
                writer.appendTrustedAcceptedRunning(
                    trustedRunningTransition())
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val mutated = c2.snapshot.copy(
                    sequence = 3L, previousSha256 = c2.sha256,
                    phase = "running",
                    // Both versions are execution-free, but children MUST
                    // never change in a metadata-only lifecycle marker.
                    children = emptyList(),
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset
                )
                val refused = assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFenced(
                        lease, store(), context(), writer, mutated, c2.sha256)
                }
                assertEquals("CHECKPOINT_RUNNING_BOUNDARY_CHANGED", refused.code)
                assertFalse(Files.exists(store().resolve("000000000003.rcp")))
            } finally {
                assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.close()
                    }.code)
            }
        }
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
    }

    @Test fun closedSecondGenerationLeaseDeniesRunningAppendAndCheckpointWithoutMutation() {
        val c2 = publish(createPreExecutionCheckpoint())
        val before = Files.readAllBytes(ledger())
        val lease = SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L))
        val writer = DurableSessionEventLedger.reopenForAppend(
            ledger(), workspace(), store(), context(), lease)
        lease.close()

        try {
            assertEquals("RESUME_FENCE_STALE_WRITER",
                assertFailsWith<SessionResumeUnconfirmed> {
                    writer.appendTrustedAcceptedRunning(trustedRunningTransition())
                }.code)
            val proposed = c2.snapshot.copy(
                sequence = 3L, previousSha256 = c2.sha256,
                phase = SessionPhaseVocabulary.runningWire)
            assertEquals("RESUME_FENCE_STALE_WRITER",
                assertFailsWith<SessionResumeUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFenced(
                        lease, store(), context(), writer, proposed, c2.sha256)
                }.code)
            assertContentEquals(before, Files.readAllBytes(ledger()))
            assertFalse(Files.exists(store().resolve("000000000003.rcp")))
        } finally {
            assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
        }
        assertEquals(c2, inspect())
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
    }

    @Test fun c3CannotMintGenerationTwoWhileTheC2WriterStillOwnsGenerationOne() {
        val c1 = createPreExecutionCheckpoint()
        val generationOne = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation(expected = 0L))
        var c2: VerifiedSessionCheckpoint? = null
        try {
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), generationOne
            ).use { writer ->
                writer.appendFenced(fencedTransition(2, "created", "accepted"))
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val accepted = c1.snapshot.copy(
                    sequence = 2L, previousSha256 = c1.sha256,
                    phase = SessionPhaseVocabulary.acceptedWire, taskState = "ready",
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset
                )
                c2 = SessionCheckpointStore.saveFromOpenLedgerFenced(
                    generationOne, store(), context(), writer, accepted, c1.sha256)
                val exactC2 = c2!!
                val before = Files.readAllBytes(ledger())
                val denied = assertFailsWith<SessionResumeUnconfirmed> {
                    DurableAcceptedRunningHandoff.publish(
                        store(), context(), ledger(),
                        resumeAttestation(expected = 1L),
                        exactC2, trustedRunningTransition())
                }
                assertEquals("RESUME_LEASE_HELD", denied.code)
                assertContentEquals(before, Files.readAllBytes(ledger()))
                assertFalse(Files.exists(store().resolve("resume-000000000002.rcf")))
                assertFalse(Files.exists(store().resolve("000000000003.rcp")))
            }
        } finally {
            generationOne.close()
        }
        assertEquals(c2, inspect())
        assertEquals(1L, SessionResumeAdmission.currentGeneration(
            store(), context(), ledger()))
    }

    @Test fun retiredSecondGenerationThenWrongCasCannotAppendC3() {
        val c2 = publish(createPreExecutionCheckpoint())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { generationTwo ->
            assertEquals(2L, generationTwo.generation)
        }
        val before = Files.readAllBytes(ledger())
        val result = assertFailsWith<SessionResumeUnconfirmed> {
            DurableAcceptedRunningHandoff.publish(
                store(), context(), ledger(), resumeAttestation(expected = 1L),
                c2, trustedRunningTransition())
        }
        assertEquals("RESUME_FENCE_CAS_FAILED", result.code)
        assertContentEquals(before, Files.readAllBytes(ledger()))
        assertEquals(c2, inspect())
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
        assertFalse(Files.exists(store().resolve("resume-000000000003.rcf")))

        // The true next-generation CAS can still publish exactly one C3.
        val c3 = DurableAcceptedRunningHandoff.publish(
            store(), context(), ledger(), resumeAttestation(expected = 2L),
            c2, trustedRunningTransition())
        assertEquals(3L, c3.snapshot.sequence)
        assertEquals(SessionPhaseVocabulary.runningWire, inspect().snapshot.phase)
        assertEquals(3L, SessionResumeAdmission.currentGeneration(
            store(), context(), ledger()))
    }

    @Test fun runningOnlyFaultSeamPoisonedBeforeWriteCannotBeRetried() {
        publish(createPreExecutionCheckpoint())
        val baseline = Files.readAllBytes(ledger())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            assertEquals("simulated C3 before-write crash",
                assertFailsWith<IllegalStateException> {
                    writer.appendTrustedRunningWithFault(trustedRunningTransition()) { phase ->
                        if (phase == "before-record-write")
                            throw IllegalStateException("simulated C3 before-write crash")
                    }
                }.message)
            assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.appendTrustedAcceptedRunning(trustedRunningTransition())
                }.code)
            assertEquals("LEDGER_REOPEN_WRITER_POISONED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
        }
        assertContentEquals(baseline, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
    }

    @Test fun runningOnlyFaultSeamAfterForceLeavesUnconfirmedTail() {
        publish(createPreExecutionCheckpoint())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            assertEquals("LEDGER_SYNC_UNCONFIRMED",
                assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.appendTrustedRunningWithFault(trustedRunningTransition()) { phase ->
                        if (phase == "forced-before-ack")
                            throw IllegalStateException("C3 force acknowledgement lost")
                    }
                }.code)
            assertEquals("LEDGER_UNACKNOWLEDGED_APPEND",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
        }
        assertEquals(3, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
        assertEquals("CHECKPOINT_JOURNAL_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
    }

    @Test fun nonOptedRunningSnapshotWithoutC3EventIsNotPublishable() {
        val c2 = publish(createPreExecutionCheckpoint())
        val baseline = Files.readAllBytes(ledger())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease).use { writer ->
                val candidate = c2.snapshot.copy(
                    sequence = 3L, previousSha256 = c2.sha256,
                    phase = SessionPhaseVocabulary.runningWire)
                val refusal = assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFenced(
                        lease, store(), context(), writer, candidate, c2.sha256)
                }
                assertEquals("CHECKPOINT_JOURNAL_UNCONFIRMED", refusal.code)
                assertFalse(Files.exists(store().resolve("000000000003.rcp")))
                assertContentEquals(baseline, Files.readAllBytes(ledger()))
            }
        }
        assertEquals(c2, inspect())
    }

    @Test fun c3CannotMutateAttemptStateUsageEffectsOrPreviousHash() {
        val c2 = publish(createPreExecutionCheckpoint())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            try {
                writer.appendTrustedAcceptedRunning(trustedRunningTransition())
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val proposed = c2.snapshot.copy(
                    sequence = 3L, previousSha256 = c2.sha256,
                    phase = SessionPhaseVocabulary.runningWire,
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset)
                val invalid = listOf(
                    "taskState" to proposed.copy(taskState = "running"),
                    "taskAttempt" to proposed.copy(taskAttempt = 1),
                    "hostAssertedUsage" to proposed.copy(hostAssertedUsage = listOf(
                        HostAssertedCheckpointUsage("task-one", "model", "known", 1L))),
                    "pendingEffects" to proposed.copy(pendingEffects = listOf(
                        CheckpointEffect("effect-one", "req-transition-3", "unknown"))),
                    "previousSha256" to proposed.copy(previousSha256 = "f".repeat(64))
                )
                val physical = Files.readAllBytes(ledger())
                invalid.forEach { (name, candidate) ->
                    val refused = assertFailsWith<CheckpointUnconfirmed>(name) {
                        SessionCheckpointStore.saveFromOpenLedgerFenced(
                            lease, store(), context(), writer, candidate, c2.sha256)
                    }
                    assertEquals(
                        if (name == "previousSha256") "CHECKPOINT_STALE_GENERATION"
                        else "CHECKPOINT_JOURNAL_UNCONFIRMED", refused.code, name)
                    assertFalse(Files.exists(store().resolve("000000000003.rcp")), name)
                    assertContentEquals(physical, Files.readAllBytes(ledger()), name)
                }
            } finally {
                assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            }
        }
        assertEquals("CHECKPOINT_JOURNAL_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
    }

    @Test fun operatorPathsRefuseCompleteC3WithNoEffectAuthority() {
        val c2 = publish(createPreExecutionCheckpoint())
        val c3 = DurableAcceptedRunningHandoff.publish(
            store(), context(), ledger(), resumeAttestation(expected = 1L),
            c2, trustedRunningTransition())
        assertEquals(c3, inspect(), "Complete C3 is valid read-only evidence")
        val ledgerBytes = Files.readAllBytes(ledger())
        val latestFence = ResumeFenceStorage.atGeneration(store(), 2L)
        assertEquals("RESUME_UNSAFE_BOUNDARY",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(
                    store(), context(), ledger(), resumeAttestation(expected = 2L)).close()
            }.code)
        assertEquals("RESUME_UNSAFE_BOUNDARY",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.reconcilePreparedAdmission(
                    store(), context(), ledger(), resumeAttestation(expected = 2L),
                    PreparedFenceReconciliation(3L, "f".repeat(64), "operator-one", true))
                    .close()
            }.code)
        assertEquals("RESUME_EFFECT_RECONCILIATION_NO_PIN",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.reconcileGenesisEffectHead(
                    store(), context(), ledger(), ledger().resolveSibling("effects.journal"),
                    ByteArray(32) { 7 }, resumeAttestation(expected = 2L),
                    GenesisEffectHeadReconciliation(
                        "operator-one", true, c3.sha256, 2L,
                        latestFence.sha256, "f".repeat(64))).close()
            }.code)
        assertContentEquals(ledgerBytes, Files.readAllBytes(ledger()))
        assertEquals(c3, inspect())
        assertFalse(Files.exists(store().resolve("resume-000000000003.rcf")))
    }

    @Test fun operatorPathsRefuseUncoveredRunningTail() {
        val c2 = publish(createPreExecutionCheckpoint())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            writer.appendTrustedAcceptedRunning(trustedRunningTransition())
            assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED",
                assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
        }
        val physical = Files.readAllBytes(ledger())
        assertEquals("CHECKPOINT_JOURNAL_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionResumeAdmission.reconcilePreparedAdmission(
                    store(), context(), ledger(), resumeAttestation(expected = 2L),
                    PreparedFenceReconciliation(3L, "f".repeat(64), "operator-one", true))
                    .close()
            }.code)
        val fence = ResumeFenceStorage.atGeneration(store(), 2L)
        assertEquals("RESUME_EFFECT_RECONCILIATION_NO_PIN",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.reconcileGenesisEffectHead(
                    store(), context(), ledger(), ledger().resolveSibling("effects.journal"),
                    ByteArray(32) { 7 }, resumeAttestation(expected = 2L),
                    GenesisEffectHeadReconciliation(
                        "operator-one", true, c2.sha256, 2L,
                        fence.sha256, "f".repeat(64))).close()
            }.code)
        assertContentEquals(physical, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
        assertFalse(Files.exists(store().resolve("resume-000000000003.rcf")))
    }

    private fun assertC3PublicationCrashWindow(phase: String) {
        val c2 = publish(createPreExecutionCheckpoint())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            val writer = DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease)
            try {
                writer.appendTrustedAcceptedRunning(trustedRunningTransition())
                val anchor = writer.withCommittedCheckpointAnchor { it }
                val c3 = c2.snapshot.copy(
                    sequence = 3L, previousSha256 = c2.sha256,
                    phase = SessionPhaseVocabulary.runningWire,
                    journalSequence = anchor.lastSequence,
                    journalHeadSha256 = anchor.headSha256,
                    journalFileSha256 = anchor.fileSha256,
                    lastTrustedJournalOffset = anchor.lastTrustedOffset)
                val refusal = assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.saveFromOpenLedgerFencedWithFault(
                        lease, store(), context(), writer, c3, c2.sha256
                    ) { observed ->
                        if (observed == phase)
                            throw IllegalStateException("simulated C3 crash at " + phase)
                    }
                }
                assertEquals("CHECKPOINT_WRITE_UNCONFIRMED", refusal.code)
                assertEquals("CHECKPOINT_WRITE_UNCONFIRMED",
                    assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointStore.saveFromOpenLedgerFenced(
                            lease, store(), context(), writer, c3, c2.sha256)
                    }.code, "No blind C3 checkpoint retry")
                assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.appendTrustedAcceptedRunning(trustedRunningTransition(4))
                    }.code, "No append after uncertain C3 publication")
            } finally {
                assertEquals("LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED",
                    assertFailsWith<SessionLedgerUnconfirmed> { writer.close() }.code)
            }
        }
        val renamed = phase != "temp-synced"
        assertEquals(renamed, Files.exists(store().resolve("000000000003.rcp")))
        assertEquals(3, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        if (renamed) {
            assertEquals(SessionPhaseVocabulary.runningWire, inspect().snapshot.phase)
            assertEquals("RESUME_UNSAFE_BOUNDARY",
                assertFailsWith<SessionResumeUnconfirmed> {
                    SessionResumeAdmission.admit(store(), context(), ledger(),
                        resumeAttestation(expected = 2L)).close()
                }.code)
        } else {
            assertEquals("CHECKPOINT_JOURNAL_MISMATCH",
                assertFailsWith<CheckpointUnconfirmed> { inspect() }.code)
        }
        assertFalse(Files.exists(store().resolve("resume-000000000003.rcf")))
    }

    @Test fun c3TempSyncedCrashNeverPublishesOrRecovers() =
        assertC3PublicationCrashWindow("temp-synced")

    @Test fun c3RenamedCrashRequiresInspectionNotRetry() =
        assertC3PublicationCrashWindow("renamed")

    @Test fun c3DirectoryFsyncFailureRequiresInspectionNotRetry() =
        assertC3PublicationCrashWindow("before-directory-fsync")

}
