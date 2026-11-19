package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    private fun inspectEffects() =
        DurableEffectRecordJournal.inspect(path(), workspace(), key, id.sessionId)

    private fun inspectDiagnostic() =
        DurableEffectRecordJournal.inspectForDiagnostics(
            path(), workspace(), key, id.sessionId)

    /** Test-only frame forgery using the real derived subkey and genesis MAC. */
    private fun appendForgedFirstEffect(body: ByteArray) {
        val fileBytes = Files.readAllBytes(path())
        val headerMac = fileBytes.copyOfRange(7 + 4, 7 + 36)
        val kdf = Mac.getInstance("HmacSHA256")
        kdf.init(SecretKeySpec(key, "HmacSHA256"))
        val subkey = kdf.doFinal("recursive-coder/effect-record-journal/v1"
            .toByteArray(StandardCharsets.US_ASCII))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(subkey, "HmacSHA256"))
        mac.update("RCEFX1\n".toByteArray(StandardCharsets.US_ASCII))
        mac.update(path().toAbsolutePath().normalize().toString()
            .toByteArray(StandardCharsets.UTF_8))
        mac.update(0.toByte())
        mac.update(headerMac)
        val auth = mac.doFinal(body)
        val frame = ByteBuffer.allocate(36 + body.size)
            .putInt(body.size).put(auth).put(body).array()
        Files.write(path(), frame, StandardOpenOption.APPEND)
    }

    private fun forgedIntent(
        sessionId: String = id.sessionId,
        generation: Long = id.fenceGeneration,
        trailingWhitespace: String = ""
    ): ByteArray = (buildJsonObject {
        put("sequence", 1)
        put("sessionId", sessionId)
        put("operationId", id.operationId)
        put("fenceGeneration", generation)
        put("kind", id.kind.name)
        put("inputSha256", id.inputSha256)
        put("state", "intent")
    }.toString() + trailingWhitespace).toByteArray(StandardCharsets.UTF_8)

    private fun denied(code: String, action: () -> Unit) {
        assertEquals(code, assertFailsWith<EffectJournalStorageUnconfirmed>(block = action).code)
    }

    @Test fun fsyncedIntentStartAndReceiptHaveRealMacsAndCanBeReplayedReadOnly() {
        withLease { lease ->
            open(lease).use { journal ->
                val first = journal.append(intent())
                assertEquals(1L, first.sequence)
                assertEquals(64, first.hostMacSha256.length)
                journal.append(started())
                journal.append(committed())
                denied("EFFECT_JOURNAL_BUSY") { inspectEffects() }
            }
        }
        val verified = inspectEffects()
        assertEquals(3L, verified.lastSequence)
        assertEquals(1L, verified.header.fenceGeneration)
        assertEquals("sess-one", verified.header.sessionId)
        assertNull(verified.tornTailOffset)
        assertEquals(verified.byteLength, verified.verifiedByteLength)
        assertEquals(listOf(intent(), started(), committed()),
            verified.entries.map { it.record })
        assertEquals(verified.entries.last().hostMacSha256, verified.headMacSha256)
        assertTrue(verified.byteLength > 7L)
        assertEquals(64, verified.fileSha256.length)
        assertFalse(Files.isSymbolicLink(path()))
    }


    @Test fun emptyJournalGenesisAuthenticatesLeaseIdentityAndExpectedContext() {
        withLease { lease ->
            open(lease).use { }
            val verified = inspectEffects()
            assertEquals(0L, verified.lastSequence)
            assertEquals(id.sessionId, verified.header.sessionId)
            assertEquals(lease.generation, verified.header.fenceGeneration)
            assertEquals(lease.admittedCheckpointSha256,
                verified.header.admittedCheckpointSha256)
            assertEquals(lease.toolAuthoritySha256, verified.header.toolAuthoritySha256)
            assertEquals(64, verified.headMacSha256.length)
            denied("EFFECT_JOURNAL_CONTEXT_MISMATCH") {
                DurableEffectRecordJournal.inspect(
                    path(), workspace(), key, "another-session")
            }
            denied("EFFECT_JOURNAL_CONTEXT_MISMATCH") {
                DurableEffectRecordJournal.inspect(
                    path(), workspace(), key, id.sessionId, expectedFenceGeneration = 2L)
            }
            denied("EFFECT_JOURNAL_CONTEXT_MISMATCH") {
                DurableEffectRecordJournal.inspect(path(), workspace(), key,
                    id.sessionId, expectedCheckpointSha256 = "0".repeat(64))
            }
            denied("EFFECT_JOURNAL_CONTEXT_MISMATCH") {
                DurableEffectRecordJournal.create(path().resolveSibling("other.rcfx"),
                    workspace(), "wrong-session", key, lease).close()
            }
        }
    }

    @Test fun forgedMacAuthenticButNoncanonicalPayloadIsRejected() {
        withLease { lease -> open(lease).use { } }
        appendForgedFirstEffect(forgedIntent(trailingWhitespace = " "))
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectEffects() }
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectDiagnostic() }
    }

    @Test fun validMacCannotSmuggleOtherSessionOrIssuingGeneration() {
        withLease { lease -> open(lease).use { } }
        appendForgedFirstEffect(forgedIntent(sessionId = "other-session"))
        denied("EFFECT_JOURNAL_INVALID_RECORD") { inspectEffects() }

        val raw = Files.readAllBytes(path())
        val genesisLength = ByteBuffer.wrap(raw, 7, 4).int
        Files.write(path(), raw.copyOfRange(0, 7 + 36 + genesisLength),
            StandardOpenOption.TRUNCATE_EXISTING)
        appendForgedFirstEffect(forgedIntent(generation = 2L))
        denied("EFFECT_JOURNAL_INVALID_RECORD") { inspectEffects() }
    }

    @Test fun invalidFrameLengthRefusesWithoutTreatingItAsAuthenticatedTail() {
        withLease { lease -> open(lease).use { it.append(intent()) } }
        val bytes = Files.readAllBytes(path())
        val genesisLength = ByteBuffer.wrap(bytes, 7, 4).int
        val effectOffset = 7 + 36 + genesisLength
        ByteBuffer.wrap(bytes, effectOffset, 4).putInt(5000)
        Files.write(path(), bytes, StandardOpenOption.TRUNCATE_EXISTING)
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectEffects() }
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectDiagnostic() }
    }

    @Test fun uncertainMayFollowIntentDirectlyWithoutStarted() {
        withLease { lease ->
            open(lease).use { journal ->
                journal.append(intent())
                journal.append(unknown())
                denied("EFFECT_JOURNAL_INVALID_ORDER") { journal.append(started()) }
            }
        }
        assertEquals(listOf(intent(), unknown()), inspectEffects().entries.map { it.record })
    }

    @Test fun symlinkedLockAndUnsafeWorkspaceRelationshipsRefuse() {
        withLease { lease ->
            val inside = workspace().resolve("private-effects")
            Files.createDirectories(inside)
            Files.setPosixFilePermissions(inside, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE))
            denied("EFFECT_JOURNAL_UNSAFE_PATH") {
                DurableEffectRecordJournal.create(inside.resolve("effects.rcfx"),
                    workspace(), id.sessionId, key, lease).close()
            }
            val nestedWorkspace = parent().resolve("nested-workspace")
            Files.createDirectories(nestedWorkspace)
            denied("EFFECT_JOURNAL_UNSAFE_PATH") {
                DurableEffectRecordJournal.create(path(), nestedWorkspace,
                    id.sessionId, key, lease).close()
            }
            open(lease).use { }
        }
        val lock = path().resolveSibling("effects.rcfx.lock")
        Files.delete(lock)
        val target = parent().resolve("symlink-target")
        Files.writeString(target, "do-not-open")
        Files.createSymbolicLink(lock, target)
        denied("EFFECT_JOURNAL_UNSAFE_LOCK") { inspectEffects() }
        assertEquals("do-not-open", Files.readString(target))
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
            inspectEffects().entries.map { it.record })
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
        assertEquals(listOf(intent(), started()), inspectEffects().entries.map { it.record })
    }

    @Test fun wrongSecretAndChangedRecordBytesFailAuthentication() {
        withLease { lease ->
            open(lease).use { it.append(intent()) }
        }
        denied("EFFECT_JOURNAL_AUTH_FAILED") {
            DurableEffectRecordJournal.inspect(
                path(), workspace(), ByteArray(32) { 42 }, id.sessionId)
        }
        val bytes = Files.readAllBytes(path())
        // Derive effect-frame offset from authenticated genesis length.
        val genesisLength = ByteBuffer.wrap(bytes, 7, 4).int
        val payloadIndex = 7 + 36 + genesisLength + 36 + 14
        bytes[payloadIndex] = (bytes[payloadIndex].toInt() xor 1).toByte()
        Files.write(path(), bytes, StandardOpenOption.TRUNCATE_EXISTING)
        denied("EFFECT_JOURNAL_AUTH_FAILED") { inspectEffects() }
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
        denied("EFFECT_JOURNAL_TRUNCATED") { inspectEffects() }
        val diagnostic = inspectDiagnostic()
        assertEquals(listOf(intent()), diagnostic.entries.map { it.record })
        assertEquals(1L, diagnostic.lastSequence)
        assertNotNull(diagnostic.tornTailOffset)
        assertEquals(diagnostic.tornTailOffset, diagnostic.verifiedByteLength)
        assertEquals(bytes.size - 1L, diagnostic.byteLength)
        assertTrue(diagnostic.verifiedByteLength < diagnostic.byteLength)
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
        assertEquals(listOf(intent()), inspectEffects().entries.map { it.record })
    }

    @Test fun closedOrRevokedLeaseCannotAcknowledgeNewEffectRecords() {
        withLease { lease ->
            val journal = open(lease)
            lease.close()
            assertFailsWith<SessionResumeUnconfirmed> { journal.append(intent()) }
            journal.close()
        }
        assertEquals(0L, inspectEffects().lastSequence)
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
        denied("EFFECT_JOURNAL_LOCK_UNAVAILABLE") { inspectEffects() }
        assertFalse(Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS),
            "Read-only inspection must not recreate lock")
    }

    @Test fun removedOrReplacedJournalPathCannotBeAppendedThroughOldDescriptor() {
        withLease { lease ->
            val writer = open(lease)
            val original = path().resolveSibling("original.rcfx")
            Files.move(path(), original)
            Files.write(path(), byteArrayOf(1, 2, 3, 4, 5, 6, 7), StandardOpenOption.CREATE_NEW)
            Files.setPosixFilePermissions(path(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            denied("EFFECT_JOURNAL_PATH_CHANGED") { writer.append(intent()) }
            writer.close()
        }
        denied("EFFECT_JOURNAL_VERSION_INVALID") { inspectEffects() }
    }
}
