package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class EffectAuthorityConformance(val adapter: String)

/** The shared contract is exercised by a new OS process for every invocation. */
@Tag("integration")
abstract class EffectAuthorityAdapterContract {
    @TempDir
    lateinit var directory: Path

    protected val register = EffectAuthorityRegister("host", "session", "register", hash('a'))
    protected val genesis = EffectAuthorityRoot(1, null, EffectAuthorityEvidence(
        register, 1, hash('b'), hash('c'), hash('d'), 0, hash('e'),
        null, EffectAuthorityState.GENESIS
    ))
    protected fun hash(c: Char) = c.toString().repeat(64)
    protected fun digest(root: EffectAuthorityRoot) = assertNotNull(EffectAuthorityPolicy.digest(root))

    protected fun successor(old: EffectAuthorityRoot, evidence: EffectAuthorityEvidence) =
        EffectAuthorityRoot(old.version + 1, digest(old), evidence)

    protected fun started(old: EffectAuthorityRoot = genesis, id: String = "operation-one",
                          checkpoint: Char = '1', head: Char = '2') = successor(old, old.evidence.copy(
        operation = EffectIdentity(register.sessionId, id, old.evidence.fenceGeneration,
            EffectKind.TRUSTED_WRITE, hash('f')),
        state = EffectAuthorityState.START_BARRIER_COMMITTED,
        sequence = old.evidence.sequence + 2,
        checkpointSha256 = hash(checkpoint), headMacSha256 = hash(head)
    ))

    protected fun terminal(old: EffectAuthorityRoot) = successor(old, old.evidence.copy(
        state = EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED, sequence = old.evidence.sequence + 1,
        checkpointSha256 = hash('3'), headMacSha256 = hash('4')
    ))

    protected abstract fun provision()
    protected abstract fun client(
        fault: String = "none",
        register: EffectAuthorityRegister = this.register,
        wrongKey: Boolean = false,
        timeout: Duration = Duration.ofSeconds(20)
    ): HostEffectAntiRollbackAuthority
    protected abstract fun truncatePrimary()
    protected abstract fun corruptBoth()
    protected abstract fun removeHistories()
    protected abstract fun marker(): Path

    private fun acknowledge(result: EffectAuthorityPublication, root: EffectAuthorityRoot) =
        assertEquals(EffectAuthorityPublication.DurableAcknowledgement(digest(root)), result)

    private fun refuse(result: EffectAuthorityPublication, reason: EffectAuthorityRefusal) =
        assertEquals(EffectAuthorityPublication.Refused(reason), result)

    private fun latest(root: EffectAuthorityRoot) =
        assertEquals(EffectAuthorityRead.Authenticated(root), client().read(register))

    @Test
    fun initializationFreshReadAndExactCas() {
        assertEquals(EffectAuthorityRead.MissingOrReset, client().read(register))
        assertFalse(EffectAuthorityPolicy.acknowledges(client().bootstrap(genesis), genesis))
        provision()
        assertEquals(EffectAuthorityRead.MissingOrReset, client().read(register))
        acknowledge(client().bootstrap(genesis), genesis)
        latest(genesis)
        refuse(client().bootstrap(genesis), EffectAuthorityRefusal.ALREADY_INITIALIZED)
        val next = started()
        refuse(client().compareAndSet(genesis.copy(evidence = genesis.evidence.copy(
            checkpointSha256 = hash('9'))), next), EffectAuthorityRefusal.STALE_PREDECESSOR)
        acknowledge(client().compareAndSet(genesis, next), next)
        refuse(client().compareAndSet(genesis, next), EffectAuthorityRefusal.STALE_PREDECESSOR)
        latest(next)
        assertFalse(EffectAuthorityPolicy.matchesLocal(client().read(register), genesis.evidence))
        assertTrue(EffectAuthorityPolicy.matchesLocal(client().read(register), next.evidence))
    }

    @Test
    fun immutableHistoryAndOperationCheckpointHeadUniqueness() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val first = started()
        val last = terminal(first)
        acknowledge(client().compareAndSet(genesis, first), first)
        acknowledge(client().compareAndSet(first, last), last)
        refuse(client().compareAndSet(last, started(last, checkpoint = '5', head = '6')),
            EffectAuthorityRefusal.REUSED_OPERATION)
        refuse(client().compareAndSet(last, started(last, "operation-two", '1', '6')),
            EffectAuthorityRefusal.INVALID_TRANSITION)
        refuse(client().compareAndSet(last, started(last, "operation-two", '5', '2')),
            EffectAuthorityRefusal.INVALID_TRANSITION)
        val second = started(last, "operation-two", '5', '6')
        refuse(client().compareAndSet(last, second.copy(predecessorSha256 = hash('9'))),
            EffectAuthorityRefusal.STALE_PREDECESSOR)
        acknowledge(client().compareAndSet(last, second), second)
        latest(second)
    }

    @Test
    fun successorGenerationCannotReuseHistoricalAnchorOrPath() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val second = successor(genesis, genesis.evidence.copy(fenceGeneration = 2,
            fenceAnchorSha256 = hash('1'), sidecarPathSha256 = hash('2'),
            checkpointSha256 = hash('3'), headMacSha256 = hash('4')))
        acknowledge(client().compareAndSet(genesis, second), second)
        val third = successor(second, second.evidence.copy(fenceGeneration = 3,
            fenceAnchorSha256 = hash('5'), sidecarPathSha256 = hash('6'),
            checkpointSha256 = hash('7'), headMacSha256 = hash('8')))
        refuse(client().compareAndSet(second, third.copy(evidence = third.evidence.copy(
            fenceAnchorSha256 = genesis.evidence.fenceAnchorSha256))), EffectAuthorityRefusal.INVALID_TRANSITION)
        refuse(client().compareAndSet(second, third.copy(evidence = third.evidence.copy(
            sidecarPathSha256 = genesis.evidence.sidecarPathSha256))), EffectAuthorityRefusal.INVALID_TRANSITION)
        refuse(client().compareAndSet(second, third.copy(evidence = third.evidence.copy(
            fenceGeneration = 4))), EffectAuthorityRefusal.INVALID_TRANSITION)
        acknowledge(client().compareAndSet(second, third), third)
        latest(third)
    }

    @Test
    fun concurrentIndependentProcessesYieldExactlyOneDurableTransition() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val roots = listOf(started(), started(id = "operation-two", checkpoint = '5', head = '6'))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = roots.map { root -> pool.submit<EffectAuthorityPublication> {
                check(start.await(10, TimeUnit.SECONDS))
                client().compareAndSet(genesis, root)
            } }
            start.countDown()
            val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
            val winner = outcomes.indexOfFirst { it is EffectAuthorityPublication.DurableAcknowledgement }
            assertTrue(winner >= 0)
            acknowledge(outcomes[winner], roots[winner])
            refuse(outcomes[1 - winner], EffectAuthorityRefusal.STALE_PREDECESSOR)
            latest(roots[winner])
        } finally { pool.shutdownNow() }
    }

    @Test
    fun failureBeforeAppendAndLostResponseNeverBecomeAcknowledgements() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val next = started()
        val before = client("before-append").compareAndSet(genesis, next)
        assertEquals(EffectAuthorityPublication.Uncertain, before)
        latest(genesis)
        val lost = client("after-both").compareAndSet(genesis, next)
        assertEquals(EffectAuthorityPublication.Uncertain, lost)
        latest(next)
        assertEquals(EffectAuthorityPublicationDisposition.QUARANTINED,
            EffectAuthorityPolicy.disposition(lost, next))
        assertFalse(EffectAuthorityPolicy.acknowledges(lost, next))
    }

    @Test
    fun crashBetweenDurabilityBoundariesQuarantinesWithoutRepair() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        assertEquals(EffectAuthorityPublication.Uncertain,
            client("after-primary").compareAndSet(genesis, started()))
        assertEquals(EffectAuthorityRead.MissingOrReset, client().read(register))
        assertFalse(EffectAuthorityPolicy.acknowledges(client().bootstrap(genesis), genesis))
        assertFalse(EffectAuthorityPolicy.acknowledges(client().compareAndSet(genesis, started()), started()))
    }

    @Test
    fun wrongCredentialsForeignIdentityAndUnavailableFailClosed() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val foreign = register.copy(epoch = hash('9'))
        assertEquals(EffectAuthorityRead.Unauthenticated, client(register = foreign).read(foreign))
        assertEquals(EffectAuthorityRead.Unauthenticated, client(wrongKey = true).read(register))
        assertEquals(EffectAuthorityPublication.Unauthenticated,
            client(wrongKey = true).compareAndSet(genesis, started()))
        assertEquals(EffectAuthorityRead.Unavailable, client("unavailable").read(register))
        assertEquals(EffectAuthorityPublication.Uncertain,
            client("unavailable").compareAndSet(genesis, started()))
        latest(genesis)
    }

    @Test
    fun truncatedAuthenticatedPrefixIsNotLatestAndCannotBootstrap() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        acknowledge(client().compareAndSet(genesis, started()), started())
        truncatePrimary()
        assertEquals(EffectAuthorityRead.MissingOrReset, client().read(register))
        assertEquals(EffectAuthorityPublication.Unavailable, client().bootstrap(genesis))
    }

    @Test
    fun corruptBothHistoriesAndResetAreNotProvisioning() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        corruptBoth()
        assertEquals(EffectAuthorityRead.MissingOrReset, client().read(register))
        assertEquals(EffectAuthorityPublication.Unavailable, client().bootstrap(genesis))
        removeHistories()
        assertEquals(EffectAuthorityRead.MissingOrReset, client().read(register))
        assertEquals(EffectAuthorityPublication.Unavailable, client().bootstrap(genesis))
        // The persisted provisioning marker remains; no automatic replacement.
        assertTrue(runCatching { provision() }.isFailure)
    }

    @Test
    fun timeoutAndInterruptionQuarantineInvocation() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val next = started()
        assertEquals(EffectAuthorityPublication.Uncertain,
            client("block", timeout = Duration.ofMillis(300)).compareAndSet(genesis, next))
        latest(genesis)
        Files.deleteIfExists(marker())
        val result = AtomicReference<EffectAuthorityPublication>()
        val interrupted = AtomicReference<Boolean>()
        val thread = Thread {
            result.set(client("block").compareAndSet(genesis, next))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        thread.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!Files.exists(marker()) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(marker()))
            thread.interrupt()
            thread.join(5000)
            assertFalse(thread.isAlive)
            assertEquals(EffectAuthorityPublication.Uncertain, result.get())
            assertEquals(true, interrupted.get())
            latest(genesis)
        } finally {
            thread.interrupt()
            thread.join(5000)
        }
    }

    @Test
    fun replayedAuthenticatedResponseIsNotFresh() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        assertIs<EffectAuthorityRead.Authenticated>(client("capture").read(register))
        assertEquals(EffectAuthorityRead.Unauthenticated, client("replay").read(register))
        assertEquals(EffectAuthorityPublication.Unauthenticated,
            client("replay").compareAndSet(genesis, started()))
        latest(genesis)
    }
}

@Tag("integration")
@EffectAuthorityConformance(adapter = "ai.yuriilab.recursivecoder.SshEffectAntiRollbackAuthority")
class SshEffectAuthorityConformanceIntegrationTest : EffectAuthorityAdapterContract() {
    private fun primary() = directory.resolve("primary")
    private fun mirror() = directory.resolve("mirror")
    private fun lock() = directory.resolve("lock")
    override fun marker() = directory.resolve("blocked")
    override fun provision() = EffectAuthorityService(
        register, primary(), mirror(), lock(), ByteArray(32) { 7 }, ByteArray(32) { 9 },
        requireAppendOnly = false
    ).provision()

    override fun client(fault: String, register: EffectAuthorityRegister, wrongKey: Boolean,
                        timeout: Duration): HostEffectAntiRollbackAuthority =
        SshEffectAntiRollbackAuthority(register, ByteArray(32) { if (wrongKey) 8 else 7 },
            listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                checkNotNull(System.getProperty("recursivecoder.fork.classpath")),
                AuthorityProcessFixture::class.java.name, directory.toString(), fault), timeout)

    override fun truncatePrimary() {
        val lines = Files.readAllLines(primary())
        Files.writeString(primary(), lines.dropLast(1).joinToString("\n", postfix = "\n"))
    }

    override fun corruptBoth() {
        val corrupt = Files.readAllBytes(primary()).also { it[it.lastIndex - 1] = 'z'.code.toByte() }
        Files.write(primary(), corrupt)
        Files.write(mirror(), corrupt)
    }

    override fun removeHistories() {
        Files.delete(primary())
        Files.delete(mirror())
    }
}

/** Same wire, validation, lock and persistence as production, no SSH or chattr in CI. */
object AuthorityProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        val mode = args[1]
        val request = System.`in`.readNBytes(AuthorityWire.LIMIT + 1)
        if (mode == "unavailable") Runtime.getRuntime().halt(2)
        if (mode == "replay") {
            System.out.write(Files.readAllBytes(directory.resolve("response")))
            return
        }
        val service = EffectAuthorityService(
            EffectAuthorityRegister("host", "session", "register", "a".repeat(64)),
            directory.resolve("primary"), directory.resolve("mirror"), directory.resolve("lock"),
            ByteArray(32) { 7 }, ByteArray(32) { 9 }, requireAppendOnly = false
        ) { point ->
            if (point == mode) Runtime.getRuntime().halt(3)
            if (mode == "block" && point == "before-append") {
                Files.writeString(directory.resolve("blocked"), "blocked")
                Thread.sleep(60000)
            }
        }
        val response = service.handle(request)
        if (mode == "capture") Files.write(directory.resolve("response"), response)
        System.out.write(response)
        System.out.flush()
    }
}
