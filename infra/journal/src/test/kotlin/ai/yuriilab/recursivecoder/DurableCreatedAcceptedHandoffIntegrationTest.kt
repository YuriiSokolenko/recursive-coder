package ai.yuriilab.recursivecoder

import java.nio.file.Files
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real local-fs, ledger and checkpoint code; no model, tools or network. */
@Tag("integration")
@OptIn(TrustedPreExecutionHandoff::class)
class DurableCreatedAcceptedHandoffIntegrationTest : SessionCheckpointTestFixture() {
    @Test fun acceptedIsDurableBeforeHandoffReturnsAndNextGenerationCanInspect() {
        val c1 = createPreExecutionCheckpoint()

        val c2 = DurableCreatedAcceptedHandoff.publish(
            store(), context(), ledger(), resumeAttestation(), c1,
            fencedTransition(2, "created", "accepted")
        )

        assertEquals(2L, c2.snapshot.sequence)
        assertEquals(c1.sha256, c2.snapshot.previousSha256)
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
            DurableCreatedAcceptedHandoff.publish(
                store(), context(), ledger(), resumeAttestation(), c1,
                fencedTransition(2, "created", "running")
            )
        }

        assertEquals("RESUME_UNSAFE_BOUNDARY", denial.code)
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertEquals(c1, inspect())
        assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        assertFalse(Files.exists(store().resolve("resume.lock")))
    }
}
