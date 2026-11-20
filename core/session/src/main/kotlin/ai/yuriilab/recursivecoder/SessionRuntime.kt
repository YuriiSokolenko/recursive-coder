package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Resolve an output destination's existing ancestor before creating any directories. */
private fun canonicalDestination(path: Path): Path {
    val missing = ArrayDeque<Path>()
    var ancestor = path
    while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
        val name = ancestor.fileName ?: refuse("Invalid output path")
        missing.addFirst(name)
        ancestor = ancestor.parent ?: refuse("Invalid output path")
    }
    val realAncestor = try { ancestor.toRealPath() } catch (_: Exception) {
        throw Refused("UNSAFE_OUTPUT_PATH", "policy", "Cannot resolve output directory")
    }
    return missing.fold(realAncestor) { parent, part -> parent.resolve(part) }
}

/** Extract transport fields once; no trusted I/O or adapter decisions occur here. */
private fun SessionRequest.admissionFacts(): SessionAdmissionFacts = SessionAdmissionFacts(
    supportedProtocol = SessionProtocol.supports(raw.textAt("version")),
    invocationSource = invocation.textAt("source"),
    attempt = invocation.longAt("attempt"),
    resumeMode = raw.objectAt("resume").textAt("mode"),
    fenceGeneration = raw.objectAt("resume").longAt("fenceGeneration"),
    requiredCapabilities = raw.stringsAt("requiredCapabilities").toSet(),
    provider = model.textAt("provider"),
    modelId = model.textAt("modelId"),
    modelFeatures = model.stringsAt("features").toSet(),
    toolPolicyMode = policy.textAt("mode"),
    allowedTools = policy.stringsAt("allowedTools").toSet(),
    deniedTools = policy.stringsAt("deniedTools").toSet(),
    writablePathCount = policy.stringsAt("writablePaths").size,
    immutableInputCount = (policy["immutableInputs"] as? JsonArray)?.size ?: 0,
    maxToolCalls = budgets.longAt("maxToolCalls"),
    maxModelRequests = budgets["maxModelRequests"]?.let { budgets.longAt("maxModelRequests") }
        ?: Long.MAX_VALUE
)

private fun SessionRequest.admit(workspaceOpener: WorkspaceOpener,
              onAudit: (ToolAudit) -> Unit = {},
              onStart: (String, String, Instant) -> Unit = { _, _, _ -> }): WorkspaceSession {
    when (val decision = SessionAdmissionPolicy.decide(admissionFacts())) {
        AdmissionDecision.Accepted -> Unit
        is AdmissionDecision.Denied -> throw Refused(
            decision.reason.code, decision.reason.category, decision.reason.message)
    }
    // Trusted task bytes, hash and workspace identity are verified only by this adapter.
    val manager = workspaceOpener.open(this, onAudit, onStart)
    try {
        manager.verifyTask(taskPath, task.objectAt("taskInputRef").textAt("sha256"))
        return manager
    } catch (e: Exception) {
        manager.close()
        throw e
    }
}

/**
 * Deny-only transport across the trusted infra -> core resume preflight.
 * A successful filesystem lookup MUST NOT become permission to resume:
 * #104/#105 have not authenticated the executing actor and external effects.
 * Host exception details, causes and suppressed records never enter
 * protocol/model output. Fatal VM errors retain ordinary propagation.
 */
sealed interface SessionResumePreflightDenial {
    val code: String
    val safeDetail: String

    data class UnsupportedFilesystem(override val safeDetail: String) :
        SessionResumePreflightDenial {
        init {
            require(Regex("checkpoint=[a-z0-9._-]{1,64} ledger=[a-z0-9._-]{1,64}")
                .matches(safeDetail)) { "Invalid filesystem detail" }
        }
        override val code: String = "RESUME_FILESYSTEM_UNSUPPORTED"
    }

    data object NotEnabled : SessionResumePreflightDenial {
        override val code: String = "UNSUPPORTED_RESUME"
        override val safeDetail: String = "Resume is not supported"
    }

    data object Unavailable : SessionResumePreflightDenial {
        override val code: String = "RESUME_ADMISSION_UNCONFIRMED"
        override val safeDetail: String = "Resume admission unavailable"
    }
}

/** Always returns a denial, never an execution grant or a lease. */
fun interface SessionResumePreflight {
    fun deny(request: SessionRequest): SessionResumePreflightDenial
}

/** Default session behavior: no thread, filesystem lookup or host capability. */
private object DisabledResumePreflight : SessionResumePreflight {
    override fun deny(request: SessionRequest): SessionResumePreflightDenial =
        SessionResumePreflightDenial.NotEnabled
}

/**
 * FileStore.type() on an unresponsive mount may not honor interruption.
 * Keep it off the session thread. Each configured runner owns at most one
 * daemon worker and no queue. A hung call only starves runtimes sharing that
 * runner; unrelated runtimes and test instances have independent workers.
 * The caller's wait is bounded, but the OS call itself may continue. No
 * late result or timeout can grant fenced execution authority.
 */
class ResumePreflightRunner(private val maxProbeMillis: Long = 500L) {
    init {
        require(maxProbeMillis in 1L..60_000L) { "Invalid resume probe timeout" }
    }
    private val worker = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        ThreadFactory { job ->
            Thread(job, "session-resume-filesystem-probe").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun deny(
        request: SessionRequest, adapter: SessionResumePreflight,
        remainingMillis: Long
    ): SessionResumePreflightDenial {
        val waitMs = minOf(maxProbeMillis, remainingMillis.coerceAtLeast(1L))
        val future = try {
            worker.submit<SessionResumePreflightDenial> { adapter.deny(request) }
        } catch (_: RejectedExecutionException) {
            return SessionResumePreflightDenial.Unavailable
        }
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return SessionResumePreflightDenial.Unavailable
        } catch (failure: ExecutionException) {
            // VM-fatal errors must abort the trusted outer runtime.
            val cause = failure.cause
            if (cause is VirtualMachineError) throw cause
            return SessionResumePreflightDenial.Unavailable
        } catch (_: InterruptedException) {
            // Future.get clears the interrupt flag. Preserve the host's
            // shutdown/cancellation signal even on this deny-only path.
            Thread.currentThread().interrupt()
            return SessionResumePreflightDenial.Unavailable
        } catch (_: Exception) {
            return SessionResumePreflightDenial.Unavailable
        } finally {
            future.cancel(true)
        }
    }
}

/**
 * Session application orchestration: admission precedes workspace/model effects;
 * completion is published only after the journal and result have been verified.
 * Session execution state is local to invoke(). The injected optional
 * preflight runner has a bounded worker shared only by invocations using
 * this same DefaultRunSessionUseCase; distinct instances are isolated.
 */
class DefaultRunSessionUseCase(
    private val model: ModelGateway,
    private val workspaceOpener: WorkspaceOpener,
    private val eventSinkFactory: EventSinkFactory,
    private val clock: Clock = Clock.systemUTC(),
    private val resumePreflight: SessionResumePreflight = DisabledResumePreflight,
    private val resumePreflightRunner: ResumePreflightRunner = ResumePreflightRunner()
) : RunSessionUseCase {

    /**
     * Trusted-host, handoff-only entry point. Deliberately separate from invoke():
     * it MUST NOT open the runtime event sink, workspace or model, emit a
     * terminal Session Protocol result, or transition into running.
     * The host must independently authenticate C1 and publish durable C2.
     * A returned receipt is NOT runtime execution/replay authority.
     */
    @TrustedSessionPreExecutionApi
    fun publishTrustedCreatedAccepted(
        request: SessionRequest,
        cancellation: Cancellation,
        host: TrustedSessionPreExecutionHost
    ): TrustedSessionHandoffReceipt {
        // This path uses cooperative cancellation only. Do NOT bind the
        // caller's thread: Cancellation.cancel()/expire() would interrupt
        // FileChannel.force/lock between the accepted append and durable C2,
        // and a retained binding could interrupt unrelated work after return.
        when (val decision = SessionAdmissionPolicy.decide(request.admissionFacts())) {
            AdmissionDecision.Accepted -> Unit
            is AdmissionDecision.Denied -> throw Refused(
                decision.reason.code, decision.reason.category, decision.reason.message)
        }
        val now = clock.instant()
        val deadline = SessionBudgetPolicy.effectiveDeadline(
            now, request.deadline, request.budgets.longAt("maxWallTimeMs"))
        SessionInterruptionGate.stopped(cancellation.signals(), now, deadline)
            ?.let { throw it }
        // No post-handoff execution or terminal write is permitted here.
        // An exception may mean C2 committed: callers must reconcile read-only.
        // One shared clock avoids competing runtime/host deadline observations.
        return host.publishCreatedAccepted(request, cancellation, deadline, clock)
    }

    /**
     * Explicit host-only C2 -> C3 RUNNING marker. Never delegates to invoke(),
     * never opens workspace/model/runtime outputs, and returns no execution
     * capability. Admission of C3 for resumed work remains denied by #104.
     */
    @TrustedRunningBoundaryApi
    fun publishTrustedAcceptedRunning(
        request: SessionRequest,
        cancellation: Cancellation,
        host: TrustedSessionRunningHost
    ): TrustedSessionHandoffReceipt {
        // Cooperative-only: do not interrupt FileChannels during durable
        // publication and do not bind this thread beyond the host call.
        when (val decision = SessionAdmissionPolicy.decide(request.admissionFacts())) {
            AdmissionDecision.Accepted -> Unit
            is AdmissionDecision.Denied -> throw Refused(
                decision.reason.code, decision.reason.category, decision.reason.message)
        }
        val now = clock.instant()
        val deadline = SessionBudgetPolicy.effectiveDeadline(
            now, request.deadline, request.budgets.longAt("maxWallTimeMs"))
        SessionInterruptionGate.stopped(cancellation.signals(), now, deadline)
            ?.let { throw it }
        return host.publishAcceptedRunning(request, cancellation, deadline, clock)
    }

    override fun invoke(request: SessionRequest, cancellation: Cancellation): SessionExecutionResult {
        cancellation.bind()
        val started = clock.instant()
        // Never open or truncate existing output files. A later recovery milestone owns replay.
        val outputRoot = request.outputRoot
        val workspaceCanonical = try { request.rootPath.toRealPath() } catch (_: Exception) {
            throw Refused("INVALID_WORKSPACE", "policy", "Cannot resolve workspace")
        }
        if (canonicalDestination(outputRoot).startsWith(workspaceCanonical) ||
            Files.isSymbolicLink(outputRoot))
            throw Refused("UNSAFE_OUTPUT_PATH", "policy", "Session output must be outside the workspace")
        Files.createDirectories(outputRoot)
        val targetPaths = listOf("eventsPath", "resultPath", "diagnosticsPath").map { request.output(it) }
        if (targetPaths.any { !it.startsWith(outputRoot) || Files.exists(it, LinkOption.NOFOLLOW_LINKS) })
            throw Refused("OUTPUT_ALREADY_EXISTS", "policy", "Output paths are unavailable")
        targetPaths.forEach { target ->
            if (target.parent != outputRoot) throw Refused("UNSAFE_OUTPUT_PATH", "policy", "Nested outputs are unsupported")
        }
        val journal = SessionEventJournal(request.sessionId, request.traceId, request.requestId,
            clock, eventSinkFactory.open(request.output("eventsPath")))
        var state = SessionPhase.CREATED
        var finalOutcome: SessionOutcome? = null
        var failure: SessionFailure? = null
        val deadline = SessionBudgetPolicy.effectiveDeadline(
            started, request.deadline, request.budgets.longAt("maxWallTimeMs"))
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "session-deadline").apply { isDaemon = true }
        }
        watchdog.schedule({ cancellation.expire() },
            Duration.between(clock.instant(), deadline).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)

        fun checkActive() {
            when (SessionBudgetPolicy.interruption(cancellation.signals(), clock.instant(), deadline)) {
                SessionEndReason.TIMEOUT -> throw Stopped(true)
                SessionEndReason.CANCELLATION -> throw Stopped(false)
                else -> Unit
            }
        }

        fun transition(to: SessionPhase, eventStatus: String = "completed") {
            check(SessionLifecyclePolicy.transition(state, to) == PhaseDecision.Allowed) {
                "Illegal session phase transition: $state -> $to"
            }
            journal.append("session.transition", eventStatus, state.wire to to.wire)
            state = to
        }

        fun finish(reason: SessionEndReason, eventStatus: String = "completed") {
            check(finalOutcome == null) { "Session already has a terminal outcome" }
            val completion = SessionLifecyclePolicy.finish(state, reason)
            check(completion is FinishDecision.Completed) { "Illegal terminal session transition" }
            for (phase in completion.transitions) {
                if (phase == SessionPhase.CANCELLING) {
                    transition(phase, "cancelled")
                } else {
                    if (reason != SessionEndReason.SUCCESS)
                        journal.append(if (reason == SessionEndReason.REFUSAL &&
                                state == SessionPhase.CREATED) "policy.denied" else "session.error",
                            eventStatus)
                    transition(phase, eventStatus)
                }
            }
            finalOutcome = completion.outcome
        }
        var workspaceManager: WorkspaceSession? = null
        val config = ModelConfig.from(request.model)
        var budgetState = SessionBudgetState()
        var eventDigest: String? = null
        var executionFailure: Throwable? = null
        try {
            try {
                // #104: even an opt-in host preflight may only DENY here.
                // It must not create resume.lock, publish a fence or launch a
                // model/tool. Runtime execution after fenced admission is gated
                // on a separately reviewed #105 authenticated effect protocol.
                if (request.raw.objectAt("resume").textAt("mode") == "resume") {
                    val earlier = SessionResumePreflightPolicy.earlierDenial(
                        request.admissionFacts())
                    if (earlier != null)
                        throw Refused(earlier.code, earlier.category, earlier.message)
                    // Check before starting any potentially blocking host probe.
                    checkActive()
                    val remaining = Duration.between(
                        clock.instant(), deadline).toMillis().coerceAtLeast(1L)
                    // The default path must retain UNSUPPORTED_RESUME even
                    // while a prior opt-in diagnostic worker is wedged.
                    val denial = if (resumePreflight === DisabledResumePreflight)
                        SessionResumePreflightDenial.NotEnabled
                    else resumePreflightRunner.deny(request, resumePreflight, remaining)
                    // A cancelled/expired caller wins over a late probe result.
                    checkActive()
                    throw Refused(denial.code, "checkpoint", denial.safeDetail)
                }
                workspaceManager = request.admit(workspaceOpener,
                    onAudit = { audit ->
                        journal.append(if (audit.permitted) "tool.completed" else "policy.denied",
                            if (audit.permitted) "completed" else "denied",
                            toolId = audit.receiptId, started = audit.started,
                            extra = buildJsonObject {
                                put("tool", audit.tool)
                                put("receiptId", audit.receiptId)
                                put("bytes", audit.outputBytes)
                                audit.code?.let { put("deniedCode", it) }
                                audit.sha256?.let { put("sha256", it) }
                            })
                    },
                    onStart = { receipt, tool, startedAt ->
                        journal.append("tool.started", "started", toolId = receipt, started = startedAt,
                            extra = buildJsonObject { put("tool", tool); put("receiptId", receipt) })
                    }
                )
                checkActive()
                val capabilities = model.negotiate(config, cancellation, deadline)
                journal.append("llm.capabilities", "completed", extra = buildJsonObject {
                    put("contextTokens", capabilities.contextTokens)
                    put("maxOutputTokens", capabilities.maxOutputTokens)
                    put("features", capabilities.features.sorted().joinToString(","))
                })
                transition(SessionPhase.ADMITTED)
                transition(SessionPhase.RUNNING)
                journal.append("agent.started", "started")
                var observed: Pair<Int, String>? = null
                // Only actual inference POSTs count; discovery GETs are not model requests.
                val maxModelRequests = request.budgets["maxModelRequests"]?.let {
                    request.budgets.longAt("maxModelRequests")
                } ?: Long.MAX_VALUE
                val maxTotalTokens = request.budgets.longAt("maxTotalTokens")
                val maxToolCalls = request.budgets.longAt("maxToolCalls")
                repeat(2) { turn ->
                    checkActive()
                    val permission = SessionBudgetPolicy.beforeTurn(budgetState, maxModelRequests, turn)
                    val allowPreToolRetry = when (permission) {
                        is BudgetDecision.Allowed -> permission.value
                        is BudgetDecision.Denied -> throw Refused(
                            permission.reason.code, "policy", "Model request budget exhausted")
                    }
                    val modelStart = clock.instant()
                    journal.append("llm.request", "started", started = modelStart,
                        extra = buildJsonObject { put("provider", config.provider); put("turn", turn + 1) })
                    val response = try {
                        model.next(config, request.taskPath, observed, cancellation, deadline, allowPreToolRetry)
                    } catch (_: InterruptedException) { checkActive(); throw Stopped(false) }
                    checkActive()
                    // Reject adapter-reported usage and retries before trusted tool side effects.
                    budgetState = when (val charged = SessionBudgetPolicy.recordModel(
                        budgetState, maxModelRequests, maxTotalTokens,
                        response.attempts, allowPreToolRetry, response.usage)) {
                        is BudgetDecision.Allowed -> charged.value
                        is BudgetDecision.Denied -> throw Refused(
                            charged.reason.code, "policy", charged.reason.message)
                    }
                    journal.append("llm.response", "completed", started = modelStart,
                        extra = buildJsonObject {
                            put("provider", config.provider)
                            put("turn", turn + 1)
                            put("attempts", response.attempts)
                            response.statusCode?.let { put("httpStatus", it) }
                            response.usage?.let {
                                put("inputTokens", it.input)
                                put("outputTokens", it.output)
                            }
                        })
                    when (val decision = response.decision) {
                        is ModelDecision.Read -> {
                            if (turn != 0 || decision.path != request.taskPath) refuse("Unexpected fake tool call")
                            checkActive()
                            val relative = workspaceManager!!.taskRelativePath(decision.path)
                            budgetState = when (val charged = SessionBudgetPolicy.recordTool(budgetState, maxToolCalls)) {
                                is BudgetDecision.Allowed -> charged.value
                                is BudgetDecision.Denied -> throw Refused(
                                    charged.reason.code, "policy", charged.reason.message)
                            }
                            val outcome = workspaceManager.execute(WorkspaceTool.Read(relative))
                            if (!outcome.permitted)
                                throw Refused(outcome.deniedCode!!, "policy", "Read-only tool denied")
                            val read = outcome.data as? ToolData.FileText
                                ?: throw Refused("TOOL_RESULT_INVALID", "policy", "Unexpected tool result")
                            if (read.sha256 != request.task.objectAt("taskInputRef").textAt("sha256"))
                                throw Refused("TASK_CHANGED", "policy", "Task changed during execution")
                            observed = read.bytes to read.sha256
                        }
                        is ModelDecision.Done -> {
                            if (turn != 1 || observed != (decision.bytes to decision.sha256))
                                throw Refused("MODEL_INVALID_COMPLETION", "provider", "Model completion did not match trusted observation")
                        }
                        ModelDecision.Refusal ->
                            throw Refused("MODEL_REFUSAL", "provider", "Model declined the request")
                    }
                }
                checkActive()
                journal.append("agent.completed", "completed")
                finish(SessionEndReason.SUCCESS)
            } catch (e: EventSinkFailure) {
                // A journal failure makes even an otherwise successful session unverifiable.
                // Do not emit a terminal success event or produce a result.
                throw e
            } catch (e: Refused) {
                failure = SessionFailure(e.code, e.category, e.explanation)
                finish(SessionEndReason.REFUSAL, "denied")
            } catch (e: Stopped) {
                failure = SessionFailure(if (e.timedOut) "SESSION_DEADLINE_EXCEEDED" else "SESSION_CANCELLED",
                    if (e.timedOut) "timeout" else "cancellation",
                    if (e.timedOut) "Session deadline exceeded" else "Session cancelled")
                finish(if (e.timedOut) SessionEndReason.TIMEOUT else SessionEndReason.CANCELLATION,
                    if (e.timedOut) "timed_out" else "cancelled")
            } catch (_: Exception) {
                failure = SessionFailure("SESSION_EXECUTION_FAILED", "infrastructure", "Session execution failed")
                finish(SessionEndReason.FAILURE, "failed")
            }
        } catch (e: Throwable) {
            executionFailure = e
        } finally {
            watchdog.shutdownNow()
            fun recordCleanupFailure(failure: Throwable) {
                val original = executionFailure
                if (original == null) executionFailure = failure
                else if (original !== failure) original.addSuppressed(failure)
            }
            try { workspaceManager?.close() } catch (e: Throwable) { recordCleanupFailure(e) }
            try { eventDigest = journal.finish() } catch (e: Throwable) { recordCleanupFailure(e) }
        }
        executionFailure?.let { throw it }

        val finishedAt = clock.instant()
        val outcome = checkNotNull(finalOutcome)
        val diagnosticsPath = request.output("diagnosticsPath")
        val diagnostics = SessionResultMapper.diagnostics(request.sessionId, outcome, journal.sequence.toLong())
        val diagnosticsContent = diagnostics.toString()
        // Validate the full terminal result before publishing any diagnostics or result file.
        val completed = SessionRunResult(
            outcome = outcome,
            startedAt = started,
            finishedAt = finishedAt,
            lastEventSeq = journal.sequence.toLong(),
            eventLog = SessionArtifact(request.output("eventsPath"), checkNotNull(eventDigest)),
            diagnostics = SessionResultMapper.artifact(diagnosticsPath, diagnosticsContent),
            failure = failure,
            usage = budgetState
        )
        val protocol = completed.toSessionProtocolJson(request)
        Files.writeString(diagnosticsPath, diagnosticsContent,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.writeString(request.output("resultPath"), protocol.toString(),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return SessionExecutionResult(outcome, protocol)
    }
}
