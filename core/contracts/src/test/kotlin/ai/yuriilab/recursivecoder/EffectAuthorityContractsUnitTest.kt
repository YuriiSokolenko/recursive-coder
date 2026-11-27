package ai.yuriilab.recursivecoder

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test-only linearizable model; not durable, authenticated or rollback resistant. */
@Tag("unit")
class EffectAuthorityContractsUnitTest {
    private val register = EffectAuthorityRegister("host", "session", "register", hash('a'))
    private val genesis = EffectAuthorityRoot(
        1L, null, EffectAuthorityEvidence(
            register, 3L, hash('b'), hash('c'), hash('d'), 0L, hash('e'),
            null, EffectAuthorityState.GENESIS
        )
    )
    private val operation = EffectIdentity(
        "session", "operation-one", 3L, EffectKind.TRUSTED_WRITE, hash('f')
    )

    private fun hash(c: Char) = c.toString().repeat(64)

    private fun digest(root: EffectAuthorityRoot) = assertNotNull(EffectAuthorityPolicy.digest(root))

    private fun successor(
        old: EffectAuthorityRoot,
        state: EffectAuthorityState,
        op: EffectIdentity?,
        sequence: Long,
        checkpoint: Char,
        mac: Char
    ) = EffectAuthorityRoot(
        old.version + 1L, digest(old), old.evidence.copy(
            state = state, operation = op, sequence = sequence,
            checkpointSha256 = hash(checkpoint).take(48) + old.version.toString(16).padStart(16, '0'),
            headMacSha256 = hash(mac).take(48) + old.version.toString(16).padStart(16, '0')
        )
    )

    private fun started(
        old: EffectAuthorityRoot = genesis, op: EffectIdentity = operation
    ) = successor(
        old, EffectAuthorityState.START_BARRIER_COMMITTED, op,
        old.evidence.sequence + 2L, '1', '2'
    )

    private fun terminal(old: EffectAuthorityRoot = started(), uncertain: Boolean = false) =
        successor(
            old,
            if (uncertain) EffectAuthorityState.TERMINAL_UNCONFIRMED
            else EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED,
            old.evidence.operation, old.evidence.sequence + 1L, '3', '4'
        )

    private enum class Fault { NONE, BEFORE_COMMIT, AFTER_COMMIT }
    private enum class Availability { PRESENT, UNAVAILABLE, UNAUTHENTICATED, RESET }

    /**
     * A single independently provisioned register is simulated by the fixture.
     * The monitor covers authentication stand-in, comparison, uniqueness and
     * append. All stored and returned records consist only of immutable values.
     * Reset hides the root, not the fact that this register was initialized.
     */
    private class AuthorityModel(private val provisioned: EffectAuthorityRegister) :
        HostEffectAntiRollbackAuthority {
        private val history = mutableListOf<EffectAuthorityRoot>()
        private var fault = Fault.NONE
        private var availability = Availability.PRESENT

        @Synchronized
        fun inject(next: Fault) { fault = next }

        @Synchronized
        fun setAvailability(next: Availability) { availability = next }

        @Synchronized
        fun versionCount(): Int = history.size

        private fun availabilityFailure(): EffectAuthorityPublication? = when (availability) {
            Availability.PRESENT -> null
            Availability.UNAVAILABLE, Availability.RESET -> EffectAuthorityPublication.Unavailable
            Availability.UNAUTHENTICATED -> EffectAuthorityPublication.Unauthenticated
        }

        private fun append(root: EffectAuthorityRoot): EffectAuthorityPublication {
            val injected = fault
            fault = Fault.NONE
            if (injected == Fault.BEFORE_COMMIT) return EffectAuthorityPublication.Uncertain
            history.add(root)
            if (injected == Fault.AFTER_COMMIT) return EffectAuthorityPublication.Uncertain
            return EffectAuthorityPublication.DurableAcknowledgement(
                checkNotNull(EffectAuthorityPolicy.digest(root))
            )
        }

        @Synchronized
        override fun bootstrap(initial: EffectAuthorityRoot): EffectAuthorityPublication {
            availabilityFailure()?.let { return it }
            if (initial.evidence.register != provisioned)
                return EffectAuthorityPublication.Unauthenticated
            if (history.isNotEmpty())
                return EffectAuthorityPublication.Refused(EffectAuthorityRefusal.ALREADY_INITIALIZED)
            if (!EffectAuthorityPolicy.validBootstrap(initial))
                return EffectAuthorityPublication.Refused(EffectAuthorityRefusal.INVALID_RECORD)
            return append(initial)
        }

        @Synchronized
        override fun read(register: EffectAuthorityRegister): EffectAuthorityRead {
            if (register != provisioned) return EffectAuthorityRead.Unauthenticated
            return when (availability) {
                Availability.RESET -> EffectAuthorityRead.MissingOrReset
                Availability.UNAVAILABLE -> EffectAuthorityRead.Unavailable
                Availability.UNAUTHENTICATED -> EffectAuthorityRead.Unauthenticated
                Availability.PRESENT -> history.lastOrNull()?.let {
                    EffectAuthorityRead.Authenticated(it)
                } ?: EffectAuthorityRead.MissingOrReset
            }
        }

        @Synchronized
        override fun compareAndSet(
            expected: EffectAuthorityRoot, proposed: EffectAuthorityRoot
        ): EffectAuthorityPublication {
            availabilityFailure()?.let { return it }
            if (expected.evidence.register != provisioned)
                return EffectAuthorityPublication.Unauthenticated
            if (history.lastOrNull() != expected)
                return EffectAuthorityPublication.Refused(EffectAuthorityRefusal.STALE_PREDECESSOR)
            EffectAuthorityPolicy.transitionRefusal(expected, proposed, history.toList())?.let {
                return EffectAuthorityPublication.Refused(it)
            }
            return append(proposed)
        }
    }

    private fun model() = AuthorityModel(register).also {
        assertTrue(EffectAuthorityPolicy.acknowledges(it.bootstrap(genesis), genesis))
    }

    @Test
    fun canonicalGenesisFixtureAndFreshBytes() {
        val expected = listOf(
            "recursive-coder/effect-authority-root/v1", "host", "session", "register",
            "a".repeat(64), "1", "-", "3", "b".repeat(64), "c".repeat(64),
            "d".repeat(64), "0", "e".repeat(64), "-", "-", "-", "genesis"
        ).joinToString("\n", postfix = "\n")
        val bytes = assertNotNull(EffectAuthorityPolicy.canonicalBytes(genesis))
        assertEquals(expected, bytes.toString(Charsets.US_ASCII))
        assertEquals(
            "24f8293fa88b95bb4da18044398d2d61dbfad9334470f533a8c13d0c12f83850",
            digest(genesis)
        )
        bytes.fill(0)
        assertEquals(expected, assertNotNull(
            EffectAuthorityPolicy.canonicalBytes(genesis)
        ).toString(Charsets.US_ASCII))
        assertNotEquals(digest(started()), digest(started(op = operation.copy(
            kind = EffectKind.GIT_CAS
        ))))
    }

    @Test
    fun canonicalOperationFixturesPinEveryKindAndState() {
        // Independent literal v1 byte and digest vectors (not enum.name or
        // production digest as the expected value). Each record is a syntax
        // fixture, not a claimed authenticated chain.
        val kinds = listOf(
            EffectKind.TRUSTED_WRITE to "TRUSTED_WRITE",
            EffectKind.ISOLATED_CHECK to "ISOLATED_CHECK",
            EffectKind.GIT_CAS to "GIT_CAS",
            EffectKind.ACCEPTANCE_HANDOFF to "ACCEPTANCE_HANDOFF",
            EffectKind.RESULT_PUBLICATION to "RESULT_PUBLICATION"
        )
        val states = listOf(
            EffectAuthorityState.START_BARRIER_COMMITTED to "start-barrier-committed",
            EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED to "terminal-evidence-committed",
            EffectAuthorityState.TERMINAL_UNCONFIRMED to "terminal-unconfirmed"
        )
        val hashes = listOf(
            listOf(
                "370583c9f8843c1c872e208697a4747245e83619ba73e37fba9dae7f169d5d0d",
                "5af6c7518e2d665dd727be5d4d52abf6c8c506e0b76aec9599af676046b524f7",
                "867bdf58190e11b956b93f884a902b1a5ac5dcc84d4a89bc56850171a3216f7f",
                "82b3310f55138466c9aad5ace74f720fc3482c0778bdc21e72dac3b2b7a6bad3",
                "e027f94bcc6509f0d2f002c73f71bc081a3fdfa26f38560d9d94b96672d326b3"
            ),
            listOf(
                "8c1d7ee4782def982e8c9f45428f28566cdb6e138c728bcd44159d73687acdb7",
                "f262e9da6861115240aaf915ccc8cce89813337f8614cf73bfb26830a1b9d766",
                "102dc513c8b540d5c466a482037ffce72383b50302f769cab6b9c301d0b179c1",
                "7c76c4e91481a68e88483b2162c1e208e132f023317c4bf1c3ddb7d9a92bd927",
                "772db47fdc12c537a5769ef2cc3be0c4fa41cd8dd7edcf336bc848c86a6443c6"
            ),
            listOf(
                "d5e59dd13d77ff9b24fa3fadf33165a1e3d63ede2257c6c2cdd47eeb998b4cd4",
                "8c6b5a5e9d7542cc115ba348f1efdfa259da42b4ef8ea3f42c21bb3ec8184cd1",
                "ddade97eeec40ed868e26155b931840932bc05de22dca905b4b943a1c65771af",
                "fddf15964ebbb8417c545fedb2050061e36241d18edd72f0043748fb4d0ff2db",
                "1249945e9ac4ab23b7b160a579ff9450a477dc28bc95f1fce384cdc278a28b10"
            )
        )
        assertEquals(EffectKind.entries.toSet(), kinds.map { it.first }.toSet())
        assertEquals(EffectAuthorityState.entries.toSet() - EffectAuthorityState.GENESIS,
            states.map { it.first }.toSet())
        states.forEachIndexed { stateIndex, (state, stateToken) ->
            kinds.forEachIndexed { kindIndex, (kind, kindToken) ->
                val counter = if (stateIndex == 0) 2L else 3L
                val root = EffectAuthorityRoot(counter, "0".repeat(64), genesis.evidence.copy(
                    sequence = counter, state = state, operation = operation.copy(kind = kind)
                ))
                val expected = listOf(
                    "recursive-coder/effect-authority-root/v1", "host", "session", "register",
                    "a".repeat(64), counter.toString(), "0".repeat(64), "3",
                    "b".repeat(64), "c".repeat(64), "d".repeat(64), counter.toString(),
                    "e".repeat(64), "operation-one", kindToken, "f".repeat(64), stateToken
                ).joinToString("\n", postfix = "\n")
                assertEquals(expected, assertNotNull(
                    EffectAuthorityPolicy.canonicalBytes(root)
                ).toString(Charsets.US_ASCII))
                assertEquals(hashes[stateIndex][kindIndex], digest(root))
            }
        }
    }

    @Test
    fun validPublicationAndLegitimateTerminalTransition() {
        val store = model()
        val first = started()
        val receipt = terminal(first)
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(genesis, first), first))
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(first, receipt), receipt))
        val second = started(receipt, operation.copy(operationId = "operation-two"))
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(receipt, second), second))
        assertEquals(4, store.versionCount())
        assertEquals(EffectAuthorityRead.Authenticated(second), store.read(register))
        assertEquals(
            EffectAuthorityPublication.Refused(EffectAuthorityRefusal.ALREADY_INITIALIZED),
            store.bootstrap(genesis)
        )
    }

    @Test
    fun malformedEvidenceHasNoEncoding() {
        val e = genesis.evidence
        val bad = listOf(
            genesis.copy(version = 0),
            genesis.copy(predecessorSha256 = hash('0')),
            genesis.copy(version = 2),
            genesis.copy(evidence = e.copy(register = register.copy(epoch = "bad"))),
            genesis.copy(evidence = e.copy(register = register.copy(hostId = "host\nother"))),
            genesis.copy(evidence = e.copy(register = register.copy(sessionId = "/secret/path"))),
            genesis.copy(evidence = e.copy(fenceGeneration = 0)),
            genesis.copy(evidence = e.copy(fenceAnchorSha256 = hash('A'))),
            genesis.copy(evidence = e.copy(checkpointSha256 = "bad")),
            genesis.copy(evidence = e.copy(sidecarPathSha256 = "/secret/path")),
            genesis.copy(evidence = e.copy(headMacSha256 = "")),
            genesis.copy(evidence = e.copy(sequence = -1)),
            genesis.copy(evidence = e.copy(sequence = 1)),
            genesis.copy(evidence = e.copy(operation = operation)),
            started().let { it.copy(evidence = it.evidence.copy(operation = null)) },
            started().let {
                it.copy(evidence = it.evidence.copy(operation = operation.copy(sessionId = "foreign")))
            },
            started().let {
                it.copy(evidence = it.evidence.copy(operation = operation.copy(fenceGeneration = 4)))
            },
            started().let {
                it.copy(evidence = it.evidence.copy(operation = operation.copy(inputSha256 = "bad")))
            }
        )
        bad.forEach {
            assertFalse(EffectAuthorityPolicy.valid(it))
            assertNull(EffectAuthorityPolicy.canonicalBytes(it))
            assertNull(EffectAuthorityPolicy.digest(it))
        }
    }

    @Test
    fun rejectsForksAndEvidenceSubstitutionWithoutMutation() {
        val store = model()
        val first = started()
        val e = first.evidence
        val bad = listOf(
            first.copy(version = 3),
            first.copy(predecessorSha256 = hash('0')),
            first.copy(evidence = e.copy(register = register.copy(epoch = hash('0')))),
            first.copy(evidence = e.copy(register = register.copy(registerId = "other"))),
            first.copy(evidence = e.copy(register = register.copy(hostId = "other"))),
            first.copy(evidence = e.copy(fenceAnchorSha256 = hash('0'))),
            first.copy(evidence = e.copy(sidecarPathSha256 = hash('0'))),
            first.copy(evidence = e.copy(checkpointSha256 = genesis.evidence.checkpointSha256)),
            first.copy(evidence = e.copy(headMacSha256 = genesis.evidence.headMacSha256)),
            first.copy(evidence = e.copy(sequence = 3)),
            terminal(first).copy(version = 2, predecessorSha256 = digest(genesis))
        )
        bad.forEachIndexed { index, candidate ->
            val reason = if (index < 2) EffectAuthorityRefusal.STALE_PREDECESSOR
            else EffectAuthorityRefusal.INVALID_TRANSITION
            assertEquals(EffectAuthorityPublication.Refused(reason),
                store.compareAndSet(genesis, candidate), "candidate $index")
            assertEquals(1, store.versionCount())
        }
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(genesis, first), first))
        assertEquals(
            EffectAuthorityPublication.Refused(EffectAuthorityRefusal.STALE_PREDECESSOR),
            store.compareAndSet(genesis, started(op = operation.copy(operationId = "fork")))
        )
        val receipt = terminal(first)
        val wrongOperation = receipt.copy(evidence = receipt.evidence.copy(
            operation = operation.copy(inputSha256 = hash('0'))
        ))
        assertEquals(
            EffectAuthorityPublication.Refused(EffectAuthorityRefusal.INVALID_TRANSITION),
            store.compareAndSet(first, wrongOperation)
        )
        assertEquals(2, store.versionCount())
    }

    @Test
    fun refusesOperationReuseEvenAfterOtherOperations() {
        val store = model()
        val first = started()
        val one = terminal(first)
        val second = started(one, operation.copy(operationId = "operation-two"))
        val two = terminal(second)
        listOf(genesis to first, first to one, one to second, second to two).forEach {
            assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(it.first, it.second), it.second))
        }
        val fresh = started(two, operation.copy(operationId = "fresh"))
        val oldCheckpoint = fresh.copy(evidence = fresh.evidence.copy(
            checkpointSha256 = first.evidence.checkpointSha256
        ))
        val oldHead = fresh.copy(evidence = fresh.evidence.copy(
            headMacSha256 = first.evidence.headMacSha256
        ))
        listOf(oldCheckpoint, oldHead).forEach {
            assertEquals(
                EffectAuthorityPublication.Refused(EffectAuthorityRefusal.INVALID_TRANSITION),
                store.compareAndSet(two, it)
            )
        }
        val reuse = started(two, operation.copy(kind = EffectKind.GIT_CAS))
        assertEquals(
            EffectAuthorityPublication.Refused(EffectAuthorityRefusal.REUSED_OPERATION),
            store.compareAndSet(two, reuse)
        )
        assertEquals(5, store.versionCount())
    }

    @Test
    fun generationChangeOnlyAllowsImmediateGenesisSuccessor() {
        val next = successor(genesis, EffectAuthorityState.GENESIS, null, 0, '1', '2')
        val e = next.evidence.copy(
            fenceGeneration = 4, fenceAnchorSha256 = hash('5'), sidecarPathSha256 = hash('6')
        )
        val valid = next.copy(evidence = e)
        assertNull(EffectAuthorityPolicy.transitionRefusal(genesis, valid, listOf(genesis)))
        listOf(2L, 3L, 5L).forEach {
            assertEquals(
                EffectAuthorityRefusal.INVALID_TRANSITION,
                EffectAuthorityPolicy.transitionRefusal(genesis, valid.copy(
                    evidence = e.copy(fenceGeneration = it)
                ), listOf(genesis))
            )
        }
        val first = started()
        val nonempty = valid.copy(version = 3, predecessorSha256 = digest(first))
        assertEquals(EffectAuthorityRefusal.INVALID_TRANSITION,
            EffectAuthorityPolicy.transitionRefusal(first, nonempty, listOf(genesis, first)))
    }

    @Test
    fun versionAndSequenceOverflowFailClosed() {
        val max = genesis.copy(version = Long.MAX_VALUE, predecessorSha256 = hash('0'))
        assertTrue(EffectAuthorityPolicy.valid(max))
        val wrapped = started().copy(version = Long.MIN_VALUE, predecessorSha256 = digest(max))
        assertEquals(
            EffectAuthorityRefusal.VERSION_EXHAUSTED,
            EffectAuthorityPolicy.transitionRefusal(max, wrapped, listOf(max))
        )
        // The sequence guard is the same one used by both transitions and
        // generation advances. Test the actual arithmetic, not malformed roots.
        assertTrue(EffectAuthorityPolicy.advancesSequence(Long.MAX_VALUE - 2, Long.MAX_VALUE, 2))
        assertTrue(EffectAuthorityPolicy.advancesSequence(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1))
        assertFalse(EffectAuthorityPolicy.advancesSequence(Long.MAX_VALUE - 1, Long.MAX_VALUE, 2))
        assertFalse(EffectAuthorityPolicy.advancesSequence(Long.MAX_VALUE, Long.MIN_VALUE, 1))
        assertFalse(EffectAuthorityPolicy.advancesSequence(Long.MAX_VALUE, Long.MAX_VALUE, 1))
        assertFalse(EffectAuthorityPolicy.advancesSequence(Long.MAX_VALUE, 0, 2))
    }

    @Test
    fun rejectsIncompleteDisconnectedAndInvalidHistoricalTransitions() {
        val first = started()
        val receipt = terminal(first)
        val second = started(receipt, operation.copy(operationId = "operation-two"))
        val latest = terminal(second)
        val reuse = started(latest)
        val full = listOf(genesis, first, receipt, second, latest)
        assertEquals(EffectAuthorityRefusal.REUSED_OPERATION,
            EffectAuthorityPolicy.transitionRefusal(latest, reuse, full))
        listOf(listOf(latest), full.drop(1), full.filter { it != receipt },
            listOf(genesis, receipt, first, second, latest)).forEach {
            assertEquals(EffectAuthorityRefusal.INVALID_HISTORY,
                EffectAuthorityPolicy.transitionRefusal(latest, reuse, it))
        }
        assertEquals(EffectAuthorityRefusal.STALE_PREDECESSOR,
            EffectAuthorityPolicy.transitionRefusal(latest, reuse, full.dropLast(1)))
        assertEquals(EffectAuthorityRefusal.STALE_PREDECESSOR,
            EffectAuthorityPolicy.transitionRefusal(latest, reuse, emptyList()))

        // Recompute all links after changing the first transition: continuity
        // alone is insufficient to accept a structurally illegal history.
        val invalidStart = first.copy(evidence = first.evidence.copy(sequence = 3))
        val invalidReceipt = terminal(invalidStart)
        val candidate = started(invalidReceipt, operation.copy(operationId = "fresh"))
        assertEquals(EffectAuthorityRefusal.INVALID_HISTORY,
            EffectAuthorityPolicy.transitionRefusal(
                invalidReceipt, candidate, listOf(genesis, invalidStart, invalidReceipt)
            ))
        val foreign = first.copy(evidence = first.evidence.copy(
            register = register.copy(epoch = hash('0'))
        ))
        val foreignReceipt = terminal(foreign)
        assertEquals(EffectAuthorityRefusal.INVALID_HISTORY,
            EffectAuthorityPolicy.transitionRefusal(
                foreign, foreignReceipt, listOf(genesis, foreign)
            ))
        val broken = first.copy(predecessorSha256 = hash('0'))
        assertEquals(EffectAuthorityRefusal.INVALID_HISTORY,
            EffectAuthorityPolicy.transitionRefusal(
                broken, terminal(broken), listOf(genesis, broken)
            ))

        val duplicate = started(receipt)
        assertEquals(EffectAuthorityRefusal.INVALID_HISTORY,
            EffectAuthorityPolicy.transitionRefusal(
                duplicate, terminal(duplicate), listOf(genesis, first, receipt, duplicate)
            ))
        val repeatedHead = second.copy(evidence = second.evidence.copy(
            headMacSha256 = genesis.evidence.headMacSha256
        ))
        assertEquals(EffectAuthorityRefusal.INVALID_HISTORY,
            EffectAuthorityPolicy.transitionRefusal(
                repeatedHead, terminal(repeatedHead), listOf(genesis, first, receipt, repeatedHead)
            ))
    }

    @Test
    fun generationTransitionsRequireNeverUsedAnchorAndPath() {
        val next = successor(genesis, EffectAuthorityState.GENESIS, null, 0, '1', '2')
        val valid = next.copy(evidence = next.evidence.copy(
            fenceGeneration = 4, fenceAnchorSha256 = hash('5'), sidecarPathSha256 = hash('6')
        ))
        val store = model()
        listOf(valid.evidence.copy(fenceAnchorSha256 = genesis.evidence.fenceAnchorSha256),
            valid.evidence.copy(sidecarPathSha256 = genesis.evidence.sidecarPathSha256)).forEach {
            assertEquals(EffectAuthorityPublication.Refused(EffectAuthorityRefusal.INVALID_TRANSITION),
                store.compareAndSet(genesis, valid.copy(evidence = it)))
        }
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(genesis, valid), valid))
        val third = successor(valid, EffectAuthorityState.GENESIS, null, 0, '3', '4')
        val fresh = third.copy(evidence = third.evidence.copy(
            fenceGeneration = 5, fenceAnchorSha256 = hash('7'), sidecarPathSha256 = hash('8')
        ))
        listOf(fresh.evidence.copy(fenceAnchorSha256 = genesis.evidence.fenceAnchorSha256),
            fresh.evidence.copy(sidecarPathSha256 = genesis.evidence.sidecarPathSha256)).forEach {
            assertEquals(EffectAuthorityPublication.Refused(EffectAuthorityRefusal.INVALID_TRANSITION),
                store.compareAndSet(valid, fresh.copy(evidence = it)))
        }
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(valid, fresh), fresh))
        assertEquals(3, store.versionCount())
    }

    @Test
    fun sameGenerationTransitionMatrix() {
        val first = started()
        val receipt = terminal(first)
        val uncertain = terminal(first, uncertain = true)
        val histories = listOf(
            listOf(genesis), listOf(genesis, first),
            listOf(genesis, first, receipt), listOf(genesis, first, uncertain)
        )
        val freshOperation = operation.copy(operationId = "new")
        for (history in histories) {
            val old = history.last()
            val from = old.evidence.state
            for (to in EffectAuthorityState.entries) {
                val candidate = when (to) {
                    EffectAuthorityState.GENESIS -> successor(old, to, null, 0, '7', '8')
                    EffectAuthorityState.START_BARRIER_COMMITTED -> successor(
                        old, to, freshOperation, old.evidence.sequence + 2, '7', '8'
                    )
                    EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED,
                    EffectAuthorityState.TERMINAL_UNCONFIRMED -> successor(
                        old, to, old.evidence.operation ?: operation,
                        maxOf(3L, old.evidence.sequence + 1), '7', '8'
                    )
                }
                assertTrue(EffectAuthorityPolicy.valid(candidate))
                val allowed = when (from) {
                    EffectAuthorityState.GENESIS,
                    EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED ->
                        to == EffectAuthorityState.START_BARRIER_COMMITTED
                    EffectAuthorityState.START_BARRIER_COMMITTED ->
                        to == EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED ||
                            to == EffectAuthorityState.TERMINAL_UNCONFIRMED
                    EffectAuthorityState.TERMINAL_UNCONFIRMED -> false
                }
                assertEquals(if (allowed) null else EffectAuthorityRefusal.INVALID_TRANSITION,
                    EffectAuthorityPolicy.transitionRefusal(old, candidate, history), "$from -> $to")
            }
        }
    }

    @Test
    fun invalidAuthenticatedRootCannotMatchLocalEvidence() {
        val invalid = genesis.copy(version = 0)
        assertFalse(EffectAuthorityPolicy.matchesLocal(
            EffectAuthorityRead.Authenticated(invalid), invalid.evidence
        ))
    }

    @Test
    fun competingCasHasExactlyOneWinner() {
        val store = model()
        val candidates = listOf(started(), started(op = operation.copy(operationId = "other")))
        val ready = CountDownLatch(candidates.size)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(candidates.size)
        try {
            val futures = candidates.map { candidate ->
                pool.submit<EffectAuthorityPublication> {
                    ready.countDown()
                    check(go.await(10, TimeUnit.SECONDS))
                    store.compareAndSet(genesis, candidate)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is EffectAuthorityPublication.DurableAcknowledgement })
            assertEquals(1, results.count {
                it == EffectAuthorityPublication.Refused(EffectAuthorityRefusal.STALE_PREDECESSOR)
            })
            val winner = candidates[results.indexOfFirst {
                it is EffectAuthorityPublication.DurableAcknowledgement
            }]
            assertEquals(EffectAuthorityRead.Authenticated(winner), store.read(register))
            assertEquals(2, store.versionCount())
        } finally {
            go.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun lostAcknowledgementBeforeAndAfterCommitNeverBecomesAcknowledged() {
        listOf(Fault.BEFORE_COMMIT, Fault.AFTER_COMMIT).forEach { fault ->
            val store = model()
            val proposed = started()
            store.inject(fault)
            val result = store.compareAndSet(genesis, proposed)
            assertEquals(EffectAuthorityPublication.Uncertain, result)
            val read = store.read(register)
            val latest = if (fault == Fault.AFTER_COMMIT) proposed else genesis
            assertEquals(EffectAuthorityRead.Authenticated(latest), read)
            assertTrue(EffectAuthorityPolicy.matchesLocal(read, latest.evidence))
            // Even an exact readback of the proposed root is not an ack.
            assertFalse(EffectAuthorityPolicy.acknowledges(result, proposed))
            assertEquals(EffectAuthorityPublicationDisposition.QUARANTINED,
                EffectAuthorityPolicy.disposition(result, proposed))
            assertEquals(if (fault == Fault.AFTER_COMMIT) 2 else 1, store.versionCount())
        }
        assertEquals(EffectAuthorityPublicationDisposition.QUARANTINED,
            EffectAuthorityPolicy.disposition(
                EffectAuthorityPublication.DurableAcknowledgement(hash('0')), started()
            ))
        assertEquals(EffectAuthorityPublicationDisposition.PROVEN_REFUSAL,
            EffectAuthorityPolicy.disposition(
                EffectAuthorityPublication.Refused(EffectAuthorityRefusal.STALE_PREDECESSOR), started()
            ))
    }

    @Test
    fun lostBootstrapAcknowledgementAndResetDoNotPermitReinitialization() {
        val store = AuthorityModel(register)
        store.inject(Fault.AFTER_COMMIT)
        assertEquals(EffectAuthorityPublication.Uncertain, store.bootstrap(genesis))
        assertEquals(EffectAuthorityRead.Authenticated(genesis), store.read(register))
        store.setAvailability(Availability.RESET)
        assertEquals(EffectAuthorityRead.MissingOrReset, store.read(register))
        assertEquals(EffectAuthorityPublication.Unavailable, store.bootstrap(genesis))
        assertEquals(1, store.versionCount())
        store.setAvailability(Availability.PRESENT)
        assertEquals(
            EffectAuthorityPublication.Refused(EffectAuthorityRefusal.ALREADY_INITIALIZED),
            store.bootstrap(genesis)
        )
        assertEquals(EffectAuthorityRead.Unauthenticated, store.read(register.copy(epoch = hash('0'))))
    }

    @Test
    fun rolledBackDivergentAheadAndUnavailableLocalEvidenceRemainUnmatched() {
        val store = model()
        val first = started()
        val receipt = terminal(first)
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(genesis, first), first))
        val read = store.read(register)
        listOf(genesis.evidence, receipt.evidence, first.evidence.copy(
            checkpointSha256 = hash('0')
        ), first.evidence.copy(sidecarPathSha256 = hash('0'))).forEach {
            assertFalse(EffectAuthorityPolicy.matchesLocal(read, it))
        }
        assertTrue(EffectAuthorityPolicy.matchesLocal(read, first.evidence))
        listOf(Availability.UNAVAILABLE, Availability.UNAUTHENTICATED, Availability.RESET).forEach {
            store.setAvailability(it)
            assertFalse(EffectAuthorityPolicy.matchesLocal(store.read(register), first.evidence))
            val result = store.compareAndSet(first, receipt)
            assertFalse(EffectAuthorityPolicy.acknowledges(result, receipt))
            assertEquals(EffectAuthorityPublicationDisposition.QUARANTINED,
                EffectAuthorityPolicy.disposition(result, receipt))
            assertEquals(2, store.versionCount())
        }
    }

    @Test
    fun unconfirmedTerminalEvidenceDoesNotAllowFurtherPublication() {
        val store = model()
        val first = started()
        val uncertain = terminal(first, uncertain = true)
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(genesis, first), first))
        // A durable record of uncertainty is not a confirmed external outcome.
        assertTrue(EffectAuthorityPolicy.acknowledges(store.compareAndSet(first, uncertain), uncertain))
        val next = started(uncertain, operation.copy(operationId = "next"))
        assertEquals(
            EffectAuthorityPublication.Refused(EffectAuthorityRefusal.INVALID_TRANSITION),
            store.compareAndSet(uncertain, next)
        )
        assertEquals(3, store.versionCount())
    }
}
