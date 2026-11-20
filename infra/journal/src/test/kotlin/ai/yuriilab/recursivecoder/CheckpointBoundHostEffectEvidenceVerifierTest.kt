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

    private fun sidecar(): Path = temp.resolve("effect-root").also { dir ->
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
            Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        }
    }.resolve("journal.rcfx")

    @Test fun durableLedgerRefusesPartialAndWrongEventEffectEnvelopesAtWriteTime() {
        val intent = EffectRecord.Intent(expected)
        val committed = EffectRecord.Committed(expected, digestB)
        val full = effectEvent(1, 1L, intent, "c".repeat(64))
        val payload = full["metadata"] as JsonObject
        val negatives = listOf(
            event(1, "req-one", "completed", payload, "checkpoint.saved"),
            event(1, "req-one", "failed", payload, "tool.completed"),
            event(1, "req-one", "failed",
                (effectEvent(1, 1L, committed, "d".repeat(64))["metadata"]
                    as JsonObject), "tool.completed"),
            event(1, "req-one", "started", buildJsonObject {
                put("effectOperationId", "effect-one")
            }, "tool.started"),
            event(1, "req-one", "started", JsonObject(payload.toMutableMap().also {
                it["effectiveUnrelatedKey"] = kotlinx.serialization.json.JsonPrimitive("bad")
            }), "tool.started"),
            event(1, "req-one", "started", JsonObject(payload.toMutableMap().also {
                it.remove("effectRecordMacSha256")
            }), "tool.started"),
            event(1, "req-one", "started", JsonObject(payload.toMutableMap().also {
                it["graphId"] = kotlinx.serialization.json.JsonPrimitive("graph-root")
            }), "tool.started")
        )
        negatives.forEachIndexed { index, invalid ->
            val path = temp.resolve("invalid-envelope-$index.ledger")
            DurableSessionEventLedger.create(path, workspace()).use { writer ->
                assertEquals("LEDGER_EFFECT_ENVELOPE_INVALID",
                    assertFailsWith<SessionLedgerUnconfirmed> {
                        writer.append(invalid)
                    }.code)
            }
            assertEquals(0, DurableSessionEventLedger.inspect(path).lastSequence)
        }
    }

    @Test fun sealedSuccessorAuthenticatesHistoricalGenesisButNoEffectOutcome() {
        val original = createPreExecutionCheckpoint()
        val path = sidecar()
        lateinit var pinned: VerifiedSessionCheckpoint
        lateinit var mac: String
        SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation()).use { lease ->
            DurableEffectRecordJournal.create(path, workspace(),
                "sess-one", hostKey, lease).use { writer ->
                pinned = SessionCheckpointStore.saveEffectHeadFenced(
                    lease, store(), context(), ledger(), writer,
                    original.snapshot.copy(sequence = original.snapshot.sequence + 1L,
                        previousSha256 = original.sha256), original.sha256)
                mac = writer.headPin().headMacSha256
            }
        }
        val fence = ResumeFenceStorage.atGeneration(store(), 1L)
        val approval = GenesisEffectHeadReconciliation("operator-one", true,
            pinned.sha256, 1L, fence.sha256, mac)
        SessionResumeAdmission.reconcileGenesisEffectHead(
            store(), context(), ledger(), path, hostKey,
            resumeAttestation(1L), approval).use { successor ->
            assertEquals(2L, successor.generation)
            val closed = DurableSessionEventLedger.inspect(ledger())
            val view = EffectJournalView(closed.headSha256,
                closed.lastSequence.toLong(), emptyList())
            var probes = 0
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, hostKey, successor,
                expected, view, IndependentEffectStateProbe {
                    probes++
                    digestB
                }).use { verifier ->
                // The entire v3/fence/anchor/HMAC-bound historical genesis is
                // now independently authenticated under the N+1 lease.
                assertTrue(verifier.authenticateCompleteJournal(
                    view, expected, successor.generation))
                assertFalse(verifier.authenticateCompleteJournal(
                    view, expected, 1L))
                assertFalse(verifier.authenticateCompleteJournal(
                    view.copy(headSha256 = digestB), expected, successor.generation))
                assertFalse(verifier.authenticateCompleteJournal(
                    view.copy(entries = listOf(EffectJournalEntry(1L,
                        EffectRecord.Intent(expected), digestB))),
                    expected, successor.generation))
                assertFalse(verifier.authenticateCompleteJournal(
                    view, expected.copy(fenceGeneration = 2L), successor.generation))
                // A genesis-only history cannot establish Committed or
                // actual non-execution; neither branch may consult the probe.
                assertEquals(EffectRecoveryOutcome.Quarantined("EFFECT_INTENT_MISSING"),
                    EffectReconciliationPolicy.reconcile(
                        expected, successor.generation, view, verifier))
                assertFalse(verifier.independentlyVerifyCommitted(
                    EffectRecord.Intent(expected),
                    EffectRecord.Started(expected),
                    EffectRecord.Committed(expected, digestB)))
                assertFalse(verifier.independentlyVerifyNotStarted(
                    EffectRecord.Intent(expected),
                    EffectNotStartedEvidence(expected, view.headSha256,
                        view.lastSequence, 2L, digestB, digestB),
                    successor.generation))
            }
            assertEquals(0, probes)
            // A verifier cannot trust the v3 checksum without its host MAC.
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, ByteArray(32), successor,
                expected, view, IndependentEffectStateProbe { digestB }).use {
                assertFalse(it.authenticateCompleteJournal(
                    view, expected, successor.generation))
            }
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path.resolveSibling("wrong.rcfx"),
                hostKey, successor, expected, view,
                IndependentEffectStateProbe { digestB }).use {
                assertFalse(it.authenticateCompleteJournal(
                    view, expected, successor.generation))
            }
            // Caller cannot re-label a historical N journal as N+1.
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, hostKey, successor,
                expected.copy(fenceGeneration = 2L), view,
                IndependentEffectStateProbe { digestB }).use {
                assertFalse(it.authenticateCompleteJournal(
                    view, expected.copy(fenceGeneration = 2L),
                    successor.generation))
            }
        }
    }

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
