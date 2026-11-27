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

private const val INVALID_EVENT_JOURNAL_PATH = "INVALID_EVENT_JOURNAL_PATH"

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
        !separate(locks, parentLocks) || cwd.startsWith(root) ||
        !separate(cwd, locks)) return false
    if (earlierRoots.any { !separate(root, it) || !separate(locks, it) } ||
        earlierLocks.any { !separate(root, it) || !separate(locks, it) })
        return false
    return (configPaths + listOfNotNull(eventsPath)).none {
        it.startsWith(root) || it.startsWith(locks)
    }
}

internal object WritableWorkflowCli {
    // Offline plan and approval formats are independent of Session Protocol v1.
    private object PlanFields {
        const val VERSION = "version"
        const val SESSION_ID = "sessionId"
        const val WORKSPACE = "workspace"
        const val ARTIFACTS = "artifacts"
        const val INSTRUCTIONS = "instructions"
        const val DEVELOPER_TARGETS = "developerTargets"
        const val FAKE_PROPOSALS = "fakeProposals"
        const val FAKE_REVIEWER_VERDICT = "fakeReviewerVerdict"
        const val CHECK = "check"
        const val LIMITS = "limits"
        const val EVENTS_PATH = "eventsPath"
        const val RECURSIVE_CHILDREN = "recursiveChildren"
    }

    private object ApprovalFields {
        const val VERSION = "version"
        const val PLAN_SHA256 = "planSha256"
        const val APPROVED_SHA256 = "approvedSha256"
    }

    private object CheckoutFields {
        const val ROOT_PATH = "rootPath"
        const val HEAD_SHA = "headSha"
        const val BRANCH = "branch"
        const val LOCK_DIRECTORY = "lockDirectory"
    }

    private object ArtifactFields {
        const val ID = "id"
        const val PATH = "path"
    }

    private object DeveloperTargetFields {
        const val PATH = "path"
        const val EXPECTED_SHA256 = "expectedSha256"
    }

    private object CheckFields {
        const val EXECUTABLE = "executable"
        const val ARGUMENTS = "arguments"
    }

    private object LimitFields {
        const val MAX_TOKENS = "maxTokens"
        const val MAX_DURATION_MILLIS = "maxDurationMillis"
        const val DEADLINE_SECONDS = "deadlineSeconds"
        const val MAX_DEPTH = "maxDepth"
        const val MAX_CHILDREN = "maxChildren"
        const val MAX_TOOL_CALLS = "maxToolCalls"
        const val CHILD_TOKENS = "childTokens"
    }

    private object ChildFields {
        const val REQUEST_ID = "requestId"
        const val DEPENDS_ON_SELF = "dependsOnSelf"
        const val OUTPUT_ARTIFACT_ID = "outputArtifactId"
        const val MAX_TOKENS = "maxTokens"
        const val MAX_TOOL_CALLS = "maxToolCalls"
    }

    private const val ISOLATION_MARKER_PATH = ".git/recursive-coder-isolated-workflow"
    private const val ISOLATION_MARKER_CONTENT = "isolated-workflow-v1\n"
    private val childTokenRange = 1L..1_000_000L
    private const val COMPLETED = "completed"
    private const val RECONCILIATION_REQUIRED = "reconciliation_required"

    private val kinds = FiveRoleWorkflowStages.order
    private val writers = kinds.dropLast(1)
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val headPattern = Regex("[0-9a-f]{40}")

    /** CLI-only diagnostics, independent of Session Protocol v1. */
    internal data class CliOutcome(
        val status: String, val code: String, val reviewerDenialCode: String? = null
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("outcome", status)
            put("code", code)
            reviewerDenialCode?.let { put("reviewerDenialCode", it) }
        }
    }

    internal fun summarize(result: FiveRoleWorkflowOutcome): CliOutcome = when (result) {
        is FiveRoleWorkflowOutcome.Completed -> CliOutcome(COMPLETED, result.graph.state.name)
        is FiveRoleWorkflowOutcome.Stopped ->
            CliOutcome("stopped", result.code, result.reviewerDenialCode)
        is FiveRoleWorkflowOutcome.ReconciliationRequired ->
            CliOutcome(RECONCILIATION_REQUIRED, result.code, result.reviewerDenialCode)
    }

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
    private val wholeUnsigned = Regex("0|[1-9][0-9]*")
    private fun strictLong(raw: JsonElement?, name: String): Long {
        val value = raw as? JsonPrimitive ?: error("Invalid $name")
        require(!value.isString && wholeUnsigned.matches(value.content)) {
            "Invalid $name"
        }
        return value.content.toLongOrNull() ?: error("Invalid $name")
    }
    private fun longAt(value: JsonObject, name: String): Long =
        strictLong(value[name], name)

    private fun optionalLong(value: JsonObject, name: String, default: Long): Long =
        if (name in value) strictLong(value[name], name) else default
    private fun optionalInt(value: JsonObject, name: String, default: Int): Int =
        if (name in value) strictLong(value[name], name).let {
            require(it <= Int.MAX_VALUE) { "Invalid $name" }
            it.toInt()
        } else default
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
            CliOutcome("denied", denied.code)
        } catch (_: Exception) {
            // Never expose paths, prompts, Git output, checks or credentials.
            CliOutcome("denied", "INVALID_WORKFLOW_CONFIGURATION")
        }
        System.out.println(outcome.json())
        return when (outcome.status) {
            COMPLETED -> 0
            RECONCILIATION_REQUIRED -> 1
            else -> 2
        }
    }

    private fun run(planPath: Path, approvalPath: Path): CliOutcome {
        require(System.getProperty("os.name").lowercase().contains("linux")) {
            "Writable workflows require Linux"
        }
        val planBytes = input(planPath)
        val plan = parse(planBytes)
        val requiredPlanFields = setOf(PlanFields.VERSION, PlanFields.SESSION_ID,
            PlanFields.WORKSPACE, PlanFields.ARTIFACTS, PlanFields.INSTRUCTIONS,
            PlanFields.DEVELOPER_TARGETS, PlanFields.FAKE_PROPOSALS,
            PlanFields.FAKE_REVIEWER_VERDICT, PlanFields.CHECK, PlanFields.LIMITS)
        only(plan, requiredPlanFields, setOf(PlanFields.EVENTS_PATH, PlanFields.RECURSIVE_CHILDREN))
        require(longAt(plan, PlanFields.VERSION) == 1L)
        val approval = parse(input(approvalPath))
        only(approval, setOf(ApprovalFields.VERSION, ApprovalFields.PLAN_SHA256, ApprovalFields.APPROVED_SHA256))
        gate(longAt(approval, ApprovalFields.VERSION) == 1L &&
            stringAt(approval, ApprovalFields.PLAN_SHA256) == sha(planBytes),
            "APPROVAL_MISMATCH")
        val approved = objectAt(approval, ApprovalFields.APPROVED_SHA256)
        require(approved.keys == writers.map { it.name }.toSet())
        val permittedDigests = writers.associateWith { kind ->
            stringAt(approved, kind.name).also { require(digestPattern.matches(it)) }
        }

        val workspace = objectAt(plan, PlanFields.WORKSPACE)
        only(workspace, setOf(CheckoutFields.ROOT_PATH, CheckoutFields.HEAD_SHA,
            CheckoutFields.BRANCH, CheckoutFields.LOCK_DIRECTORY))
        val root = Path.of(stringAt(workspace, CheckoutFields.ROOT_PATH))
        val locks = Path.of(stringAt(workspace, CheckoutFields.LOCK_DIRECTORY))
        val expectedHead = stringAt(workspace, CheckoutFields.HEAD_SHA)
        val branch = stringAt(workspace, CheckoutFields.BRANCH)
        gate(root.isAbsolute && locks.isAbsolute &&
            headPattern.matches(expectedHead) &&
            Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS),
            WorkspaceDenialCodes.INVALID_WORKSPACE)
        val checkout = root.toRealPath()
        gate(checkout == root.normalize(), WorkspaceDenialCodes.INVALID_WORKSPACE)
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
        val eventsPath = plan[PlanFields.EVENTS_PATH]?.let {
            val name = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw WorkflowDenied(INVALID_EVENT_JOURNAL_PATH)
            val path = try { Path.of(name) }
                catch (_: InvalidPathException) {
                    throw WorkflowDenied(INVALID_EVENT_JOURNAL_PATH)
                }
            gate(path.isAbsolute, INVALID_EVENT_JOURNAL_PATH)
            val location = try { realLocation(path) }
                catch (_: Exception) {
                    throw WorkflowDenied(INVALID_EVENT_JOURNAL_PATH)
                }
            gate(!location.startsWith(checkout) &&
                !location.startsWith(realLocks) &&
                !realLocks.startsWith(location) &&
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS),
                INVALID_EVENT_JOURNAL_PATH)
            path
        }
        val marker = checkout.resolve(ISOLATION_MARKER_PATH)
        gate(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
            Files.readString(marker) == ISOLATION_MARKER_CONTENT,
            "MARKER_MISSING")
        // The shared read-only reconciler owns Git ref validation and typed
        // baseline denial codes. The workflow host independently rechecks.
        when (val baseline = LinuxStageGitBaselineReconciler(checkout, locks)
            .inspectCleanBaseline(expectedHead, branch)) {
            StageGitBaselineResult.Clean -> Unit
            is StageGitBaselineResult.Denied -> throw WorkflowDenied(baseline.code)
        }
        val artifacts = objectAt(plan, PlanFields.ARTIFACTS)
        val instructions = objectAt(plan, PlanFields.INSTRUCTIONS)
        val proposals = objectAt(plan, PlanFields.FAKE_PROPOSALS)
        require(artifacts.keys == writers.map { it.name }.toSet() &&
            instructions.keys == kinds.map { it.name }.toSet() &&
            proposals.keys == writers.map { it.name }.toSet())
        val targets = writers.associateWith { kind ->
            val target = objectAt(artifacts, kind.name)
            only(target, setOf(ArtifactFields.ID, ArtifactFields.PATH))
            FiveRoleArtifactTarget(ArtifactId(stringAt(target, ArtifactFields.ID)),
                stringAt(target, ArtifactFields.PATH))
        }
        val stageInstructions = kinds.associateWith { stringAt(instructions, it.name) }
        val fixtureProposals = writers.associateWith { stringAt(proposals, it.name) }
        require(stageInstructions.values.all { it.isNotBlank() } &&
            fixtureProposals.values.all { it.isNotBlank() })
        val developerTargets = (plan.getValue(PlanFields.DEVELOPER_TARGETS) as? JsonArray)
            ?.map { entry ->
                val target = entry as? JsonObject ?: error("Invalid Developer target")
                require(target.keys == setOf(DeveloperTargetFields.PATH, DeveloperTargetFields.EXPECTED_SHA256) ||
                    target.keys == setOf(DeveloperTargetFields.PATH))
                val before = target[DeveloperTargetFields.EXPECTED_SHA256]?.let {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                        ?: error("Invalid Developer preimage")
                }
                if (before != null) require(digestPattern.matches(before))
                DeveloperFileTarget(stringAt(target, DeveloperTargetFields.PATH), before)
            } ?: error("Developer targets are required")
        val verdict = stringAt(plan, PlanFields.FAKE_REVIEWER_VERDICT)
        require(verdict in setOf(ReviewerDisposition.ACCEPT.name, ReviewerDisposition.REJECT.name, ReviewerDisposition.NEEDS_HUMAN.name))

        val check = objectAt(plan, PlanFields.CHECK)
        only(check, setOf(CheckFields.EXECUTABLE, CheckFields.ARGUMENTS))
        val executable = Path.of(stringAt(check, CheckFields.EXECUTABLE))
        val arguments = (check.getValue(CheckFields.ARGUMENTS) as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: error("Invalid check argument")
        } ?: error("Invalid check arguments")
        // Accident guard, not a quality guarantee (issue #163): resolve symlinks and
        // refuse constant-result binaries. Multi-call binaries (e.g. coreutils)
        // dispatch on the invoked name, so both the configured and the resolved
        // file names are checked. Command launchers such as `env` are refused
        // outright: their option grammar (`-u NAME`, `-S`, `VAR=value`, ...) makes
        // the launched program unparseable here, so the check must name the
        // target executable directly.
        val constant = setOf("true", "false")
        val launchers = setOf("env")
        val names = if (executable.isAbsolute && Files.isExecutable(executable))
            setOfNotNull(executable.fileName?.toString(),
                executable.toRealPath().fileName?.toString()) else emptySet()
        require(names.isNotEmpty() && names.none { it in constant || it in launchers } &&
            arguments.size <= 16 && arguments.all { it.length <= 1024 }) {
            "A meaningful operator-selected isolated check is required"
        }

        val limits = objectAt(plan, PlanFields.LIMITS)
        only(limits, setOf(LimitFields.MAX_TOKENS, LimitFields.MAX_DURATION_MILLIS,
            LimitFields.DEADLINE_SECONDS), setOf(LimitFields.MAX_DEPTH,
            LimitFields.MAX_CHILDREN, LimitFields.MAX_TOOL_CALLS, LimitFields.CHILD_TOKENS))
        val tokens = longAt(limits, LimitFields.MAX_TOKENS)
        val duration = longAt(limits, LimitFields.MAX_DURATION_MILLIS)
        val deadlineSeconds = longAt(limits, LimitFields.DEADLINE_SECONDS)
        require(tokens in 100..1000000 && duration in 1000..600000 &&
            deadlineSeconds in 1..600)
        val sessionId = stringAt(plan, PlanFields.SESSION_ID)
        require(sessionId.length <= 100 &&
            ToolAuthorizationPolicy.validHostIdentifier(sessionId))
        val recursiveConfig = plan[PlanFields.RECURSIVE_CHILDREN]?.let { raw ->
            val children = raw as? JsonObject ?: error("Invalid recursive children")
            // StageKind names are part of this installed plan's JSON key contract.
            require(children.keys == setOf(StageKind.SDD.name, StageKind.DEVELOP.name))
            val allRoots = mutableSetOf<Path>()
            val allLocks = mutableSetOf<Path>()
            listOf(StageKind.SDD, StageKind.DEVELOP).associateWith { kind ->
                val entry = objectAt(children, kind.name)
                only(entry, setOf(CheckoutFields.ROOT_PATH, CheckoutFields.HEAD_SHA,
                    CheckoutFields.BRANCH, CheckoutFields.LOCK_DIRECTORY, ChildFields.REQUEST_ID),
                    if (kind == StageKind.DEVELOP)
                        setOf(ChildFields.DEPENDS_ON_SELF, ChildFields.OUTPUT_ARTIFACT_ID,
                            ChildFields.MAX_TOKENS, ChildFields.MAX_TOOL_CALLS)
                    else setOf(ChildFields.DEPENDS_ON_SELF, ChildFields.MAX_TOKENS, ChildFields.MAX_TOOL_CALLS))
                val childPath = Path.of(stringAt(entry, CheckoutFields.ROOT_PATH))
                val childLockPath = Path.of(stringAt(entry, CheckoutFields.LOCK_DIRECTORY))
                val childHead = stringAt(entry, CheckoutFields.HEAD_SHA)
                val childBranch = stringAt(entry, CheckoutFields.BRANCH)
                val requestId = stringAt(entry, ChildFields.REQUEST_ID)
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
                val marker = childRoot.resolve(ISOLATION_MARKER_PATH)
                gate(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
                    Files.readString(marker) == ISOLATION_MARKER_CONTENT,
                    "CHILD_MARKER_MISSING")
                when (val state = LinuxStageGitBaselineReconciler(childRoot, childLocks)
                    .inspectCleanBaseline(childHead, childBranch)) {
                    StageGitBaselineResult.Clean -> Unit
                    is StageGitBaselineResult.Denied ->
                        throw WorkflowDenied(state.code)
                }
                val self = entry[ChildFields.DEPENDS_ON_SELF]?.let {
                    (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull
                        ?: error("Invalid cyclic test fixture")
                } ?: false
                val overrideId = entry[ChildFields.OUTPUT_ARTIFACT_ID]?.let {
                    ArtifactId((it as? JsonPrimitive)?.takeIf { p -> p.isString }
                        ?.content ?: error("Invalid child artifact ID"))
                }
                // dependsOnSelf and outputArtifactId are offline-only
                // adversarial fixture knobs, never live-provider features.
                val childTokenOverride = if (ChildFields.MAX_TOKENS in entry)
                    strictLong(entry[ChildFields.MAX_TOKENS], "child maxTokens") else null
                val childToolOverride = if (ChildFields.MAX_TOOL_CALLS in entry)
                    strictLong(entry[ChildFields.MAX_TOOL_CALLS], "child maxToolCalls") else null
                require(childTokenOverride == null ||
                    childTokenOverride in childTokenRange)
                require(childToolOverride == null || childToolOverride in 0..128)
                InstalledChildCheckout(childRoot, childHead, childBranch,
                    childLocks, requestId, self, overrideId,
                    childTokenOverride, childToolOverride)
            }
        }
        val maxDepth = optionalInt(limits, LimitFields.MAX_DEPTH, 1)
        val maxChildren = optionalInt(limits, LimitFields.MAX_CHILDREN, 1)
        // Each Developer child runs four independent trusted write/check cycles.
        val maxToolCalls = optionalLong(limits, LimitFields.MAX_TOOL_CALLS, 16L)
        val childTokens = optionalLong(limits, LimitFields.CHILD_TOKENS, 1000L)
        require(maxDepth in 0..8 && maxChildren in 0..8 &&
            maxToolCalls in 0..128 && childTokens in childTokenRange)
        require(recursiveConfig != null || limits.keys ==
            setOf(LimitFields.MAX_TOKENS, LimitFields.MAX_DURATION_MILLIS, LimitFields.DEADLINE_SECONDS))
        val graphId = GraphId(sessionId + "-graph")
        val correlation = GraphCorrelation(sessionId, graphId, sessionId + "-workflow")
        val first = StageNode(StageCorrelation(sessionId, graphId,
            StageRunId(sessionId + "-sdd"), sessionId + "-request",
            sessionId + "-sdd-correlation"),
            StageKind.SDD, StageState.CREATED, AgentId(sessionId + "-sdd-agent"))
        val graph = StageGraph(correlation, StageGraphState.RUNNING, listOf(first),
            emptyList(), StageBudget(tokens, duration, 0, 0))
        require(StageGraphPolicy.validate(graph) == StageGateDecision.Permit)
        // Hard live-model feature gate: the installed writable surface only
        // constructs FakeModel and never accepts a provider from input JSON.
        val model = ModelConfig(ModelProviderIds.FAKE, ModelProviderIds.FAKE_MODEL_ID, "http://localhost/unused",
            "env:FAKE_UNUSED", 8192, 512, setOf(ModelFeatureIds.TOOL_CALLS), null, 1000, 1000)
        val host = LinuxFiveRoleWorkflowHost(checkout, graph, expectedHead, branch,
            mapOf(INSTALLED_OFFLINE_CHECK_ID to StageIsolatedCheck(executable, arguments)),
            setOf(StageCheckIds.GIT_DIFF_CHECK, INSTALLED_OFFLINE_CHECK_ID),
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
        val childExecutor = ConfiguredFakeRoleExecutor(fixtureProposals, ReviewerDisposition.ACCEPT.name)
        // One deadline and one cancellation token for the parent plan AND its
        // recursive children, so stopping the workflow stops both (issue #163).
        val workflowDeadline = Instant.now().plusSeconds(deadlineSeconds)
        val workflowCancellation = Cancellation()
        val recursive = recursiveConfig?.let { configs ->
            InstalledRecursiveWorkflow(sessionId, host,
                StageIsolatedCheck(executable, arguments),
                targets, stageInstructions, developerTargets,
                permittedDigests, model,
                workflowDeadline,
                workflowCancellation, childExecutor, configs, tokens, duration,
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
            return CliOutcome("denied", "EVENT_JOURNAL_UNAVAILABLE")
        }
        recursive?.attachJournal(journal)
        val result = try {
            runBlocking {
                runner.execute(FiveRoleWorkflowPlan(targets, stageInstructions,
                    developerTargets, model, workflowDeadline,
                    workflowCancellation),
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
            return CliOutcome(RECONCILIATION_REQUIRED, "WORKFLOW_EXECUTION_UNCONFIRMED")
        }
        // A failed or partially flushed event stream cannot accompany a
        // successful workflow. Always verify the real bytes after close.
        if (journal != null) {
            try { journal.finish() } catch (_: Exception) {
                return CliOutcome(RECONCILIATION_REQUIRED, "EVENT_JOURNAL_UNCONFIRMED")
            }
        }
        if (recursive?.reconciliationRequired == true)
            return CliOutcome(RECONCILIATION_REQUIRED, InstalledChildDenialCodes.CHILD_EXECUTION_UNCONFIRMED)
        return summarize(result)
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
                val checks = Regex("""<${ReviewerPromptEvidenceTags.CHECK} id="([^"]+)" receipt="[^"]+" />""")
                    .findAll(request.instruction).map { ReviewerEvidenceReferences.CHECK_PREFIX + it.groupValues[1] }.toList()
                val write = Regex("""<${ReviewerPromptEvidenceTags.WRITE} id="([^"]+)" />""")
                    .find(request.instruction)?.groupValues?.get(1)
                    ?: error("Trusted Reviewer write evidence missing")
                val refs = (checks + (ReviewerEvidenceReferences.WRITE_PREFIX + write)).joinToString(",") {
                    "\"$it\""
                }
                val (code, scope) = when (verdict) {
                    ReviewerDisposition.ACCEPT.name -> ReviewerReasonCode.OK.wire to ReviewerCorrectionScope.NONE.name
                    ReviewerDisposition.REJECT.name -> ReviewerReasonCode.IMPLEMENTATION_DEFECT.wire to ReviewerCorrectionScope.IMPLEMENTATION_ONLY.name
                    else -> ReviewerReasonCode.EVIDENCE_MISSING.wire to ReviewerCorrectionScope.NONE.name
                }
                """{"${ReviewerProposalFields.VERDICT}":"$verdict",""" +
                    """"${ReviewerProposalFields.CODE}":"$code",""" +
                    """"${ReviewerProposalFields.SCOPE}":"$scope",""" +
                    """"${ReviewerProposalFields.EVIDENCE}":[$refs]}"""
            } else proposals.getValue(stage.kind)
            val usage = StageRoleUsage(1, 100, 30)
            events.emit(StageRoleEvent(stage.correlation, stage.kind.role, stage.agentId,
                StageRoleEventKind.PROPOSED, usage))
            return StageRoleResult(stage.correlation, stage.kind.role, stage.agentId,
                StageRoleOutcome.Proposed(proposal), usage)
        }
    }
}

/** Operator-selected check ID shared by installed root and child workflow adapters. */
internal const val INSTALLED_OFFLINE_CHECK_ID = "offline-check"
