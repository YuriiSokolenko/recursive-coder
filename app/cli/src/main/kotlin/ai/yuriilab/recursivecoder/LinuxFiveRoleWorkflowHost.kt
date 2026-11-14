package ai.yuriilab.recursivecoder

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A live, read-only view of a stage output whose approval is backed by the
 * genuine write/check tool session and an HMAC held by the Linux host.
 *
 * This is NOT an independently issued child receipt or a writable capability.
 * A child may consume it only when its own stage-run identity matches the
 * producer recorded by the trusted host (no parent-to-child ID remapping).
 */
data class AuthenticatedAcceptedStageReceipt(
    val graphCorrelation: GraphCorrelation,
    val stageCorrelation: StageCorrelation,
    val origin: StageKind,
    val manifest: StageArtifactManifest,
    val writeToolId: String,
    val checkedIds: Set<String>,
    val writtenPostimages: Map<String, String>
)

/**
 * One in-process, Linux-only five-role workflow authority.
 *
 * The caller MUST select independently reviewed approvals, meaningful sandbox
 * checks, and an isolated checkout. Not exposed by the default read-only CLI.
 * No crash/restart recovery: a failure after mutation quarantines this instance.
 */
class LinuxFiveRoleWorkflowHost(
    workspace: Path,
    initial: StageGraph,
    initialHead: String,
    private val branch: String,
    private val checks: Map<String, StageIsolatedCheck>,
    private val requiredChecks: Set<String>,
    private val approvalGateway:
        (StageArtifactManifest, StageValidationEvidence, StageRoleUsage) -> PreparationApproval,
    lockDirectory: Path,
    /**
     * Trusted-host only: pre-admitted recursive stage identities for later
     * stages. Never fill this map from a model or serialized plan.
     */
    private val preauthorizedStageIdentities: Map<StageKind, Pair<StageRunId, String>> =
        emptyMap()
) : FiveRoleWorkflowHost, PreparationStageHost, StageToolSessionOpener {
    private val root = workspace.toRealPath(LinkOption.NOFOLLOW_LINKS)
    private val reconciler = LinuxStageGitBaselineReconciler(
        root, lockDirectory, gitExecutable = Path.of("/usr/bin/git")
    )
    private val linuxTools = LinuxStageToolSessionOpener(lockDirectory)
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    @Volatile private var lastGitBaselineDenialCode: String? = null
    private var graph = initial
    private var accepted = emptySet<ArtifactId>()
    private var head = initialHead
    private var quarantined = false
    private var writer: Writer? = null
    private var reviewing: ReviewerStageLease? = null
    private var reviewHandoff: ReviewerDeveloperHandoff? = null
    private var finalReview: ReviewerDecisionRecord? = null
    private val completed = mutableMapOf<StageRunId, AcceptedEvidence>()

    private data class Changed(val path: String, val before: String?, val after: String)
    private data class AttestedWrite(
        val generation: String,
        val stageId: StageRunId,
        val baseline: String,
        val validation: StageValidationEvidence,
        val writeId: String,
        val files: List<Changed>,
        val signature: String
    )
    private data class AcceptedEvidence(val write: AttestedWrite, val usage: StageRoleUsage)
    private data class Writer(
        val lease: PreparationStageLease,
        var opened: Boolean = false,
        var attemptedWrite: Boolean = false,
        var approvalPending: Boolean = false,
        var evidence: AttestedWrite? = null,
        var candidate: StageArtifactManifest? = null,
        var decision: PreparationApproval? = null,
        var usage: StageRoleUsage? = null
    )

    init {
        require(StageGraphPolicy.validate(graph) == StageGateDecision.Permit)
        require(graph.state == StageGraphState.RUNNING && graph.stages.size == 1 &&
            graph.stages.single().kind == StageKind.SDD &&
            graph.stages.single().state in setOf(StageState.CREATED, StageState.READY) &&
            graph.artifacts.isEmpty())
        require(Regex("[0-9a-f]{40}").matches(head))
        require(preauthorizedStageIdentities.keys.all { it != StageKind.SDD } &&
            preauthorizedStageIdentities.values.all { (_, requestId) ->
                ToolAuthorizationPolicy.validHostIdentifier(requestId)
            })
        require("git_diff_check" in requiredChecks &&
            (requiredChecks - "git_diff_check").isNotEmpty() &&
            (requiredChecks - "git_diff_check").all { it in checks })
        require(clean()) {
            "Git baseline rejected: " + (lastGitBaselineDenialCode ?: "UNKNOWN")
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    /**
     * Canonical versioned, length-prefixed encoding; no data-class toString(),
     * delimiter ambiguity, platform charset, or mutable collection iteration.
     */
    private fun payload(value: AttestedWrite): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            fun field(text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            fun optional(text: String?) {
                output.writeBoolean(text != null)
                if (text != null) field(text)
            }
            field("recursive-coder-attested-write-v1")
            field(value.generation)
            field(value.stageId.value)
            field(value.baseline)
            val validation = value.validation
            val correlation = validation.correlation
            field(correlation.sessionId)
            field(correlation.graphId.value)
            field(correlation.stageRunId.value)
            field(correlation.requestId)
            field(correlation.correlationId)
            optional(correlation.causationId)
            field(validation.actor.value)
            output.writeBoolean(validation.successful)
            optional(validation.deniedCode)
            output.writeInt(validation.requiredChecks.size)
            validation.requiredChecks.forEach { check ->
                field(check.checkId)
                optional(check.toolId)
                optional(check.baselineHead)
                optional(check.policyHash)
                optional(check.manifestHash)
                optional(check.deniedCode)
            }
            field(value.writeId)
            output.writeInt(value.files.size)
            value.files.forEach { changed ->
                field(changed.path)
                optional(changed.before)
                field(changed.after)
            }
        }
        return buffer.toByteArray()
    }

    private fun mac(value: ByteArray): String {
        val signer = Mac.getInstance("HmacSHA256")
        signer.init(SecretKeySpec(secret, "HmacSHA256"))
        return signer.doFinal(value).joinToString("") { "%02x".format(it) }
    }

    private fun authentic(value: AttestedWrite) = MessageDigest.isEqual(
        value.signature.toByteArray(Charsets.US_ASCII),
        mac(payload(value)).toByteArray(Charsets.US_ASCII)
    )

    /**
     * Best-effort operator diagnostic for the latest baseline inspection,
     * not a per-call result: concurrent clean() checks overwrite this shared
     * volatile field. Use the reconciler's typed result for call-local facts.
     */
    fun lastBaselineDenialCode(): String? = lastGitBaselineDenialCode

    /** Shared scrubbed Git admission, now with one overall five-second budget. */
    private fun clean(): Boolean {
        val result = reconciler.inspectCleanBaseline(head, branch)
        lastGitBaselineDenialCode = (result as? StageGitBaselineResult.Denied)?.code
        return result == StageGitBaselineResult.Clean
    }

    /**
     * Read authorization is established by trusted stage artifact/target policy.
     * The path guard below also refuses protected write-policy paths in case
     * another caller accidentally supplies a model-controlled name.
     *
     * NOFOLLOW_LINKS + a descriptor-relative regular-file check reject FIFOs,
     * devices and symlink entries. External hostile same-UID swaps still require
     * the caller's isolated-checkout deployment contract.
     */
    override fun readBytes(path: String): ByteArray? {
        if (!ToolAuthorizationPolicy.validDeclaredPath(path) ||
            StageKind.entries.none { kind -> kind != StageKind.REVIEW &&
                ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    kind.role, WriteOperationKind.CREATE, path
                )) == ToolAuthorizationDecision.Permit }) return null
        fun read(dir: SecureDirectoryStream<Path>, names: List<String>): ByteArray {
            if (names.size > 1) {
                dir.newDirectoryStream(Path.of(names.first()), LinkOption.NOFOLLOW_LINKS).use {
                    val child = it as? SecureDirectoryStream<Path>
                        ?: error("Secure directory stream required")
                    return read(child, names.drop(1))
                }
            }
            val name = Path.of(names.single())
            val view = dir.getFileAttributeView(name,
                BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?: error("Regular-file attributes required")
            require(view.readAttributes().isRegularFile) {
                "Only ordinary regular files may be read"
            }
            dir.newByteChannel(name,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
                val result = Channels.newInputStream(it).readNBytes(8 * 1024 * 1024 + 1)
                require(result.size <= 8 * 1024 * 1024)
                return result
            }
        }
        return try {
            Files.newDirectoryStream(root).use {
                val dir = it as? SecureDirectoryStream<Path> ?: return null
                read(dir, path.split("/"))
            }
        } catch (_: Exception) { null }
    }

    private fun frozenIntact(): Boolean = graph.artifacts
        .filter { it.id in accepted || it.freeze == ArtifactFreeze.FROZEN }
        .all { item ->
            readBytes(item.declaredPath)?.let { digest(it) == item.sha256 } == true
        }

    private fun idle() = !quarantined && writer == null && reviewing == null
    private fun newId(prefix: String) = prefix + "-" + UUID.randomUUID().toString()

    @Synchronized
    override fun snapshot(): PreparationStageSnapshot {
        check(!quarantined) { "WORKSPACE_RECONCILIATION_REQUIRED" }
        return PreparationStageSnapshot(graph, accepted.toSet())
    }

    @Synchronized
    override fun beginStage(expectedGraph: StageGraph, stageRunId: StageRunId,
        expectedAcceptedIds: Set<ArtifactId>): PreparationStageLease? {
        if (!idle() || graph != expectedGraph || accepted != expectedAcceptedIds ||
            !clean() || !frozenIntact()) return null
        val stage = graph.stages.singleOrNull { it.correlation.stageRunId == stageRunId }
            ?: return null
        if (stage.kind == StageKind.REVIEW) return null
        val ready = if (stage.state == StageState.CREATED) {
            if (StageGraphPolicy.authorizeStageTransition(graph, stageRunId,
                    StageState.READY, accepted) != StageGateDecision.Permit) return null
            graph.copy(stages = graph.stages.map {
                if (it.correlation.stageRunId == stageRunId)
                    it.copy(state = StageState.READY) else it
            })
        } else if (stage.state == StageState.READY) graph else return null
        if (StageGraphPolicy.admitStage(ready, stageRunId, accepted) !=
            StageGateDecision.Permit) return null
        val running = ready.copy(stages = ready.stages.map {
            if (it.correlation.stageRunId == stageRunId)
                it.copy(state = StageState.RUNNING) else it
        })
        val generation = newId("lease")
        val facts = StageToolHostSnapshot(running, stageRunId, stage.correlation.requestId,
            accepted.toSet(), root, head, branch, checks, requiredChecks)
        val authority = StageToolAuthoritySource {
            synchronized(this) {
                if (!quarantined && writer?.lease?.generation == generation &&
                    graph == running && accepted == facts.hostAcceptedArtifactIds &&
                    head == facts.baselineHead) facts else null
            }
        }
        val lease = PreparationStageLease(generation, stageRunId,
            running, accepted.toSet(), authority)
        graph = running
        writer = Writer(lease)
        return lease
    }

    // Interactive approval/resume requires a separate durable authorization flow.
    // Never turn an unverified pending draft into a new executable generation.
    override fun beginHumanApproval(expectedGraph: StageGraph, stageRunId: StageRunId,
        expectedAcceptedIds: Set<ArtifactId>): PreparationStageLease? = null

    @Synchronized
    override fun current(lease: PreparationStageLease): Boolean =
        !quarantined && writer?.lease == lease && graph == lease.graph &&
            accepted == lease.acceptedIds

    /**
     * TrustedWriteRuntime verifies its own HMAC checks in verify() before this
     * wrapper issues a host-HMAC-signed, generation-fenced handoff attestation.
     */
    override fun open(authority: StageToolAuthoritySource): StageToolOpenResult {
        val owner = synchronized(this) {
            val active = writer
            if (quarantined || active == null || active.lease.authority !== authority ||
                active.opened) return StageToolOpenResult.Denied("STALE_STAGE_AUTHORITY")
            active.opened = true
            active
        }
        val opened = try { linuxTools.open(authority) }
            catch (_: Exception) { StageToolOpenResult.Denied("STAGE_TOOL_UNAVAILABLE") }
        if (opened is StageToolOpenResult.Denied) {
            synchronized(this) { if (writer === owner) owner.opened = false }
            return opened
        }
        val inner = (opened as StageToolOpenResult.Granted).session
        return StageToolOpenResult.Granted(object : StageToolSession {
            private var wrote = false
            private var writeId: String? = null
            private var files = emptyList<Changed>()

            override fun write(operations: List<StageWrite>): StageWriteEvidence {
                if (wrote || operations.isEmpty() || operations.size > 8 ||
                    operations.any { it is StageWrite.Delete } ||
                    operations.map { it.path }.toSet().size != operations.size)
                    return StageWriteEvidence(null, "UNSUPPORTED_STAGE_CHANGESET")
                wrote = true
                synchronized(this@LinuxFiveRoleWorkflowHost) {
                    if (writer !== owner || quarantined)
                        return StageWriteEvidence(null, "STALE_STAGE_AUTHORITY")
                    owner.attemptedWrite = true
                }
                val result = inner.write(operations)
                if (result.permitted) {
                    writeId = result.toolId
                    files = operations.map {
                        when (it) {
                            is StageWrite.Create -> Changed(it.path, null,
                                digest(it.content.toByteArray(Charsets.UTF_8)))
                            is StageWrite.Edit -> Changed(it.path, it.expectedSha256,
                                digest(it.content.toByteArray(Charsets.UTF_8)))
                            is StageWrite.Delete -> error("Deletes are disallowed")
                        }
                    }.sortedBy { it.path }
                }
                return result
            }

            override fun runCheck(checkId: String) = inner.runCheck(checkId)

            override fun verify(): StageValidationEvidence {
                val verification = inner.verify()
                val id = writeId
                if (verification.successful && id != null && files.isNotEmpty()) {
                    synchronized(this@LinuxFiveRoleWorkflowHost) {
                        if (writer === owner && current(owner.lease)) {
                            val draft = AttestedWrite(owner.lease.generation,
                                owner.lease.stageRunId, head,
                                verification.copy(
                                    requiredChecks = verification.requiredChecks.toList()),
                                id, files.toList(), "")
                            owner.evidence = draft.copy(signature = mac(payload(draft)))
                        }
                    }
                }
                return verification
            }

            override fun close() = inner.close()
        })
    }

    /**
     * Never run an external approval decision while holding the host monitor.
     * The generation is reserved under lock, and the decision is discarded if
     * the lease was aborted, changed, or the approved physical bytes drifted.
     */
    override fun approve(candidate: StageArtifactManifest,
        evidence: StageValidationEvidence, usage: StageRoleUsage): PreparationApproval {
        val active = synchronized(this) {
            val active = writer ?: return PreparationApproval.REJECT
            val checked = active.evidence ?: return PreparationApproval.REJECT
            if (active.approvalPending || active.decision != null ||
                !current(active.lease) || !authentic(checked) ||
                checked.validation != evidence ||
                checked.stageId != candidate.producerStageRunId ||
                candidate.ownerRole != graph.stages.single {
                    it.correlation.stageRunId == checked.stageId
                }.kind.role ||
                candidate.approval != ArtifactApproval.DRAFT ||
                candidate.freeze != ArtifactFreeze.EDITABLE ||
                checked.files.none { it.path == candidate.declaredPath &&
                    it.after == candidate.sha256 } || !frozenIntact() ||
                readBytes(candidate.declaredPath)?.let { digest(it) == candidate.sha256 } != true)
                return PreparationApproval.REJECT
            active.approvalPending = true
            active
        }
        val result = try {
            approvalGateway(candidate, evidence, usage)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            PreparationApproval.REJECT
        } catch (_: Exception) {
            // A failed external decision never creates a trusted approval.
            PreparationApproval.REJECT
        }
        return synchronized(this) {
            active.approvalPending = false
            val checked = active.evidence
            val valid = writer === active && current(active.lease) &&
                checked != null && authentic(checked) &&
                checked.validation == evidence &&
                checked.files.all { file ->
                    readBytes(file.path)?.let { digest(it) == file.after } == true
                } && frozenIntact()
            if (!valid) {
                // Never re-prompt a human gateway on a lease whose bytes/fence
                // drifted while approval was pending. An aborted lease stays
                // revoked; only the still-current writer gets a terminal deny.
                if (writer === active) {
                    active.candidate = candidate
                    active.decision = PreparationApproval.REJECT
                    active.usage = usage.copy()
                }
                PreparationApproval.REJECT
            } else {
                active.candidate = candidate
                active.decision = result
                active.usage = usage.copy()
                result
            }
        }
    }

    @Synchronized
    override fun finishStage(lease: PreparationStageLease,
        updatedGraph: StageGraph, updatedAcceptedIds: Set<ArtifactId>,
        pending: PreparationPendingApproval?): Boolean {
        val active = writer ?: return false
        if (!current(lease) || pending != null ||
            !PreparationStageFinishPolicy.permits(lease, graph, accepted,
                updatedGraph, updatedAcceptedIds, null, active.decision,
                active.candidate)) return false
        val next = updatedGraph.stages.singleOrNull {
            it.correlation.stageRunId == lease.stageRunId
        } ?: return false
        if (next.state == StageState.ACCEPTED) {
            val proof = active.evidence ?: return false
            val manifest = updatedGraph.artifacts.singleOrNull {
                it.id in next.outputArtifacts
            } ?: return false
            if (!authentic(proof) || proof.generation != lease.generation ||
                proof.baseline != head || proof.stageId != lease.stageRunId ||
                proof.files.none { it.path == manifest.declaredPath &&
                    it.after == manifest.sha256 } ||
                proof.files.any { item ->
                    readBytes(item.path)?.let { digest(it) == item.after } != true
                } || !frozenIntact()) return false
            val usage = active.usage ?: return false
            completed[lease.stageRunId] = AcceptedEvidence(proof, usage)
        } else if (active.attemptedWrite) {
            quarantined = true
        }
        graph = updatedGraph
        accepted = updatedAcceptedIds.toSet()
        writer = null
        return true
    }

    @Synchronized
    override fun abortStage(lease: PreparationStageLease,
        terminal: StageState): PreparationStageSnapshot? {
        val active = writer ?: return null
        if (!current(lease) || terminal !in setOf(StageState.FAILED, StageState.CANCELLED))
            return null
        graph = graph.copy(stages = graph.stages.map {
            if (it.correlation.stageRunId == lease.stageRunId)
                it.copy(state = terminal) else it
        })
        if (active.attemptedWrite) quarantined = true
        writer = null
        return PreparationStageSnapshot(graph, accepted.toSet())
    }

    /**
     * Read-only, live evidence gate for recursive child integration. A manifest
     * in the StageGraph (or a model-provided digest) is never sufficient. This
     * checks the genuine generation-fenced TrustedWriteRuntime attestation,
     * required checks, accepted/frozen output and all written bytes again.
     *
     * The caller must separately validate the child's recursive topology,
     * ownership and stageRunId. No delegation or synthetic acceptance occurs.
     */
    @Synchronized
    fun authenticatedAcceptedStage(stageRunId: StageRunId):
        AuthenticatedAcceptedStageReceipt? {
        if (quarantined || writer != null || reviewing != null) return null
        val stage = graph.stages.singleOrNull {
            it.correlation.stageRunId == stageRunId &&
                it.state == StageState.ACCEPTED && it.kind != StageKind.REVIEW &&
                it.supersededBy == null
        } ?: return null
        val proof = completed[stageRunId]?.write ?: return null
        val manifest = graph.artifacts.singleOrNull {
            it.id in stage.outputArtifacts
        } ?: return null
        if (stage.outputArtifacts.size != 1 ||
            manifest.id !in accepted || manifest.producerStageRunId != stageRunId ||
            manifest.ownerRole != stage.kind.role ||
            manifest.kind != stage.kind.output ||
            manifest.approval != ArtifactApproval.ACCEPTED ||
            manifest.freeze != ArtifactFreeze.FROZEN ||
            !authentic(proof) || proof.stageId != stageRunId ||
            proof.validation.correlation != stage.correlation ||
            proof.validation.actor != stage.agentId ||
            !proof.validation.successful ||
            proof.validation.deniedCode != null ||
            proof.writeId.isBlank() ||
            proof.validation.requiredChecks.map { it.checkId }.toSet() != requiredChecks ||
            proof.validation.requiredChecks.size != requiredChecks.size ||
            proof.validation.requiredChecks.any { it.deniedCode != null } ||
            proof.files.none { it.path == manifest.declaredPath &&
                it.after == manifest.sha256 } ||
            proof.files.map { it.path }.toSet().size != proof.files.size ||
            !frozenIntact()) return null
        if (proof.files.any { item ->
                readBytes(item.path)?.let { digest(it) == item.after } != true
            }) return null
        return AuthenticatedAcceptedStageReceipt(graph.correlation, stage.correlation,
            stage.kind, manifest, proof.writeId,
            proof.validation.requiredChecks.map { it.checkId }.toSet(),
            proof.files.associate { it.path to it.after })
    }

    private fun verifiedDeveloper(evidence: DeveloperReviewEvidence): Boolean {
        val acceptedEvidence = completed[evidence.manifest.producerStageRunId] ?: return false
        val proof = acceptedEvidence.write
        return acceptedEvidence.usage == evidence.usage &&
            authentic(proof) && proof.validation == evidence.validation &&
            proof.writeId == evidence.writeToolId &&
            proof.files.filter { it.path != evidence.manifest.declaredPath }
                .map { ReviewerChangedFile(it.path, it.before, it.after) } ==
                evidence.changedFiles.sortedBy { it.path } &&
            proof.files.any { it.path == evidence.manifest.declaredPath &&
                it.after == evidence.manifest.sha256 } &&
            graph.artifacts.contains(evidence.manifest) &&
            evidence.manifest.id in accepted &&
            proof.files.all { item ->
                readBytes(item.path)?.let { digest(it) == item.after } == true
            } && frozenIntact()
    }

    @Synchronized
    override fun advance(expected: PreparationStageSnapshot, next: StageKind,
        developerHandoff: DeveloperReviewEvidence?): PreparationStageSnapshot? {
        if (!idle() || graph != expected.graph || accepted != expected.acceptedIds ||
            !frozenIntact() || next == StageKind.SDD) return null
        val previous = graph.stages.lastOrNull() ?: return null
        if (StageKind.entries.indexOf(next) !=
            StageKind.entries.indexOf(previous.kind) + 1 ||
            previous.state != StageState.ACCEPTED) return null
        val proof = completed[previous.correlation.stageRunId]?.write ?: return null
        if (!authentic(proof) || proof.baseline != head) return null
        if (next == StageKind.REVIEW) {
            val evidence = developerHandoff ?: return null
            if (!verifiedDeveloper(evidence)) return null
        } else if (developerHandoff != null) return null
        val frozen = graph.artifacts.filter { it.id in accepted &&
            proof.files.none { changed -> changed.path == it.declaredPath } }
            .associate { it.declaredPath to it.sha256 }
        val status = reconciler.reconcile(StageGitReconciliationRequest(head, branch,
            previous.kind.role,
            proof.files.map { StageGitAcceptedFile(it.path, it.after) }, frozen))
        val newHead = (status as? StageGitReconciliationResult.Committed)?.newHead
        if (newHead == null) {
            quarantined = true
            return null
        }
        head = newHead
        val dependencies = graph.stages.filter {
            it.kind.output in next.requiredInputs && it.state == StageState.ACCEPTED
        }
        val planned = preauthorizedStageIdentities[next]
        val newStageId = planned?.first ?: StageRunId(newId("run-" + next.name.lowercase()))
        if (graph.stages.any { it.correlation.stageRunId == newStageId }) {
            quarantined = true
            return null
        }
        val newStage = StageNode(
            StageCorrelation(graph.correlation.sessionId, graph.correlation.graphId,
                newStageId, planned?.second ?: newId("request"), newId("correlation")),
            next, StageState.CREATED,
            AgentId(newId("actor-" + next.name.lowercase())),
            dependencies.map { it.correlation.stageRunId }.toSet(),
            dependencies.flatMap { it.outputArtifacts }.toSet()
        )
        val result = graph.copy(stages = graph.stages + newStage)
        if (StageGraphPolicy.validate(result) != StageGateDecision.Permit ||
            !frozenIntact() || !clean()) {
            quarantined = true
            return null
        }
        graph = result
        if (next == StageKind.REVIEW) {
            val evidence = requireNotNull(developerHandoff)
            reviewHandoff = ReviewerDeveloperHandoff(evidence,
                graph.stages.dropLast(1).map {
                    ReviewerHistoryEntry(it.correlation, it.kind.role, it.state)
                })
        }
        return PreparationStageSnapshot(graph, accepted.toSet())
    }

    /**
     * Separate reviewer adapter: reviewer and preparation beginStage have
     * identical parameters but different lease return types in Kotlin.
     * The Reviewer never sees StageToolSessionOpener or writer authority.
     */
    val reviewerHost: ReviewerStageHost = object : ReviewerStageHost {
        override fun readBytes(path: String) = this@LinuxFiveRoleWorkflowHost.readBytes(path)

        override fun beginStage(expectedGraph: StageGraph, stageRunId: StageRunId,
            expectedAcceptedIds: Set<ArtifactId>): ReviewerStageLease? =
            synchronized(this@LinuxFiveRoleWorkflowHost) {
                if (!idle() || graph != expectedGraph || accepted != expectedAcceptedIds ||
                    !clean() || !frozenIntact()) return@synchronized null
                val stage = graph.stages.singleOrNull {
                    it.correlation.stageRunId == stageRunId && it.kind == StageKind.REVIEW
                } ?: return@synchronized null
                val handoff = reviewHandoff ?: return@synchronized null
                if (!verifiedDeveloper(handoff.developerResult) ||
                    stage.state != StageState.CREATED ||
                    StageGraphPolicy.authorizeStageTransition(graph, stageRunId,
                        StageState.READY, accepted) != StageGateDecision.Permit)
                    return@synchronized null
                val ready = graph.copy(stages = graph.stages.map {
                    if (it.correlation.stageRunId == stageRunId)
                        it.copy(state = StageState.READY) else it
                })
                if (StageGraphPolicy.admitStage(ready, stageRunId, accepted) !=
                    StageGateDecision.Permit) return@synchronized null
                val running = ready.copy(stages = ready.stages.map {
                    if (it.correlation.stageRunId == stageRunId)
                        it.copy(state = StageState.RUNNING) else it
                })
                val lease = ReviewerStageLease(newId("review-lease"), stageRunId,
                    running, accepted.toSet(), handoff)
                graph = running
                reviewing = lease
                lease
            }

        override fun current(lease: ReviewerStageLease): Boolean =
            synchronized(this@LinuxFiveRoleWorkflowHost) {
                !quarantined && reviewing == lease && graph == lease.graph &&
                    accepted == lease.acceptedIds
            }

        override fun verifyDeveloperEvidence(lease: ReviewerStageLease): Boolean =
            synchronized(this@LinuxFiveRoleWorkflowHost) {
                current(lease) && reviewHandoff == lease.handoff &&
                    verifiedDeveloper(lease.handoff.developerResult) && clean()
            }

        override fun finishStage(lease: ReviewerStageLease,
            updatedGraph: StageGraph, acceptedIds: Set<ArtifactId>,
            decision: ReviewerDecisionRecord): Boolean =
            synchronized(this@LinuxFiveRoleWorkflowHost) {
                if (!current(lease) || !verifyDeveloperEvidence(lease) ||
                    ReviewerStageFinishPolicy.permits(lease, updatedGraph,
                        acceptedIds, decision, true) != StageGateDecision.Permit ||
                    !frozenIntact()) return@synchronized false
                graph = updatedGraph
                reviewing = null
                finalReview = decision
                true
            }

        override fun abortStage(lease: ReviewerStageLease,
            terminal: StageState): ReviewerStageSnapshot? =
            synchronized(this@LinuxFiveRoleWorkflowHost) {
                if (!current(lease) ||
                    terminal !in setOf(StageState.FAILED, StageState.CANCELLED))
                    return@synchronized null
                graph = graph.copy(stages = graph.stages.map {
                    if (it.correlation.stageRunId == lease.stageRunId)
                        it.copy(state = terminal) else it
                })
                reviewing = null
                ReviewerStageSnapshot(graph, accepted.toSet())
            }
    }

    @Synchronized
    override fun finishSuccess(expected: PreparationStageSnapshot,
        reviewerId: StageRunId): StageGraph? {
        if (!idle() || graph != expected.graph || accepted != expected.acceptedIds ||
            finalReview?.correlation?.stageRunId != reviewerId ||
            finalReview?.verdict != ReviewerDisposition.ACCEPT ||
            reviewHandoff?.let { verifiedDeveloper(it.developerResult) } != true ||
            !frozenIntact() || !clean() ||
            StageGraphPolicy.authorizeGraphTransition(graph, StageGraphState.SUCCEEDED,
                accepted, setOf(reviewerId)) != StageGateDecision.Permit) return null
        graph = graph.copy(state = StageGraphState.SUCCEEDED)
        return graph
    }
}
/**
 * Strict adapter for an already independently executed *child* stage. A
 * trusted caller binds the exact child stage IDs; the adapter will not turn
 * a parent's accepted write into an invented child receipt.
 *
 * A future child-stage writer must use its actual RecursiveGraphPolicy-
 * derived stageRunId as the authenticated Linux stage identity. An existing
 * parent-stage output deliberately fails this check.
 */
class LinuxAuthenticatedRecursiveChildIntegrationHost(
    private val host: LinuxFiveRoleWorkflowHost,
    private val authorizedChildStages: Set<StageRunId>
) : RecursiveChildIntegrationHost {
    override fun acceptedArtifactIds(): Set<ArtifactId> =
        authorizedChildStages.mapNotNull { host.authenticatedAcceptedStage(it)?.manifest?.id }
            .toSet()

    override fun manifestsFor(child: RecursiveNode): List<StageArtifactManifest>? {
        val parentId = child.parentGraphId ?: return null
        val parentStageId = child.parentStageRunId ?: return null
        val requestId = child.requestId ?: return null
        if (child.stageRunId !in authorizedChildStages ||
            child.graphId != RecursiveGraphPolicy.childId(parentId, parentStageId, requestId) ||
            child.stageRunId != RecursiveGraphPolicy.childStageId(child.graphId))
            return null
        val receipt = host.authenticatedAcceptedStage(child.stageRunId) ?: return null
        if (receipt.origin != child.origin ||
            receipt.graphCorrelation.graphId != child.graphId ||
            receipt.graphCorrelation.parentGraphId != child.parentGraphId ||
            receipt.graphCorrelation.parentStageRunId != child.parentStageRunId ||
            receipt.stageCorrelation.stageRunId != child.stageRunId ||
            receipt.stageCorrelation.requestId != requestId ||
            receipt.stageCorrelation.graphId != child.graphId ||
            receipt.manifest.declaredPath !in child.writablePaths ||
            receipt.manifest.declaredPath in child.frozenPaths)
            return null
        return listOf(receipt.manifest)
    }

    override fun readBytes(path: String): ByteArray? = host.readBytes(path)
}
