package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Pure deterministic policy tests: the "read" callback represents the
 * production adapter's fresh authenticated physical recheck on both sides
 * of the external host state probe. The callback is NEVER a production
 * alternative for SessionCheckpointStore.inspectEffectHeadBound.
 */
@Tag("unit")
class CommittedEffectStatePolicyUnitTest {
    private val id = EffectIdentity(
        "sess-one", "effect-one", 1L, EffectKind.TRUSTED_WRITE, "a".repeat(64))
    private val intent = EffectRecord.Intent(id)
    private val started = EffectRecord.Started(id)
    private val committed = EffectRecord.Committed(id, "b".repeat(64))
    private val entries = listOf(intent, started, committed)
        .mapIndexed { i, record ->
            EffectJournalEntry(i.toLong() + 3, record, "c".repeat(64))
        }

    @Test fun positiveRequiresIndependentStateAndTwoFullEvidenceReads() {
        var reads = 0
        var probes = 0
        val result = CommittedEffectStatePolicy.verify(
            id, intent, started, committed,
            { reads++; entries },
            IndependentEffectStateProbe {
                assertEquals(id, it)
                probes++
                "b".repeat(64)
            })
        assertTrue(result)
        assertEquals(2, reads)
        assertEquals(1, probes)
    }

    @Test fun wrongMalformedMissingOrThrowingProbeAlwaysRefuses() {
        val answers = listOf<String?>("f".repeat(64), "not-a-digest", null)
        answers.forEach { answer ->
            var reads = 0
            assertFalse(CommittedEffectStatePolicy.verify(
                id, intent, started, committed, { reads++; entries },
                IndependentEffectStateProbe { answer }))
            assertEquals(1, reads, "No second read after a non-verifying probe")
        }
        var probes = 0
        assertFalse(CommittedEffectStatePolicy.verify(
            id, intent, started, committed, { entries },
            IndependentEffectStateProbe {
                probes++
                throw IllegalStateException("external reader unavailable")
            }))
        assertEquals(1, probes)
    }

    @Test fun evidenceChangesOrBecomesUnavailableAfterProbeRefuse() {
        val changed = entries.dropLast(1)
        var reads = 0
        assertFalse(CommittedEffectStatePolicy.verify(
            id, intent, started, committed, {
                reads++
                if (reads == 1) entries else changed
            }, IndependentEffectStateProbe { "b".repeat(64) }))
        assertEquals(2, reads)

        reads = 0
        assertFalse(CommittedEffectStatePolicy.verify(
            id, intent, started, committed, {
                reads++
                if (reads == 1) entries else null
            }, IndependentEffectStateProbe { "b".repeat(64) }))
        assertEquals(2, reads)

        reads = 0
        assertFalse(CommittedEffectStatePolicy.verify(
            id, intent, started, committed, {
                reads++
                if (reads == 1) entries else throw IllegalStateException("lease changed")
            }, IndependentEffectStateProbe { "b".repeat(64) }))
        assertEquals(2, reads)
    }

    @Test fun incompleteOrConflictingPhysicalOrderCannotCallProbe() {
        val changedIdentity = id.copy(inputSha256 = "d".repeat(64))
        val badHistories = listOf(
            entries.take(2),
            listOf(entries[1], entries[0], entries[2]),
            listOf(entries[0], entries[1],
                entries[2].copy(record = EffectRecord.Committed(changedIdentity, "b".repeat(64)))),
            emptyList()
        )
        badHistories.forEach { history ->
            var probes = 0
            assertFalse(CommittedEffectStatePolicy.verify(
                id, intent, started, committed, { history },
                IndependentEffectStateProbe {
                    probes++
                    "b".repeat(64)
                }))
            assertEquals(0, probes)
        }
        assertFalse(CommittedEffectStatePolicy.verify(
            id, intent, started, committed.copy(observedStateSha256 = "wrong"),
            { entries }, IndependentEffectStateProbe { fail("Must not call probe") }))
    }

    @Test fun vmFatalErrorEscapesAndMustAbortHostRecovery() {
        assertFailsWith<AssertionError> {
            CommittedEffectStatePolicy.verify(
                id, intent, started, committed, { entries },
                IndependentEffectStateProbe { throw AssertionError("fatal probe") })
        }
        assertFailsWith<StackOverflowError> {
            CommittedEffectStatePolicy.verify(
                id, intent, started, committed,
                { throw StackOverflowError("fatal evidence parse") },
                IndependentEffectStateProbe { "b".repeat(64) })
        }
    }
}
