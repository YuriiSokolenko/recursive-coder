package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Real RCLDG1 + checkpoint/fence + RCEFX1 provenance, and isolated pure
 * projection fixtures. No real external state is inferred from a receipt.
 */
@Tag("integration")
@OptIn(ResumeOperatorOnlyApi::class)
class CheckpointBoundHostEffectEvidenceVerifierTest : SessionCheckpointTestFixture() {
    private val hostKey = ByteArray(32) { (it + 20).toByte() }
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)
    private val expected = EffectIdentity("sess-one", "effect-one", 1L,
        EffectKind.TRUSTED_WRITE, digestA)

    private fun physical(vararg records: EffectRecord) =
        VerifiedEffectRecordJournal(
            EffectJournalHeader("sess-one", 1L, digestA, digestB),
            records.mapIndexed { i, record ->
                EffectJournalEntry(i.toLong() + 1L, record,
                    ('c' + i).toString().repeat(64))
            },
            records.size.toLong(), "c".repeat(64), "d".repeat(64),
            500L, 500L
        )

    private fun effectEvent(eventSequence: Int, physicalSequence: Long,
                            record: EffectRecord, mac: String): JsonObject {
        val state = when (record) {
            is EffectRecord.Intent -> "intent"
            is EffectRecord.Started -> "started"
            is EffectRecord.Committed -> "committed"
            is EffectRecord.Unconfirmed -> "unconfirmed"
        }
        val metadata = buildJsonObject {
            put("effectOperationId", record.identity.operationId)
            put("effectFenceGeneration", record.identity.fenceGeneration)
            put("effectKind", record.identity.kind.name)
            put("effectInputSha256", record.identity.inputSha256)
            put("effectRecordSequence", physicalSequence)
            put("effectRecordMacSha256", mac)
            put("effectState", state)
            if (record is EffectRecord.Committed)
                put("effectObservedStateSha256", record.observedStateSha256)
            if (record is EffectRecord.Unconfirmed)
                put("effectErrorCode", record.code)
        }
        return event(eventSequence, "req-effect", when (state) {
            "intent", "started" -> "started"
            "committed" -> "completed"
            else -> "failed"
        }, metadata, when (state) {
            "intent", "started" -> "tool.started"
            "committed" -> "tool.completed"
            else -> "session.error"
        })
    }

    @Test fun completeEffectProjectionMatchesEveryPhysicalRecordAndSessionEvent() {
        val records = arrayOf(
            EffectRecord.Intent(expected), EffectRecord.Started(expected),
            EffectRecord.Committed(expected, digestB))
        val physical = physical(*records)
        val history = listOf(
            event(1, "req-one", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "checkpoint.saved"),
            effectEvent(2, 1L, records[0], "c".repeat(64)),
            JsonObject(event(3, "req-two", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "checkpoint.saved").toMutableMap().also { it.remove("metadata") }),
            effectEvent(4, 2L, records[1], "d".repeat(64)),
            effectEvent(5, 3L, records[2], "e".repeat(64))
        )
        val result = assertNotNull(EffectEvidenceProjection.fromSessionLedger(history, physical))
        assertEquals(listOf(2L, 4L, 5L), result.map { it.sequence })
        assertEquals(records.toList(), result.map { it.record })
        assertEquals(listOf("c", "d", "e"), result.map { it.hostMacSha256.take(1) })
        // Missing a single ledger effect does not become an implicit no-op.
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history.filterIndexed { i, _ -> i != 3 }, physical))
        // A correct-looking but wrong MAC must not match the physical prefix.
        val wrongMac = history.toMutableList().also {
            it[4] = effectEvent(5, 3L, records[2], "f".repeat(64))
        }
        assertNull(EffectEvidenceProjection.fromSessionLedger(wrongMac, physical))
        // A second record with the same sequence is not authoritative.
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history + effectEvent(6, 3L, records[2], "e".repeat(64)), physical))
        // Invalid metadata must not be silently skipped as a non-effect event.
        val partial = history.toMutableList().also {
            it[2] = event(3, "req-two", "completed", buildJsonObject {
                put("effectOperationId", "effect-one")
            }, "checkpoint.saved")
        }
        assertNull(EffectEvidenceProjection.fromSessionLedger(partial, physical))
        // Unmarked tool events may be mutating and therefore fail closed.
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history + event(6, "req-three", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "tool.completed"), physical))
    }

    @Test fun realSessionLedgerRoundTripsEntireEffectEnvelopeProjection() {
        val records = arrayOf(EffectRecord.Intent(expected),
            EffectRecord.Started(expected), EffectRecord.Committed(expected, digestB))
        val physical = physical(*records)
        DurableSessionEventLedger.create(ledger(), workspace()).use { writer ->
            writer.append(event(1, "req-one", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "checkpoint.saved"))
            writer.flush()
            records.forEachIndexed { i, record ->
                writer.append(effectEvent(i + 2, i.toLong() + 1L, record,
                    physical.entries[i].hostMacSha256))
                writer.flush()
            }
        }
        val real = DurableSessionEventLedger.inspect(ledger())
        assertEquals(4, real.lastSequence)
        assertEquals(3, EffectEvidenceProjection.fromSessionLedger(
            real.events, physical)?.size)
        val dropped = real.events.filterIndexed { i, _ -> i != 2 }
        assertNull(EffectEvidenceProjection.fromSessionLedger(dropped, physical))
    }

    @Test fun unconfirmedAndTornPhysicalHistoriesNeverProjectAsComplete() {
        val intent = EffectRecord.Intent(expected)
        val physical = physical(intent, EffectRecord.Unconfirmed(expected, "HOST_ERROR"))
        val history = listOf(
            effectEvent(1, 1L, intent, "c".repeat(64)),
            effectEvent(2, 2L, physical.entries.last().record, "d".repeat(64))
        )
        assertEquals(2, EffectEvidenceProjection.fromSessionLedger(history, physical)?.size)
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history, physical.copy(tornTailOffset = 500L,
                tailKind = EffectJournalTailKind.SHORT_FRAME)))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history, physical.copy(lastSequence = 1L)))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history, physical.copy(entries = physical.entries.take(1))))
    }

    private fun sidecar(): Path = temp.resolve("effect-root").also { dir ->
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
            Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        }
    }.resolve("journal.rcfx")

    @Test fun realPinnedGenesisRequiresClosedJournalExactViewAndCurrentLease() {
        val initial = createPreExecutionCheckpoint()
        val live = SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation())
        try {
            val path = sidecar()
            DurableEffectRecordJournal.create(path, workspace(), "sess-one", hostKey,
                live).use { writer ->
                val c2 = SessionCheckpointStore.saveEffectHeadFenced(
                    live, store(), context(), ledger(), writer,
                    initial.snapshot.copy(sequence = 2L,
                        previousSha256 = initial.sha256), initial.sha256)
                assertEquals(0L, c2.snapshot.effectJournalHead?.lastSequence)
                val physicalLedger = DurableSessionEventLedger.inspect(ledger())
                val headView = EffectJournalView(physicalLedger.headSha256,
                    physicalLedger.lastSequence.toLong(), emptyList())
                CheckpointBoundHostEffectEvidenceVerifier(store(), context(), ledger(),
                    path, hostKey, live, expected, headView,
                    IndependentEffectStateProbe { digestB }).use { verifier ->
                    // A live, exclusively held journal writer blocks inspection.
                    assertFalse(verifier.authenticateCompleteJournal(
                        headView, expected, live.generation))
                }
            }
            val replay = DurableSessionEventLedger.inspect(ledger())
            val view = EffectJournalView(replay.headSha256,
                replay.lastSequence.toLong(), emptyList())
            CheckpointBoundHostEffectEvidenceVerifier(store(), context(), ledger(),
                path, hostKey, live, expected, view,
                IndependentEffectStateProbe { digestB }).use { verifier ->
                assertTrue(verifier.authenticateCompleteJournal(
                    view, expected, live.generation))
                assertFalse(verifier.authenticateCompleteJournal(
                    view, expected, live.generation + 1L))
                assertFalse(verifier.authenticateCompleteJournal(
                    view.copy(headSha256 = digestB), expected, live.generation))
                assertFalse(verifier.authenticateCompleteJournal(
                    view.copy(entries = listOf(EffectJournalEntry(1L,
                        EffectRecord.Intent(expected), digestB))),
                    expected, live.generation))
                assertEquals(EffectRecoveryOutcome.Quarantined("EFFECT_INTENT_MISSING"),
                    EffectReconciliationPolicy.reconcile(
                        expected, live.generation, view, verifier))
                // Even a forged Committed and a dishonest state probe cannot
                // produce success without physical and event-ledger receipts.
                assertFalse(verifier.independentlyVerifyCommitted(
                    EffectRecord.Intent(expected), EffectRecord.Started(expected),
                    EffectRecord.Committed(expected, digestB)))
                assertFalse(verifier.independentlyVerifyNotStarted(
                    EffectRecord.Intent(expected),
                    EffectNotStartedEvidence(expected, view.headSha256,
                        view.lastSequence, 2L, digestB, digestB), 2L))
            }
            CheckpointBoundHostEffectEvidenceVerifier(store(), context(), ledger(),
                path, ByteArray(32) { 0 }, live, expected, view,
                IndependentEffectStateProbe { digestB }).use { verifier ->
                assertFalse(verifier.authenticateCompleteJournal(
                    view, expected, live.generation))
            }
            CheckpointBoundHostEffectEvidenceVerifier(store(), context(), ledger(),
                path, hostKey, live, expected, view,
                IndependentEffectStateProbe { digestB }).use { verifier ->
                verifier.close()
                assertFalse(verifier.authenticateCompleteJournal(
                    view, expected, live.generation))
            }
        } finally { live.close() }
        val finished = DurableSessionEventLedger.inspect(ledger())
        val view = EffectJournalView(finished.headSha256,
            finished.lastSequence.toLong(), emptyList())
        CheckpointBoundHostEffectEvidenceVerifier(store(), context(), ledger(),
            sidecar(), hostKey, live, expected, view,
            IndependentEffectStateProbe { digestB }).use { verifier ->
            assertFalse(verifier.authenticateCompleteJournal(view, expected, 1L))
        }
    }
}
