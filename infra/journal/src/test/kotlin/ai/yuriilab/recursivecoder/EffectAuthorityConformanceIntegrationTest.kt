package ai.yuriilab.recursivecoder

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertContentEquals
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
    protected abstract fun truncateBoth()
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
            assertTrue(winner >= 0, "No durable winner: $outcomes")
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
    fun crashBeforeSecondForceDoesNotProduceAcknowledgement() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val next = started()
        val result = client("before-mirror-force").compareAndSet(genesis, next)
        assertEquals(EffectAuthorityPublication.Uncertain, result)
        // Process death alone leaves the page cache intact. Fresh read forces
        // the equal histories, but cannot retroactively acknowledge the CAS.
        latest(next)
        assertEquals(EffectAuthorityPublicationDisposition.QUARANTINED,
            EffectAuthorityPolicy.disposition(result, next))
    }

    @Test
    fun crashBetweenDurabilityBoundariesQuarantinesWithoutRepair() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        assertEquals(EffectAuthorityPublication.Uncertain,
            client("after-primary").compareAndSet(genesis, started()))
        assertEquals(EffectAuthorityRead.Unavailable, client().read(register))
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
    fun oneSidedTruncationIsUnavailableAndCannotBootstrap() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        acknowledge(client().compareAndSet(genesis, started()), started())
        truncatePrimary()
        assertEquals(EffectAuthorityRead.Unavailable, client().read(register))
        assertEquals(EffectAuthorityPublication.Unavailable, client().bootstrap(genesis))
    }

    @Test
    fun corruptBothHistoriesAndResetAreNotProvisioning() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        corruptBoth()
        assertEquals(EffectAuthorityRead.Unauthenticated, client().read(register))
        assertEquals(EffectAuthorityPublication.Unauthenticated, client().bootstrap(genesis))
        removeHistories()
        assertEquals(EffectAuthorityRead.Unavailable, client().read(register))
        assertEquals(EffectAuthorityPublication.Unavailable, client().bootstrap(genesis))
        // The persisted provisioning marker remains; no automatic replacement.
        assertTrue(runCatching { provision() }.isFailure)
    }

    @Test
    fun coherentAuthorityDomainRollbackIsOutsideThreatModel() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        acknowledge(client().compareAndSet(genesis, started()), started())
        truncateBoth()
        // Privileged rollback of both authority logs violates deployment assumptions.
        latest(genesis)
        refuse(client().bootstrap(genesis), EffectAuthorityRefusal.ALREADY_INITIALIZED)
    }

    @Test
    fun malformedAuthenticatedRepliesAndForeignRootsNeverAuthorize() {
        provision()
        acknowledge(client().bootstrap(genesis), genesis)
        val next = started()
        for (mode in listOf("wrong-digest", "unknown-status", "unknown-refusal", "oversized")) {
            assertEquals(EffectAuthorityPublication.Uncertain, client(mode).compareAndSet(genesis, next), mode)
        }
        assertEquals(EffectAuthorityPublication.Unauthenticated,
            client("wrong-field-count").compareAndSet(genesis, next))
        for (mode in listOf("invalid-history", "noncontiguous-history", "wrong-field-count", "unknown-status")) {
            assertEquals(EffectAuthorityRead.Unauthenticated, client(mode).read(register), mode)
        }
        assertEquals(EffectAuthorityRead.Unavailable, client("oversized").read(register))
        val foreign = register.copy(epoch = hash('9'))
        val foreignGenesis = genesis.copy(evidence = genesis.evidence.copy(register = foreign))
        val foreignNext = next.copy(evidence = next.evidence.copy(register = foreign))
        assertEquals(EffectAuthorityPublication.Unauthenticated, client().bootstrap(foreignGenesis))
        assertEquals(EffectAuthorityPublication.Unauthenticated, client().compareAndSet(foreignGenesis, next))
        assertEquals(EffectAuthorityPublication.Unauthenticated, client().compareAndSet(genesis, foreignNext))
        latest(genesis)
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
    @Test
    fun productionStorageGuardRejectsUnprotectedLogs() {
        provision()
        assertEquals(EffectAuthorityRead.Unavailable,
            client("enforce-append-only").read(register))
        assertEquals(EffectAuthorityPublication.Unavailable,
            client("enforce-append-only").bootstrap(genesis))
        assertEquals(EffectAuthorityRead.MissingOrReset, client().read(register))
    }

    private fun primary() = directory.resolve("primary")
    private fun mirror() = directory.resolve("mirror")
    private fun lock() = directory.resolve("lock")
    override fun marker() = directory.resolve("blocked")
    private fun keyFile(name: String) = directory.resolve(name)

    @BeforeEach
    fun createKeys() {
        for ((name, value) in listOf("transport" to 7, "history" to 9)) {
            val path = keyFile(name)
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")))
            Files.write(path, ByteArray(32) { value.toByte() })
        }
    }

    private fun process(main: String, args: List<String>): Process =
        ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", checkNotNull(System.getProperty("recursivecoder.fork.classpath")), main) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()

    private fun hostExit(mode: String): Int {
        val child = process(EffectAuthorityHostCommand::class.java.name,
            AuthorityProcessFixture.hostArgs(directory, mode).toList())
        try {
            child.outputStream.close()
            check(child.waitFor(20, TimeUnit.SECONDS))
            assertEquals(0, child.inputStream.readAllBytes().size)
            return child.exitValue()
        } finally { child.destroyForcibly() }
    }

    override fun provision() {
        check(hostExit("provision-never-initialized") == 0)
    }

    @Test
    fun actualHostCommandRejectsUnsupportedModeAndBrokenOutput() {
        assertEquals(1, hostExit("unsupported"))
        provision()
        assertEquals(EffectAuthorityPublication.Uncertain, client("broken-output").bootstrap(genesis))
        assertEquals(EffectAuthorityRead.Authenticated(genesis), client().read(register))
    }

    @Test
    fun connectRequiresExplicitApprovalAndExactSshArguments() {
        fun connect(approved: Boolean = true, destination: String = "authority@host.example",
                    port: Int = 2222, transport: Path = keyFile("transport"),
                    identity: Path = directory.resolve("ssh"), known: Path = directory.resolve("known"),
                    timeout: Duration = Duration.ofSeconds(10)) =
            SshEffectAntiRollbackAuthority.connect(register, transport, identity, known,
                destination, port, timeout, approved)
        val client = connect()
        assertEquals(listOf("/usr/bin/ssh", "-F", "/dev/null", "-T",
            "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "UserKnownHostsFile=" + directory.resolve("known"),
            "-o", "GlobalKnownHostsFile=/dev/null", "-o", "IdentitiesOnly=yes",
            "-o", "IdentityAgent=none", "-o", "ConnectionAttempts=1", "-o", "ClearAllForwardings=yes",
            "-o", "PreferredAuthentications=publickey", "-o", "ControlMaster=no",
            "-i", directory.resolve("ssh").toString(), "-p", "2222", "--", "authority@host.example"),
            client.command)
        assertFails { connect(approved = false) }
        assertFails { connect(destination = "-bad@host") }
        assertFails { connect(destination = "authority@host;bad") }
        assertFails { connect(port = 0) }
        assertFails { connect(port = 65536) }
        assertFails { connect(transport = Path.of("relative")) }
        assertFails { connect(identity = Path.of("relative")) }
        assertFails { connect(known = Path.of("relative")) }
        assertFails { connect(timeout = Duration.ZERO) }
    }

    @Test
    fun keyLoaderRequiresOwnedPrivateRegularFileAndExactLength() {
        val key = keyFile("transport")
        assertContentEquals(ByteArray(32) { 7 }, AuthorityWire.key(key))
        val link = directory.resolve("link")
        Files.createSymbolicLink(link, key)
        assertFails { AuthorityWire.key(link) }
        assertFails { AuthorityWire.key(directory) }
        assertFails { AuthorityWire.key(directory.resolve("absent")) }
        for (mode in listOf("rw-r-----", "rw----r--", "rwx------", "-w-------")) {
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString(mode))
            assertFails { AuthorityWire.key(key) }
        }
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("r--------"))
        assertContentEquals(ByteArray(32) { 7 }, AuthorityWire.key(key))
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"))
        for (size in listOf(31, 33)) {
            Files.write(key, ByteArray(size))
            assertFails { AuthorityWire.key(key) }
        }
    }

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

    override fun truncateBoth() {
        truncatePrimary()
        Files.write(mirror(), Files.readAllBytes(primary()))
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

/** Real host command parser and persistence, with test-only transport faults. */
object AuthorityProcessFixture {
    fun hostArgs(directory: Path, mode: String) = arrayOf(mode,
        directory.resolve("primary").toString(), directory.resolve("mirror").toString(),
        directory.resolve("lock").toString(), directory.resolve("transport").toString(),
        directory.resolve("history").toString(), "host", "session", "register", "a".repeat(64))

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        val mode = args[1]
        if (mode == "unavailable") Runtime.getRuntime().halt(2)
        if (mode == "replay") {
            System.`in`.readNBytes(AuthorityWire.LIMIT + 1)
            System.out.write(Files.readAllBytes(directory.resolve("response")))
            return
        }
        if (mode in setOf("wrong-digest", "unknown-status", "unknown-refusal", "oversized",
                "wrong-field-count", "invalid-history", "noncontiguous-history")) {
            val request = System.`in`.readNBytes(AuthorityWire.LIMIT + 1)
            if (mode == "oversized") {
                System.out.write(ByteArray(AuthorityWire.LIMIT + 1))
                return
            }
            val hash = AuthorityWire.hash(request)
            val key = ByteArray(32) { 7 }
            val register = EffectAuthorityRegister("host", "session", "register", "a".repeat(64))
            val root = EffectAuthorityRoot(1, null, EffectAuthorityEvidence(register, 1,
                "b".repeat(64), "c".repeat(64), "d".repeat(64), 0, "e".repeat(64),
                null, EffectAuthorityState.GENESIS))
            val encoded = checkNotNull(AuthorityWire.encode(root))
            val payload = when (mode) {
                "wrong-digest" -> "$hash\nack\n" + "9".repeat(64)
                "unknown-status" -> "$hash\nunknown\n"
                "unknown-refusal" -> "$hash\nrefused\nUNKNOWN"
                "wrong-field-count" -> "$hash\nack"
                "invalid-history" -> "$hash\nhistory\ninvalid"
                else -> "$hash\nhistory\n$encoded,$encoded"
            }
            System.out.write(AuthorityWire.pack(key, AuthorityWire.RESPONSE, payload))
            return
        }
        val original = System.out
        val capture = ByteArrayOutputStream()
        if (mode == "capture") System.setOut(PrintStream(capture))
        if (mode == "broken-output") System.setOut(PrintStream(object : OutputStream() {
            override fun write(b: Int) { throw IOException("fixture") }
        }))
        if (mode == "enforce-append-only") {
            EffectAuthorityHostCommand.main(hostArgs(directory, "serve-independent"))
            return
        }
        EffectAuthorityHostCommand.run(hostArgs(directory, "serve-independent"), requireAppendOnly = false) { point ->
            if (point.wireValue == mode) Runtime.getRuntime().halt(3)
            if (mode == "block" && point == AuthorityFaultPoint.BEFORE_APPEND) {
                Files.writeString(directory.resolve("blocked"), "blocked")
                Thread.sleep(60000)
            }
        }
        if (mode == "capture") {
            val response = capture.toByteArray()
            Files.write(directory.resolve("response"), response)
            original.write(response)
            original.flush()
        }
    }
}

@Tag("unit")
class AuthorityWireVocabularyUnitTest {
    @Test
    fun stableWireTokensAndStrictDecoding() {
        assertEquals(listOf("read", "bootstrap", "cas"), AuthorityVerb.entries.map { it.wireValue })
        assertEquals(listOf("ack", "refused", "missing", "history", "unauthenticated", "unavailable"),
            AuthorityStatus.entries.map { it.wireValue })
        assertEquals(listOf("before-append", "before-primary-force", "after-primary",
            "before-mirror-force", "after-both"), AuthorityFaultPoint.entries.map { it.wireValue })
        assertEquals("-", AuthorityWire.ABSENT)
        assertEquals(null, AuthorityVerb.decode("READ"))
        assertEquals(null, AuthorityStatus.decode("unknown"))
    }

    @Test
    fun appendOnlyFlagParserRequiresAValidFlagField() {
        assertTrue(AuthorityWire.appendOnlyFlags("-----a--------e------- /authority/log"))
        assertFalse(AuthorityWire.appendOnlyFlags("--------------e------- /authority/log"))
        assertFalse(AuthorityWire.appendOnlyFlags("a /authority/log"))
        assertFalse(AuthorityWire.appendOnlyFlags("garbage containing a"))
        assertFalse(AuthorityWire.appendOnlyFlags("a-------------e------- /authority/log"))
        assertFalse(AuthorityWire.appendOnlyFlags(null))
    }
}

/** Pure fixture values deliberately do not call the Linux filesystem. */
@Tag("unit")
class AuthorityKeyPolicyUnitTest {
    private val owned = AuthorityKeyMetadata(1000, 0x8180, true, "device:inode")

    @Test
    fun parsesOnlyOneValidLinuxEffectiveUidWithMatchingFilesystemUid() {
        assertEquals(1000L, AuthorityKeyPolicy.effectiveUid(listOf(
            "Name: java", "Uid:\t42\t1000\t43\t1000", "Gid:\t9\t9\t9\t9"
        )))
        assertEquals(0L, AuthorityKeyPolicy.effectiveUid(listOf("Uid: 0 0 0 0")))
        assertEquals(4294967294L, AuthorityKeyPolicy.effectiveUid(
            listOf("Uid: 1 4294967294 1 4294967294")))
        for (status in listOf(
            emptyList(),
            listOf("Uid: 1 2 3"),
            listOf("Uid: 1 2 3 2 4"),
            listOf("Uid: 1 2 3 2", "Uid: 1 2 3 2"),
            listOf("Uid: 1 -2 3 -2"),
            listOf("Uid: 1 +2 3 +2"),
            listOf("Uid: 1 02 3 02"),
            listOf("Uid: 1 4294967295 3 4294967295"),
            listOf("Uid: 1 999999999999999999999 3 2"),
            listOf("Uid: 1 invalid 3 2"),
            listOf("Uid: 1 2 3 4"),
            listOf("Uid:"),
            listOf("Gid: 1 2 3 2")
        )) assertEquals(null, AuthorityKeyPolicy.effectiveUid(status), status.toString())
    }

    @Test
    fun acceptsOnlyOwnedRegularPrivateFileWithKnownIdentity() {
        assertTrue(AuthorityKeyPolicy.accepts(owned, 1000))
        assertTrue(AuthorityKeyPolicy.accepts(owned.copy(mode = 0x8100), 1000))
        assertFalse(AuthorityKeyPolicy.accepts(owned.copy(ownerUid = 1001), 1000))
        assertFalse(AuthorityKeyPolicy.accepts(owned, 1001))
        assertFalse(AuthorityKeyPolicy.accepts(owned.copy(fileKey = null), 1000))
        assertFalse(AuthorityKeyPolicy.accepts(owned.copy(regularFile = false), 1000))
        assertFalse(AuthorityKeyPolicy.accepts(owned.copy(ownerUid = -1), -1))
        assertFalse(AuthorityKeyPolicy.accepts(owned.copy(ownerUid = 4294967295L), 4294967295L))
        for (mode in listOf(0x81A0, 0x8184, 0x81C0, 0x8080, 0x8980, 0x8580, 0x8380)) {
            assertFalse(AuthorityKeyPolicy.accepts(owned.copy(mode = mode), 1000), mode.toString(16))
        }
    }

    @Test
    fun rejectsBeforeAfterIdentityOwnershipPermissionAndTypeChanges() {
        assertTrue(AuthorityKeyPolicy.samePrivateFile(owned, owned.copy(), 1000))
        val changed = listOf(
            owned.copy(fileKey = "device:replacement"),
            owned.copy(fileKey = null),
            owned.copy(ownerUid = 1001),
            owned.copy(mode = 0x8100), // Still private but changed during read.
            owned.copy(mode = 0x8184),
            owned.copy(regularFile = false)
        )
        for (metadata in changed) {
            assertFalse(AuthorityKeyPolicy.samePrivateFile(owned, metadata, 1000))
            assertFalse(AuthorityKeyPolicy.samePrivateFile(metadata, owned, 1000))
        }
        assertFalse(AuthorityKeyPolicy.samePrivateFile(owned, owned, 1001))
    }
}
