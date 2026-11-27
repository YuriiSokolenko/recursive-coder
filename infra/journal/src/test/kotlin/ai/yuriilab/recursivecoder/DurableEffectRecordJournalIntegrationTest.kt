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

    private fun expectation(): EffectJournalExpectation =
        ResumeFenceStorage.atGeneration(store(), 1L).let {
            EffectJournalExpectation(it.sessionId, it.generation,
                it.checkpointSha256, it.toolAuthoritySha256)
        }

    private fun inspectEffects() =
        DurableEffectRecordJournal.inspect(path(), workspace(), key, expectation())

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

    @Test fun admittedSessionIdRequiresLiveLeasePublicationMonitor() {
        withLease { lease ->
            assertEquals("RESUME_FENCE_MONITOR_REQUIRED",
                assertFailsWith<SessionResumeUnconfirmed> {
                    lease.admittedSessionId()
                }.code)
            assertEquals(id.sessionId, lease.publishIfCurrent {
                lease.admittedSessionId()
            })
        }
    }

    @Test fun fsyncedIntentStartAndReceiptHaveRealMacsAndCanBeReplayedReadOnly() {
        withLease { lease ->
            open(lease).use { journal ->
                assertEquals(setOf(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path(), LinkOption.NOFOLLOW_LINKS))
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

        // Independent on-disk framing and exact JSON payload order.
        val bytes = ByteBuffer.wrap(Files.readAllBytes(path()))
        val magic = ByteArray(7)
        bytes.get(magic)
        assertContentEquals("RCEFX1\n".toByteArray(StandardCharsets.US_ASCII), magic)
        fun nextPayload(): String {
            val length = bytes.int
            val mac = ByteArray(32)
            bytes.get(mac)
            val payload = ByteArray(length)
            bytes.get(payload)
            return payload.toString(StandardCharsets.UTF_8)
        }
        val expected = expectation()
        val checkpoint = expected.admittedCheckpointSha256
        val authority = expected.toolAuthoritySha256
        assertEquals("""{"type":"genesis","sessionId":"sess-one","fenceGeneration":1,""" +
            """"admittedCheckpointSha256":"$checkpoint","toolAuthoritySha256":"$authority"}""",
            nextPayload())
        val input = "a".repeat(64)
        val observed = "b".repeat(64)
        fun prefix(sequence: Int) =
            """{"sequence":$sequence,"sessionId":"sess-one","operationId":"effect-one",""" +
            """"fenceGeneration":1,"kind":"TRUSTED_WRITE","inputSha256":"$input","""
        assertEquals(prefix(1) + """"state":"intent"}""", nextPayload())
        assertEquals(prefix(2) + """"state":"started"}""", nextPayload())
        assertEquals(prefix(3) + """"state":"committed","observedStateSha256":"$observed"}""",
            nextPayload())
        assertFalse(bytes.hasRemaining())
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
                    path(), workspace(), key,
                    expectation().copy(sessionId = "another-session"))
            }
            denied("EFFECT_JOURNAL_CONTEXT_MISMATCH") {
                DurableEffectRecordJournal.inspect(
                    path(), workspace(), key,
                    expectation().copy(fenceGeneration = 2L))
            }
            denied("EFFECT_JOURNAL_CONTEXT_MISMATCH") {
                DurableEffectRecordJournal.inspect(path(), workspace(), key,
                    expectation().copy(admittedCheckpointSha256 = "0".repeat(64)))
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

    @Test fun invalidPhysicalSizeAndGenesisFailClosedBeforeRecordReplay() {
        withLease { lease -> open(lease).use { it.append(intent()) } }
        val original = Files.readAllBytes(path())
        Files.write(path(), ByteArray(0), StandardOpenOption.TRUNCATE_EXISTING)
        denied("EFFECT_JOURNAL_SIZE_INVALID") { inspectEffects() }
        denied("EFFECT_JOURNAL_SIZE_INVALID") { inspectDiagnostic() }
        Files.write(path(), original.copyOf(7), StandardOpenOption.TRUNCATE_EXISTING)
        denied("EFFECT_JOURNAL_GENESIS_INVALID") { inspectEffects() }
        denied("EFFECT_JOURNAL_GENESIS_INVALID") { inspectDiagnostic() }
        val invalidLength = original.copyOf()
        ByteBuffer.wrap(invalidLength, 7, 4).putInt(0)
        Files.write(path(), invalidLength, StandardOpenOption.TRUNCATE_EXISTING)
        denied("EFFECT_JOURNAL_GENESIS_INVALID") { inspectEffects() }
        denied("EFFECT_JOURNAL_GENESIS_INVALID") { inspectDiagnostic() }
        Files.write(path(), original, StandardOpenOption.TRUNCATE_EXISTING)
        assertEquals(1L, inspectEffects().lastSequence)
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
                path(), workspace(), ByteArray(32) { 42 }, expectation())
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
        assertEquals(EffectJournalTailKind.SHORT_FRAME, diagnostic.tailKind)
        assertEquals(diagnostic.tornTailOffset, diagnostic.verifiedByteLength)
        assertEquals(bytes.size - 1L, diagnostic.byteLength)
        assertTrue(diagnostic.verifiedByteLength < diagnostic.byteLength)
        assertEquals(bytes.size - 1L, Files.size(path()))
    }


    @Test fun externallyTrustedHeadDetectsCompleteSuffixRollback() {
        lateinit var trusted: EffectJournalHeadPin
        withLease { lease ->
            open(lease).use { journal ->
                journal.append(intent())
                val receipt = journal.append(started())
                trusted = EffectJournalHeadPin(receipt.sequence, receipt.hostMacSha256)
            }
        }
        assertEquals(2L, DurableEffectRecordJournal.inspectPinnedForDiagnostics(
            path(), workspace(), key, expectation(), trusted).lastSequence)
        denied("EFFECT_JOURNAL_PIN_MISMATCH") {
            DurableEffectRecordJournal.inspectPinnedForDiagnostics(path(), workspace(), key,
                expectation(), trusted.copy(headMacSha256 = "0".repeat(64)))
        }
        denied("EFFECT_JOURNAL_PIN_INVALID") {
            DurableEffectRecordJournal.inspectPinnedForDiagnostics(path(), workspace(), key,
                expectation(), trusted.copy(lastSequence = -1L))
        }
        val raw = Files.readAllBytes(path())
        var offset = 7
        // Keep genesis and complete intent, discard the complete Started frame.
        repeat(2) {
            val size = ByteBuffer.wrap(raw, offset, 4).int
            offset += 36 + size
        }
        Files.write(path(), raw.copyOf(offset), StandardOpenOption.TRUNCATE_EXISTING)
        assertEquals(1L, inspectEffects().lastSequence)
        denied("EFFECT_JOURNAL_PIN_MISMATCH") {
            DurableEffectRecordJournal.inspectPinnedForDiagnostics(
                path(), workspace(), key, expectation(), trusted)
        }
        // This fixture checks physical equality to a test-owned pin only.
        // Production authority instead comes from SessionCheckpointStore,
        // and its checkpoint root still lacks independent rollback resistance.
    }

    @Test fun attackerSelfPinMatchesValidSuffixAndRolledBackGenesisButIsOnlyDiagnostic() {
        lateinit var genesis: ByteArray
        lateinit var genesisPin: EffectJournalHeadPin
        withLease { lease ->
            open(lease).use { journal ->
                genesis = Files.readAllBytes(path())
                genesisPin = journal.headPin()
                journal.append(intent())
            }
        }
        // The writer owns an exclusive lock. An attacker can only inspect
        // the complete suffix after the writer closes, not during append.
        val suffix = inspectEffects()
        val selfPin = EffectJournalHeadPin(suffix.lastSequence, suffix.headMacSha256)
        // The caller can pin its own observed suffix. A successful physical
        // check says nothing about checkpoint authority or external effects.
        val selfPinned = DurableEffectRecordJournal.inspectPinnedForDiagnostics(
            path(), workspace(), key, expectation(), selfPin)
        assertEquals(1L, selfPinned.lastSequence)
        assertEquals(selfPin.headMacSha256, selfPinned.headMacSha256)
        denied("EFFECT_JOURNAL_PIN_MISMATCH") {
            DurableEffectRecordJournal.inspectPinnedForDiagnostics(
                path(), workspace(), key, expectation(), genesisPin)
        }

        // Coherent, valid-MAC suffix rollback reproduces the pre-existing
        // indistinguishability gap: the old self-pin matches perfectly.
        Files.write(path(), genesis, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)
        val rolledBack = DurableEffectRecordJournal.inspectPinnedForDiagnostics(
            path(), workspace(), key, expectation(), genesisPin)
        assertEquals(0L, rolledBack.lastSequence)
        assertEquals(genesisPin.headMacSha256, rolledBack.headMacSha256)
        denied("EFFECT_JOURNAL_PIN_MISMATCH") {
            DurableEffectRecordJournal.inspectPinnedForDiagnostics(
                path(), workspace(), key, expectation(), selfPin)
        }
        // Neither diagnostic match may be used as no-start, commit or replay proof.
    }

    @Test fun validUnpinnedExtraFrameMustNotBePromotedToCommittedHead() {
        lateinit var prior: EffectJournalHeadPin
        withLease { lease ->
            open(lease).use { journal ->
                val first = journal.append(intent())
                prior = EffectJournalHeadPin(first.sequence, first.hostMacSha256)
                journal.append(started())
            }
        }
        assertEquals(2L, inspectEffects().lastSequence)
        denied("EFFECT_JOURNAL_PIN_MISMATCH") {
            DurableEffectRecordJournal.inspectPinnedForDiagnostics(
                path(), workspace(), key, expectation(), prior)
        }
    }

    @Test fun effectJournalDoesNotBrickCheckpointInspectionFencedSaveOrNextAdmission() {
        val first = createPreExecutionCheckpoint()
        SessionResumeAdmission.admit(store(), context(), ledger(), resumeAttestation()).use {
            lease ->
            open(lease).use { journal ->
                journal.append(intent())
                journal.append(started())
            }
            assertEquals(first.sha256, inspect().sha256)
            val next = SessionCheckpointStore.saveFenced(
                lease, store(), context(), ledger(),
                first.snapshot.copy(sequence = 2L, previousSha256 = first.sha256),
                first.sha256)
            assertEquals(2L, next.snapshot.sequence)
            assertEquals(next.sha256, inspect().sha256)
        }
        SessionResumeAdmission.admit(
            store(), context(), ledger(), resumeAttestation(1L)).use {
            assertEquals(2L, it.generation)
            it.assertCurrent()
        }
        val names = Files.list(store()).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        assertTrue(names.none { it.startsWith("effect-head-") ||
            it.startsWith(".effect-anchor-") })
        assertEquals(listOf(intent(), started()), inspectEffects().entries.map { it.record })
    }

    @Test fun lengthWrittenWithZeroedBodyIsNotAZeroFilledDiagnosticTail() {
        withLease { lease -> open(lease).use { it.append(intent()) } }
        // Length was durably written; only the MAC/body are zero. Do NOT
        // treat this as an all-zero frame and silently accept it as a crash.
        val partial = ByteBuffer.allocate(4 + 32 + 32).putInt(32).array()
        Files.write(path(), partial, StandardOpenOption.APPEND)
        denied("EFFECT_JOURNAL_AUTH_FAILED") { inspectEffects() }
        denied("EFFECT_JOURNAL_AUTH_FAILED") { inspectDiagnostic() }
    }

    @Test fun zeroFilledExtendedTailIsDiagnosticOnlyAndKeepsVerifiedPrefix() {
        withLease { lease -> open(lease).use { it.append(intent()) } }
        val before = Files.readAllBytes(path())
        val extra = ByteArray(512)
        Files.write(path(), extra, StandardOpenOption.APPEND)
        // Strict verification must NOT silently accept extra zeroed bytes.
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectEffects() }
        val result = inspectDiagnostic()
        assertEquals(listOf(intent()), result.entries.map { it.record })
        assertEquals(1L, result.lastSequence)
        assertEquals(EffectJournalTailKind.ZERO_FILLED, result.tailKind)
        assertEquals(before.size.toLong(), result.verifiedByteLength)
        assertEquals(before.size.toLong(), result.tornTailOffset)
        assertEquals(before.size + extra.size.toLong(), result.byteLength)
        assertContentEquals(before + extra, Files.readAllBytes(path()))
    }

    @Test fun malformedExpectedContextFailsBeforeReadingDisk() {
        withLease { lease -> open(lease).use { } }
        denied("EFFECT_JOURNAL_EXPECTATION_INVALID") {
            DurableEffectRecordJournal.inspect(path(), workspace(), key,
                expectation().copy(toolAuthoritySha256 = "not-a-digest"))
        }
        denied("EFFECT_JOURNAL_CONTEXT_MISMATCH") {
            DurableEffectRecordJournal.inspect(path(), workspace(), key,
                expectation().copy(toolAuthoritySha256 = "a".repeat(64)))
        }
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

    @Test fun authenticatedUnknownPhysicalRecordStateIsRejected() {
        withLease { lease -> open(lease).use { } }
        val payload = forgedIntent().toString(StandardCharsets.UTF_8)
            .replace("\"state\":\"intent\"", "\"state\":\"future-state\"")
        assertTrue(payload.contains("future-state"))
        appendForgedFirstEffect(payload.toByteArray(StandardCharsets.UTF_8))
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectEffects() }
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectDiagnostic() }
    }

    @Test fun authenticatedUnknownPhysicalEffectKindIsRejected() {
        withLease { lease -> open(lease).use { } }
        val payload = forgedIntent().toString(StandardCharsets.UTF_8)
            .replace("\"kind\":\"TRUSTED_WRITE\"", "\"kind\":\"FUTURE_KIND\"")
        assertTrue(payload.contains("FUTURE_KIND"))
        appendForgedFirstEffect(payload.toByteArray(StandardCharsets.UTF_8))
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectEffects() }
        denied("EFFECT_JOURNAL_RECORD_CORRUPT") { inspectDiagnostic() }
    }
}
