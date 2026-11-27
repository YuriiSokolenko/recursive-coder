package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/**
 * An RCEFX1 journal whose pin was checked against a freshly inspected
 * checkpoint/fence/anchor root by SessionCheckpointStore. It is NOT an
 * independent anti-rollback root or permission to execute/replay effects.
 *
 * No public constructor or factory: only the private store implementation
 * can mint this type. Raw MAC-valid journals and diagnostic self-pins do not
 * implement it, and cannot enter authority-relevant projections.
 */
sealed interface CheckpointAuthenticatedEffectJournal {
    val physical: VerifiedEffectRecordJournal
}

/**
 * Host-only metadata. No model text, tool responses, file contents, credentials or
 * arbitrary extension map are supported by the checkpoint format.
 */
data class CheckpointBinding(
    val policySha256: String,
    val workspaceIdentitySha256: String,
    val gitHead: String,
    val gitBranch: String,
    val gitBaselineSha256: String,
    val graphId: String,
    val graphGeneration: Long,
    val frozenInputsSha256: String
)

data class CheckpointChild(val id: String, val parentId: String, val state: String)
data class CheckpointArtifact(val id: String, val ownerId: String, val sha256: String)
/**
 * Host-asserted accounting; "known" means known to the host, NOT independently
 * reconciled against journal records. Never authorize quota or replay from it.
 */
data class HostAssertedCheckpointUsage(
    val scopeId: String,
    val kind: String,
    val certainty: String,
    val units: Long?
)
data class CheckpointEffect(val id: String, val correlationId: String, val state: String)

/** Exact issuing fence and HMAC head, co-published in a canonical checkpoint. */
data class CheckpointEffectJournalHead(
    val sessionId: String,
    val fenceGeneration: Long,
    val admittedCheckpointSha256: String,
    val toolAuthoritySha256: String,
    val sidecarPathSha256: String,
    val lastSequence: Long,
    val headMacSha256: String
) {
    fun expectation() = EffectJournalExpectation(sessionId, fenceGeneration,
        admittedCheckpointSha256, toolAuthoritySha256)
    fun pin() = EffectJournalHeadPin(lastSequence, headMacSha256)
}

data class SessionCheckpoint(
    val sequence: Long,
    val previousSha256: String?,
    val binding: CheckpointBinding,
    val sessionId: String,
    val traceId: String,
    val phase: String,
    val taskId: String,
    val taskAttempt: Int,
    val taskState: String,
    val children: List<CheckpointChild>,
    val acceptedArtifacts: List<CheckpointArtifact>,
    val hostAssertedUsage: List<HostAssertedCheckpointUsage>,
    val pendingEffects: List<CheckpointEffect>,
    val journalSequence: Int,
    val journalHeadSha256: String,
    val journalFileSha256: String,
    val lastTrustedJournalOffset: Long,
    /** Non-null selects the canonical v2 payload; legacy v1 stays unchanged. */
    val effectJournalHead: CheckpointEffectJournalHead? = null,
    /** Operator-authenticated genesis-only transition; canonical v3. */
    val effectReconciliation: CheckpointEffectReconciliation? = null
)

data class VerifiedSessionCheckpoint(
    val snapshot: SessionCheckpoint,
    val sha256: String,
    /** Read-only evidence, never admission to repeat a model/tool operation. */
    val reconciliationRequired: Boolean
) {
    /** Numeric usage claims are never independently verified against ledger receipts. */
    val usageLedgerVerified: Boolean get() = false
    val resumeAllowed: Boolean get() = false
}

class CheckpointUnconfirmed(val code: String, cause: Throwable? = null) :
    IllegalStateException(code, cause)

/**
 * The caller must derive these facts from trusted host policy, Git inspection and
 * workspace identity, never from model-generated JSON or a checkpoint on disk.
 */
data class CheckpointHostContext(
    val workspaceRoot: Path,
    val policySha256: String,
    val gitHead: String,
    val gitBranch: String,
    val gitBaselineSha256: String,
    val graphId: String,
    val graphGeneration: Long,
    val frozenInputsSha256: String
) {
    fun binding(): CheckpointBinding {
        val canonical = try { workspaceRoot.toRealPath() }
            catch (e: Exception) { throw CheckpointUnconfirmed("CHECKPOINT_WORKSPACE_INVALID", e) }
        return CheckpointBinding(policySha256,
            SessionCheckpointCodec.sha256(canonical.toString().toByteArray(StandardCharsets.UTF_8)),
            gitHead, gitBranch, gitBaselineSha256, graphId, graphGeneration, frozenInputsSha256)
    }
}

/** Pure deterministic codec, with exact field sets and byte-for-byte canonicality. */
object SessionCheckpointCodec {
    const val MAX_PAYLOAD = 512 * 1024

    // Payload versions are independent of the physical RCCHK1 framing and protocol v1.
    private const val BASE_VERSION = 1L
    private const val EFFECT_HEAD_VERSION = 2L
    private const val RECONCILIATION_VERSION = 3L

    private object BindingFields {
        const val POLICY_SHA256 = "policySha256"
        const val WORKSPACE_IDENTITY_SHA256 = "workspaceIdentitySha256"
        const val GIT_HEAD = "gitHead"
        const val GIT_BRANCH = "gitBranch"
        const val GIT_BASELINE_SHA256 = "gitBaselineSha256"
        const val GRAPH_ID = "graphId"
        const val GRAPH_GENERATION = "graphGeneration"
        const val FROZEN_INPUTS_SHA256 = "frozenInputsSha256"
    }

    private object ChildFields {
        const val ID = "id"
        const val PARENT_ID = "parentId"
        const val STATE = "state"
    }

    private object ArtifactFields {
        const val ID = "id"
        const val OWNER_ID = "ownerId"
        const val SHA256 = "sha256"
    }

    private object UsageFields {
        const val SCOPE_ID = "scopeId"
        const val KIND = "kind"
        const val CERTAINTY = "certainty"
        const val UNITS = "units"
    }

    private object PendingEffectFields {
        const val ID = "id"
        const val CORRELATION_ID = "correlationId"
        const val STATE = "state"
    }

    private object HeadFields {
        const val SESSION_ID = "sessionId"
        const val FENCE_GENERATION = "fenceGeneration"
        const val ADMITTED_CHECKPOINT_SHA256 = "admittedCheckpointSha256"
        const val TOOL_AUTHORITY_SHA256 = "toolAuthoritySha256"
        const val SIDECAR_PATH_SHA256 = "sidecarPathSha256"
        const val LAST_SEQUENCE = "lastSequence"
        const val HEAD_MAC_SHA256 = "headMacSha256"
    }

    private object ReconciliationFields {
        const val SOURCE_CHECKPOINT_SHA256 = "sourceCheckpointSha256"
        const val SOURCE_FENCE_GENERATION = "sourceFenceGeneration"
        const val SOURCE_FENCE_SHA256 = "sourceFenceSha256"
        const val OPERATOR_ID = "operatorId"
        const val HOST_SEAL_MAC_SHA256 = "hostSealMacSha256"
        const val SOURCE_HEAD = "sourceHead"
    }

    private object RootFields {
        const val KIND = "kind"
        const val VERSION = "version"
        const val SEQUENCE = "sequence"
        const val PREVIOUS_SHA256 = "previousSha256"
        const val BINDING = "binding"
        const val SESSION_ID = "sessionId"
        const val TRACE_ID = "traceId"
        const val PHASE = "phase"
        const val TASK_ID = "taskId"
        const val TASK_ATTEMPT = "taskAttempt"
        const val TASK_STATE = "taskState"
        const val CHILDREN = "children"
        const val ACCEPTED_ARTIFACTS = "acceptedArtifacts"
        const val HOST_ASSERTED_USAGE = "hostAssertedUsage"
        const val PENDING_EFFECTS = "pendingEffects"
        const val JOURNAL_SEQUENCE = "journalSequence"
        const val JOURNAL_HEAD_SHA256 = "journalHeadSha256"
        const val JOURNAL_FILE_SHA256 = "journalFileSha256"
        const val LAST_TRUSTED_JOURNAL_OFFSET = "lastTrustedJournalOffset"
        const val EFFECT_JOURNAL_HEAD = "effectJournalHead"
        const val EFFECT_RECONCILIATION = "effectReconciliation"
    }

    private val DIGEST = Regex("[0-9a-f]{64}")
    private val GIT_HEAD = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    private val BRANCH = Regex("refs/heads/[A-Za-z0-9._/-]{1,200}")
    // Exposed by :core:session; no independent recovery-only session phases.
    private val PHASES = SessionPhaseVocabulary.wires
    // Wire spellings mirror RecursiveNodeState and TaskState. Do not conflate
    // a recursive child with a queued task or invent recovery-only states.
    private val CHILD_STATES = setOf(CheckpointChildStates.READY,
        CheckpointChildStates.RUNNING, CheckpointChildStates.SUCCEEDED,
        CheckpointChildStates.FAILED, CheckpointChildStates.CANCELLED,
        CheckpointChildStates.TIMED_OUT)
    private val TASK_STATES = setOf(CheckpointTaskStates.CREATED, CheckpointTaskStates.READY,
        CheckpointTaskStates.BLOCKED, CheckpointTaskStates.RUNNING, CheckpointTaskStates.PAUSED,
        CheckpointTaskStates.SUCCEEDED, CheckpointTaskStates.FAILED,
        CheckpointTaskStates.NEEDS_ATTENTION, CheckpointTaskStates.CANCELLED)
    private fun invalid(): Nothing = throw CheckpointUnconfirmed("CHECKPOINT_PAYLOAD_INVALID")
    private fun validId(id: String) = ToolAuthorizationPolicy.validHostIdentifier(id)
    private fun digest(value: String) = DIGEST.matches(value)
    private fun preExecutionOnly(s: SessionCheckpoint): Boolean =
        s.phase in setOf(SessionPhaseVocabulary.createdWire, SessionPhaseVocabulary.acceptedWire) &&
            s.taskAttempt == 0 && s.pendingEffects.isEmpty()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun validate(s: SessionCheckpoint) {
        val b = s.binding
        if (s.sequence !in 1..999_999_999_999L ||
            (s.sequence == 1L) != (s.previousSha256 == null) ||
            (s.previousSha256 != null && !digest(s.previousSha256)) ||
            !listOf(b.policySha256, b.workspaceIdentitySha256, b.gitBaselineSha256,
                b.frozenInputsSha256, s.journalHeadSha256, s.journalFileSha256).all(::digest) ||
            !GIT_HEAD.matches(b.gitHead) || !BRANCH.matches(b.gitBranch) ||
            b.gitBranch.contains("..") || b.gitBranch.contains("//") ||
            b.gitBranch.endsWith("/") || b.graphGeneration < 0 ||
            !listOf(b.graphId, s.sessionId, s.traceId, s.taskId).all(::validId) ||
            s.phase !in PHASES || s.taskState !in TASK_STATES ||
            (s.phase in SessionPhaseVocabulary.terminalWires &&
                s.taskState !in setOf(CheckpointTaskStates.SUCCEEDED, CheckpointTaskStates.FAILED,
                    CheckpointTaskStates.CANCELLED, CheckpointTaskStates.NEEDS_ATTENTION)) ||
            (s.phase == SessionPhaseVocabulary.succeededWire &&
                (s.taskState != CheckpointTaskStates.SUCCEEDED ||
                    s.pendingEffects.isNotEmpty() ||
                    s.hostAssertedUsage.any { it.certainty == CheckpointUsageCertainty.UNKNOWN })) ||
            s.taskAttempt !in 0..1_000_000 ||
            s.journalSequence < 1 || s.lastTrustedJournalOffset < 7)
            invalid()

        // RecursiveNode.parentGraphId points to the host-owned root GraphId,
        // not the Task.id; task attempt is a separate checkpoint dimension.
        if (s.taskId == b.graphId) invalid() // disambiguate task vs graph usage
        val known = mutableSetOf(b.graphId)
        if (s.children.size > 10_000 || s.acceptedArtifacts.size > 10_000 ||
            s.hostAssertedUsage.size > 10_000 || s.pendingEffects.size > 10_000)
            invalid()
        if (s.children.any { !validId(it.id) || !validId(it.parentId) ||
                it.state !in CHILD_STATES || it.id == b.graphId || it.id == s.taskId } ||
            s.children.map { it.id }.toSet().size != s.children.size)
            invalid()
        val unresolved = s.children.toMutableList()
        while (unresolved.isNotEmpty()) {
            val ready = unresolved.filter { it.parentId in known }
            if (ready.isEmpty()) invalid() // orphan, cycle or self-parent
            ready.forEach { known.add(it.id) }
            unresolved.removeAll(ready.toSet())
        }
        if (s.acceptedArtifacts.any { !validId(it.id) || it.ownerId !in known ||
                !digest(it.sha256) } ||
            s.acceptedArtifacts.map { it.id }.toSet().size != s.acceptedArtifacts.size)
            invalid()
        if (s.hostAssertedUsage.any {
                !validId(it.scopeId) || (it.scopeId !in known && it.scopeId != s.taskId) ||
                    it.kind !in setOf(CheckpointUsageKinds.MODEL, CheckpointUsageKinds.TOOL) ||
                    it.certainty !in setOf(CheckpointUsageCertainty.KNOWN,
                        CheckpointUsageCertainty.UNKNOWN) ||
                    (it.certainty == CheckpointUsageCertainty.KNOWN && (it.units == null || it.units < 0)) ||
                    (it.certainty == CheckpointUsageCertainty.UNKNOWN && it.units != null)
            } || s.hostAssertedUsage.map { it.scopeId to it.kind }.toSet().size != s.hostAssertedUsage.size)
            invalid()
        if (s.pendingEffects.any { !validId(it.id) || !validId(it.correlationId) ||
                it.state !in setOf("pending", "unknown") } ||
            s.pendingEffects.map { it.id }.toSet().size != s.pendingEffects.size)
            invalid()
        s.effectJournalHead?.let { head ->
            if (head.sessionId != s.sessionId ||
                !validId(head.sessionId) ||
                head.fenceGeneration <= 0L ||
                !listOf(head.admittedCheckpointSha256, head.toolAuthoritySha256,
                    head.sidecarPathSha256, head.headMacSha256).all(::digest) ||
                head.lastSequence !in 0L..DurableEffectRecordJournal.MAX_RECORDS.toLong() ||
                // This slice only pins isolated pre-execution physical evidence.
                !preExecutionOnly(s))
                invalid()
        }
        s.effectReconciliation?.let { rec ->
            val src = rec.sourceHead
            if (!validId(rec.operatorId) ||
                !listOf(rec.sourceCheckpointSha256, rec.sourceFenceSha256,
                    rec.hostSealMacSha256).all(::digest) ||
                rec.sourceFenceGeneration !in
                    1L..ResumeFenceStorage.MAX_FENCES.toLong() ||
                src.sessionId != s.sessionId || !validId(src.sessionId) ||
                src.fenceGeneration != rec.sourceFenceGeneration ||
                src.lastSequence != 0L ||
                !listOf(src.admittedCheckpointSha256, src.toolAuthoritySha256,
                    src.sidecarPathSha256, src.headMacSha256).all(::digest) ||
                // Only v3 has a reconciliation record; it never has a
                // current head. Successor checkpoints use ordinary v1/v2.
                s.effectJournalHead != null || !preExecutionOnly(s))
                invalid()
        }
    }

    fun encode(s: SessionCheckpoint): ByteArray {
        validate(s)
        val b = s.binding
        val root = buildJsonObject {
            put(RootFields.KIND, CHECKPOINT_KIND)
            put(RootFields.VERSION, when {
                s.effectReconciliation != null -> RECONCILIATION_VERSION
                s.effectJournalHead != null -> EFFECT_HEAD_VERSION
                else -> BASE_VERSION
            })
            put(RootFields.SEQUENCE, s.sequence)
            put(RootFields.PREVIOUS_SHA256, s.previousSha256?.let(::JsonPrimitive) ?: JsonNull)
            put(RootFields.BINDING, buildJsonObject {
                put(BindingFields.POLICY_SHA256, b.policySha256)
                put(BindingFields.WORKSPACE_IDENTITY_SHA256, b.workspaceIdentitySha256)
                put(BindingFields.GIT_HEAD, b.gitHead)
                put(BindingFields.GIT_BRANCH, b.gitBranch)
                put(BindingFields.GIT_BASELINE_SHA256, b.gitBaselineSha256)
                put(BindingFields.GRAPH_ID, b.graphId)
                put(BindingFields.GRAPH_GENERATION, b.graphGeneration)
                put(BindingFields.FROZEN_INPUTS_SHA256, b.frozenInputsSha256)
            })
            put(RootFields.SESSION_ID, s.sessionId)
            put(RootFields.TRACE_ID, s.traceId)
            put(RootFields.PHASE, s.phase)
            put(RootFields.TASK_ID, s.taskId)
            put(RootFields.TASK_ATTEMPT, s.taskAttempt)
            put(RootFields.TASK_STATE, s.taskState)
            put(RootFields.CHILDREN, buildJsonArray {
                s.children.sortedBy { it.id }.forEach { child ->
                    add(buildJsonObject {
                        put(ChildFields.ID, child.id); put(ChildFields.PARENT_ID, child.parentId)
                        put(ChildFields.STATE, child.state)
                    })
                }
            })
            put(RootFields.ACCEPTED_ARTIFACTS, buildJsonArray {
                s.acceptedArtifacts.sortedBy { it.id }.forEach { artifact ->
                    add(buildJsonObject {
                        put(ArtifactFields.ID, artifact.id); put(ArtifactFields.OWNER_ID, artifact.ownerId)
                        put(ArtifactFields.SHA256, artifact.sha256)
                    })
                }
            })
            put(RootFields.HOST_ASSERTED_USAGE, buildJsonArray {
                s.hostAssertedUsage.sortedWith(compareBy({ it.scopeId }, { it.kind })).forEach { usage ->
                    add(buildJsonObject {
                        put(UsageFields.SCOPE_ID, usage.scopeId); put(UsageFields.KIND, usage.kind)
                        put(UsageFields.CERTAINTY, usage.certainty)
                        put(UsageFields.UNITS, usage.units?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            })
            put(RootFields.PENDING_EFFECTS, buildJsonArray {
                s.pendingEffects.sortedBy { it.id }.forEach { effect ->
                    add(buildJsonObject {
                        put(PendingEffectFields.ID, effect.id)
                        put(PendingEffectFields.CORRELATION_ID, effect.correlationId)
                        put(PendingEffectFields.STATE, effect.state)
                    })
                }
            })
            put(RootFields.JOURNAL_SEQUENCE, s.journalSequence)
            put(RootFields.JOURNAL_HEAD_SHA256, s.journalHeadSha256)
            put(RootFields.JOURNAL_FILE_SHA256, s.journalFileSha256)
            put(RootFields.LAST_TRUSTED_JOURNAL_OFFSET, s.lastTrustedJournalOffset)
            if (s.effectReconciliation != null && s.effectJournalHead == null)
                put(RootFields.EFFECT_JOURNAL_HEAD, JsonNull)
            s.effectJournalHead?.let { head ->
                put(RootFields.EFFECT_JOURNAL_HEAD, buildJsonObject {
                    put(HeadFields.SESSION_ID, head.sessionId)
                    put(HeadFields.FENCE_GENERATION, head.fenceGeneration)
                    put(HeadFields.ADMITTED_CHECKPOINT_SHA256, head.admittedCheckpointSha256)
                    put(HeadFields.TOOL_AUTHORITY_SHA256, head.toolAuthoritySha256)
                    put(HeadFields.SIDECAR_PATH_SHA256, head.sidecarPathSha256)
                    put(HeadFields.LAST_SEQUENCE, head.lastSequence)
                    put(HeadFields.HEAD_MAC_SHA256, head.headMacSha256)
                })
            }
            s.effectReconciliation?.let { record ->
                put(RootFields.EFFECT_RECONCILIATION, buildJsonObject {
                    put(ReconciliationFields.SOURCE_CHECKPOINT_SHA256, record.sourceCheckpointSha256)
                    put(ReconciliationFields.SOURCE_FENCE_GENERATION, record.sourceFenceGeneration)
                    put(ReconciliationFields.SOURCE_FENCE_SHA256, record.sourceFenceSha256)
                    put(ReconciliationFields.OPERATOR_ID, record.operatorId)
                    put(ReconciliationFields.HOST_SEAL_MAC_SHA256, record.hostSealMacSha256)
                    put(ReconciliationFields.SOURCE_HEAD, buildJsonObject {
                        val head = record.sourceHead
                        put(HeadFields.SESSION_ID, head.sessionId)
                        put(HeadFields.FENCE_GENERATION, head.fenceGeneration)
                        put(HeadFields.ADMITTED_CHECKPOINT_SHA256, head.admittedCheckpointSha256)
                        put(HeadFields.TOOL_AUTHORITY_SHA256, head.toolAuthoritySha256)
                        put(HeadFields.SIDECAR_PATH_SHA256, head.sidecarPathSha256)
                        put(HeadFields.LAST_SEQUENCE, head.lastSequence)
                        put(HeadFields.HEAD_MAC_SHA256, head.headMacSha256)
                    })
                })
            }
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_PAYLOAD) invalid()
        return bytes
    }

    private fun obj(v: JsonElement?): JsonObject = v as? JsonObject ?: invalid()
    private fun fields(o: JsonObject, vararg keys: String): JsonObject {
        if (o.keys != keys.toSet()) invalid()
        return o
    }
    private fun string(o: JsonObject, key: String): String {
        val p = o[key] as? JsonPrimitive ?: invalid()
        if (!p.isString) invalid()
        return p.content
    }
    private fun long(o: JsonObject, key: String): Long {
        val p = o[key] as? JsonPrimitive ?: invalid()
        if (p.isString) invalid()
        return p.longOrNull ?: invalid()
    }
    private fun array(o: JsonObject, key: String): JsonArray =
        o[key] as? JsonArray ?: invalid()
    private fun nullableString(o: JsonObject, key: String): String? =
        if (o[key] == JsonNull) null else string(o, key)

    fun decode(bytes: ByteArray): SessionCheckpoint {
        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD) invalid()
        try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val root = obj(Json.parseToJsonElement(decoded))
            val version = long(root, RootFields.VERSION)
            if (version !in setOf(BASE_VERSION, EFFECT_HEAD_VERSION, RECONCILIATION_VERSION) ||
                string(root, RootFields.KIND) != CHECKPOINT_KIND)
                throw CheckpointUnconfirmed("CHECKPOINT_VERSION_UNSUPPORTED")
            val keys = arrayOf(RootFields.KIND, RootFields.VERSION, RootFields.SEQUENCE,
                RootFields.PREVIOUS_SHA256,
                RootFields.BINDING, RootFields.SESSION_ID, RootFields.TRACE_ID, RootFields.PHASE,
                    RootFields.TASK_ID, RootFields.TASK_ATTEMPT,
                RootFields.TASK_STATE, RootFields.CHILDREN, RootFields.ACCEPTED_ARTIFACTS,
                    RootFields.HOST_ASSERTED_USAGE,
                RootFields.PENDING_EFFECTS, RootFields.JOURNAL_SEQUENCE, RootFields.JOURNAL_HEAD_SHA256,
                RootFields.JOURNAL_FILE_SHA256, RootFields.LAST_TRUSTED_JOURNAL_OFFSET)
            val o = when (version) {
                BASE_VERSION -> fields(root, *keys)
                EFFECT_HEAD_VERSION -> fields(root, *keys, RootFields.EFFECT_JOURNAL_HEAD)
                else -> fields(root, *keys, RootFields.EFFECT_JOURNAL_HEAD, RootFields.EFFECT_RECONCILIATION)
            }
            val b = fields(obj(o[RootFields.BINDING]), BindingFields.POLICY_SHA256,
                BindingFields.WORKSPACE_IDENTITY_SHA256,
                BindingFields.GIT_HEAD, BindingFields.GIT_BRANCH, BindingFields.GIT_BASELINE_SHA256,
                    BindingFields.GRAPH_ID,
                BindingFields.GRAPH_GENERATION, BindingFields.FROZEN_INPUTS_SHA256)
            val binding = CheckpointBinding(string(b, BindingFields.POLICY_SHA256),
                string(b, BindingFields.WORKSPACE_IDENTITY_SHA256), string(b, BindingFields.GIT_HEAD),
                string(b, BindingFields.GIT_BRANCH), string(b, BindingFields.GIT_BASELINE_SHA256),
                string(b, BindingFields.GRAPH_ID), long(b, BindingFields.GRAPH_GENERATION), string(b,
                    BindingFields.FROZEN_INPUTS_SHA256))
            val children = array(o, RootFields.CHILDREN).map {
                val x = fields(obj(it), ChildFields.ID, ChildFields.PARENT_ID, ChildFields.STATE)
                CheckpointChild(string(x, ChildFields.ID), string(x, ChildFields.PARENT_ID), string(x,
                    ChildFields.STATE))
            }
            val artifacts = array(o, RootFields.ACCEPTED_ARTIFACTS).map {
                val x = fields(obj(it), ArtifactFields.ID, ArtifactFields.OWNER_ID, ArtifactFields.SHA256)
                CheckpointArtifact(string(x, ArtifactFields.ID), string(x, ArtifactFields.OWNER_ID),
                    string(x, ArtifactFields.SHA256))
            }
            val usage = array(o, RootFields.HOST_ASSERTED_USAGE).map {
                val x = fields(obj(it), UsageFields.SCOPE_ID, UsageFields.KIND, UsageFields.CERTAINTY,
                    UsageFields.UNITS)
                HostAssertedCheckpointUsage(string(x, UsageFields.SCOPE_ID), string(x, UsageFields.KIND),
                    string(x, UsageFields.CERTAINTY),
                    if (x[UsageFields.UNITS] == JsonNull) null else long(x, UsageFields.UNITS))
            }
            val effects = array(o, RootFields.PENDING_EFFECTS).map {
                val x = fields(obj(it), PendingEffectFields.ID, PendingEffectFields.CORRELATION_ID,
                    PendingEffectFields.STATE)
                CheckpointEffect(string(x, PendingEffectFields.ID), string(x,
                    PendingEffectFields.CORRELATION_ID), string(x, PendingEffectFields.STATE))
            }
            val attempt = long(o, RootFields.TASK_ATTEMPT)
            val eventSequence = long(o, RootFields.JOURNAL_SEQUENCE)
            if (attempt !in Int.MIN_VALUE..Int.MAX_VALUE ||
                eventSequence !in Int.MIN_VALUE..Int.MAX_VALUE)
                invalid()
            fun readHead(value: JsonElement?): CheckpointEffectJournalHead {
                val h = fields(obj(value), HeadFields.SESSION_ID, HeadFields.FENCE_GENERATION,
                    HeadFields.ADMITTED_CHECKPOINT_SHA256, HeadFields.TOOL_AUTHORITY_SHA256,
                    HeadFields.SIDECAR_PATH_SHA256, HeadFields.LAST_SEQUENCE, HeadFields.HEAD_MAC_SHA256)
                return CheckpointEffectJournalHead(string(h, HeadFields.SESSION_ID),
                    long(h, HeadFields.FENCE_GENERATION), string(h, HeadFields.ADMITTED_CHECKPOINT_SHA256),
                    string(h, HeadFields.TOOL_AUTHORITY_SHA256), string(h, HeadFields.SIDECAR_PATH_SHA256),
                    long(h, HeadFields.LAST_SEQUENCE), string(h, HeadFields.HEAD_MAC_SHA256))
            }
            val head = if (version == BASE_VERSION || (version == RECONCILIATION_VERSION &&
                    o[RootFields.EFFECT_JOURNAL_HEAD] == JsonNull)) null
                else readHead(o[RootFields.EFFECT_JOURNAL_HEAD])
            val reconciliation = if (version == RECONCILIATION_VERSION) {
                val rec = fields(obj(o[RootFields.EFFECT_RECONCILIATION]),
                    ReconciliationFields.SOURCE_CHECKPOINT_SHA256,
                        ReconciliationFields.SOURCE_FENCE_GENERATION,
                    ReconciliationFields.SOURCE_FENCE_SHA256, ReconciliationFields.OPERATOR_ID,
                        ReconciliationFields.HOST_SEAL_MAC_SHA256,
                    ReconciliationFields.SOURCE_HEAD)
                CheckpointEffectReconciliation(string(rec, ReconciliationFields.SOURCE_CHECKPOINT_SHA256),
                    long(rec, ReconciliationFields.SOURCE_FENCE_GENERATION),
                    string(rec, ReconciliationFields.SOURCE_FENCE_SHA256),
                        readHead(rec[ReconciliationFields.SOURCE_HEAD]),
                    string(rec, ReconciliationFields.OPERATOR_ID), string(rec,
                        ReconciliationFields.HOST_SEAL_MAC_SHA256))
            } else null
            val s = SessionCheckpoint(long(o, RootFields.SEQUENCE), nullableString(o,
                RootFields.PREVIOUS_SHA256),
                binding, string(o, RootFields.SESSION_ID), string(o, RootFields.TRACE_ID), string(o,
                    RootFields.PHASE),
                string(o, RootFields.TASK_ID), attempt.toInt(), string(o, RootFields.TASK_STATE),
                children, artifacts, usage, effects, eventSequence.toInt(),
                string(o, RootFields.JOURNAL_HEAD_SHA256), string(o, RootFields.JOURNAL_FILE_SHA256),
                long(o, RootFields.LAST_TRUSTED_JOURNAL_OFFSET), head, reconciliation)
            if (!encode(s).contentEquals(bytes))
                throw CheckpointUnconfirmed("CHECKPOINT_NONCANONICAL")
            return s
        } catch (e: CheckpointUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw CheckpointUnconfirmed("CHECKPOINT_PAYLOAD_INVALID", e)
        }
    }
}

/**
 * Linux procfs Uid fields: real, effective, saved and filesystem UID.
 * The second field is the process's effective identity even when sudo/su
 * changes it without changing the real UID. Linux filesystem access checks
 * use the fourth (filesystem UID), which can differ after setfsuid.
 * Unavailable or malformed procfs evidence is a refusal, never a fallback
 * to mutable JVM properties or an external executable.
 */
internal object LinuxCheckpointOwner {
    private val UID_LINE = Regex("""Uid:\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)\s*""")

    fun parseEffectiveUid(status: String): Long? {
        val line = status.lineSequence().filter { it.startsWith("Uid:") }.singleOrNull()
            ?: return null
        return UID_LINE.matchEntire(line)?.groupValues?.get(2)?.toLongOrNull()
    }

    fun effectiveUid(): Long? = try {
        parseEffectiveUid(Files.readString(Path.of("/proc/self/status"),
            StandardCharsets.US_ASCII))
    } catch (_: Exception) {
        null
    }

    fun requireMatchingUid(directoryUid: Any?, effectiveUid: Long?) {
        if (directoryUid !is Number || effectiveUid == null || effectiveUid < 0 ||
            directoryUid.toLong() != effectiveUid)
            throw CheckpointUnconfirmed("CHECKPOINT_UNSAFE_DIRECTORY")
    }
}

/**
 * Immutable checkpoint files in a dedicated host-owned directory, outside
 * the workspace. All published files are self-checksummed and chained. A torn
 * temp is ignored; a damaged published generation is never silently skipped.
 *
 * An advisory lock excludes cooperating processes. Malicious same-UID file
 * substitution is out of scope; the enclosing directory MUST be private.
 */
object SessionCheckpointStore {
    private val MAGIC = "RCCHK1\n".toByteArray(StandardCharsets.US_ASCII)
    private val NAMES = Regex("[0-9]{12}\\.rcp")
    private val TEMP_NAMES = Regex("\\.pending-[a-f0-9-]{36}\\.tmp")
    private val heldLocks = ConcurrentHashMap.newKeySet<Path>()
    private const val MAX_GENERATIONS = 256

    private fun refuse(code: String): Nothing = throw CheckpointUnconfirmed(code)

    private fun checkedDirectory(directory: Path, workspace: Path,
                                 effectiveUid: () -> Long?): Path {
        val dir = directory.toAbsolutePath().normalize()
        val root = try { workspace.toRealPath() }
            catch (e: Exception) { throw CheckpointUnconfirmed("CHECKPOINT_WORKSPACE_INVALID", e) }
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ||
                dir.toRealPath() != dir || dir.startsWith(root) || root.startsWith(dir))
                refuse("CHECKPOINT_UNSAFE_DIRECTORY")
            // Linux POSIX store: compare numeric owner with the effective UID,
            // never user.name or the real UID. Unknown identities fail closed.
            val attributes = Files.readAttributes(dir, PosixFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS)
            val ownerOnly = setOf(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            LinuxCheckpointOwner.requireMatchingUid(
                Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS),
                effectiveUid())
            if (attributes.permissions() != ownerOnly)
                refuse("CHECKPOINT_UNSAFE_DIRECTORY")
        } catch (e: CheckpointUnconfirmed) {
            throw e
        } catch (e: Exception) {
            throw CheckpointUnconfirmed("CHECKPOINT_UNSAFE_DIRECTORY", e)
        }
        return dir
    }

    /** The resume OS lease is separate from the short checkpoint lock. */
    internal fun checkedAdmissionDirectory(directory: Path, context: CheckpointHostContext): Path =
        checkedDirectory(directory, context.workspaceRoot, LinuxCheckpointOwner::effectiveUid)

    private fun <T> withLease(directory: Path, create: Boolean, block: () -> T): T {
        val lockPath = directory.resolve(CHECKPOINT_LOCK_NAME)
        if (!heldLocks.add(lockPath)) refuse("CHECKPOINT_LEASE_HELD")
        var owner: FileChannel? = null
        var lock: java.nio.channels.FileLock? = null
        var failure: Throwable? = null
        try {
            if (Files.isSymbolicLink(lockPath)) refuse("CHECKPOINT_UNSAFE_LOCK")
            owner = try {
                if (create) FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                else FileChannel.open(lockPath, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)
            } catch (e: java.nio.file.NoSuchFileException) {
                throw CheckpointUnconfirmed("CHECKPOINT_LOCK_MISSING", e)
            }
            lock = try { owner.tryLock() }
                catch (_: OverlappingFileLockException) { null }
            if (lock == null) refuse("CHECKPOINT_LEASE_HELD")
            return block()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try { lock?.release() } catch (e: Exception) {
                if (failure != null) failure.addSuppressed(e)
                else failure = CheckpointUnconfirmed("CHECKPOINT_LEASE_RELEASE_UNCONFIRMED", e)
            }
            try { owner?.close() } catch (e: Exception) {
                if (failure != null) failure.addSuppressed(e)
                else failure = CheckpointUnconfirmed("CHECKPOINT_LEASE_RELEASE_UNCONFIRMED", e)
            }
            heldLocks.remove(lockPath)
            if (failure is CheckpointUnconfirmed &&
                (failure as CheckpointUnconfirmed).code == "CHECKPOINT_LEASE_RELEASE_UNCONFIRMED")
                throw failure
        }
    }

    private fun checkpointName(sequence: Long): String =
        String.format(Locale.ROOT, "%012d.rcp", sequence)

    private fun readOne(file: Path): VerifiedSessionCheckpoint {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            refuse("CHECKPOINT_UNSAFE_FILE")
        val maxSize = MAGIC.size + 4L + 32 + SessionCheckpointCodec.MAX_PAYLOAD
        if (Files.size(file) > maxSize) refuse("CHECKPOINT_SIZE_INVALID")
        val data = FileChannel.open(file, StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size > maxSize) refuse("CHECKPOINT_SIZE_INVALID")
            if (size < MAGIC.size + 4 + 32) refuse("CHECKPOINT_TRUNCATED")
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) refuse("CHECKPOINT_TRUNCATED")
            }
            buffer.flip()
            buffer
        }
        val header = ByteArray(MAGIC.size)
        data.get(header)
        if (!header.contentEquals(MAGIC)) refuse("CHECKPOINT_VERSION_UNSUPPORTED")
        val length = data.int
        if (length !in 1..SessionCheckpointCodec.MAX_PAYLOAD ||
            data.remaining() != 32 + length)
            refuse("CHECKPOINT_TRUNCATED")
        val recordedDigest = ByteArray(32)
        data.get(recordedDigest)
        val payload = ByteArray(length)
        data.get(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        if (!MessageDigest.isEqual(recordedDigest, digest))
            refuse("CHECKPOINT_HASH_MISMATCH")
        val checkpoint = SessionCheckpointCodec.decode(payload)
        return VerifiedSessionCheckpoint(checkpoint, SessionCheckpointCodec.sha256(payload),
            checkpoint.hostAssertedUsage.any { it.certainty == CheckpointUsageCertainty.UNKNOWN } ||
                checkpoint.pendingEffects.isNotEmpty() ||
                checkpoint.effectJournalHead != null ||
                checkpoint.effectReconciliation != null)
    }

    /**
     * The SHA-chained checkpoint generation must not drop, regress, or
     * silently retarget a previously published effect pin.
     */
    private fun pinnedHeadTransition(
        previous: VerifiedSessionCheckpoint?, next: SessionCheckpoint
    ): Boolean {
        val prior = previous?.snapshot ?: return next.effectReconciliation == null
        val newRec = next.effectReconciliation
        if (newRec != null) {
            // Only the immediate successor of a pinned checkpoint can be v3.
            // The signed record lives at this boundary, NOT on successors.
            val oldHead = prior.effectJournalHead
            return prior.effectReconciliation == null && oldHead != null &&
                next.effectJournalHead == null &&
                newRec.sourceCheckpointSha256 == previous.sha256 &&
                newRec.sourceHead == oldHead &&
                newRec.sourceFenceGeneration == oldHead.fenceGeneration
        }
        prior.effectReconciliation?.let { transition ->
            // The one-shot v3 may be followed after any later admitted fence.
            // Generation N+1 MUST have committed the exact v3 SHA; a later
            // v2 head must be issued by its own (possibly N+2+) fence.
            return next.effectJournalHead == null ||
                next.effectJournalHead.fenceGeneration >
                    transition.sourceFenceGeneration
        }
        val old = prior.effectJournalHead ?: return true
        val current = next.effectJournalHead ?: return false
        if (old.sessionId != current.sessionId ||
            old.fenceGeneration != current.fenceGeneration ||
            old.admittedCheckpointSha256 != current.admittedCheckpointSha256 ||
            old.toolAuthoritySha256 != current.toolAuthoritySha256 ||
            old.sidecarPathSha256 != current.sidecarPathSha256 ||
            current.lastSequence < old.lastSequence)
            return false
        // Only a physical, HMAC-verified RCEFX1 chain may establish an
        // increased sequence; SHA-linked checkpoint history alone cannot.
        return current.lastSequence != old.lastSequence ||
            current.headMacSha256 == old.headMacSha256
    }

    private fun readAll(directory: Path): List<VerifiedSessionCheckpoint> {
        val files = Files.newDirectoryStream(directory).use { it.toList() }
        val snapshots = mutableListOf<Path>()
        for (file in files) {
            val name = file.fileName.toString()
            when {
                name == CHECKPOINT_LOCK_NAME -> {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        refuse("CHECKPOINT_UNSAFE_LOCK")
                }
                name == RESUME_LOCK_NAME -> {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        refuse("CHECKPOINT_UNSAFE_LOCK")
                }
                ResumeFenceStorage.NAME.matches(name) -> { /* Validated below. */ }
                ResumeFenceStorage.TEMP.matches(name) -> { /* Unpublished fence temp. */ }
                ResumeImmutablePublication.PROBE_NAME.matches(name) -> {
                    // A killed preflight may leave a regular file/hard link.
                    // Read-only inspect ignores it; only admission deletes it.
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        refuse("CHECKPOINT_UNRECOGNIZED_ENTRY")
                }
                NAMES.matches(name) -> snapshots.add(file)
                TEMP_NAMES.matches(name) -> { /* Interrupted write; never a committed snapshot. */ }
                else -> refuse("CHECKPOINT_UNRECOGNIZED_ENTRY")
            }
        }
        if (snapshots.size > MAX_GENERATIONS) refuse("CHECKPOINT_SIZE_INVALID")
        // Verify the complete fence chain first. Each pinned checkpoint
        // must name an actual committed issuing fence and an earlier
        // checkpoint from THIS verified chain, not self-authenticated
        // claims from its own sidecar genesis.
        val latestFence = try { ResumeFenceStorage.latest(directory) }
        catch (e: SessionResumeUnconfirmed) {
            throw CheckpointUnconfirmed("CHECKPOINT_RESUME_FENCE_UNCONFIRMED", e)
        }
        val fencesByGeneration = mutableMapOf<Long, ResumeFenceRecord>()
        val precedingCheckpointShas = mutableSetOf<String>()
        val sorted = snapshots.sortedBy { it.fileName.toString() }
        val result = ArrayList<VerifiedSessionCheckpoint>()
        for ((index, path) in sorted.withIndex()) {
            val current = readOne(path)
            val s = current.snapshot
            val previous = result.lastOrNull()
            if (s.sequence != index.toLong() + 1 ||
                path.fileName.toString() != checkpointName(s.sequence) ||
                s.previousSha256 != previous?.sha256 ||
                (previous != null && (s.binding != previous.snapshot.binding ||
                    s.sessionId != previous.snapshot.sessionId ||
                    s.traceId != previous.snapshot.traceId ||
                    s.journalSequence < previous.snapshot.journalSequence ||
                    s.lastTrustedJournalOffset < previous.snapshot.lastTrustedJournalOffset ||
                    (s.journalSequence == previous.snapshot.journalSequence &&
                        (s.journalHeadSha256 != previous.snapshot.journalHeadSha256 ||
                            s.journalFileSha256 != previous.snapshot.journalFileSha256)) ||
                    !pinnedHeadTransition(previous, s))))
                refuse("CHECKPOINT_CHAIN_MISMATCH")
            previous?.snapshot?.effectReconciliation?.let { transition ->
                // A v3 -> ordinary successor is valid only AFTER generation
                // N+1 commits a fence to the exact v3 checkpoint SHA.
                val nextGeneration = transition.sourceFenceGeneration + 1L
                if (latestFence == null || nextGeneration > latestFence.generation)
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH")
                val successor = try {
                    fencesByGeneration.getOrPut(nextGeneration) {
                        ResumeFenceStorage.atGeneration(directory, nextGeneration)
                    }
                } catch (e: Exception) {
                    throw CheckpointUnconfirmed(
                        "CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH", e)
                }
                if (successor.checkpointSha256 != previous.sha256 ||
                    successor.generation != nextGeneration ||
                    successor.sessionId != s.sessionId ||
                    successor.traceId != s.traceId ||
                    successor.policySha256 != s.binding.policySha256 ||
                    (s.effectJournalHead != null &&
                        s.effectJournalHead.fenceGeneration <=
                            transition.sourceFenceGeneration))
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH")
            }
            s.effectReconciliation?.let { transition ->
                if (transition.sourceCheckpointSha256 != previous?.sha256)
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_SOURCE_MISMATCH")
                val issuer = try {
                    fencesByGeneration.getOrPut(transition.sourceFenceGeneration) {
                        ResumeFenceStorage.atGeneration(directory,
                            transition.sourceFenceGeneration)
                    }
                } catch (e: Exception) {
                    throw CheckpointUnconfirmed(
                        "CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH", e)
                }
                if (issuer.generation != transition.sourceFenceGeneration ||
                    issuer.sha256 != transition.sourceFenceSha256 ||
                    issuer.sessionId != s.sessionId ||
                    issuer.traceId != s.traceId ||
                    issuer.policySha256 != s.binding.policySha256 ||
                    issuer.checkpointSha256 != transition.sourceHead.admittedCheckpointSha256 ||
                    issuer.toolAuthoritySha256 != transition.sourceHead.toolAuthoritySha256)
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH")
            }
            s.effectJournalHead?.let { head ->
                if (head.admittedCheckpointSha256 !in precedingCheckpointShas)
                    refuse("CHECKPOINT_EFFECT_HEAD_ADMISSION_MISMATCH")
                if (latestFence == null ||
                    head.fenceGeneration > latestFence.generation)
                    refuse("CHECKPOINT_EFFECT_HEAD_FENCE_MISMATCH")
                val issuer = try {
                    fencesByGeneration.getOrPut(head.fenceGeneration) {
                        ResumeFenceStorage.atGeneration(directory, head.fenceGeneration)
                    }
                } catch (e: Exception) {
                    throw CheckpointUnconfirmed("CHECKPOINT_EFFECT_HEAD_FENCE_MISMATCH", e)
                }
                if (issuer.generation != head.fenceGeneration ||
                    issuer.sessionId != head.sessionId ||
                    issuer.sessionId != s.sessionId ||
                    issuer.traceId != s.traceId ||
                    issuer.policySha256 != s.binding.policySha256 ||
                    issuer.checkpointSha256 != head.admittedCheckpointSha256 ||
                    issuer.toolAuthoritySha256 != head.toolAuthoritySha256)
                    refuse("CHECKPOINT_EFFECT_HEAD_FENCE_MISMATCH")
            }
            result.add(current)
            precedingCheckpointShas.add(current.sha256)
        }
        // A committed fence cannot refer to an absent or replaced checkpoint.
        // This also detects a canonical v3 payload rehashed after the next
        // fence has durably pinned its original SHA.
        if (latestFence != null) {
            for (generation in 1L..latestFence.generation) {
                val fence = fencesByGeneration.getOrPut(generation) {
                    ResumeFenceStorage.atGeneration(directory, generation)
                }
                if (fence.checkpointSha256 !in precedingCheckpointShas)
                    refuse("CHECKPOINT_RESUME_FENCE_CHECKPOINT_MISSING")
            }
        }
        // A pinned latest checkpoint cannot silently trail a later fence.
        // Historical heads may be older when a v3 reconciliation follows.
        result.lastOrNull()?.snapshot?.effectJournalHead?.let { head ->
            if (head.fenceGeneration != latestFence?.generation)
                refuse("CHECKPOINT_EFFECT_HEAD_FENCE_MISMATCH")
        }
        // readOne() cannot decide whether v3 needs operator reconciliation:
        // that is a property of the independently committed fence history.
        // A prepared (uncommitted) N+1 anchor must NOT clear this flag.
        result.lastOrNull()?.let { tail ->
            val transition = tail.snapshot.effectReconciliation
            if (transition != null && latestFence != null &&
                latestFence.generation > transition.sourceFenceGeneration) {
                val generation = transition.sourceFenceGeneration + 1L
                val committed = try {
                    fencesByGeneration.getOrPut(generation) {
                        ResumeFenceStorage.atGeneration(directory, generation)
                    }
                } catch (e: Exception) {
                    throw CheckpointUnconfirmed(
                        "CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH", e)
                }
                if (committed.generation != generation ||
                    committed.checkpointSha256 != tail.sha256 ||
                    committed.sessionId != tail.snapshot.sessionId ||
                    committed.traceId != tail.snapshot.traceId ||
                    committed.policySha256 != tail.snapshot.binding.policySha256)
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH")
                result[result.lastIndex] = tail.copy(
                    reconciliationRequired =
                        tail.snapshot.hostAssertedUsage.any {
                            it.certainty == CheckpointUsageCertainty.UNKNOWN
                        } || tail.snapshot.pendingEffects.isNotEmpty())
            }
        }
        return result
    }

    private fun verify(s: SessionCheckpoint, context: CheckpointHostContext,
                       ledger: VerifiedSessionEventLedger) {
        try {
        if (s.binding != context.binding()) refuse("CHECKPOINT_BASELINE_CHANGED")
        if (s.journalSequence != ledger.lastSequence ||
            s.journalHeadSha256 != ledger.headSha256 ||
            s.journalFileSha256 != ledger.fileSha256 ||
            s.lastTrustedJournalOffset != ledger.lastTrustedOffset)
            refuse("CHECKPOINT_JOURNAL_MISMATCH")
        if (ledger.events.isEmpty() || ledger.events.any { event ->
                event[SessionEnvelopeFields.SESSION_ID]?.jsonPrimitive?.content != s.sessionId ||
                    event[SessionEnvelopeFields.TRACE_ID]?.jsonPrimitive?.content != s.traceId
            }) refuse("CHECKPOINT_SESSION_MISMATCH")
        val graph = setOf(s.binding.graphId) + s.children.map { it.id }
        for (event in ledger.events) {
            val metadata = event[SessionEventFields.METADATA] as? JsonObject ?: continue
            for (key in listOf(StageEventMetadataFields.GRAPH_ID,
                StageEventMetadataFields.PARENT_GRAPH_ID, StageEventMetadataFields.CHILD_GRAPH_ID)) {
                val value = metadata[key]?.jsonPrimitive?.content ?: continue
                if (value !in graph) refuse("CHECKPOINT_GRAPH_MISMATCH")
            }
        }
        val eventCorrelations = ledger.events.mapNotNull {
            it[SessionEventFields.CORRELATION_ID]?.jsonPrimitive?.content
        }.toSet()
        if (s.pendingEffects.any { it.correlationId !in eventCorrelations })
            refuse("CHECKPOINT_CORRELATION_MISMATCH")

        // A missing usage entry cannot mean zero: first-party request/tool events
        // require an explicit known or unknown ledger entry in the snapshot.
        val eventTypes = ledger.events.mapNotNull { it[SessionEventFields.TYPE]?.jsonPrimitive?.content }
        if (eventTypes.any { it.startsWith("llm.") } &&
                s.hostAssertedUsage.none { it.kind == CheckpointUsageKinds.MODEL } ||
            eventTypes.any { it.startsWith("tool.") } &&
                s.hostAssertedUsage.none { it.kind == CheckpointUsageKinds.TOOL })
            refuse("CHECKPOINT_USAGE_UNCONFIRMED")

        // Multiple tool starts can share a request correlation. Count them per
        // physical tool execution ID (or correlation when the ID is absent);
        // a single later completion must never clear two uncertain operations.
        fun toolKey(event: JsonObject): String {
            val actor = event[SessionEventFields.ACTOR] as? JsonObject
            val execution = actor?.get(SessionEventActorFields.TOOL_EXECUTION_ID)
            return if (execution == null || execution == JsonNull)
                "correlation:" + event[SessionEventFields.CORRELATION_ID]!!.jsonPrimitive.content
            else "execution:" + execution.jsonPrimitive.content
        }
        val unmatched = mutableMapOf<String, ArrayDeque<String>>()
        for (event in ledger.events) {
            val type = event[SessionEventFields.TYPE]!!.jsonPrimitive.content
            if (type != SessionEventKinds.TOOL_STARTED && type != SessionEventKinds.TOOL_COMPLETED) continue
            val key = toolKey(event)
            if (type == SessionEventKinds.TOOL_STARTED)
                unmatched.getOrPut(key) { ArrayDeque() }
                    .addLast(event[SessionEventFields.CORRELATION_ID]!!.jsonPrimitive.content)
            else unmatched[key]?.removeFirstOrNull()
        }
        val unresolved = unmatched.values.flatMap { it.toList() }
            .groupingBy { it }.eachCount()
        val effectsByCorrelation = s.pendingEffects.groupingBy { it.correlationId }.eachCount()
        if (unresolved.any { (correlation, count) ->
                (effectsByCorrelation[correlation] ?: 0) < count
            })
            refuse("CHECKPOINT_SIDE_EFFECT_UNCONFIRMED")

        if (s.acceptedArtifacts.any { artifact ->
                ledger.events.none { event ->
                    val m = event[SessionEventFields.METADATA] as? JsonObject
                    event[SessionEventFields.STATUS]?.jsonPrimitive?.content ==
                        SessionEventStatuses.COMPLETED &&
                        m?.get(StageEventMetadataFields.ARTIFACT_ID)?.jsonPrimitive?.content == artifact.id &&
                        m?.get(StageEventMetadataFields.ARTIFACT_SHA256)?.jsonPrimitive?.content ==
                            artifact.sha256 &&
                        (m?.get(StageEventMetadataFields.CHILD_GRAPH_ID)
                            ?: m?.get(StageEventMetadataFields.GRAPH_ID))
                            ?.jsonPrimitive?.content == artifact.ownerId
                }
            }) refuse("CHECKPOINT_ARTIFACT_UNCONFIRMED")
        } catch (e: CheckpointUnconfirmed) {
            throw e
        } catch (e: Exception) {
            // A corrupted or unexpected journal envelope is a typed refusal, never a
            // leaked NPE, ClassCastException or IllegalArgumentException.
            throw CheckpointUnconfirmed("CHECKPOINT_JOURNAL_UNCONFIRMED", e)
        }
    }

    private fun assertLedgerOutsideWorkspace(path: Path, workspace: Path) {
        val root = workspace.toRealPath()
        if (path.toAbsolutePath().normalize().startsWith(root))
            refuse("CHECKPOINT_LEDGER_INSIDE_WORKSPACE")
    }

    private fun inspectLedger(path: Path, workspace: Path): VerifiedSessionEventLedger {
        assertLedgerOutsideWorkspace(path, workspace)
        val file = path.toAbsolutePath().normalize()
        return try { DurableSessionEventLedger.inspect(file) }
            catch (e: SessionLedgerUnconfirmed) {
                throw CheckpointUnconfirmed("CHECKPOINT_JOURNAL_UNCONFIRMED", e)
            }
    }

    /**
     * Atomic CREATE_NEW generation. Checkpoint files are never overwritten, even
     * when an unacknowledged prior rename survived a crash. Failures after the
     * rename must be reconciled, not retried as if no write happened.
     */
    fun save(directory: Path, context: CheckpointHostContext, ledgerPath: Path,
             snapshot: SessionCheckpoint, expectedPreviousSha256: String?): VerifiedSessionCheckpoint =
        saveWithFault(directory, context, ledgerPath, snapshot, expectedPreviousSha256) { }

    /** Internal per-invocation identity seam; never mutate global authorization state. */
    internal fun save(directory: Path, context: CheckpointHostContext, ledgerPath: Path,
             snapshot: SessionCheckpoint, expectedPreviousSha256: String?,
             effectiveUid: () -> Long?): VerifiedSessionCheckpoint =
        persist(directory, context, snapshot, expectedPreviousSha256, {}, effectiveUid) {
            ResumeFenceAnchor.verify(ledgerPath,
                ResumeFenceStorage.latest(directory.toAbsolutePath().normalize()),
                directory.toAbsolutePath().normalize())
            inspectLedger(ledgerPath, context.workspaceRoot)
        }

    /**
     * Deterministic interruption seams for the integration tier. Faults are injected
     * only by tests, after durable temp sync or after published atomic rename.
     */
    internal fun saveWithFault(directory: Path, context: CheckpointHostContext, ledgerPath: Path,
             snapshot: SessionCheckpoint, expectedPreviousSha256: String?,
             afterPhase: (String) -> Unit): VerifiedSessionCheckpoint =
        persist(directory, context, snapshot, expectedPreviousSha256, afterPhase,
            LinuxCheckpointOwner::effectiveUid) {
            ResumeFenceAnchor.verify(ledgerPath,
                ResumeFenceStorage.latest(directory.toAbsolutePath().normalize()),
                directory.toAbsolutePath().normalize())
            inspectLedger(ledgerPath, context.workspaceRoot)
        }

    /**
     * The live writer provides the verified committed anchor while its append monitor
     * remains held. No independent inspect can open the ledger's existing lease.
     */
    fun saveFromOpenLedger(directory: Path, context: CheckpointHostContext,
             ledger: DurableSessionEventLedger, snapshot: SessionCheckpoint,
             expectedPreviousSha256: String?): VerifiedSessionCheckpoint =
        saveFromOpenLedger(directory, context, ledger, snapshot, expectedPreviousSha256,
            LinuxCheckpointOwner::effectiveUid)

    /** Internal per-invocation identity seam for the live-writer path. */
    internal fun saveFromOpenLedger(directory: Path, context: CheckpointHostContext,
             ledger: DurableSessionEventLedger, snapshot: SessionCheckpoint,
             expectedPreviousSha256: String?,
             effectiveUid: () -> Long?): VerifiedSessionCheckpoint =
        try {
            assertLedgerOutsideWorkspace(ledger.checkpointStoragePath, context.workspaceRoot)
            ledger.withCommittedCheckpointAnchor { anchor ->
                persist(directory, context, snapshot, expectedPreviousSha256, {},
                    effectiveUid) {
                    ResumeFenceAnchor.verify(ledger.checkpointStoragePath,
                        ResumeFenceStorage.latest(directory.toAbsolutePath().normalize()),
                        directory.toAbsolutePath().normalize())
                    anchor
                }
            }
        } catch (e: SessionLedgerUnconfirmed) {
            val code = if (e.code == DurableSessionEventLedger.LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE)
                "CHECKPOINT_LIVE_LEDGER_TOO_LARGE" else "CHECKPOINT_JOURNAL_UNCONFIRMED"
            throw CheckpointUnconfirmed(code, e)
        }

    /**
     * Path-based fenced publication is restricted to an unchanged, already
     * checkpoint-covered pre-execution ledger. It cannot promote a stranded
     * post-append tail (whose fsync acknowledgement may be unknown).
     * Reopened writers must use saveFromOpenLedgerFenced while still open.
     */
    fun saveFenced(lease: SessionResumeLease, directory: Path,
                   context: CheckpointHostContext, ledgerPath: Path,
                   snapshot: SessionCheckpoint, expectedPreviousSha256: String?): VerifiedSessionCheckpoint =
        lease.publishIfCurrent {
            lease.requireLedgerPath(ledgerPath)
            persist(directory, context, snapshot, expectedPreviousSha256, {},
                LinuxCheckpointOwner::effectiveUid, lease,
                requireCoveredPreExecutionPrefix = true) {
                inspectLedger(ledgerPath, context.workspaceRoot)
            }
        }

    /**
     * Host-only, pre-execution physical head publication. Pins the exact
     * live journal writer under the current resume lease, then writes it
     * atomically inside the canonical SHA-chained checkpoint payload.
     *
     * This is NOT effect execution authority. Pinned checkpoints require
     * reconciliation, so automatic resume is blocked after publication.
     * The sidecar must be fsynced before this call; Started must be pinned
     * through this API before any future executor invokes an effect.
     */
    @OptIn(ResumeOperatorOnlyApi::class)
    fun saveEffectHeadFenced(lease: SessionResumeLease,
                   directory: Path, context: CheckpointHostContext,
                   ledgerPath: Path, journal: DurableEffectRecordJournal,
                   snapshot: SessionCheckpoint,
                   expectedPreviousSha256: String?): VerifiedSessionCheckpoint =
        saveEffectHeadFencedImpl(lease, directory, context, ledgerPath,
            journal, snapshot, expectedPreviousSha256, {})

    /** Test-only deterministic failure after forced temp or generation rename. */
    @LedgerTestFaultInjection
    @OptIn(ResumeOperatorOnlyApi::class)
    internal fun saveEffectHeadFencedWithFault(lease: SessionResumeLease,
                   directory: Path, context: CheckpointHostContext,
                   ledgerPath: Path, journal: DurableEffectRecordJournal,
                   snapshot: SessionCheckpoint, expectedPreviousSha256: String?,
                   afterPhase: (String) -> Unit): VerifiedSessionCheckpoint =
        saveEffectHeadFencedImpl(lease, directory, context, ledgerPath,
            journal, snapshot, expectedPreviousSha256, afterPhase)

    @OptIn(ResumeOperatorOnlyApi::class)
    private fun saveEffectHeadFencedImpl(lease: SessionResumeLease,
                   directory: Path, context: CheckpointHostContext,
                   ledgerPath: Path, journal: DurableEffectRecordJournal,
                   snapshot: SessionCheckpoint, expectedPreviousSha256: String?,
                   afterPhase: (String) -> Unit): VerifiedSessionCheckpoint =
        lease.publishIfCurrent {
            lease.requireLedgerPath(ledgerPath)
            if (snapshot.effectJournalHead != null)
                refuse("CHECKPOINT_EFFECT_HEAD_CALLER_SUPPLIED")
            val head = journal.checkpointHeadUnderLease(lease)
            if (snapshot.sessionId != head.sessionId)
                refuse("CHECKPOINT_EFFECT_HEAD_CONTEXT_MISMATCH")
            val pinned = snapshot.copy(effectJournalHead = head)
            var publicationStarted = false
            try {
                persist(directory, context, pinned, expectedPreviousSha256,
                    afterPhase, LinuxCheckpointOwner::effectiveUid, lease,
                    requireCoveredPreExecutionPrefix = true,
                    onPublicationStarted = { publicationStarted = true },
                    pinnedHeadEvidence = head) {
                    inspectLedger(ledgerPath, context.workspaceRoot)
                }
            } catch (error: Throwable) {
                // Once a generation write started, neither its publication
                // nor directory fsync acknowledgement may be guessed.
                // No more effects can be acknowledged through this writer.
                if (publicationStarted) journal.markPinPublicationUnconfirmed()
                throw error
            }
        }

    /**
     * Read the latest authenticated checkpoint generation and require its
     * exact effect head to match the independently authenticated RCEFX1
     * stream. Never synthesize an expected pin from the sidecar itself.
     * The checkpoint chain and existing fence/anchor remain the trust base;
     * protecting against total rollback of that entire root is deferred.
     */
    @OptIn(ResumeOperatorOnlyApi::class)
    fun inspectEffectHeadBound(directory: Path, context: CheckpointHostContext,
                   ledgerPath: Path, sidecarPath: Path,
                   hostKey: ByteArray): CheckpointAuthenticatedEffectJournal {
        val verified = inspect(directory, context, ledgerPath)
        val head = verified.snapshot.effectJournalHead
            ?: refuse("CHECKPOINT_EFFECT_HEAD_MISSING")
        return inspectAuthenticatedEffectHead(
            AuthenticatedEffectHead(head), sidecarPath, context.workspaceRoot, hostKey)
    }

    /**
     * A capability local to the trusted checkpoint store. Neither the type
     * nor its constructor is exposed to callers. In particular a caller
     * cannot turn an RCEFX1-derived head into an authenticated pin.
     * Minted after fresh verification in the two read entrypoints above or
     * in the locked, operator-approved genesis-only transition below.
     */
    private class AuthenticatedEffectHead(val pinned: CheckpointEffectJournalHead)

    private class StoreBoundEffectJournal(
        override val physical: VerifiedEffectRecordJournal
    ) : CheckpointAuthenticatedEffectJournal

    /**
     * The only internal historical/current verifier entrypoint. It derives
     * the trusted head from fresh on-disk checkpoint/fence/anchor inspection
     * and the actual current lease, never from a caller-supplied head or
     * a public VerifiedSessionCheckpoint data class.
     *
     * `expectedCheckpointSha256` is a TOCTOU equality check, NOT authority.
     * A stale lease, a changed checkpoint, a mismatching policy, or an
     * invalid v3 seal throws; the read-only verifier maps such exceptions
     * to denial. This does NOT authorize effect execution or replay.
     */
    @OptIn(ResumeOperatorOnlyApi::class)
    internal fun inspectEffectHeadForLease(
        directory: Path, context: CheckpointHostContext,
        ledgerPath: Path, sidecarPath: Path, hostKey: ByteArray,
        lease: SessionResumeLease, boundIdentity: EffectIdentity,
        expectedCheckpointSha256: String
    ): CheckpointAuthenticatedEffectJournal {
        lease.assertCurrent()
        lease.requireLedgerPath(ledgerPath)
        val verified = inspect(directory, context, ledgerPath)
        if (verified.sha256 != expectedCheckpointSha256)
            refuse("CHECKPOINT_EFFECT_HEAD_CHECKPOINT_CHANGED")
        val head = CheckpointBoundEffectHeadPolicy.authenticatedHead(
            verified, lease.generation, lease.admittedCheckpointSha256,
            boundIdentity, hostKey) ?: refuse("CHECKPOINT_EFFECT_HEAD_UNAUTHENTICATED")
        return inspectAuthenticatedEffectHead(
            AuthenticatedEffectHead(head), sidecarPath, context.workspaceRoot, hostKey)
    }

    /**
     * One physical reader for current v2 and historical v3. The exact-pin
     * comparison is owned by the trusted checkpoint store, never the public
     * diagnostic self-pin API. This is checkpoint provenance, NOT independent
     * high-watermark/whole-root rollback protection or effect authority.
     */
    @OptIn(ResumeOperatorOnlyApi::class)
    private fun inspectAuthenticatedEffectHead(
        head: AuthenticatedEffectHead, sidecarPath: Path,
        workspaceRoot: Path, hostKey: ByteArray
    ): CheckpointAuthenticatedEffectJournal {
        val pinned = head.pinned
        if (pinned.sidecarPathSha256 != effectJournalPathSha256(sidecarPath))
            refuse("CHECKPOINT_EFFECT_HEAD_PATH_MISMATCH")
        val expectedPin = pinned.pin()
        DurableEffectRecordJournal.requireValidPin(expectedPin)
        val physical = DurableEffectRecordJournal.inspect(
            sidecarPath, workspaceRoot, hostKey, pinned.expectation())
        DurableEffectRecordJournal.requireExactPin(physical, expectedPin)
        return StoreBoundEffectJournal(physical)
    }

    fun saveFromOpenLedgerFenced(lease: SessionResumeLease, directory: Path,
                   context: CheckpointHostContext, ledger: DurableSessionEventLedger,
                   snapshot: SessionCheckpoint, expectedPreviousSha256: String?): VerifiedSessionCheckpoint =
        saveFromOpenLedgerFencedImpl(lease, directory, context, ledger, snapshot,
            expectedPreviousSha256, {})

    /** Inject failure at the same durable boundaries as the regular save tests. */
    @LedgerTestFaultInjection
    internal fun saveFromOpenLedgerFencedWithFault(lease: SessionResumeLease,
                   directory: Path, context: CheckpointHostContext,
                   ledger: DurableSessionEventLedger, snapshot: SessionCheckpoint,
                   expectedPreviousSha256: String?,
                   afterPhase: (String) -> Unit): VerifiedSessionCheckpoint =
        saveFromOpenLedgerFencedImpl(lease, directory, context, ledger, snapshot,
            expectedPreviousSha256, afterPhase)

    private fun saveFromOpenLedgerFencedImpl(lease: SessionResumeLease, directory: Path,
                   context: CheckpointHostContext, ledger: DurableSessionEventLedger,
                   snapshot: SessionCheckpoint, expectedPreviousSha256: String?,
                   afterPhase: (String) -> Unit): VerifiedSessionCheckpoint =
        lease.publishIfCurrent {
            try {
                // Only reopened lease-bound writers may enter this save path.
                ledger.requireFencedCheckpointWriter()
                lease.requireLedgerPath(ledger.checkpointStoragePath)
                assertLedgerOutsideWorkspace(ledger.checkpointStoragePath, context.workspaceRoot)
                ledger.withCommittedCheckpointAnchor { anchor ->
                    // Pre-persist proof, under the SAME writer monitor as the
                    // publication and post-persist acknowledgement.
                    ledger.requireFencedCheckpointPublication(lease, anchor, snapshot)
                    var generationRenamed = false
                    var publicationWriteStarted = false
                    val persisted = try {
                        persist(directory, context, snapshot, expectedPreviousSha256,
                            { phase ->
                                if (phase == "renamed") generationRenamed = true
                                afterPhase(phase)
                            }, LinuxCheckpointOwner::effectiveUid, lease,
                            onPublicationStarted = { publicationWriteStarted = true }) { anchor }
                    } catch (error: Throwable) {
                        // A refusal before temp-file writing cannot publish C2.
                        // Once writing began, even a failed pre-rename publication
                        // may have uncertain effects and is never retried blindly.
                        val uncertain = publicationWriteStarted || generationRenamed
                        if (uncertain)
                            ledger.markFencedCheckpointPublicationUnconfirmed()
                        throw error
                    }
                    ledger.markFencedCheckpointPublished()
                    persisted
                }
            } catch (e: SessionLedgerUnconfirmed) {
                val code = when (e.code) {
                    DurableSessionEventLedger.LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE ->
                        "CHECKPOINT_LIVE_LEDGER_TOO_LARGE"
                    "LEDGER_NOT_FENCED" -> "CHECKPOINT_WRITER_NOT_FENCED"
                    "LEDGER_REOPEN_CHECKPOINT_UNCONFIRMED" ->
                        "CHECKPOINT_WRITE_UNCONFIRMED"
                    else -> "CHECKPOINT_JOURNAL_UNCONFIRMED"
                }
                throw CheckpointUnconfirmed(code, e)
            }
        }

    private fun persist(directory: Path, context: CheckpointHostContext,
             snapshot: SessionCheckpoint, expectedPreviousSha256: String?,
             afterPhase: (String) -> Unit,
             effectiveUid: () -> Long?,
             fence: SessionResumeLease? = null,
             requireCoveredPreExecutionPrefix: Boolean = false,
             onPublicationStarted: () -> Unit = {},
             pinnedHeadEvidence: CheckpointEffectJournalHead? = null,
             ledgerEvidence: () -> VerifiedSessionEventLedger): VerifiedSessionCheckpoint {
        val dir = checkedDirectory(directory, context.workspaceRoot, effectiveUid)
        return withLease(dir, create = true) {
            try { ResumeFenceStorage.requireCurrentForSave(dir, fence, snapshot) }
            catch (e: SessionResumeUnconfirmed) {
                if (fence == null) throw CheckpointUnconfirmed(e.code, e)
                throw e
            }
            val existing = readAll(dir)
            val previous = existing.lastOrNull()
            if (snapshot.effectJournalHead != pinnedHeadEvidence)
                refuse("CHECKPOINT_EFFECT_HEAD_UNVERIFIED")
            // v3 is a one-shot operator-only boundary. A normal save may
            // follow it, but cannot mint or copy an operator seal.
            if (snapshot.effectReconciliation != null)
                refuse("CHECKPOINT_EFFECT_RECONCILIATION_UNVERIFIED")
            previous?.snapshot?.effectReconciliation?.let { transition ->
                // The invariant concerns the first fence after v3, not the
                // latest fence: a later N+2 lease may be the first to save.
                val generation = transition.sourceFenceGeneration + 1L
                val successor = try {
                    ResumeFenceStorage.atGeneration(dir, generation)
                } catch (e: Exception) {
                    throw CheckpointUnconfirmed(
                        "CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH", e)
                }
                if (successor.generation != generation ||
                    successor.checkpointSha256 != previous.sha256)
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_FENCE_MISMATCH")
            }
            if (!pinnedHeadTransition(previous, snapshot))
                refuse("CHECKPOINT_EFFECT_HEAD_ROLLBACK")
            if (expectedPreviousSha256 != previous?.sha256 ||
                snapshot.sequence != (previous?.snapshot?.sequence ?: 0L) + 1L ||
                snapshot.previousSha256 != previous?.sha256)
                refuse("CHECKPOINT_STALE_GENERATION")
            val ledger = try { ledgerEvidence() }
            catch (e: SessionResumeUnconfirmed) {
                if (fence == null)
                    throw CheckpointUnconfirmed("CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED", e)
                throw e
            }
            // RUNNING is a one-way execution-denied marker, not a normal
            // checkpoint save. Verify the entire unchanged metadata-only C2
            // source under checkpoint.lock; the only legal differences are
            // checkpoint generation, physical ledger anchor and phase.
            // Source and destination must both remain effect-free.
            if (fence != null && snapshot.phase == SessionPhaseVocabulary.runningWire) {
                val source = previous?.snapshot
                    ?: refuse("CHECKPOINT_RUNNING_BOUNDARY_CHANGED")
                if (source.phase != SessionPhaseVocabulary.acceptedWire ||
                    !PreExecutionSnapshotPolicy.safe(source) ||
                    source.effectJournalHead != null ||
                    source.effectReconciliation != null ||
                    snapshot != source.copy(
                        sequence = Math.addExact(source.sequence, 1L),
                        previousSha256 = previous.sha256,
                        phase = SessionPhaseVocabulary.runningWire,
                        journalSequence = ledger.lastSequence,
                        journalHeadSha256 = ledger.headSha256,
                        journalFileSha256 = ledger.fileSha256,
                        lastTrustedJournalOffset = ledger.lastTrustedOffset
                    ))
                    refuse("CHECKPOINT_RUNNING_BOUNDARY_CHANGED")
            }
            if (requireCoveredPreExecutionPrefix) {
                // Both fenced checkpoint APIs must use the exact same
                // pre-execution snapshot and verified phase-history contract.
                if (!PreExecutionSnapshotPolicy.safe(snapshot) ||
                    PreExecutionPhasePolicy.phaseFromVerifiedHistory(
                        snapshot.phase, ledger.events) == null)
                    refuse("CHECKPOINT_RESUME_UNSAFE_BOUNDARY")
                // Only an open, acknowledged fenced writer can prove new frames
                // were fsynced. Path-only saves may not bless a valid-looking
                // but uncheckpointed tail after a crash or failed close.
                val covered = previous?.snapshot
                    ?: refuse("CHECKPOINT_FENCED_LEDGER_ADVANCED")
                if (covered.journalSequence != ledger.lastSequence ||
                    covered.journalHeadSha256 != ledger.headSha256 ||
                    covered.journalFileSha256 != ledger.fileSha256 ||
                    covered.lastTrustedJournalOffset != ledger.lastTrustedOffset)
                    refuse("CHECKPOINT_FENCED_LEDGER_ADVANCED")
            }
            SessionCheckpointCodec.validate(snapshot)
            verify(snapshot, context, ledger)
            if (previous != null &&
                (snapshot.binding != previous.snapshot.binding ||
                    snapshot.sessionId != previous.snapshot.sessionId ||
                    snapshot.traceId != previous.snapshot.traceId ||
                    snapshot.journalSequence < previous.snapshot.journalSequence ||
                    snapshot.lastTrustedJournalOffset < previous.snapshot.lastTrustedJournalOffset))
                refuse("CHECKPOINT_CHAIN_MISMATCH")
            publishGeneration(dir, snapshot, afterPhase, onPublicationStarted)
        }
    }

    /**
     * Already inside checkpoint.lock. Reuse the established publication
     * protocol for both normal fenced saves and genesis-only reconciliation.
     */
    private fun publishGeneration(
        dir: Path, snapshot: SessionCheckpoint,
        afterPhase: (String) -> Unit = {},
        onPublicationStarted: () -> Unit = {}
    ): VerifiedSessionCheckpoint {
            val payload = SessionCheckpointCodec.encode(snapshot)
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            val frame = ByteBuffer.allocate(MAGIC.size + 4 + 32 + payload.size)
                .put(MAGIC).putInt(payload.size).put(digest).put(payload).array()
            val destination = dir.resolve(checkpointName(snapshot.sequence))
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                refuse("CHECKPOINT_STALE_GENERATION")
            val temporary = dir.resolve(".pending-" + UUID.randomUUID().toString() + ".tmp")
            var writeFailure: Throwable? = null
            try {
                FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    // Mark the start of physical generation write operations.
                    // Earlier validation and fence refusals leave C2 absent.
                    onPublicationStarted()
                    val bytes = ByteBuffer.wrap(frame)
                    while (bytes.hasRemaining()) {
                        if (channel.write(bytes) <= 0) refuse("CHECKPOINT_WRITE_UNCONFIRMED")
                    }
                    channel.force(true)
                }
                afterPhase("temp-synced")
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                    refuse("CHECKPOINT_STALE_GENERATION")
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
                afterPhase("renamed")
                // Fault-injection boundary: opening the directory succeeded,
                // but crashing just before its fsync leaves an unacknowledged C3.
                // This tests interruption before force(), not an I/O exception in it.
                FileChannel.open(dir, StandardOpenOption.READ).use { channel ->
                    afterPhase("before-directory-fsync")
                    channel.force(true)
                }
            } catch (e: Exception) {
                val mapped = if (e is CheckpointUnconfirmed) e
                    else CheckpointUnconfirmed("CHECKPOINT_WRITE_UNCONFIRMED", e)
                writeFailure = mapped
                throw mapped
            } finally {
                // An incomplete temp is never authoritative; a published generation
                // is never deleted, even when directory fsync was unconfirmed.
                try {
                    Files.deleteIfExists(temporary)
                } catch (cleanup: Exception) {
                    if (writeFailure != null) writeFailure.addSuppressed(cleanup)
                    else throw CheckpointUnconfirmed("CHECKPOINT_TEMP_CLEANUP_UNCONFIRMED", cleanup)
                }
            }
            val readback = readOne(destination)
            if (readback.snapshot != snapshot)
                refuse("CHECKPOINT_WRITE_UNCONFIRMED")
            return readback
    }

    /**
     * Exactly-once operator reconciliation for a *genesis-only* RCEFX1 head.
     * Only callable while resume.lock is held by SessionResumeAdmission.
     * The entire read, sidecar authentication, checkpoint v3 publication and
     * downstream fence CAS are serialized by checkpoint.lock.
     *
     * A record with ANY Intent, Started, Committed or Unconfirmed is denied.
     * We cannot classify unknown external outcomes without #105 verifier.
     */
    @OptIn(ResumeOperatorOnlyApi::class)
    internal fun <T> withGenesisReconciliationEvidence(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        sidecarPath: Path, hostKey: ByteArray,
        operator: GenesisEffectHeadReconciliation,
        prepared: PreparedFenceReconciliation? = null,
        afterCheckpointCommitted: () -> Unit = {},
        block: (Path, VerifiedSessionCheckpoint, VerifiedSessionEventLedger) -> T
    ): T {
        val dir = checkedAdmissionDirectory(directory, context)
        return withLease(dir, create = false) {
            if (!operator.operatorApproved ||
                !ToolAuthorizationPolicy.validHostIdentifier(operator.operatorId) ||
                hostKey.size < 32)
                throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_DENIED")
            val all = readAll(dir)
            val latest = all.lastOrNull() ?: refuse("CHECKPOINT_MISSING")
            val pending = latest.snapshot.effectReconciliation
            if (pending != null && latest.snapshot.effectJournalHead != null)
                throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_ALREADY_ADVANCED")
            val source = if (pending == null) latest
                else all.getOrNull(all.lastIndex - 1)
                    ?: refuse("CHECKPOINT_EFFECT_RECONCILIATION_SOURCE_MISMATCH")
            val head = source.snapshot.effectJournalHead
                ?: throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_NO_PIN")
            val fence = ResumeFenceStorage.latest(dir)
                ?: throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_NO_FENCE")
            if (prepared == null) {
                // A prepared future anchor is NEVER guessed or promoted here.
                ResumeFenceAnchor.verify(ledgerPath, fence, dir)
            } else {
                // The prepared-fence branch is only legal after the v3
                // checkpoint itself has durably committed. Verify the full
                // prior anchor/audit chain and the exact prepared head.
                if (pending == null)
                    throw SessionResumeUnconfirmed(
                        "RESUME_EFFECT_RECONCILIATION_NO_PREPARED_TRANSITION")
                val status = ResumeFenceAnchor.recoveryStatus(
                    ledgerPath, fence, dir, latest.sha256,
                    latest.snapshot.binding.policySha256)
                if (status?.generation != prepared.expectedPreparedGeneration ||
                    status.fenceSha256 != prepared.expectedPreparedFenceSha256 ||
                    status.state !in setOf(
                        ResumeReconciliationState.PREPARED_UNAPPROVED,
                        ResumeReconciliationState.APPROVED_PENDING))
                    throw SessionResumeUnconfirmed(
                        "RESUME_EFFECT_RECONCILIATION_PREPARED_MISMATCH")
            }
            if (operator.expectedFenceGeneration != fence.generation ||
                operator.expectedFenceGeneration != head.fenceGeneration ||
                operator.expectedFenceSha256 != fence.sha256 ||
                operator.expectedCheckpointSha256 != source.sha256 ||
                operator.expectedHeadMacSha256 != head.headMacSha256 ||
                head.sidecarPathSha256 != effectJournalPathSha256(sidecarPath) ||
                head.sessionId != source.snapshot.sessionId ||
                fence.checkpointSha256 != head.admittedCheckpointSha256 ||
                fence.toolAuthoritySha256 != head.toolAuthoritySha256 ||
                head.lastSequence != 0L)
                throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_CONTEXT_MISMATCH")

            val ledger = inspectLedger(ledgerPath, context.workspaceRoot)
            verify(latest.snapshot, context, ledger)
            if (!PreExecutionSnapshotPolicy.safe(latest.snapshot) ||
                PreExecutionPhasePolicy.phaseFromVerifiedHistory(
                    latest.snapshot.phase, ledger.events) == null)
                throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_UNSAFE_BOUNDARY")
            // This head was read from the immutable checkpoint chain and
            // checked against the live fence, anchor and ledger above. A
            // caller-provided diagnostic self-pin cannot enter this path.
            val physical = inspectAuthenticatedEffectHead(
                AuthenticatedEffectHead(head), sidecarPath, context.workspaceRoot, hostKey).physical
            if (physical.lastSequence != 0L || physical.entries.isNotEmpty())
                throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_EFFECT_PRESENT")

            val unsigned = CheckpointEffectReconciliation(
                source.sha256, fence.generation, fence.sha256, head,
                operator.operatorId, "0".repeat(64))
            val signed = GenesisEffectReconciliationSeal.seal(hostKey, unsigned,
                source.snapshot)
            val verified = if (pending != null) {
                GenesisEffectReconciliationSeal.verify(hostKey, pending, source.snapshot)
                if (pending != signed ||
                    latest.snapshot.previousSha256 != source.sha256 ||
                    latest.snapshot.effectJournalHead != null)
                    throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_CONFLICT")
                latest
            } else {
                if (all.size >= MAX_GENERATIONS)
                    refuse("CHECKPOINT_SIZE_INVALID")
                val proposed = source.snapshot.copy(
                    sequence = source.snapshot.sequence + 1L,
                    previousSha256 = source.sha256,
                    effectJournalHead = null,
                    effectReconciliation = signed)
                SessionCheckpointCodec.validate(proposed)
                if (!pinnedHeadTransition(source, proposed))
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_UNVERIFIED")
                verify(proposed, context, ledger)
                val published = publishGeneration(dir, proposed)
                // Recheck the full chain under the same lock BEFORE a new fence
                // may refer to this transition checkpoint.
                val rebound = readAll(dir).lastOrNull()
                    ?: refuse("CHECKPOINT_MISSING")
                if (rebound.sha256 != published.sha256 ||
                    rebound.snapshot != published.snapshot)
                    refuse("CHECKPOINT_EFFECT_RECONCILIATION_UNCONFIRMED")
                // Testable crash seam: v3 durable, successor fence NOT yet
                // prepared/published. Never erase v3 on callback failure.
                afterCheckpointCommitted()
                published
            }
            block(dir, verified, ledger)
        }
    }

    /**
     * Hold the short checkpoint lock over checkpoint/journal verification and
     * fence CAS. No host locks are retained across subsequent model or tool work.
     * resume.lock is acquired first by SessionResumeAdmission and stays held.
     */
    internal fun <T> withAdmissionEvidence(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        fileStoreType: (Path) -> String,
        block: (Path, VerifiedSessionCheckpoint, VerifiedSessionEventLedger) -> T
    ): T {
        val dir = checkedAdmissionDirectory(directory, context)
        // Diagnostics and ordinary fenced admission share the local-filesystem
        // requirement, before taking checkpoint.lock or verifying mutable state.
        ResumeLocalFileStorePolicy.requireSupportedPaths(dir, ledgerPath, fileStoreType)
        return withLease(dir, create = false) {
            val latest = readAll(dir).lastOrNull() ?: refuse("CHECKPOINT_MISSING")
            val ledger = inspectLedger(ledgerPath, context.workspaceRoot)
            verify(latest.snapshot, context, ledger)
            block(dir, latest, ledger)
        }
    }

    /**
     * Initial read: validate lease authority before the ledger lock exists.
     * The later head recheck uses admittedCheckpointHeadForReopen() so that
     * ledger → checkpoint does not add a ledger → lease monitor acquisition.
     */
    internal fun admittedCheckpointForReopen(
        lease: SessionResumeLease, directory: Path, context: CheckpointHostContext
    ): VerifiedSessionCheckpoint {
        lease.assertCurrent()
        return admittedCheckpointHeadForReopen(
            lease.admittedCheckpointSha256, directory, context)
    }

    /**
     * Check the immutable pinned checkpoint SHA under the short checkpoint
     * lock only. No resume-lease monitor or ledger lock is acquired here.
     * Safe to call with the long-lived ledger OS lock already held.
     */
    internal fun admittedCheckpointHeadForReopen(
        admittedSha256: String, directory: Path, context: CheckpointHostContext
    ): VerifiedSessionCheckpoint {
        val checked = checkedDirectory(directory, context.workspaceRoot,
            LinuxCheckpointOwner::effectiveUid)
        return withLease(checked, create = false) {
            val current = readAll(checked).lastOrNull() ?: refuse("CHECKPOINT_MISSING")
            if (current.sha256 != admittedSha256 ||
                current.snapshot.binding != context.binding() ||
                current.reconciliationRequired)
                refuse("CHECKPOINT_REOPEN_UNCONFIRMED")
            current
        }
    }

    /** Full semantic replay validation for the reopened writer, under ledger OS lock. */
    internal fun verifyReopenLedger(
        checkpoint: SessionCheckpoint, context: CheckpointHostContext,
        ledger: VerifiedSessionEventLedger
    ) {
        verify(checkpoint, context, ledger)
    }

    /** Read-only inspection creates no files and verifies every published generation. */
    fun inspect(directory: Path, context: CheckpointHostContext,
                ledgerPath: Path): VerifiedSessionCheckpoint =
        inspect(directory, context, ledgerPath, LinuxCheckpointOwner::effectiveUid)

    /** Internal per-invocation identity seam; inspect remains strictly read-only. */
    internal fun inspect(directory: Path, context: CheckpointHostContext,
                ledgerPath: Path, effectiveUid: () -> Long?): VerifiedSessionCheckpoint {
        val dir = checkedDirectory(directory, context.workspaceRoot, effectiveUid)
        return withLease(dir, create = false) {
            val latest = readAll(dir).lastOrNull() ?: refuse("CHECKPOINT_MISSING")
            verify(latest.snapshot, context, inspectLedger(ledgerPath, context.workspaceRoot))
            try { ResumeFenceAnchor.verify(ledgerPath, ResumeFenceStorage.latest(dir), dir) }
            catch (e: SessionResumeUnconfirmed) {
                throw CheckpointUnconfirmed("CHECKPOINT_RESUME_ANCHOR_UNCONFIRMED", e)
            }
            latest
        }
    }

    /** Fenced resume is intentionally outside #103; no verified snapshot is execution authority. */
    fun requireResumeAdmission(@Suppress("UNUSED_PARAMETER") checkpoint: VerifiedSessionCheckpoint): Nothing =
        throw CheckpointUnconfirmed("CHECKPOINT_RESUME_DENIED")
}

private const val CHECKPOINT_KIND = "host-checkpoint"
