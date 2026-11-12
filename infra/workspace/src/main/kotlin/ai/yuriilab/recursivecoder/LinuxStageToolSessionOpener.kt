package ai.yuriilab.recursivecoder

import java.nio.file.Path

/**
 * The only stage-to-filesystem bridge. All physical writes, path traversal,
 * Git baseline checks, bubblewrap execution and HMAC receipt validation remain
 * in TrustedWriteRuntime. No Koog/model surface is registered here.
 */
class LinuxStageToolSessionOpener(
    private val lockDirectory: Path =
        Path.of(System.getProperty("user.home"), ".recursive-coder", "locks")
) : StageToolSessionOpener {

    override fun open(authority: StageToolAuthoritySource): StageToolOpenResult {
        val snapshot = try { authority.current() }
            catch (_: Exception) { null }
            ?: return StageToolOpenResult.Denied("STAGE_AUTHORITY_UNAVAILABLE")
        if (!ToolAuthorizationPolicy.validHostIdentifier(snapshot.requestId))
            return StageToolOpenResult.Denied("INVALID_REQUEST_ID")
        val graph = snapshot.graph
        val stage = graph.stages.firstOrNull {
            it.correlation.stageRunId == snapshot.stageRunId
        } ?: return StageToolOpenResult.Denied("UNKNOWN_STAGE")
        if (stage.correlation.requestId != snapshot.requestId)
            return StageToolOpenResult.Denied("REQUEST_CORRELATION_MISMATCH")
        if (stage.kind.role == EngineeringRole.REVIEWER)
            return StageToolOpenResult.Denied("ROLE_NOT_AUTHORIZED")
        if (stage.state != StageState.READY && stage.state != StageState.RUNNING)
            return StageToolOpenResult.Denied("STAGE_NOT_READY")

        // A RUNNING stage has already crossed the READY -> RUNNING boundary.
        // Recheck the same prerequisites as an admission; do not transition it.
        val admissionGraph = if (stage.state == StageState.RUNNING)
            graph.copy(stages = graph.stages.map {
                if (it.correlation.stageRunId == snapshot.stageRunId)
                    it.copy(state = StageState.READY) else it
            })
        else graph
        val admitted = StageGraphPolicy.admitStage(
            admissionGraph, snapshot.stageRunId, snapshot.hostAcceptedArtifactIds)
        if (admitted is StageGateDecision.Deny)
            return StageToolOpenResult.Denied(admitted.code)

        val manifests = graph.artifacts.associateBy { it.id }
        if (snapshot.hostAcceptedArtifactIds.any { id ->
                manifests[id]?.approval != ArtifactApproval.ACCEPTED ||
                    manifests[id]?.freeze != ArtifactFreeze.FROZEN
            })
            return StageToolOpenResult.Denied("INVALID_HOST_ACCEPTANCE")

        val revision = snapshot.revisionAuthorization
        val reviewed = if (revision == null) null else {
            val artifact = manifests[revision.artifactId]
                ?: return StageToolOpenResult.Denied("UNKNOWN_ARTIFACT")
            if (artifact.freeze != ArtifactFreeze.FROZEN)
                return StageToolOpenResult.Denied("INVALID_REVISION_AUTHORIZATION")
            val decision = StageGraphPolicy.authorizeArtifactRevision(
                graph, revision.artifactId, stage.kind.role, revision)
            if (decision is StageGateDecision.Deny)
                return StageToolOpenResult.Denied(decision.code)
            ReviewedChange(revision.review.changeRequestId, revision.review.requestedBy,
                revision.review.reviewedBy, stage.kind.role, setOf(artifact.declaredPath))
        }
        // Pin every frozen manifest, including historical/superseded artifacts.
        // The host-accepted ID set is a separate trust fact and is never inferred
        // from model text or a manifest's self-reported approval.
        val pinned = graph.artifacts.filter { it.freeze == ArtifactFreeze.FROZEN }
            .associate { it.declaredPath to it.sha256 }
        val policy = TrustedWritePolicy(
            sessionId = graph.correlation.sessionId,
            root = snapshot.root,
            baselineHead = snapshot.baselineHead,
            branch = snapshot.branch,
            role = stage.kind.role,
            approvedArtifacts = pinned,
            reviewedChange = reviewed,
            checks = snapshot.checks.mapValues { (_, check) ->
                TrustedCheck(check.executable, check.arguments, check.timeoutMs, check.maxOutputBytes)
            },
            requiredChecks = snapshot.requiredChecks,
            maxToolCalls = snapshot.maxToolCalls
        )
        val runtime = try {
            TrustedWriteRuntime.open(policy, lockDirectory = lockDirectory)
        } catch (error: Refused) {
            return StageToolOpenResult.Denied(error.code)
        } catch (_: Exception) {
            return StageToolOpenResult.Denied("STAGE_TOOL_UNAVAILABLE")
        }
        val actor = stage.agentId ?: run {
            runtime.close()
            return StageToolOpenResult.Denied("MISSING_STAGE_ACTOR")
        }
        return StageToolOpenResult.Granted(
            LinuxStageToolSession(authority, snapshot, actor, stage.correlation,
                runtime, snapshot.toString())
        )
    }
}

private class LinuxStageToolSession(
    private val authority: StageToolAuthoritySource,
    private val admitted: StageToolHostSnapshot,
    private val actor: AgentId,
    private val correlation: StageCorrelation,
    private val runtime: TrustedWriteRuntime,
    private val admittedFingerprint: String
) : StageToolSession {
    private val passed = linkedMapOf<String, Pair<TrustedCheckReceipt, StageCheckEvidence>>()
    private var revoked = false
    private var finished = false
    private var closed = false

    private fun currentDenial(): String? {
        if (closed) return "WRITE_SESSION_INVALID"
        if (revoked) return "STALE_STAGE_AUTHORITY"
        if (finished) return "STAGE_TOOL_FINISHED"
        val now = try { authority.current() } catch (_: Exception) { null }
        if (now == null || now != admitted || now.toString() != admittedFingerprint) {
            revoked = true
            passed.clear()
            return "STALE_STAGE_AUTHORITY"
        }
        return null
    }

    @Synchronized
    override fun write(operations: List<StageWrite>): StageWriteEvidence {
        currentDenial()?.let { return StageWriteEvidence(null, it) }
        val writes = operations.map { op ->
            when (op) {
                is StageWrite.Create -> WriteOperation.Create(op.path, op.content)
                is StageWrite.Edit -> WriteOperation.Edit(op.path, op.expectedSha256, op.content)
                is StageWrite.Delete -> WriteOperation.Delete(op.path, op.expectedSha256)
            }
        }
        val result = runtime.apply(writes)
        // Conservative: even a rejected attempt cannot reuse older stage-check evidence.
        passed.clear()
        return StageWriteEvidence(result.toolId, result.deniedCode)
    }

    @Synchronized
    override fun runCheck(checkId: String): StageCheckEvidence {
        currentDenial()?.let { return StageCheckEvidence(checkId, null, deniedCode = it) }
        passed.remove(checkId)
        val result = runtime.runCheck(checkId)
        val receipt = result.receipt
        val evidence = if (result.passed && receipt != null)
            StageCheckEvidence(checkId, result.toolId, receipt.baselineHead,
                receipt.policyHash, receipt.manifestHash)
        else StageCheckEvidence(checkId, result.toolId, deniedCode =
            result.deniedCode ?: "CHECK_UNAVAILABLE")
        if (receipt != null && evidence.passed) passed[checkId] = receipt to evidence
        return evidence
    }

    @Synchronized
    override fun verify(): StageValidationEvidence {
        val denied = currentDenial()
        if (denied != null)
            return StageValidationEvidence(correlation, actor, emptyList(), false, denied)
        // Deliberately preserve the host-declared check order for diagnostics.
        val evidence = admitted.requiredChecks.sorted().mapNotNull { passed[it]?.second }
        val receipts = admitted.requiredChecks.sorted().mapNotNull { passed[it]?.first }
        val result = runtime.finish(receipts)
        if (result.successful) finished = true
        return StageValidationEvidence(correlation, actor, evidence,
            result.successful, result.deniedCode)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        passed.clear()
        runtime.close()
    }
}
