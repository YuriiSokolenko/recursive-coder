package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Physical authenticated effect records; no external effects or auto-recovery. */
@Tag("integration")
@OptIn(EffectJournalFaultInjection::class, ResumeOperatorOnlyApi::class)
class DurableEffectRecordJournalIntegrationTest : SessionCheckpointTestFixture() {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val id = EffectIdentity("sess-one", "effect-one", 1L,
        EffectKind.TRUSTED_WRITE, "a".repeat(64))

    private fun parent(): Path = temp.resolve("effect-private").also {
        if (!Files.exists(it)) {
            Files.createDirectories(it)
            Files.setPosixFilePermissions(it, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE))
        }
    }
    private fun path(): Path = parent().resolve("effects.rcfx")
    private fun intent() = EffectRecord.Intent(id)
    private fun started() = EffectRecord.Started(id)
    private fun committed() = EffectRecord.Committed(id, "b".repeat(64))
    private fun unknown() = EffectRecord.Unconfirmed(id, "CAS_OUTCOME_UNKNOWN")

    private fun withLease(block: (SessionResumeLease) -> Unit) {
        createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation())
            .use(block)
    }

    private fun open(lease: SessionResumeLease) =
        DurableEffectRecordJournal.create(path(), workspace(), id.sessionId, key, lease)

    private fun inspect() = DurableEffectRecordJournal.inspect(path(), workspace(), key)

    private fun denied(code: String, action: () -> Unit) {
        assertEquals(code, assertFailsWith<EffectJournalStorageUnconfirmed>(action).code)
    }

    @Test fun fsyncedIntentStartAndReceiptHaveRealMacsAndCanBeReplayedReadOnly() {
        withLease { lease ->
            open(lease).use { journal ->
                val first = journal.append(intent())
                assertEquals(1L, first.sequence)
                assertEquals(64, first.hostMacSha256.length)
                journal.append(started())
                journal.append(committed())
                denied("EFFECT_JOURNAL_BUSY") { inspect() }
            }
        }
        val verified = inspect()
        assertEquals(3L, verified.lastSequence)
        assertEquals(listOf(intent(), started(), committed()),
            verified.entries.map { it.record })
        assertEquals(verified.entries.last().hostMacSha256, verified.headMacSha256)
        assertTrue(verified.byteLength > 7L)
        assertEquals(64, verified.fileSha256.length)
        assertFalse(Files.isSymbolicLink(path()))
    }

    @Test fun unknownTerminalIsRetainedButNeverTreatedAsACommit() {
        withLease { lease ->
            open(lease).use { journal ->
                journal.append(intent())
                journal.append(started())
                journal.append(unknown())
                denied("EFFECT_JOURNAL_INVALID_ORDER") { journal.append(committed()) }
            }
        }
        assertEquals(listOf(intent(), started(), unknown()),
            inspect().entries.map { it.record })
    }

    @Test fun duplicateAndMissingStagesCannotReachDisk() {
        withLease { lease ->
            open(lease).use { journal ->
                denied("EFFECT_JOURNAL_INVALID_ORDER") { journal.append(started()) }
                journal.append(intent())
                denied("EFFECT_JOURNAL_INVALID_ORDER") { journal.append(intent()) }
                denied("EFFECT_JOURNAL_REUSED_OPERATION") {
                    journal.append(EffectRecord.Started(id.copy(kind = EffectKind.GIT_CAS)))
                }
                denied("EFFECT_JOURNAL_STALE_GENERATION") {
                    journal.append(EffectRecord.Started(id.copy(fenceGeneration = 2L)))
                }
                journal.append(started())
            }
        }
        assertEquals(listOf(intent(), started()), inspect().entries.map { it.record })
    }

    @Test fun wrongSecretAndChangedRecordBytesFailAuthentication() {
        withLease { lease ->
            open(lease).use { it.append(intent()) }
        }
        denied("EFFECT_JOURNAL_AUTH_FAILED") {
            DurableEffectRecordJournal.inspect(path(), workspace(), ByteArray(32) { 42 })
        }
        val bytes = Files.readAllBytes(path())
        // Frame begins after 7-byte magic; change a byte inside its canonical payload.
        val payloadIndex = 7 + 4 + 32 + 14
        bytes[payloadIndex] = (bytes[payloadIndex].toInt() xor 1).toByte()
        Files.write(path(), bytes, StandardOpenOption.TRUNCATE_EXISTING)
        denied("EFFECT_JOURNAL_AUTH_FAILED") { inspect() }
    }

    @Test fun tornTailIsNotRepairedOrAcceptedAsAValidPrefix() {
        withLease { lease ->
            open(lease).use { journal ->
                journal.append(intent())
                journal.append(started())
            }
        }
        val bytes = Files.readAllBytes(path())
        Files.write(path(), bytes.copyOf(bytes.size - 1),
            StandardOpenOption.TRUNCATE_EXISTING)
        denied("EFFECT_JOURNAL_TRUNCATED") { inspect() }
        assertEquals(bytes.size - 1L, Files.size(path()))
    }

    @Test fun postFsyncLostAcknowledgementPoisonsWriterWithoutDroppingDiskEvidence() {
        withLease { lease ->
            val journal = open(lease)
            denied("EFFECT_JOURNAL_APPEND_UNCONFIRMED") {
                journal.appendWithFault(intent()) { phase ->
                    if (phase == "forced-before-ack") error("simulated crash boundary")
                }
            }
            denied("EFFECT_JOURNAL_WRITER_UNAVAILABLE") {
                journal.append(started())
            }
            denied("EFFECT_JOURNAL_APPEND_UNCONFIRMED") { journal.close() }
        }
        // Disk record can exist even though append did NOT acknowledge it.
        // Inspection is evidence of bytes, not approval/replay authority.
        assertEquals(listOf(intent()), inspect().entries.map { it.record })
    }

    @Test fun closedOrRevokedLeaseCannotAcknowledgeNewEffectRecords() {
        withLease { lease ->
            val journal = open(lease)
            lease.close()
            assertFailsWith<SessionResumeUnconfirmed> { journal.append(intent()) }
            journal.close()
        }
        assertEquals(0L, inspect().lastSequence)
    }

    @Test fun privateDirectoryMissingLockAndNoOverwriteAreFailClosed() {
        withLease { lease ->
            val outside = temp.resolve("not-private")
            Files.createDirectories(outside)
            Files.setPosixFilePermissions(outside, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ))
            denied("EFFECT_JOURNAL_UNSAFE_PATH") {
                DurableEffectRecordJournal.create(outside.resolve("unsafe"), workspace(),
                    id.sessionId, key, lease).close()
            }
            open(lease).use { it.append(intent()) }
            denied("EFFECT_JOURNAL_EXISTS") { open(lease).close() }
        }
        val lockFile = path().resolveSibling("effects.rcfx.lock")
        Files.delete(lockFile)
        denied("EFFECT_JOURNAL_LOCK_UNAVAILABLE") { inspect() }
        assertFalse(Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS),
            "Read-only inspection must not recreate lock")
    }

    @Test fun removedOrReplacedJournalPathCannotBeAppendedThroughOldDescriptor() {
        withLease { lease ->
            val writer = open(lease)
            val original = path().resolveSibling("original.rcfx")
            Files.move(path(), original)
            Files.write(path(), byteArrayOf(1, 2, 3), StandardOpenOption.CREATE_NEW)
            denied("EFFECT_JOURNAL_PATH_CHANGED") { writer.append(intent()) }
            writer.close()
        }
        denied("EFFECT_JOURNAL_VERSION_INVALID") { inspect() }
    }
}
