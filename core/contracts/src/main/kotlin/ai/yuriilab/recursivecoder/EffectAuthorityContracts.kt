package ai.yuriilab.recursivecoder

import java.security.MessageDigest

/** Independently provisioned identity; epoch is an opaque SHA-256-sized identifier. */
data class EffectAuthorityRegister(
    val hostId: String,
    val sessionId: String,
    val registerId: String,
    val epoch: String
)

enum class EffectAuthorityState(val wireValue: String) {
    GENESIS("genesis"),
    START_BARRIER_COMMITTED("start-barrier-committed"),
    TERMINAL_EVIDENCE_COMMITTED("terminal-evidence-committed"),
    TERMINAL_UNCONFIRMED("terminal-unconfirmed")
}

/**
 * Exact local evidence to compare with the independently authenticated latest
 * root. Strings are immutable, not evidence of authentication by themselves.
 * No path, secret, external result or exception payload belongs in this value.
 */
data class EffectAuthorityEvidence(
    val register: EffectAuthorityRegister,
    val fenceGeneration: Long,
    val fenceAnchorSha256: String,
    val checkpointSha256: String,
    val sidecarPathSha256: String,
    val sequence: Long,
    val headMacSha256: String,
    val operation: EffectIdentity?,
    val state: EffectAuthorityState
)

/** Immutable version of the independent register, not a session wire record. */
data class EffectAuthorityRoot(
    val version: Long,
    val predecessorSha256: String?,
    val evidence: EffectAuthorityEvidence
)

enum class EffectAuthorityRefusal {
    INVALID_RECORD,
    INVALID_HISTORY,
    STALE_PREDECESSOR,
    INVALID_TRANSITION,
    REUSED_OPERATION,
    VERSION_EXHAUSTED,
    ALREADY_INITIALIZED
}

sealed interface EffectAuthorityRead {
    data class Authenticated(val root: EffectAuthorityRoot) : EffectAuthorityRead
    data object MissingOrReset : EffectAuthorityRead
    data object Unavailable : EffectAuthorityRead
    data object Unauthenticated : EffectAuthorityRead
}

sealed interface EffectAuthorityPublication {
    /** Exact proposed digest, returned only after irreversible durable commit. */
    data class DurableAcknowledgement(val rootSha256: String) : EffectAuthorityPublication
    /** The authority proved this invocation did not mutate anything. */
    data class Refused(val reason: EffectAuthorityRefusal) : EffectAuthorityPublication
    /** No authenticated outcome; not proof of non-mutation. */
    data object Unavailable : EffectAuthorityPublication
    data object Unauthenticated : EffectAuthorityPublication
    /** The invocation may or may not have committed. */
    data object Uncertain : EffectAuthorityPublication
}

/** Evidence classification only; even a durable ack does not authorize execution. */
enum class EffectAuthorityPublicationDisposition {
    ACKNOWLEDGED_EVIDENCE,
    PROVEN_REFUSAL,
    QUARANTINED
}

/**
 * Host-owned port. The standalone adapter lives in infra/journal. An adapter must
 * authenticate requests, responses and the complete immutable history, bind
 * the independently provisioned host/session/register/epoch, isolate write
 * credentials from the journal HMAC key and agent UID, and retain history
 * irreversibly outside the local filesystem/snapshot rollback domain.
 *
 * Bootstrap requires independently authenticated provisioning that proves
 * this exact session register has NEVER been initialized. Absence on read
 * is not provisioning, and reset/replacement or epoch rotation is forbidden.
 * Reads must be linearizable and authenticate the actual latest root; no
 * cache or caller-supplied record may substitute for it.
 *
 * CAS must atomically authenticate and compare the exact expected predecessor
 * against the latest root, validate the proposed successor and enforce
 * operation uniqueness over the entire session history, then append exactly
 * one version. The trusted publication boundary must authenticate the exact
 * physical checkpoint, RCEFX1 prefix and effective issuing fence/anchor;
 * a valid digest or numeric generation alone is not that proof. Refusal
 * requires proof of no mutation by this invocation.
 * A durable acknowledgement requires independently durable commit and an
 * authenticated response bound to the invocation and exact proposed digest.
 * Timeout, interrupt, lost response and escaped failure are unconfirmed;
 * the host must quarantine the invocation, never retry or execute an effect
 * automatically. Later readback is not the original acknowledgement.
 *
 * All results are evidence only. This port grants no execution, recovery,
 * checkpoint acceptance, retry, or successor lease authority.
 */
interface HostEffectAntiRollbackAuthority {
    fun bootstrap(initial: EffectAuthorityRoot): EffectAuthorityPublication
    fun read(register: EffectAuthorityRegister): EffectAuthorityRead
    fun compareAndSet(
        expected: EffectAuthorityRoot,
        proposed: EffectAuthorityRoot
    ): EffectAuthorityPublication
}

/** Pure syntax, canonical digest and conservative publication transition rules. */
object EffectAuthorityPolicy {
    private const val DOMAIN = "recursive-coder/effect-authority-root/v1"
    private const val FIRST_VERSION = 1L
    private val sha256 = Regex("[0-9a-f]{64}")

    private fun validRegister(register: EffectAuthorityRegister): Boolean =
        ToolAuthorizationPolicy.validHostIdentifier(register.hostId) &&
            ToolAuthorizationPolicy.validHostIdentifier(register.sessionId) &&
            ToolAuthorizationPolicy.validHostIdentifier(register.registerId) &&
            sha256.matches(register.epoch)

    fun valid(root: EffectAuthorityRoot): Boolean {
        val e = root.evidence
        val op = e.operation
        if (!validRegister(e.register) || root.version < FIRST_VERSION ||
            (root.version == FIRST_VERSION) != (root.predecessorSha256 == null) ||
            (root.predecessorSha256 != null && !sha256.matches(root.predecessorSha256)) ||
            e.fenceGeneration <= 0L || e.sequence < 0L ||
            !sha256.matches(e.fenceAnchorSha256) ||
            !sha256.matches(e.checkpointSha256) ||
            !sha256.matches(e.sidecarPathSha256) ||
            !sha256.matches(e.headMacSha256)
        ) return false
        if (e.state == EffectAuthorityState.GENESIS) {
            return op == null && e.sequence == 0L
        }
        if (root.version == FIRST_VERSION || op == null ||
            op.sessionId != e.register.sessionId ||
            op.fenceGeneration != e.fenceGeneration ||
            !ToolAuthorizationPolicy.validHostIdentifier(op.operationId) ||
            !sha256.matches(op.inputSha256)
        ) return false
        return when (e.state) {
            EffectAuthorityState.GENESIS -> false
            EffectAuthorityState.START_BARRIER_COMMITTED -> e.sequence >= 2L
            EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED,
            EffectAuthorityState.TERMINAL_UNCONFIRMED -> e.sequence >= 3L
        }
    }

    /**
     * Canonical v1: fixed-order ASCII lines, final LF, decimal positive
     * counters (sequence may be zero), lower-case hex digests, explicit
     * state and kind tokens. A dash denotes absent predecessor/operation
     * fields; identifiers cannot contain LF or equal a dash. Each invocation
     * produces fresh bytes. Invalid records have no canonical encoding.
     */
    fun canonicalBytes(root: EffectAuthorityRoot): ByteArray? {
        if (!valid(root)) return null
        val e = root.evidence
        val op = e.operation
        // Reuse the exact RCEFX1/envelope EffectKind vocabulary.
        val kind = op?.kind?.name ?: "-"
        return listOf(
            DOMAIN, e.register.hostId, e.register.sessionId,
            e.register.registerId, e.register.epoch, root.version.toString(),
            root.predecessorSha256 ?: "-", e.fenceGeneration.toString(),
            e.fenceAnchorSha256, e.checkpointSha256, e.sidecarPathSha256,
            e.sequence.toString(), e.headMacSha256, op?.operationId ?: "-",
            kind, op?.inputSha256 ?: "-", e.state.wireValue
        ).joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
    }

    fun digest(root: EffectAuthorityRoot): String? =
        canonicalBytes(root)?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

    fun validBootstrap(root: EffectAuthorityRoot): Boolean =
        valid(root) && root.version == FIRST_VERSION &&
            root.evidence.state == EffectAuthorityState.GENESIS

    /**
     * The adapter calls this inside the same atomic transaction as its
     * authenticated latest-root comparison and immutable append.
     * [history] must contain every version from bootstrap to [previous].
     * This policy independently checks structural continuity and all historic
     * transitions; it cannot authenticate provenance or prove latestness.
     * The adapter must provide a stable, authenticated snapshot.
     * Null means structurally valid, never permission to perform an effect.
     */
    fun transitionRefusal(
        previous: EffectAuthorityRoot,
        next: EffectAuthorityRoot,
        history: List<EffectAuthorityRoot>
    ): EffectAuthorityRefusal? {
        if (!valid(previous)) return EffectAuthorityRefusal.INVALID_RECORD
        // Diagnose exhaustion even if the attempted version wrapped negative.
        if (previous.version == Long.MAX_VALUE) return EffectAuthorityRefusal.VERSION_EXHAUSTED
        if (!valid(next)) return EffectAuthorityRefusal.INVALID_RECORD
        val snapshot = history.toList()
        if (snapshot.lastOrNull() != previous) return EffectAuthorityRefusal.STALE_PREDECESSOR
        if (snapshot.size.toLong() != previous.version ||
            !validBootstrap(snapshot.first())
        ) return EffectAuthorityRefusal.INVALID_HISTORY

        val index = HistoryIndex()
        index.record(snapshot.first())
        for (i in 1 until snapshot.size) {
            val current = snapshot[i]
            if (stepRefusal(snapshot[i - 1], current, index) != null)
                return EffectAuthorityRefusal.INVALID_HISTORY
            index.record(current)
        }
        return stepRefusal(previous, next, index)
    }

    // Build the uniqueness index only from roots already validated in order.
    // Linear history traversal; do not recursively revalidate every prefix.
    private class HistoryIndex {
        val checkpoints = mutableSetOf<String>()
        val heads = mutableSetOf<String>()
        val anchors = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        val operations = mutableSetOf<String>()

        fun record(root: EffectAuthorityRoot) {
            val e = root.evidence
            checkpoints.add(e.checkpointSha256)
            heads.add(e.headMacSha256)
            anchors.add(e.fenceAnchorSha256)
            paths.add(e.sidecarPathSha256)
            e.operation?.let { operations.add(it.operationId) }
        }
    }

    // Difference of ordered nonnegative Longs cannot overflow. Kept internal
    // so boundary arithmetic can be tested without fabricating huge histories.
    internal fun advancesSequence(previous: Long, next: Long, count: Long): Boolean =
        previous >= 0L && next >= previous && count > 0L && next - previous == count

    private fun stepRefusal(
        previous: EffectAuthorityRoot,
        next: EffectAuthorityRoot,
        index: HistoryIndex
    ): EffectAuthorityRefusal? {
        if (!valid(next)) return EffectAuthorityRefusal.INVALID_RECORD
        if (previous.version == Long.MAX_VALUE) return EffectAuthorityRefusal.VERSION_EXHAUSTED
        if (next.version != previous.version + 1L ||
            next.predecessorSha256 != digest(previous)
        ) return EffectAuthorityRefusal.STALE_PREDECESSOR

        val old = previous.evidence
        val new = next.evidence
        if (old.register != new.register ||
            new.checkpointSha256 in index.checkpoints ||
            new.headMacSha256 in index.heads
        ) return EffectAuthorityRefusal.INVALID_TRANSITION

        // Only immediate genesis-to-genesis successors are supported.
        // Both identities must be fresh across ALL generations.
        if (old.fenceGeneration != new.fenceGeneration) {
            return if (advancesSequence(old.fenceGeneration, new.fenceGeneration, 1L) &&
                old.state == EffectAuthorityState.GENESIS &&
                new.state == EffectAuthorityState.GENESIS &&
                new.fenceAnchorSha256 !in index.anchors &&
                new.sidecarPathSha256 !in index.paths
            ) null else EffectAuthorityRefusal.INVALID_TRANSITION
        }
        if (old.fenceAnchorSha256 != new.fenceAnchorSha256 ||
            old.sidecarPathSha256 != new.sidecarPathSha256
        ) return EffectAuthorityRefusal.INVALID_TRANSITION

        return when (old.state) {
            EffectAuthorityState.GENESIS,
            EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED -> {
                if (new.state != EffectAuthorityState.START_BARRIER_COMMITTED ||
                    !advancesSequence(old.sequence, new.sequence, 2L)
                ) EffectAuthorityRefusal.INVALID_TRANSITION
                else if (checkNotNull(new.operation).operationId in index.operations)
                    EffectAuthorityRefusal.REUSED_OPERATION
                else null
            }
            EffectAuthorityState.START_BARRIER_COMMITTED -> {
                if (new.operation != old.operation ||
                    (new.state != EffectAuthorityState.TERMINAL_EVIDENCE_COMMITTED &&
                        new.state != EffectAuthorityState.TERMINAL_UNCONFIRMED) ||
                    !advancesSequence(old.sequence, new.sequence, 1L)
                ) EffectAuthorityRefusal.INVALID_TRANSITION else null
            }
            EffectAuthorityState.TERMINAL_UNCONFIRMED -> EffectAuthorityRefusal.INVALID_TRANSITION
        }
    }

    /** Classify this invocation's result; never reconstruct it from readback. */
    fun disposition(
        result: EffectAuthorityPublication,
        proposed: EffectAuthorityRoot
    ): EffectAuthorityPublicationDisposition = when {
        result is EffectAuthorityPublication.DurableAcknowledgement &&
            digest(proposed) == result.rootSha256 ->
            EffectAuthorityPublicationDisposition.ACKNOWLEDGED_EVIDENCE
        result is EffectAuthorityPublication.Refused ->
            EffectAuthorityPublicationDisposition.PROVEN_REFUSAL
        else -> EffectAuthorityPublicationDisposition.QUARANTINED
    }

    fun acknowledges(result: EffectAuthorityPublication, proposed: EffectAuthorityRoot): Boolean =
        disposition(result, proposed) == EffectAuthorityPublicationDisposition.ACKNOWLEDGED_EVIDENCE

    /**
     * Exact evidence equality is diagnostic only. A mismatch in either
     * direction, invalid root or non-authenticated read remains quarantined.
     * Even a match cannot resolve a lost publication acknowledgement.
     */
    fun matchesLocal(read: EffectAuthorityRead, local: EffectAuthorityEvidence): Boolean =
        read is EffectAuthorityRead.Authenticated && valid(read.root) && read.root.evidence == local
}
