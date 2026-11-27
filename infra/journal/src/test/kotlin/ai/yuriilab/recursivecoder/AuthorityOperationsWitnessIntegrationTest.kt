package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@Tag("integration")
class AuthorityOperationsWitnessIntegrationTest {
    @TempDir
    lateinit var directory: Path

    private fun hash(c: Char) = c.toString().repeat(64)
    private val register = EffectAuthorityRegister("host", "session", "register", hash('a'))
    private val oldKey = ByteArray(32) { 9 }
    private val newKey = ByteArray(32) { 10 }
    private val oldIds = AuthorityCredentialIds(hash('1'), hash('2'), hash('3'), hash('4'))
    private val newIds = AuthorityCredentialIds(hash('5'), hash('6'), hash('7'), hash('8'))
    private val genesis = EffectAuthorityRoot(1, null, EffectAuthorityEvidence(
        register, 1, hash('b'), hash('c'), hash('d'), 0, hash('e'), null, EffectAuthorityState.GENESIS
    ))
    private val nextRoot = EffectAuthorityRoot(2, EffectAuthorityPolicy.digest(genesis),
        genesis.evidence.copy(sequence = 2, checkpointSha256 = hash('9'), headMacSha256 = hash('0'),
            operation = EffectIdentity("session", "op", 1, EffectKind.TRUSTED_WRITE, hash('f')),
            state = EffectAuthorityState.START_BARRIER_COMMITTED))

    private val marker = AuthorityWire.pack(oldKey, AuthorityWire.PROVISION,
        AuthorityWire.identity(register)) + byteArrayOf(10)
    private fun append(bytes: ByteArray, root: EffectAuthorityRoot, key: ByteArray) =
        bytes + AuthorityWire.pack(key, AuthorityWire.RECORD, checkNotNull(AuthorityWire.encode(root))) +
            byteArrayOf(10)
    private fun image(vararg segments: ByteArray) = AuthorityHistoryImage(marker,
        segments.map { AuthoritySegmentImage(it, it.copyOf()) })
    private fun witness(): AuthorityOperationsWitness {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        return AuthorityOperationsWitness(register, Files.createFile(directory.resolve("witness")),
            Files.createFile(directory.resolve("mutex")), pair.private, pair.public, requireAppendOnly = false)
    }

    private fun advance(w: AuthorityOperationsWitness, previous: AuthorityOperationsRecord?,
                        phase: AuthorityOperationsPhase, ids: AuthorityCredentialIds, image: AuthorityHistoryImage,
                        key: ByteArray) = w.advance(previous?.digest(register), phase, ids, hash('f'), image, key)

    @Test
    fun rotationSealsOldBytesAndContinuesExactRootChainWithoutOldKey() {
        val w = witness()
        val enrolled = advance(w, null, AuthorityOperationsPhase.ACTIVE, oldIds, image(marker), oldKey)
        val original = append(marker, genesis, oldKey)
        val published = advance(w, enrolled, AuthorityOperationsPhase.ACTIVE, oldIds, image(original), oldKey)
        val frozen = advance(w, published, AuthorityOperationsPhase.FROZEN, oldIds, image(original), oldKey)
        val prepared = advance(w, frozen, AuthorityOperationsPhase.PREPARED, newIds, image(original), oldKey)
        assertFails { advance(w, prepared, AuthorityOperationsPhase.ACTIVE, newIds, image(original), oldKey) }
        val header = AuthorityOperationsHistory.successorHeader(register, prepared.segments.last(),
            prepared.rootSha256, newIds.history, newKey)
        assertFails { advance(w, prepared, AuthorityOperationsPhase.CUTOVER, newIds, image(original, header), oldKey) }
        val cutover = advance(w, prepared, AuthorityOperationsPhase.CUTOVER, newIds, image(original, header), newKey)
        val verified = advance(w, cutover, AuthorityOperationsPhase.VERIFIED, newIds, image(original, header), newKey)
        assertEquals(published.rootSha256, verified.rootSha256)
        assertEquals(published.segments.single(), verified.segments.first())
        val extended = append(header, nextRoot, newKey)
        assertFails { advance(w, verified, AuthorityOperationsPhase.ACTIVE, newIds, image(marker, extended), newKey) }
        assertFails { advance(w, verified, AuthorityOperationsPhase.ACTIVE, newIds, image(original, extended), oldKey) }
        val latest = advance(w, verified, AuthorityOperationsPhase.ACTIVE, newIds, image(original, extended), newKey)
        assertEquals(EffectAuthorityPolicy.digest(nextRoot), latest.rootSha256)
        assertEquals(latest, w.read())
        assertFails { advance(w, verified, AuthorityOperationsPhase.ACTIVE, newIds, image(original, extended), newKey) }
        assertEquals(latest, w.read())
    }

    @Test
    fun coherentRollbackDivergenceResetAndQuarantineNeverReopen() {
        val w = witness()
        val enrolled = advance(w, null, AuthorityOperationsPhase.ACTIVE, oldIds, image(marker), oldKey)
        val original = append(marker, genesis, oldKey)
        val published = advance(w, enrolled, AuthorityOperationsPhase.ACTIVE, oldIds, image(original), oldKey)
        val before = Files.readAllBytes(directory.resolve("witness"))
        assertFails { advance(w, published, AuthorityOperationsPhase.FROZEN, oldIds, image(marker), oldKey) }
        assertFails { advance(w, published, AuthorityOperationsPhase.FROZEN, oldIds,
            AuthorityHistoryImage(marker, listOf(AuthoritySegmentImage(original, marker))), oldKey) }
        assertFails { advance(w, null, AuthorityOperationsPhase.ACTIVE, oldIds, image(marker), oldKey) }
        assertContentEquals(before, Files.readAllBytes(directory.resolve("witness")))
        val quarantined = w.advance(published.digest(register), AuthorityOperationsPhase.QUARANTINED, oldIds, hash('f'))
        assertFails { advance(w, quarantined, AuthorityOperationsPhase.FROZEN, oldIds, image(original), oldKey) }
        val retired = w.advance(quarantined.digest(register), AuthorityOperationsPhase.RETIRED, oldIds, hash('f'))
        assertFails { advance(w, retired, AuthorityOperationsPhase.FROZEN, oldIds, image(original), oldKey) }
        assertEquals(retired, w.read())
    }

    @Test
    fun competingWitnessCasAndTornSignedLogFailClosed() {
        val w = witness()
        val enrolled = advance(w, null, AuthorityOperationsPhase.ACTIVE, oldIds, image(marker), oldKey)
        val original = append(marker, genesis, oldKey)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val attempts = List(2) {
                executor.submit<Boolean> {
                    start.await()
                    runCatching {
                        advance(w, enrolled, AuthorityOperationsPhase.ACTIVE, oldIds, image(original), oldKey)
                    }.isSuccess
                }
            }
            start.countDown()
            assertEquals(1, attempts.count { it.get(20, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
        val path = directory.resolve("witness")
        val bytes = Files.readAllBytes(path)
        Files.write(path, bytes.copyOf(bytes.size - 1))
        assertFails { w.read() }
        assertFails { w.advance(null, AuthorityOperationsPhase.RETIRED, oldIds, hash('f')) }
        assertTrue(Files.size(path) == bytes.size - 1L)
    }
}
