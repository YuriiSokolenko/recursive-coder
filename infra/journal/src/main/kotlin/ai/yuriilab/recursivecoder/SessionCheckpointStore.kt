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
    val lastTrustedJournalOffset: Long
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
    private val DIGEST = Regex("[0-9a-f]{64}")
    private val GIT_HEAD = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    private val BRANCH = Regex("refs/heads/[A-Za-z0-9._/-]{1,200}")
    // Exposed by :core:session; no independent recovery-only session phases.
    private val PHASES = SessionPhaseVocabulary.wires
    // Wire spellings mirror RecursiveNodeState and TaskState. Do not conflate
    // a recursive child with a queued task or invent recovery-only states.
    private val CHILD_STATES = setOf("ready", "running", "succeeded", "failed",
        "cancelled", "timed_out")
    private val TASK_STATES = setOf("created", "ready", "blocked", "running",
        "paused", "succeeded", "failed", "needs-attention", "cancelled")
    private fun invalid(): Nothing = throw CheckpointUnconfirmed("CHECKPOINT_PAYLOAD_INVALID")
    private fun validId(id: String) = ToolAuthorizationPolicy.validHostIdentifier(id)
    private fun digest(value: String) = DIGEST.matches(value)

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
                s.taskState !in setOf("succeeded", "failed", "cancelled", "needs-attention")) ||
            (s.phase == "succeeded" && (s.taskState != "succeeded" ||
                s.pendingEffects.isNotEmpty() ||
                s.hostAssertedUsage.any { it.certainty == "unknown" })) ||
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
                    it.kind !in setOf("model", "tool") ||
                    it.certainty !in setOf("known", "unknown") ||
                    (it.certainty == "known" && (it.units == null || it.units < 0)) ||
                    (it.certainty == "unknown" && it.units != null)
            } || s.hostAssertedUsage.map { it.scopeId to it.kind }.toSet().size != s.hostAssertedUsage.size)
            invalid()
        if (s.pendingEffects.any { !validId(it.id) || !validId(it.correlationId) ||
                it.state !in setOf("pending", "unknown") } ||
            s.pendingEffects.map { it.id }.toSet().size != s.pendingEffects.size)
            invalid()
    }

    fun encode(s: SessionCheckpoint): ByteArray {
        validate(s)
        val b = s.binding
        val root = buildJsonObject {
            put("kind", "host-checkpoint")
            put("version", 1)
            put("sequence", s.sequence)
            put("previousSha256", s.previousSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("binding", buildJsonObject {
                put("policySha256", b.policySha256)
                put("workspaceIdentitySha256", b.workspaceIdentitySha256)
                put("gitHead", b.gitHead)
                put("gitBranch", b.gitBranch)
                put("gitBaselineSha256", b.gitBaselineSha256)
                put("graphId", b.graphId)
                put("graphGeneration", b.graphGeneration)
                put("frozenInputsSha256", b.frozenInputsSha256)
            })
            put("sessionId", s.sessionId)
            put("traceId", s.traceId)
            put("phase", s.phase)
            put("taskId", s.taskId)
            put("taskAttempt", s.taskAttempt)
            put("taskState", s.taskState)
            put("children", buildJsonArray {
                s.children.sortedBy { it.id }.forEach { child ->
                    add(buildJsonObject {
                        put("id", child.id); put("parentId", child.parentId)
                        put("state", child.state)
                    })
                }
            })
            put("acceptedArtifacts", buildJsonArray {
                s.acceptedArtifacts.sortedBy { it.id }.forEach { artifact ->
                    add(buildJsonObject {
                        put("id", artifact.id); put("ownerId", artifact.ownerId)
                        put("sha256", artifact.sha256)
                    })
                }
            })
            put("hostAssertedUsage", buildJsonArray {
                s.hostAssertedUsage.sortedWith(compareBy({ it.scopeId }, { it.kind })).forEach { usage ->
                    add(buildJsonObject {
                        put("scopeId", usage.scopeId); put("kind", usage.kind)
                        put("certainty", usage.certainty)
                        put("units", usage.units?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            })
            put("pendingEffects", buildJsonArray {
                s.pendingEffects.sortedBy { it.id }.forEach { effect ->
                    add(buildJsonObject {
                        put("id", effect.id); put("correlationId", effect.correlationId)
                        put("state", effect.state)
                    })
                }
            })
            put("journalSequence", s.journalSequence)
            put("journalHeadSha256", s.journalHeadSha256)
            put("journalFileSha256", s.journalFileSha256)
            put("lastTrustedJournalOffset", s.lastTrustedJournalOffset)
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
            val o = fields(obj(Json.parseToJsonElement(decoded)), "kind", "version", "sequence",
                "previousSha256", "binding", "sessionId", "traceId", "phase", "taskId",
                "taskAttempt", "taskState", "children", "acceptedArtifacts", "hostAssertedUsage",
                "pendingEffects", "journalSequence", "journalHeadSha256", "journalFileSha256",
                "lastTrustedJournalOffset")
            if (string(o, "kind") != "host-checkpoint" || long(o, "version") != 1L)
                throw CheckpointUnconfirmed("CHECKPOINT_VERSION_UNSUPPORTED")
            val b = fields(obj(o["binding"]), "policySha256", "workspaceIdentitySha256",
                "gitHead", "gitBranch", "gitBaselineSha256", "graphId",
                "graphGeneration", "frozenInputsSha256")
            val binding = CheckpointBinding(string(b, "policySha256"),
                string(b, "workspaceIdentitySha256"), string(b, "gitHead"),
                string(b, "gitBranch"), string(b, "gitBaselineSha256"),
                string(b, "graphId"), long(b, "graphGeneration"), string(b, "frozenInputsSha256"))
            val children = array(o, "children").map {
                val x = fields(obj(it), "id", "parentId", "state")
                CheckpointChild(string(x, "id"), string(x, "parentId"), string(x, "state"))
            }
            val artifacts = array(o, "acceptedArtifacts").map {
                val x = fields(obj(it), "id", "ownerId", "sha256")
                CheckpointArtifact(string(x, "id"), string(x, "ownerId"), string(x, "sha256"))
            }
            val usage = array(o, "hostAssertedUsage").map {
                val x = fields(obj(it), "scopeId", "kind", "certainty", "units")
                HostAssertedCheckpointUsage(string(x, "scopeId"), string(x, "kind"),
                    string(x, "certainty"),
                    if (x["units"] == JsonNull) null else long(x, "units"))
            }
            val effects = array(o, "pendingEffects").map {
                val x = fields(obj(it), "id", "correlationId", "state")
                CheckpointEffect(string(x, "id"), string(x, "correlationId"), string(x, "state"))
            }
            val attempt = long(o, "taskAttempt")
            val eventSequence = long(o, "journalSequence")
            if (attempt !in Int.MIN_VALUE..Int.MAX_VALUE ||
                eventSequence !in Int.MIN_VALUE..Int.MAX_VALUE)
                invalid()
            val s = SessionCheckpoint(long(o, "sequence"), nullableString(o, "previousSha256"),
                binding, string(o, "sessionId"), string(o, "traceId"), string(o, "phase"),
                string(o, "taskId"), attempt.toInt(), string(o, "taskState"),
                children, artifacts, usage, effects, eventSequence.toInt(),
                string(o, "journalHeadSha256"), string(o, "journalFileSha256"),
                long(o, "lastTrustedJournalOffset"))
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
        val lockPath = directory.resolve("checkpoint.lock")
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
            checkpoint.hostAssertedUsage.any { it.certainty == "unknown" } ||
                checkpoint.pendingEffects.isNotEmpty())
    }

    private fun readAll(directory: Path): List<VerifiedSessionCheckpoint> {
        val files = Files.newDirectoryStream(directory).use { it.toList() }
        val snapshots = mutableListOf<Path>()
        for (file in files) {
            val name = file.fileName.toString()
            when {
                name == "checkpoint.lock" -> {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        refuse("CHECKPOINT_UNSAFE_LOCK")
                }
                name == "resume.lock" -> {
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
        // Every published fence is verified on read, even outside resume admission.
        try { ResumeFenceStorage.latest(directory) }
        catch (e: SessionResumeUnconfirmed) {
            throw CheckpointUnconfirmed("CHECKPOINT_RESUME_FENCE_UNCONFIRMED", e)
        }
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
                            s.journalFileSha256 != previous.snapshot.journalFileSha256)))))
                refuse("CHECKPOINT_CHAIN_MISMATCH")
            result.add(current)
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
                event["sessionId"]?.jsonPrimitive?.content != s.sessionId ||
                    event["traceId"]?.jsonPrimitive?.content != s.traceId
            }) refuse("CHECKPOINT_SESSION_MISMATCH")
        val graph = setOf(s.binding.graphId) + s.children.map { it.id }
        for (event in ledger.events) {
            val metadata = event["metadata"] as? JsonObject ?: continue
            for (key in listOf("graphId", "parentGraphId", "childGraphId")) {
                val value = metadata[key]?.jsonPrimitive?.content ?: continue
                if (value !in graph) refuse("CHECKPOINT_GRAPH_MISMATCH")
            }
        }
        val eventCorrelations = ledger.events.mapNotNull {
            it["correlationId"]?.jsonPrimitive?.content
        }.toSet()
        if (s.pendingEffects.any { it.correlationId !in eventCorrelations })
            refuse("CHECKPOINT_CORRELATION_MISMATCH")

        // A missing usage entry cannot mean zero: first-party request/tool events
        // require an explicit known or unknown ledger entry in the snapshot.
        val eventTypes = ledger.events.mapNotNull { it["type"]?.jsonPrimitive?.content }
        if (eventTypes.any { it.startsWith("llm.") } &&
                s.hostAssertedUsage.none { it.kind == "model" } ||
            eventTypes.any { it.startsWith("tool.") } &&
                s.hostAssertedUsage.none { it.kind == "tool" })
            refuse("CHECKPOINT_USAGE_UNCONFIRMED")

        // Multiple tool starts can share a request correlation. Count them per
        // physical tool execution ID (or correlation when the ID is absent);
        // a single later completion must never clear two uncertain operations.
        fun toolKey(event: JsonObject): String {
            val actor = event["actor"] as? JsonObject
            val execution = actor?.get("toolExecutionId")
            return if (execution == null || execution == JsonNull)
                "correlation:" + event["correlationId"]!!.jsonPrimitive.content
            else "execution:" + execution.jsonPrimitive.content
        }
        val unmatched = mutableMapOf<String, ArrayDeque<String>>()
        for (event in ledger.events) {
            val type = event["type"]!!.jsonPrimitive.content
            if (type != "tool.started" && type != "tool.completed") continue
            val key = toolKey(event)
            if (type == "tool.started")
                unmatched.getOrPut(key) { ArrayDeque() }
                    .addLast(event["correlationId"]!!.jsonPrimitive.content)
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
                    val m = event["metadata"] as? JsonObject
                    event["status"]?.jsonPrimitive?.content == "completed" &&
                        m?.get("artifactId")?.jsonPrimitive?.content == artifact.id &&
                        m?.get("artifactSha256")?.jsonPrimitive?.content == artifact.sha256 &&
                        (m?.get("childGraphId") ?: m?.get("graphId"))
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

    /** An old or retired callback cannot publish after the first resume fence. */
    fun saveFenced(lease: SessionResumeLease, directory: Path,
                   context: CheckpointHostContext, ledgerPath: Path,
                   snapshot: SessionCheckpoint, expectedPreviousSha256: String?): VerifiedSessionCheckpoint =
        lease.publishIfCurrent {
            lease.requireLedgerPath(ledgerPath)
            persist(directory, context, snapshot, expectedPreviousSha256, {},
                LinuxCheckpointOwner::effectiveUid, lease) {
                inspectLedger(ledgerPath, context.workspaceRoot)
            }
        }

    fun saveFromOpenLedgerFenced(lease: SessionResumeLease, directory: Path,
                   context: CheckpointHostContext, ledger: DurableSessionEventLedger,
                   snapshot: SessionCheckpoint, expectedPreviousSha256: String?): VerifiedSessionCheckpoint =
        lease.publishIfCurrent {
            lease.requireLedgerPath(ledger.checkpointStoragePath)
            try {
                assertLedgerOutsideWorkspace(ledger.checkpointStoragePath, context.workspaceRoot)
                ledger.withCommittedCheckpointAnchor { anchor ->
                    persist(directory, context, snapshot, expectedPreviousSha256, {},
                        LinuxCheckpointOwner::effectiveUid, lease) { anchor }
                }
            } catch (e: SessionLedgerUnconfirmed) {
                val code = if (e.code == DurableSessionEventLedger.LIVE_CHECKPOINT_SIZE_EXCEEDED_CODE)
                    "CHECKPOINT_LIVE_LEDGER_TOO_LARGE" else "CHECKPOINT_JOURNAL_UNCONFIRMED"
                throw CheckpointUnconfirmed(code, e)
            }
        }

    private fun persist(directory: Path, context: CheckpointHostContext,
             snapshot: SessionCheckpoint, expectedPreviousSha256: String?,
             afterPhase: (String) -> Unit,
             effectiveUid: () -> Long?,
             fence: SessionResumeLease? = null,
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
            SessionCheckpointCodec.validate(snapshot)
            verify(snapshot, context, ledger)
            if (previous != null &&
                (snapshot.binding != previous.snapshot.binding ||
                    snapshot.sessionId != previous.snapshot.sessionId ||
                    snapshot.traceId != previous.snapshot.traceId ||
                    snapshot.journalSequence < previous.snapshot.journalSequence ||
                    snapshot.lastTrustedJournalOffset < previous.snapshot.lastTrustedJournalOffset))
                refuse("CHECKPOINT_CHAIN_MISMATCH")
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
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
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
            readback
        }
    }

    /**
     * Hold the short checkpoint lock over checkpoint/journal verification and
     * fence CAS. No host locks are retained across subsequent model or tool work.
     * resume.lock is acquired first by SessionResumeAdmission and stays held.
     */
    internal fun <T> withAdmissionEvidence(
        directory: Path, context: CheckpointHostContext, ledgerPath: Path,
        block: (Path, VerifiedSessionCheckpoint, VerifiedSessionEventLedger) -> T
    ): T {
        val dir = checkedAdmissionDirectory(directory, context)
        return withLease(dir, create = false) {
            val latest = readAll(dir).lastOrNull() ?: refuse("CHECKPOINT_MISSING")
            val ledger = inspectLedger(ledgerPath, context.workspaceRoot)
            verify(latest.snapshot, context, ledger)
            block(dir, latest, ledger)
        }
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
