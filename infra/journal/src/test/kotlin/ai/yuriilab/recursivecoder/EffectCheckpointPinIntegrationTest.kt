package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real filesystem/checkpoint/fence regressions. No external effect execution. */
@Tag("integration")
@OptIn(ResumeOperatorOnlyApi::class, LedgerTestFaultInjection::class)
class EffectCheckpointPinIntegrationTest : SessionCheckpointTestFixture() {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private fun parent(): Path = temp.resolve("effect-private").also {
        if (!Files.exists(it)) {
            Files.createDirectories(it)
            Files.setPosixFilePermissions(it, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE))
        }
    }
    private fun path(): Path = parent().resolve("effects.rcfx")
    private val identity = EffectIdentity("sess-one", "effect-one", 1L,
        EffectKind.TRUSTED_WRITE, "a".repeat(64))

    private fun open(lease: SessionResumeLease): DurableEffectRecordJournal =
        DurableEffectRecordJournal.create(path(), workspace(),
            identity.sessionId, key, lease)

    private fun admit(): SessionResumeLease =
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())

    private fun advanced(source: VerifiedSessionCheckpoint): SessionCheckpoint =
        source.snapshot.copy(sequence = source.snapshot.sequence + 1L,
            previousSha256 = source.sha256, effectJournalHead = null)

    private fun savePin(lease: SessionResumeLease, journal: DurableEffectRecordJournal,
                        previous: VerifiedSessionCheckpoint): VerifiedSessionCheckpoint =
        SessionCheckpointStore.saveEffectHeadFenced(
            lease, store(), context(), ledger(), journal,
            advanced(previous), previous.sha256)

    @Test fun v1BytesAreUnchangedAndV2GenesisAndStartedPinAreDurable() {
        val first = createPreExecutionCheckpoint()
        val original = SessionCheckpointCodec.encode(first.snapshot)
        assertTrue(original.toString(StandardCharsets.UTF_8).contains("\"version\":1"))
        assertFalse(original.toString(StandardCharsets.UTF_8).contains("effectJournalHead"))
        lateinit var finalPinned: VerifiedSessionCheckpoint
        admit().use { lease ->
            open(lease).use { journal ->
                val genesis = journal.headPin()
                assertEquals(0L, genesis.lastSequence)
                assertEquals(64, genesis.headMacSha256.length)

                val pinnedGenesis = savePin(lease, journal, first)
                val genesisHead = assertNotNull(pinnedGenesis.snapshot.effectJournalHead)
                assertEquals(identity.sessionId, genesisHead.sessionId)
                assertEquals(lease.generation, genesisHead.fenceGeneration)
                assertEquals(lease.admittedCheckpointSha256,
                    genesisHead.admittedCheckpointSha256)
                assertEquals(lease.toolAuthoritySha256, genesisHead.toolAuthoritySha256)
                assertEquals(genesis, genesisHead.pin())
                assertTrue(pinnedGenesis.reconciliationRequired)
                assertTrue(SessionCheckpointCodec.encode(pinnedGenesis.snapshot)
                    .toString(StandardCharsets.UTF_8).contains("\"version\":2"))
                assertEquals(pinnedGenesis.snapshot,
                    SessionCheckpointCodec.decode(
                        SessionCheckpointCodec.encode(pinnedGenesis.snapshot)))

                journal.append(EffectRecord.Intent(identity))
                val started = journal.append(EffectRecord.Started(identity))
                finalPinned = savePin(lease, journal, pinnedGenesis)
                assertEquals(started.sequence,
                    finalPinned.snapshot.effectJournalHead!!.lastSequence)
                assertEquals(started.hostMacSha256,
                    finalPinned.snapshot.effectJournalHead!!.headMacSha256)
                assertEquals(journal.headPin(),
                    finalPinned.snapshot.effectJournalHead!!.pin())
                assertEquals(finalPinned.sha256, inspect().sha256)
            }
        }
        assertEquals(finalPinned.sha256, inspect().sha256)
        assertEquals(2L, SessionCheckpointStore.inspectEffectHeadBound(
            store(), context(), ledger(), path(), key).lastSequence)
        val refusal = assertFailsWith<SessionResumeUnconfirmed> {
            SessionResumeAdmission.admit(
                store(), context(), ledger(), resumeAttestation(1))
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY", refusal.code)
        assertFalse(Files.list(store()).use { entries ->
            entries.anyMatch { it.fileName.toString().startsWith("effect-head-") }
        })
    }

    @Test fun legacyFencedSaveCannotPublishCallerSuppliedPinOrDropExistingPin() {
        val first = createPreExecutionCheckpoint()
        admit().use { lease ->
            open(lease).use { journal ->
                val fabricated = advanced(first).copy(effectJournalHead =
                    CheckpointEffectJournalHead(identity.sessionId, lease.generation,
                        lease.admittedCheckpointSha256, lease.toolAuthoritySha256,
                        "f".repeat(64), 0L, "a".repeat(64)))
                assertEquals("CHECKPOINT_EFFECT_HEAD_UNVERIFIED",
                    assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointStore.saveFenced(lease, store(), context(),
                            ledger(), fabricated, first.sha256)
                    }.code)
                assertEquals(first.sha256, inspect().sha256)
                val pinned = savePin(lease, journal, first)
                assertEquals("CHECKPOINT_EFFECT_HEAD_ROLLBACK",
                    assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointStore.saveFenced(lease, store(), context(),
                            ledger(), advanced(pinned), pinned.sha256)
                    }.code)
                assertEquals(pinned.sha256, inspect().sha256)
            }
        }
    }

    @Test fun completeEffectSuffixRollbackAndWrongSidecarPathAreRejected() {
        val first = createPreExecutionCheckpoint()
        admit().use { lease ->
            open(lease).use { journal ->
                journal.append(EffectRecord.Intent(identity))
                journal.append(EffectRecord.Started(identity))
                savePin(lease, journal, first)
            }
        }
        val different = path().resolveSibling("other.rcfx")
        assertEquals("CHECKPOINT_EFFECT_HEAD_PATH_MISMATCH",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.inspectEffectHeadBound(
                    store(), context(), ledger(), different, key)
            }.code)
        val raw = Files.readAllBytes(path())
        var offset = 7
        // Strip the complete final Started record, keeping valid genesis+Intent.
        repeat(2) {
            val len = ByteBuffer.wrap(raw, offset, 4).int
            offset += 36 + len
        }
        Files.write(path(), raw.copyOf(offset), StandardOpenOption.TRUNCATE_EXISTING)
        assertEquals("EFFECT_JOURNAL_PIN_MISMATCH",
            assertFailsWith<EffectJournalStorageUnconfirmed> {
                SessionCheckpointStore.inspectEffectHeadBound(
                    store(), context(), ledger(), path(), key)
            }.code)
    }

    @Test fun pinCannotBePublishedFromClosedJournalAndNoGenerationIsCreated() {
        val first = createPreExecutionCheckpoint()
        admit().use { lease ->
            val journal = open(lease)
            val genesis = journal.headPin()
            assertEquals(0L, genesis.lastSequence)
            journal.close()
            assertEquals("EFFECT_JOURNAL_WRITER_UNAVAILABLE",
                assertFailsWith<EffectJournalStorageUnconfirmed> {
                    journal.headPin()
                }.code)
            assertEquals("EFFECT_JOURNAL_WRITER_UNAVAILABLE",
                assertFailsWith<EffectJournalStorageUnconfirmed> {
                    savePin(lease, journal, first)
                }.code)
            assertEquals(first.sha256, inspect().sha256)
            assertFalse(Files.exists(store().resolve("000000000002.rcp")))
        }
    }

    @Test fun uncertainCheckpointPinPublicationQuarantinesWriter() {
        val first = createPreExecutionCheckpoint()
        admit().use { lease ->
            val journal = open(lease)
            journal.append(EffectRecord.Intent(identity))
            val failure = assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointStore.saveEffectHeadFencedWithFault(
                    lease, store(), context(), ledger(), journal,
                    advanced(first), first.sha256) { phase ->
                    if (phase == "temp-synced")
                        error("simulated lost checkpoint pin acknowledgement")
                }
            }
            assertEquals("CHECKPOINT_WRITE_UNCONFIRMED", failure.code)
            assertEquals("EFFECT_JOURNAL_WRITER_UNAVAILABLE",
                assertFailsWith<EffectJournalStorageUnconfirmed> {
                    journal.append(EffectRecord.Started(identity))
                }.code)
            assertEquals("EFFECT_JOURNAL_APPEND_UNCONFIRMED",
                assertFailsWith<EffectJournalStorageUnconfirmed> {
                    journal.close()
                }.code)
            assertEquals(first.sha256, inspect().sha256)
            assertFalse(Files.exists(store().resolve("000000000002.rcp")))
        }
    }

    @Test fun malformedV2CannotMasqueradeAsLegacyV1Checkpoint() {
        val first = createPreExecutionCheckpoint()
        admit().use { lease ->
            open(lease).use { journal ->
                val saved = savePin(lease, journal, first)
                val good = SessionCheckpointCodec.encode(saved.snapshot)
                val raw = good.toString(StandardCharsets.UTF_8)
                val downgrade = raw.replace("\"version\":2", "\"version\":1")
                assertEquals("CHECKPOINT_PAYLOAD_INVALID",
                    assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointCodec.decode(
                            downgrade.toByteArray(StandardCharsets.UTF_8))
                    }.code)
                val missing = raw.replace(
                    Regex(",\"effectJournalHead\":\\{.*\\}$"), "}")
                assertEquals("CHECKPOINT_PAYLOAD_INVALID",
                    assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointCodec.decode(
                            missing.toByteArray(StandardCharsets.UTF_8))
                    }.code)
            }
        }
    }
}
