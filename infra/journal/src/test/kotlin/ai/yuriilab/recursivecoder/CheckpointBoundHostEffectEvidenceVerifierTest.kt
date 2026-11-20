package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
        assertEquals(3, (EffectEvidenceProjection.inspectForDiagnostics(
            real.events, physical) as? EffectEvidenceProjection.Result.Verified)
            ?.entries?.size)
        val dropped = real.events.filterIndexed { i, _ -> i != 2 }
        assertIs<EffectEvidenceProjection.Result.Denied>(
            EffectEvidenceProjection.inspectForDiagnostics(dropped, physical))
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
            // Wrong host key is rejected, independently of which MAC check
            // fails. The isolated v3 seal policy unit test specifically pins
            // RESUME_EFFECT_RECONCILIATION_MAC_MISMATCH with a correct host key.
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, ByteArray(32), successor,
                expected, view, IndependentEffectStateProbe { digestB }).use {
                assertFalse(it.authenticateCompleteJournal(
                    view, expected, successor.generation))
            }
            // Copy REAL, byte-identical authenticated genesis to another
            // private-path file. The exact rejection code must be the path
            // SHA check, before opening or recomputing the path-bound HMAC.
            val v3ForPin = SessionCheckpointStore.inspect(
                store(), context(), ledger())
            assertNotNull(v3ForPin.snapshot.effectReconciliation)
            val wrongPath = path.resolveSibling("different-locator.rcfx")
            Files.copy(path, wrongPath)
            Files.setPosixFilePermissions(wrongPath, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            assertContentEquals(Files.readAllBytes(path), Files.readAllBytes(wrongPath))
            assertEquals("CHECKPOINT_EFFECT_HEAD_PATH_MISMATCH",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointStore.inspectEffectHeadForLease(
                        store(), context(), ledger(), wrongPath, hostKey,
                        successor, expected, v3ForPin.sha256)
                }.code)
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), wrongPath,
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
            // A physical torn nonzero suffix after the N -> N+1 transition
            // must be detected by the HMAC-pinned reader, not by v3 metadata.
            val physicalBytes = Files.readAllBytes(path)
            try {
                Files.write(path, byteArrayOf(0x55), StandardOpenOption.APPEND)
                assertEquals("EFFECT_JOURNAL_TRUNCATED",
                    assertFailsWith<EffectJournalStorageUnconfirmed> {
                        SessionCheckpointStore.inspectEffectHeadForLease(
                            store(), context(), ledger(), path, hostKey,
                            successor, expected, v3ForPin.sha256)
                    }.code)
                CheckpointBoundHostEffectEvidenceVerifier(
                    store(), context(), ledger(), path, hostKey, successor,
                    expected, view, IndependentEffectStateProbe { digestB }).use {
                    assertFalse(it.authenticateCompleteJournal(
                        view, expected, successor.generation))
                }
            } finally {
                Files.write(path, physicalBytes,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            }
            // Restore the same physical bytes to prove the preceding denial
            // was caused by tampering rather than an unrelated authority loss.
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, hostKey, successor,
                expected, view, IndependentEffectStateProbe { digestB }).use {
                assertTrue(it.authenticateCompleteJournal(
                    view, expected, successor.generation))
            }
            // The historical window ends as soon as v3 ceases to be the
            // admitted latest checkpoint, even if N+1 still holds its lease.
            val v3 = SessionCheckpointStore.inspect(store(), context(), ledger())
            assertNotNull(v3.snapshot.effectReconciliation)
            val successorCheckpoint = v3.snapshot.copy(
                sequence = v3.snapshot.sequence + 1L,
                previousSha256 = v3.sha256,
                effectJournalHead = null, effectReconciliation = null)
            SessionCheckpointStore.saveFenced(successor, store(), context(),
                ledger(), successorCheckpoint, v3.sha256)
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, hostKey, successor,
                expected, view, IndependentEffectStateProbe { digestB }).use {
                assertFalse(it.authenticateCompleteJournal(
                    view, expected, successor.generation))
            }
        }
        // An N+2 lease is never allowed to reinterpret the old N genesis
        // as directly issued in the generation immediately preceding it.
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(2L)).use { nPlusTwo ->
            assertEquals(3L, nPlusTwo.generation)
            val closed = DurableSessionEventLedger.inspect(ledger())
            val view = EffectJournalView(closed.headSha256,
                closed.lastSequence.toLong(), emptyList())
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, hostKey, nPlusTwo,
                expected, view, IndependentEffectStateProbe { digestB }).use {
                assertFalse(it.authenticateCompleteJournal(
                    view, expected, nPlusTwo.generation))
            }
        }
    }

    @Test fun validHostMacAppendAfterTransitionCannotBypassSealedGenesisPin() {
        val original = createPreExecutionCheckpoint()
        val path = sidecar()
        lateinit var pinned: VerifiedSessionCheckpoint
        lateinit var mac: String
        lateinit var genesisBytes: ByteArray
        lateinit var validIntentFrame: ByteArray

        // Produce an authentic, fsynced Intent frame with the real N host
        // key, exact path, genesis chain and live writer. This fixture does
        // NOT execute an external effect or persist an effectful checkpoint.
        SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation()).use { n ->
            DurableEffectRecordJournal.create(path, workspace(),
                "sess-one", hostKey, n).use { writer ->
                pinned = SessionCheckpointStore.saveEffectHeadFenced(
                    n, store(), context(), ledger(), writer,
                    original.snapshot.copy(
                        sequence = original.snapshot.sequence + 1L,
                        previousSha256 = original.sha256), original.sha256)
                mac = writer.headPin().headMacSha256
                genesisBytes = Files.readAllBytes(path)
                writer.append(EffectRecord.Intent(expected))
                val withValidIntent = Files.readAllBytes(path)
                assertTrue(withValidIntent.size > genesisBytes.size)
                validIntentFrame = withValidIntent.copyOfRange(
                    genesisBytes.size, withValidIntent.size)
                assertTrue(validIntentFrame.size > 36)
            }
        }
        // Recreate the exact pinned, physically empty original before the
        // operator approves the canonical N -> N+1 genesis reconciliation.
        Files.write(path, genesisBytes,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        val fence = ResumeFenceStorage.atGeneration(store(), 1L)
        SessionResumeAdmission.reconcileGenesisEffectHead(
            store(), context(), ledger(), path, hostKey, resumeAttestation(1L),
            GenesisEffectHeadReconciliation(
                "operator-one", true, pinned.sha256, 1L, fence.sha256, mac))
            .use { successor ->
                val v3 = SessionCheckpointStore.inspect(store(), context(), ledger())
                val sealedHead = assertNotNull(
                    v3.snapshot.effectReconciliation).sourceHead
                assertEquals(0L, sealedHead.lastSequence)
                val closed = DurableSessionEventLedger.inspect(ledger())
                val view = EffectJournalView(closed.headSha256,
                    closed.lastSequence.toLong(), emptyList())
                CheckpointBoundHostEffectEvidenceVerifier(
                    store(), context(), ledger(), path, hostKey, successor,
                    expected, view, IndependentEffectStateProbe {
                        fail("Historical genesis must never consult external state")
                    }).use { verifier ->
                    assertTrue(verifier.authenticateCompleteJournal(
                        view, expected, successor.generation))
                    try {
                        // Simulate a same-key, same-path stale writer after
                        // transition. The appended frame is structurally
                        // valid AND independently HMAC-authenticated.
                        Files.write(path, validIntentFrame, StandardOpenOption.APPEND)
                        val verifiedPhysical = DurableEffectRecordJournal.inspect(
                            path, workspace(), hostKey, sealedHead.expectation())
                        assertEquals(1L, verifiedPhysical.lastSequence)
                        assertEquals(EffectRecord.Intent(expected),
                            verifiedPhysical.entries.single().record)
                        // An attacker can always invent a pin for that valid
                        // suffix, but the result is a diagnostic-only type.
                        val attackerPin = EffectJournalHeadPin(
                            verifiedPhysical.lastSequence, verifiedPhysical.headMacSha256)
                        val selfPinned = DurableEffectRecordJournal
                            .inspectPinnedForDiagnostics(path, workspace(), hostKey,
                                sealedHead.expectation(), attackerPin)
                        assertEquals(1L, selfPinned.lastSequence)
                        assertEquals(attackerPin.headMacSha256, selfPinned.headMacSha256)
                        // Regression guard: the current verifier always denies
                        // a claimed commit here. This is NOT evidence supplied
                        // by the diagnostic result or a tested future probe.
                        assertFalse(verifier.independentlyVerifyCommitted(
                            EffectRecord.Intent(expected),
                            EffectRecord.Started(expected),
                            EffectRecord.Committed(expected, digestB)))

                        // The pinned sequence/MAC comparison must be the
                        // ONLY remaining obstacle; removing that guard
                        // makes this precise regression fail.
                        assertEquals("EFFECT_JOURNAL_PIN_MISMATCH",
                            assertFailsWith<EffectJournalStorageUnconfirmed> {
                                SessionCheckpointStore.inspectEffectHeadForLease(
                                    store(), context(), ledger(), path, hostKey,
                                    successor, expected, v3.sha256)
                            }.code)
                        assertFalse(verifier.authenticateCompleteJournal(
                            view, expected, successor.generation))
                    } finally {
                        Files.write(path, genesisBytes,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)
                    }
                    assertTrue(verifier.authenticateCompleteJournal(
                        view, expected, successor.generation))
                    // This physical success after rolling the valid suffix
                    // back is NOT a proof the effect never began.
                    val genesisDiagnostic = DurableEffectRecordJournal
                        .inspectPinnedForDiagnostics(path, workspace(), hostKey,
                            sealedHead.expectation(), sealedHead.pin())
                    assertEquals(0L, genesisDiagnostic.lastSequence)
                    assertEquals(sealedHead.headMacSha256, genesisDiagnostic.headMacSha256)
                    // Regression guard for today's always-deny no-start
                    // verifier; not proof that rollback was detected.
                    assertFalse(verifier.independentlyVerifyNotStarted(
                        EffectRecord.Intent(expected),
                        EffectNotStartedEvidence(expected, view.headSha256,
                            view.lastSequence, successor.generation, digestB, digestB),
                        successor.generation))
                }
            }
    }

    @Test fun nPlusTwoCannotAuthenticateHistoricalGenesisWhileV3IsStillTail() {
        val original = createPreExecutionCheckpoint()
        val path = sidecar()
        lateinit var pinned: VerifiedSessionCheckpoint
        lateinit var mac: String
        SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation()).use { n ->
            DurableEffectRecordJournal.create(path, workspace(),
                "sess-one", hostKey, n).use { writer ->
                pinned = SessionCheckpointStore.saveEffectHeadFenced(
                    n, store(), context(), ledger(), writer,
                    original.snapshot.copy(sequence = original.snapshot.sequence + 1L,
                        previousSha256 = original.sha256), original.sha256)
                mac = writer.headPin().headMacSha256
            }
        }
        val fence = ResumeFenceStorage.atGeneration(store(), 1L)
        SessionResumeAdmission.reconcileGenesisEffectHead(
            store(), context(), ledger(), path, hostKey, resumeAttestation(1L),
            GenesisEffectHeadReconciliation(
                "operator-one", true, pinned.sha256, 1L, fence.sha256, mac))
            .use { nPlusOne ->
                val ledgerView = DurableSessionEventLedger.inspect(ledger())
                val view = EffectJournalView(ledgerView.headSha256,
                    ledgerView.lastSequence.toLong(), emptyList())
                CheckpointBoundHostEffectEvidenceVerifier(
                    store(), context(), ledger(), path, hostKey, nPlusOne,
                    expected, view, IndependentEffectStateProbe {
                        fail("Empty historical genesis must not probe external state")
                    }).use {
                    assertTrue(it.authenticateCompleteJournal(
                        view, expected, nPlusOne.generation))
                }
            }
        val v3 = SessionCheckpointStore.inspect(store(), context(), ledger())
        assertNotNull(v3.snapshot.effectReconciliation)
        assertNull(v3.snapshot.effectJournalHead)
        SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation(2L)).use { nPlusTwo ->
            assertEquals(3L, nPlusTwo.generation)
            assertEquals(v3.sha256, nPlusTwo.admittedCheckpointSha256)
            val ledgerView = DurableSessionEventLedger.inspect(ledger())
            val view = EffectJournalView(ledgerView.headSha256,
                ledgerView.lastSequence.toLong(), emptyList())
            // v3 is STILL the tail, and its physical pin is intact.
            // Only the immediate-successor generation check can deny.
            assertEquals(v3.sha256,
                SessionCheckpointStore.inspect(store(), context(), ledger()).sha256)
            CheckpointBoundHostEffectEvidenceVerifier(
                store(), context(), ledger(), path, hostKey, nPlusTwo,
                expected, view, IndependentEffectStateProbe {
                    fail("N+2 must never consult external state")
                }).use {
                assertFalse(it.authenticateCompleteJournal(
                    view, expected, nPlusTwo.generation))
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
