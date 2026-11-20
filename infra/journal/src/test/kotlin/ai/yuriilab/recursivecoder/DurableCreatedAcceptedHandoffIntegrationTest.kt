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
                fencedTransition(3, "accepted", "running"))
            val failedClose = assertFailsWith<SessionLedgerUnconfirmed> {
                writer.close()
            }
            assertEquals("LEDGER_REOPEN_CHECKPOINT_REQUIRED", failedClose.code)
        }
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
        assertEquals(3, DurableSessionEventLedger.inspect(ledger()).lastSequence)
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
                    fencedTransition(3, "accepted", "running"))
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

}
