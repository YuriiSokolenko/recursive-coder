package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Operator-approved GENESIS ONLY transition. No physical effect executes and
 * a Started record is never classified from an operator boolean.
 */
@Tag("integration")
@OptIn(ResumeOperatorOnlyApi::class, LedgerTestFaultInjection::class)
class GenesisEffectReconciliationIntegrationTest : SessionCheckpointTestFixture() {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private fun parent(): Path = temp.resolve("effect-private").also {
        if (!Files.exists(it)) {
            Files.createDirectories(it)
            Files.setPosixFilePermissions(it, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE))
        }
    }
    private fun path(generation: Long = 1L) =
        parent().resolve("effects-" + generation + ".rcfx")
    private fun head(lease: SessionResumeLease) =
        DurableEffectRecordJournal.create(path(lease.generation), workspace(),
            "sess-one", key, lease)
    private fun advanced(previous: VerifiedSessionCheckpoint) =
        previous.snapshot.copy(sequence = previous.snapshot.sequence + 1L,
            previousSha256 = previous.sha256, effectJournalHead = null)
    private data class Ready(val pinned: VerifiedSessionCheckpoint,
                             val fence: ResumeFenceRecord,
                             val mac: String)

    private fun pinnedGenesis(): Ready {
        val c1 = createPreExecutionCheckpoint()
        lateinit var result: Ready
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())
            .use { lease ->
                head(lease).use { journal ->
                    val pinned = SessionCheckpointStore.saveEffectHeadFenced(
                        lease, store(), context(), ledger(), journal,
                        advanced(c1), c1.sha256)
                    result = Ready(pinned, ResumeFenceStorage.atGeneration(store(), 1L),
                        journal.headPin().headMacSha256)
                }
            }
        return result
    }

    private fun approval(ready: Ready, operatorId: String = "operator-one") =
        GenesisEffectHeadReconciliation(operatorId, true,
            ready.pinned.sha256, 1L, ready.fence.sha256, ready.mac)

    private fun reconcile(ready: Ready, keyOverride: ByteArray = key,
                          approval: GenesisEffectHeadReconciliation = approval(ready)
    ) = SessionResumeAdmission.reconcileGenesisEffectHead(
        store(), context(), ledger(), path(), keyOverride,
        resumeAttestation(1L), approval)

    @Test fun operatorSealsGenesisTransitionThenAdmitsNextFenceAndNewJournal() {
        val ready = pinnedGenesis()
        assertEquals("RESUME_UNSAFE_BOUNDARY",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(
                    store(), context(), ledger(), resumeAttestation(1L))
            }.code)

        reconcile(ready).use { lease ->
            assertEquals(2L, lease.generation)
            val transition = inspect()
            val record = assertNotNull(transition.snapshot.effectReconciliation)
            assertNull(transition.snapshot.effectJournalHead)
            assertEquals(ready.pinned.sha256, record.sourceCheckpointSha256)
            assertEquals(ready.fence.sha256, record.sourceFenceSha256)
            assertEquals(0L, record.sourceHead.lastSequence)
            assertEquals("operator-one", record.operatorId)
            assertEquals(64, record.approvalMacSha256.length)
            assertTrue(transition.reconciliationRequired)
            assertTrue(SessionCheckpointCodec.encode(transition.snapshot)
                .toString(StandardCharsets.UTF_8).contains("\"version\":3"))
            assertEquals(transition.snapshot,
                SessionCheckpointCodec.decode(
                    SessionCheckpointCodec.encode(transition.snapshot)))

            head(lease).use { journal2 ->
                val c4 = SessionCheckpointStore.saveEffectHeadFenced(
                    lease, store(), context(), ledger(), journal2,
                    advanced(transition), transition.sha256)
                assertEquals(2L, c4.snapshot.effectJournalHead!!.fenceGeneration)
                assertEquals(record, c4.snapshot.effectReconciliation)
            }
            // Physical inspection requires the writer's exclusive lock to
            // be released; this is intentional, not a concurrent read API.
            assertEquals(0L, SessionCheckpointStore.inspectEffectHeadBound(
                store(), context(), ledger(), path(2L), key).lastSequence)
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(
                    store(), context(), ledger(), resumeAttestation(2L))
            }.code)
        assertEquals(4L, inspect().snapshot.sequence)
        assertEquals(2L, ResumeFenceStorage.latest(store())!!.generation)
    }

    @Test fun mismatchedOrUnapprovedOperatorCannotPublishTransition() {
        val ready = pinnedGenesis()
        val candidate = approval(ready)
        val wrong = listOf(
            candidate.copy(operatorApproved = false),
            candidate.copy(expectedCheckpointSha256 = "a".repeat(64)),
            candidate.copy(expectedFenceSha256 = "b".repeat(64)),
            candidate.copy(expectedHeadMacSha256 = "c".repeat(64)),
            candidate.copy(operatorId = "!"),
            candidate.copy(expectedFenceGeneration = 2L)
        )
        wrong.forEach { forged ->
            assertFailsWith<SessionResumeUnconfirmed> {
                reconcile(ready, approval = forged).close()
            }
            assertEquals(ready.pinned.sha256, inspect().sha256)
            assertEquals(1L, ResumeFenceStorage.latest(store())!!.generation)
        }
    }

    @Test fun missingHostKeyOrWrongKeyCannotReconcileGenesis() {
        val ready = pinnedGenesis()
        assertFailsWith<SessionResumeUnconfirmed> {
            reconcile(ready, keyOverride = ByteArray(8)).close()
        }
        assertFails { reconcile(ready, keyOverride = ByteArray(32) { 9 }).close() }
        assertEquals(ready.pinned.sha256, inspect().sha256)
    }

    @Test fun intentOrStartedRecordRemainsQuarantinedEvenIfOperatorApproves() {
        val c1 = createPreExecutionCheckpoint()
        lateinit var pinned: VerifiedSessionCheckpoint
        lateinit var mac: String
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())
            .use { lease ->
                head(lease).use { journal ->
                    val intent = EffectRecord.Intent(EffectIdentity("sess-one",
                        "effect-one", 1L, EffectKind.TRUSTED_WRITE, "a".repeat(64)))
                    journal.append(intent)
                    journal.append(EffectRecord.Started(intent.identity))
                    pinned = SessionCheckpointStore.saveEffectHeadFenced(
                        lease, store(), context(), ledger(), journal,
                        advanced(c1), c1.sha256)
                    mac = journal.headPin().headMacSha256
                }
            }
        val ready = Ready(pinned, ResumeFenceStorage.atGeneration(store(), 1L), mac)
        assertFailsWith<SessionResumeUnconfirmed> {
            reconcile(ready).close()
        }
        assertEquals(pinned.sha256, inspect().sha256)
        assertEquals(1L, ResumeFenceStorage.latest(store())!!.generation)
    }

    @Test fun intentOnlyCannotBeMistakenForProvenNotStarted() {
        val c1 = createPreExecutionCheckpoint()
        lateinit var pinned: VerifiedSessionCheckpoint
        lateinit var mac: String
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())
            .use { lease ->
                head(lease).use { journal ->
                    journal.append(EffectRecord.Intent(EffectIdentity("sess-one",
                        "effect-one", 1L, EffectKind.TRUSTED_WRITE, "a".repeat(64))))
                    pinned = SessionCheckpointStore.saveEffectHeadFenced(
                        lease, store(), context(), ledger(), journal,
                        advanced(c1), c1.sha256)
                    mac = journal.headPin().headMacSha256
                }
            }
        val ready = Ready(pinned, ResumeFenceStorage.atGeneration(store(), 1L), mac)
        assertEquals("RESUME_EFFECT_RECONCILIATION_CONTEXT_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                reconcile(ready).close()
            }.code)
        assertEquals(pinned.sha256, inspect().sha256)
        assertEquals(1L, ResumeFenceStorage.latest(store())!!.generation)
    }

    @Test fun noImplicitReconciliationViaLegacySaveOrAdmission() {
        val ready = pinnedGenesis()
        // A forged caller cannot publish a v3 marker through normal saves.
        val fabricated = CheckpointEffectReconciliation(
            ready.pinned.sha256, 1L, ready.fence.sha256,
            assertNotNull(ready.pinned.snapshot.effectJournalHead),
            "operator-one", "0".repeat(64))
        assertEquals("RESUME_UNSAFE_BOUNDARY",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(
                    store(), context(), ledger(), resumeAttestation(1L))
            }.code)
        // Normal checkpoint publication has no current lease and refuses,
        // even if the caller supplies a syntactically correct transition.
        assertFails {
            SessionCheckpointStore.save(store(), context(), ledger(),
                advanced(ready.pinned).copy(effectReconciliation = fabricated),
                ready.pinned.sha256)
        }
        assertEquals(ready.pinned.sha256, inspect().sha256)
    }

    @Test fun v3CrashBeforeFenceRetriesSameSealedTransitionWithoutDuplicate() {
        val ready = pinnedGenesis()
        val failure = assertFailsWith<SessionResumeUnconfirmed> {
            SessionResumeAdmission.reconcileGenesisWithFault(
                store(), context(), ledger(), path(), key,
                resumeAttestation(1L), approval(ready)) {
                error("simulated crash after durable v3 before successor fence")
            }.close()
        }
        assertEquals("RESUME_ADMISSION_UNCONFIRMED", failure.code)
        val durable = inspect()
        assertEquals(3L, durable.snapshot.sequence)
        assertNull(durable.snapshot.effectJournalHead)
        assertNotNull(durable.snapshot.effectReconciliation)
        assertEquals(1L, ResumeFenceStorage.latest(store())!!.generation)
        reconcile(ready).use { lease ->
            assertEquals(2L, lease.generation)
            assertEquals(durable.sha256, inspect().sha256)
        }
        assertEquals(3L, inspect().snapshot.sequence)
    }

    @Test fun forgedReconciliationMacWithValidCheckpointShaNeverAdmits() {
        val ready = pinnedGenesis()
        assertFailsWith<SessionResumeUnconfirmed> {
            SessionResumeAdmission.reconcileGenesisWithFault(
                store(), context(), ledger(), path(), key,
                resumeAttestation(1L), approval(ready)) {
                error("simulated crash after durable v3 before successor fence")
            }.close()
        }
        val c3 = inspect()
        val rec = assertNotNull(c3.snapshot.effectReconciliation)
        val payload = SessionCheckpointCodec.encode(c3.snapshot.copy(
            effectReconciliation = rec.copy(approvalMacSha256 = "f".repeat(64))))
        val frame = ByteBuffer.allocate(7 + 4 + 32 + payload.size)
            .put("RCCHK1\n".toByteArray(StandardCharsets.US_ASCII))
            .putInt(payload.size)
            .put(MessageDigest.getInstance("SHA-256").digest(payload))
            .put(payload).array()
        Files.write(store().resolve("000000000003.rcp"), frame,
            StandardOpenOption.TRUNCATE_EXISTING)
        assertEquals("RESUME_EFFECT_RECONCILIATION_MAC_MISMATCH",
            assertFailsWith<SessionResumeUnconfirmed> {
                reconcile(ready).close()
            }.code)
        assertEquals(1L, ResumeFenceStorage.latest(store())!!.generation)
    }

    @Test fun rawRehashWithForgeryCannotAuthorizeNormalResume() {
        val ready = pinnedGenesis()
        reconcile(ready).use { }
        val c3 = inspect()
        val record = assertNotNull(c3.snapshot.effectReconciliation)
        val payload = SessionCheckpointCodec.encode(c3.snapshot.copy(
            effectReconciliation = record.copy(approvalMacSha256 = "f".repeat(64))))
        val framed = ByteBuffer.allocate(7 + 4 + 32 + payload.size)
            .put("RCCHK1\n".toByteArray(StandardCharsets.US_ASCII))
            .putInt(payload.size)
            .put(MessageDigest.getInstance("SHA-256").digest(payload))
            .put(payload).array()
        Files.write(store().resolve("000000000003.rcp"), framed,
            StandardOpenOption.TRUNCATE_EXISTING)
        // The read-only store authenticates the chain structure, not the
        // operator HMAC: only the explicit host-key admission path does that.
        assertEquals("CHECKPOINT_RESUME_FENCE_CHECKPOINT_MISSING",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionResumeAdmission.admit(
                    store(), context(), ledger(), resumeAttestation(2L))
            }.code)
    }
}
