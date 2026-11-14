package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * A deliberately separate, fake-only installed CLI entry point. It does not make
 * SessionRequest v1 or the native task scheduler writable. Only the Linux host
 * owns Git, artifact acceptance and trusted stage-tool capabilities.
 *
 * The operator provisions a dedicated checkout and a separate, plan-bound
 * approval file before opting in. The marker is an explicit deployment assertion,
 * NOT a substitute for OS-level filesystem isolation of the checkout.
 */
/**
 * Pure, canonical child checkout scope validation. The CLI independently
 * verifies the actual Git baseline/marker after this structural check.
 * Inputs must already be canonicalized; no model controls any of these paths.
 */
internal fun installedChildCheckoutScopesDisjoint(
    root: Path, locks: Path, parent: Path, parentLocks: Path,
    configPaths: List<Path>, eventsPath: Path?,
    earlierRoots: Set<Path>, earlierLocks: Set<Path>, cwd: Path
): Boolean {
    fun separate(a: Path, b: Path): Boolean =
        !a.startsWith(b) && !b.startsWith(a)
    if (!separate(root, locks) || !separate(root, parent) ||
        !separate(root, parentLocks) || !separate(locks, parent) ||
        !separate(locks, parentLocks) || cwd.startsWith(root)) return false
    if (earlierRoots.any { !separate(root, it) || !separate(locks, it) } ||
        earlierLocks.any { !separate(root, it) || !separate(locks, it) })
        return false
    return (configPaths + listOfNotNull(eventsPath)).none {
        it.startsWith(root) || it.startsWith(locks)
    }
}

internal object WritableWorkflowCli {
    private val kinds = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD,
        StageKind.DEVELOP, StageKind.REVIEW)
    private val writers = kinds.dropLast(1)
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val headPattern = Regex("[0-9a-f]{40}")

    private class WorkflowDenied(val code: String) : IllegalArgumentException()

    private fun gate(allowed: Boolean, code: String) {
        if (!allowed) throw WorkflowDenied(code)
    }

    /** Resolve symlinked parents, including the existing prefix of new lock paths. */
    private fun realLocation(path: Path): Path {
        require(path.isAbsolute)
        var prefix = path.normalize()
        val missing = ArrayDeque<Path>()
        while (!Files.exists(prefix, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(prefix.fileName ?: error("Invalid external path"))
            prefix = prefix.parent ?: error("Invalid external path")
        }
        var resolved = prefix.toRealPath()
        for (component in missing) resolved = resolved.resolve(component)
        return resolved.normalize()
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun objectAt(value: JsonObject, name: String): JsonObject =
        value.getValue(name) as? JsonObject ?: error("Invalid $name")
    private fun stringAt(value: JsonObject, name: String): String =
        (value.getValue(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Invalid $name")
    private fun longAt(value: JsonObject, name: String): Long =
        (value.getValue(name) as? JsonPrimitive)?.longOrNull
            ?: error("Invalid $name")
    private fun only(value: JsonObject, names: Set<String>,
        optional: Set<String> = emptySet()) {
        require(value.keys.containsAll(names) && (value.keys - names).all { it in optional }) {
            "Unexpected workflow configuration fields"
        }
    }

    private fun input(path: Path): ByteArray {
        require(path.isAbsolute && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(path) in 1L..65536L) { "Invalid operator configuration file" }
        return Files.readAllBytes(path)
    }

    private fun parse(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: error("Configuration must be a JSON object")

    fun execute(planPath: Path, approvalPath: Path): Int {
        val outcome = try { run(planPath, approvalPath) }
        catch (denied: WorkflowDenied) {
            "denied" to denied.code
        } catch (_: Exception) {
            // Never expose paths, prompts, Git output, checks or credentials.
            "denied" to "INVALID_WORKFLOW_CONFIGURATION"
        }
        System.out.println(buildJsonObject {
            put("outcome", outcome.first)
            put("code", outcome.second)
        })
        return when (outcome.first) {
            "completed" -> 0
            "reconciliation_required" -> 1
            else -> 2
        }
    }

    private fun run(planPath: Path, approvalPath: Path): Pair<String, String> {
        require(System.getProperty("os.name").lowercase().contains("linux")) {
            "Writable workflows require Linux"
        }
        val planBytes = input(planPath)
        val plan = parse(planBytes)
        val requiredPlanFields = setOf("version", "sessionId", "workspace", "artifacts",
            "instructions", "developerTargets", "fakeProposals",
            "fakeReviewerVerdict", "check", "limits")
        only(plan, requiredPlanFields, setOf("eventsPath", "recursiveChildren"))
        require(longAt(plan, "version") == 1L)
        val approval = parse(input(approvalPath))
        only(approval, setOf("version", "planSha256", "approvedSha256"))
        gate(longAt(approval, "version") == 1L &&
            stringAt(approval, "planSha256") == sha(planBytes),
            "APPROVAL_MISMATCH")
        val approved = objectAt(approval, "approvedSha256")
        require(approved.keys == writers.map { it.name }.toSet())
        val permittedDigests = writers.associateWith { kind ->
            stringAt(approved, kind.name).also { require(digestPattern.matches(it)) }
        }

        val workspace = objectAt(plan, "workspace")
        only(workspace, setOf("rootPath", "headSha", "branch", "lockDirectory"))
        val root = Path.of(stringAt(workspace, "rootPath"))
        val locks = Path.of(stringAt(workspace, "lockDirectory"))
        val expectedHead = stringAt(workspace, "headSha")
        val branch = stringAt(workspace, "branch")
        gate(root.isAbsolute && locks.isAbsolute &&
            headPattern.matches(expectedHead) &&
            Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS),
            "INVALID_WORKSPACE")
        val checkout = root.toRealPath()
        gate(checkout == root.normalize(), "INVALID_WORKSPACE")
        gate(!Path.of("").toAbsolutePath().toRealPath().startsWith(checkout),
            "CWD_INSIDE_CHECKOUT")
        gate(!realLocation(planPath).startsWith(checkout) &&
            !realLocation(approvalPath).startsWith(checkout),
            "CONFIG_INSIDE_CHECKOUT")
        val realLocks = realLocation(locks)
        gate(!realLocks.startsWith(checkout) && !checkout.startsWith(realLocks),
            "LOCK_OVERLAPS_CHECKOUT")
        // An optional journal is exclusively created outside the checkout and
        // lock scope. The journal is metadata-only, never an artifact approval.
        val eventsPath = plan["eventsPath"]?.let {
            val name = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw WorkflowDenied("INVALID_EVENT_JOURNAL_PATH")
            val path = try { Path.of(name) }
                catch (_: InvalidPathException) {
                    throw WorkflowDenied("INVALID_EVENT_JOURNAL_PATH")
                }
            gate(path.isAbsolute, "INVALID_EVENT_JOURNAL_PATH")
            val location = try { realLocation(path) }
                catch (_: Exception) {
                    throw WorkflowDenied("INVALID_EVENT_JOURNAL_PATH")
                }
            gate(!location.startsWith(checkout) &&
                !location.startsWith(realLocks) &&
                !realLocks.startsWith(location) &&
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS),
                "INVALID_EVENT_JOURNAL_PATH")
            path
        }
        val marker = checkout.resolve(".git/recursive-coder-isolated-workflow")
        gate(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
            Files.readString(marker) == "isolated-workflow-v1\n",
            "MARKER_MISSING")
        // The shared read-only reconciler owns Git ref validation and typed
        // baseline denial codes. The workflow host independently rechecks.
        when (val baseline = LinuxStageGitBaselineReconciler(checkout, locks)
            .inspectCleanBaseline(expectedHead, branch)) {
            StageGitBaselineResult.Clean -> Unit
            is StageGitBaselineResult.Denied -> throw WorkflowDenied(baseline.code)
        }
        val artifacts = objectAt(plan, "artifacts")
        val instructions = objectAt(plan, "instructions")
        val proposals = objectAt(plan, "fakeProposals")
        require(artifacts.keys == writers.map { it.name }.toSet() &&
            instructions.keys == kinds.map { it.name }.toSet() &&
            proposals.keys == writers.map { it.name }.toSet())
        val targets = writers.associateWith { kind ->
            val target = objectAt(artifacts, kind.name)
            only(target, setOf("id", "path"))
            FiveRoleArtifactTarget(ArtifactId(stringAt(target, "id")),
                stringAt(target, "path"))
        }
        val stageInstructions = kinds.associateWith { stringAt(instructions, it.name) }
        val fixtureProposals = writers.associateWith { stringAt(proposals, it.name) }
        require(stageInstructions.values.all { it.isNotBlank() } &&
            fixtureProposals.values.all { it.isNotBlank() })
        val developerTargets = (plan.getValue("developerTargets") as? JsonArray)
            ?.map { entry ->
                val target = entry as? JsonObject ?: error("Invalid Developer target")
                require(target.keys == setOf("path", "expectedSha256") ||
                    target.keys == setOf("path"))
                val before = target["expectedSha256"]?.let {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                        ?: error("Invalid Developer preimage")
                }
                if (before != null) require(digestPattern.matches(before))
                DeveloperFileTarget(stringAt(target, "path"), before)
            } ?: error("Developer targets are required")
        val verdict = stringAt(plan, "fakeReviewerVerdict")
        require(verdict in setOf("ACCEPT", "REJECT", "NEEDS_HUMAN"))

        val check = objectAt(plan, "check")
        only(check, setOf("executable", "arguments"))
        val executable = Path.of(stringAt(check, "executable"))
        val arguments = (check.getValue("arguments") as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: error("Invalid check argument")
        } ?: error("Invalid check arguments")
        require(executable.isAbsolute && Files.isExecutable(executable) &&
            executable.toString() !in setOf("/usr/bin/true", "/bin/true") &&
            arguments.size <= 16 && arguments.all { it.length <= 1024 }) {
            "A meaningful operator-selected isolated check is required"
        }

        val limits = objectAt(plan, "limits")
        only(limits, setOf("maxTokens", "maxDurationMillis", "deadlineSeconds"),
            setOf("maxDepth", "maxChildren", "maxToolCalls", "childTokens"))
        val tokens = longAt(limits, "maxTokens")
        val duration = longAt(limits, "maxDurationMillis")
        val deadlineSeconds = longAt(limits, "deadlineSeconds")
        require(tokens in 100..1000000 && duration in 1000..600000 &&
            deadlineSeconds in 1..600)
        val sessionId = stringAt(plan, "sessionId")
        require(sessionId.length <= 100 &&
            ToolAuthorizationPolicy.validHostIdentifier(sessionId))
        val recursiveConfig = plan["recursiveChildren"]?.let { raw ->
            val children = raw as? JsonObject ?: error("Invalid recursive children")
            require(children.keys == setOf("SDD", "DEVELOP"))
            val allRoots = mutableSetOf<Path>()
            val allLocks = mutableSetOf<Path>()
            listOf(StageKind.SDD, StageKind.DEVELOP).associateWith { kind ->
                val entry = objectAt(children, kind.name)
                only(entry, setOf("rootPath", "headSha", "branch",
                    "lockDirectory", "requestId"),
                    if (kind == StageKind.DEVELOP)
                        setOf("dependsOnSelf", "outputArtifactId", "maxTokens")
                    else setOf("dependsOnSelf", "maxTokens"))
                val childPath = Path.of(stringAt(entry, "rootPath"))
                val childLockPath = Path.of(stringAt(entry, "lockDirectory"))
                val childHead = stringAt(entry, "headSha")
                val childBranch = stringAt(entry, "branch")
                val requestId = stringAt(entry, "requestId")
                gate(childPath.isAbsolute && childLockPath.isAbsolute &&
                    Files.isDirectory(childPath.resolve(".git"), LinkOption.NOFOLLOW_LINKS) &&
                    headPattern.matches(childHead) && childHead == expectedHead &&
                    childBranch == branch &&
                    ToolAuthorizationPolicy.validHostIdentifier(requestId),
                    "INVALID_CHILD_WORKSPACE")
                val childRoot = childPath.toRealPath()
                val childLocks = realLocation(childLockPath)
                gate(childRoot == childPath.normalize() &&
                    installedChildCheckoutScopesDisjoint(
                        childRoot, childLocks, checkout, realLocks,
                        listOf(realLocation(planPath), realLocation(approvalPath)),
                        eventsPath?.let(::realLocation),
                        allRoots, allLocks,
                        Path.of("").toAbsolutePath().toRealPath()),
                    "CHILD_WORKSPACE_OVERLAP")
                allRoots.add(childRoot)
                allLocks.add(childLocks)
                val marker = childRoot.resolve(".git/recursive-coder-isolated-workflow")
                gate(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
                    Files.readString(marker) == "isolated-workflow-v1\n",
                    "CHILD_MARKER_MISSING")
                when (val state = LinuxStageGitBaselineReconciler(childRoot, childLocks)
                    .inspectCleanBaseline(childHead, childBranch)) {
                    StageGitBaselineResult.Clean -> Unit
                    is StageGitBaselineResult.Denied ->
                        throw WorkflowDenied(state.code)
                }
                val self = entry["dependsOnSelf"]?.let {
                    (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull
                        ?: error("Invalid cyclic test fixture")
                } ?: false
                val overrideId = entry["outputArtifactId"]?.let {
                    ArtifactId((it as? JsonPrimitive)?.takeIf { p -> p.isString }
                        ?.content ?: error("Invalid child artifact ID"))
                }
                val childTokenOverride = entry["maxTokens"]?.let {
                    (it as? JsonPrimitive)?.longOrNull
                        ?: error("Invalid child token grant")
                }
                require(childTokenOverride == null ||
                    childTokenOverride in 1..1000000)
                InstalledChildCheckout(childRoot, childHead, childBranch,
                    childLocks, requestId, self, overrideId, childTokenOverride)
            }
        }
        val maxDepth = (limits["maxDepth"] as? JsonPrimitive)?.intOrNull ?: 1
        val maxChildren = (limits["maxChildren"] as? JsonPrimitive)?.intOrNull ?: 1
        val maxToolCalls = (limits["maxToolCalls"] as? JsonPrimitive)?.longOrNull ?: 6L
        val childTokens = (limits["childTokens"] as? JsonPrimitive)?.longOrNull ?: 500L
        require(maxDepth in 0..8 && maxChildren in 0..8 &&
            maxToolCalls in 0..128 && childTokens in 1..1000000)
        require(recursiveConfig != null || limits.keys ==
            setOf("maxTokens", "maxDurationMillis", "deadlineSeconds"))
        val graphId = GraphId(sessionId + "-graph")
        val correlation = GraphCorrelation(sessionId, graphId, sessionId + "-workflow")
        val first = StageNode(StageCorrelation(sessionId, graphId,
            StageRunId(sessionId + "-sdd"), sessionId + "-request",
            sessionId + "-sdd-correlation"),
            StageKind.SDD, StageState.CREATED, AgentId(sessionId + "-sdd-agent"))
        val graph = StageGraph(correlation, StageGraphState.RUNNING, listOf(first),
            emptyList(), StageBudget(tokens, duration, 0, 0))
        require(StageGraphPolicy.validate(graph) == StageGateDecision.Permit)
        val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
            "env:FAKE_UNUSED", 8192, 512, setOf("tool_calls"), null, 1000, 1000)
        val host = LinuxFiveRoleWorkflowHost(checkout, graph, expectedHead, branch,
            mapOf("offline-check" to StageIsolatedCheck(executable, arguments)),
            setOf("git_diff_check", "offline-check"),
            { candidate, _, _ ->
                val kind = writers.singleOrNull { it.role == candidate.ownerRole }
                if (kind != null && targets.getValue(kind).path == candidate.declaredPath &&
                    targets.getValue(kind).id == candidate.id &&
                    permittedDigests[kind] == candidate.sha256)
                    PreparationApproval.ACCEPT else PreparationApproval.REJECT
            }, locks)
        // Child review is independently accepted in the offline fixture. The
        // configured verdict remains authoritative for the parent Reviewer,
        // including a rejection *after* successful child integration.
        val childExecutor = ConfiguredFakeRoleExecutor(fixtureProposals, "ACCEPT")
        val recursive = recursiveConfig?.let { configs ->
            InstalledRecursiveWorkflow(sessionId, host,
                StageIsolatedCheck(executable, arguments),
                targets, stageInstructions, developerTargets,
                permittedDigests, model,
                Instant.now().plusSeconds(deadlineSeconds),
                Cancellation(), childExecutor, configs, tokens, duration,
                maxDepth, maxChildren, maxToolCalls, childTokens, null)
        }
        val callback: (suspend (StageRoleRequest) -> String?)? =
            if (recursive == null) null
            else { request -> recursive.beforeProposal(request) }
        val executor = ConfiguredFakeRoleExecutor(fixtureProposals, verdict, callback)
        val runner = RunFiveRoleWorkflowUseCase(
            RunPreparationStageUseCase(executor, host, host),
            RunDeveloperStageUseCase(executor, host, host),
            RunReviewerStageUseCase(executor, host.reviewerHost), recursive ?: host)
        val journal = try {
            eventsPath?.let { path ->
                // If construction fails *after* CREATE_NEW, release the open
                // writer. Keep the file as diagnostic evidence: a retry must
                // use a newly provisioned path, never overwrite an old journal.
                val sink = JsonlEventSink.open(path)
                try {
                    SessionEventJournal(sessionId, sessionId + "-trace",
                        sessionId + "-workflow", Clock.systemUTC(), sink)
                } catch (failure: Exception) {
                    try { sink.close() } catch (closing: Exception) {
                        failure.addSuppressed(closing)
                    }
                    throw failure
                }
            }
        } catch (_: Exception) {
            return "denied" to "EVENT_JOURNAL_UNAVAILABLE"
        }
        recursive?.attachJournal(journal)
        val result = try {
            runBlocking {
                runner.execute(FiveRoleWorkflowPlan(targets, stageInstructions,
                    developerTargets, model, Instant.now().plusSeconds(deadlineSeconds),
                    Cancellation()),
                    journal?.let(::CorrelatedRoleSessionEvents) ?: StageRoleEventSink { },
                    journal?.let(::CorrelatedPreparationSessionEvents)
                        ?: PreparationStageEventSink { },
                    journal?.let(::CorrelatedReviewerSessionEvents)
                        ?: ReviewerDecisionSink { })
            }
        } catch (_: Exception) {
            // A stage may have written or advanced HEAD before an unexpected
            // failure. Never classify an unconfirmed runtime as a safe denial.
            try { journal?.close() } catch (_: Exception) { }
            return "reconciliation_required" to "WORKFLOW_EXECUTION_UNCONFIRMED"
        }
        // A failed or partially flushed event stream cannot accompany a
        // successful workflow. Always verify the real bytes after close.
        if (journal != null) {
            try { journal.finish() } catch (_: Exception) {
                return "reconciliation_required" to "EVENT_JOURNAL_UNCONFIRMED"
            }
        }
        if (recursive?.reconciliationRequired == true)
            return "reconciliation_required" to "CHILD_EXECUTION_UNCONFIRMED"
        return when (result) {
            is FiveRoleWorkflowOutcome.Completed ->
                "completed" to result.graph.state.name
            is FiveRoleWorkflowOutcome.Stopped ->
                "stopped" to result.code
            is FiveRoleWorkflowOutcome.ReconciliationRequired ->
                "reconciliation_required" to result.code
        }
    }

    /**
     * Offline stage fixture (not a live LLM). Validates the real FakeModel gateway
     * contract and emits proposal metadata, never hands a model write authority.
     * Review evidence IDs come from the trusted Reviewer prompt at execution time.
     */
    private class ConfiguredFakeRoleExecutor(
        private val proposals: Map<StageKind, String>,
        private val verdict: String,
        private val beforeProposal: (suspend (StageRoleRequest) -> String?)? = null
    ) : StageRoleExecutor {
        private val gateway: ModelGateway = FakeModel()

        override suspend fun execute(request: StageRoleRequest,
            events: StageRoleEventSink): StageRoleResult {
            val stage = request.graph.stages.single { it.correlation.stageRunId == request.stageRunId }
            require(stage.kind.role == request.expectedRole &&
                stage.correlation.requestId == request.requestId &&
                request.requestedTools.isEmpty())
            gateway.negotiate(request.model, request.cancellation, request.deadline)
            events.emit(StageRoleEvent(stage.correlation, stage.kind.role, stage.agentId,
                StageRoleEventKind.STARTED, StageRoleUsage()))
            val refusal = beforeProposal?.invoke(request)
            if (refusal != null) {
                events.emit(StageRoleEvent(stage.correlation, stage.kind.role,
                    stage.agentId, StageRoleEventKind.DENIED, StageRoleUsage(), refusal))
                return StageRoleResult(stage.correlation, stage.kind.role, stage.agentId,
                    StageRoleOutcome.Denied(refusal), StageRoleUsage())
            }
            val proposal = if (stage.kind == StageKind.REVIEW) {
                val checks = Regex("""<verified-check id="([^"]+)" receipt="[^"]+" />""")
                    .findAll(request.instruction).map { "check:" + it.groupValues[1] }.toList()
                val write = Regex("""<verified-write id="([^"]+)" />""")
                    .find(request.instruction)?.groupValues?.get(1)
                    ?: error("Trusted Reviewer write evidence missing")
                val refs = (checks + "write:$write").joinToString(",") {
                    "\"$it\""
                }
                val (code, scope) = when (verdict) {
                    "ACCEPT" -> "OK" to "NONE"
                    "REJECT" -> "IMPLEMENTATION_DEFECT" to "IMPLEMENTATION_ONLY"
                    else -> "EVIDENCE_MISSING" to "NONE"
                }
                """{"verdict":"$verdict","code":"$code","scope":"$scope","evidence":[$refs]}"""
            } else proposals.getValue(stage.kind)
            val usage = StageRoleUsage(1, 100, 30)
            events.emit(StageRoleEvent(stage.correlation, stage.kind.role, stage.agentId,
                StageRoleEventKind.PROPOSED, usage))
            return StageRoleResult(stage.correlation, stage.kind.role, stage.agentId,
                StageRoleOutcome.Proposed(proposal), usage)
        }
    }
}
