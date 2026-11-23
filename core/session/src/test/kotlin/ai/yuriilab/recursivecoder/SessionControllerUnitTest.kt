package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Session-core tests use only contract interfaces. Concrete model and workspace
 * adapters are exercised by their own modules and the app:cli integration suite.
 */
@Tag("integration")
class SessionControllerUnitTest {
    @TempDir lateinit var temp: Path
    // A distinct sink belongs to each controller, even when a test starts more than one.
    private var activeEventSink = InMemoryEventSink()
    private var nextEventSink = activeEventSink
    private val eventSink get() = activeEventSink
    private val eventSinkFactory = EventSinkFactory {
        nextEventSink.also { sink ->
            activeEventSink = sink
            nextEventSink = InMemoryEventSink()
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fixture(text: String = "Read the isolated task"): SessionRequest {
        val root = temp.resolve("workspace")
        Files.createDirectories(root)
        val task = root.resolve("task.md")
        Files.writeString(task, text)
        val out = temp.resolve("out")
        val raw = buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "req-unit"); put("sessionId", "session-unit"); put("traceId", "trace-unit")
            put("invocation", buildJsonObject {
                put("source", "cli"); put("invocationId", "invoke-unit"); put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-unit"); put("rootPath", root.toString())
                put("baselineSha", "a".repeat(40)); put("headSha", "a".repeat(40))
                put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", "task-unit")
                put("taskInputRef", buildJsonObject {
                    put("path", task.toString()); put("sha256", sha256(text))
                })
            })
            put("model", buildJsonObject {
                put("provider", "fake"); put("modelId", "fake-v1")
                put("baseUrl", "http://fake.invalid/v1"); put("credentialRef", "env:FAKE_TOKEN")
                put("contextTokens", 8192); put("maxOutputTokens", 128)
                put("features", buildJsonArray { add("tool_calls") })
            })
            put("toolPolicy", buildJsonObject {
                put("mode", "read-only")
                put("allowedTools", buildJsonArray { add("read") })
                put("deniedTools", buildJsonArray { add("safe_edit") })
                put("protectedPaths", buildJsonArray { add(".git/**") })
                put("writablePaths", buildJsonArray {})
                put("policySha256", "b".repeat(64))
            })
            put("budgets", buildJsonObject {
                put("deadlineAt", Instant.now().plusSeconds(30).toString())
                put("maxWallTimeMs", 12000); put("maxTotalTokens", 10000)
                put("maxToolCalls", 1); put("maxChildAgents", 0); put("maxDepth", 0)
                put("maxModelRequests", 2)
            })
            put("outputs", buildJsonObject {
                put("rootDir", out.toString())
                put("resultPath", out.resolve("result.json").toString())
                put("eventsPath", out.resolve("events.jsonl").toString())
                put("checkpointDir", out.resolve("checkpoints").toString())
                put("diagnosticsPath", out.resolve("diagnostics.json").toString())
            })
            put("resume", buildJsonObject {
                put("mode", "fresh"); put("fenceGeneration", 1); put("checkpoint", JsonNull)
            })
            put("requiredCapabilities", buildJsonArray {
                add("session-events-v1"); add("fake-model-v1"); add("read-only-tools-v1")
            })
        }
        return SessionRequest.parse(raw.toString())
    }

    private fun withField(request: SessionRequest, key: String, value: JsonElement): SessionRequest =
        SessionRequest.parse(JsonObject(request.raw + (key to value)).toString())

    private fun withBudget(request: SessionRequest, key: String, value: Long): SessionRequest =
        withField(request, "budgets",
            JsonObject(request.budgets + (key to JsonPrimitive(value))))

    private fun withDeadline(request: SessionRequest, deadline: Instant): SessionRequest =
        withField(request, "budgets", JsonObject(request.budgets +
            ("deadlineAt" to JsonPrimitive(deadline.toString()))))

    /** No wall-clock reads, sleeping or worker threads in deterministic tests. */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock {
            require(zone == ZoneOffset.UTC) { "MutableClock supports UTC only" }
            return this
        }
        override fun instant(): Instant = current
        fun advanceMillis(value: Long) { current = current.plusMillis(value) }
    }

    private class ManualDeadlineScheduler : SessionDeadlineScheduler {
        var delayMs: Long? = null
            private set
        var closed: Boolean = false
            private set
        private var timeout: (() -> Unit)? = null

        override fun schedule(delayMs: Long, onDeadline: () -> Unit): AutoCloseable {
            check(timeout == null) { "Scheduled more than once" }
            this.delayMs = delayMs
            timeout = onDeadline
            return AutoCloseable { closed = true }
        }

        fun fire() = checkNotNull(timeout).invoke()
    }

    private class StubWorkspace(
        private val request: SessionRequest,
        private val taskText: String,
        private val clock: Clock = Clock.systemUTC()
    ) : WorkspaceSession {
        var onRead: () -> Unit = {}
        var verificationCount = 0
        var readCount = 0
        var closed = false
        var denyRead = false
        var failClose = false
        private var onAudit: (ToolAudit) -> Unit = {}
        private var onStart: (String, String, Instant) -> Unit = { _, _, _ -> }

        fun attach(
            audit: (ToolAudit) -> Unit,
            start: (String, String, Instant) -> Unit
        ): StubWorkspace {
            onAudit = audit
            onStart = start
            return this
        }

        override fun verifyTask(path: Path, expectedSha: String) {
            verificationCount++
            if (path != request.taskPath || expectedSha != digest(taskText))
                throw Refused("TASK_DIGEST_MISMATCH", "policy", "Task digest differs")
        }

        override fun taskRelativePath(path: Path): String {
            if (path != request.taskPath)
                throw Refused("INVALID_TASK_PATH", "policy", "Unexpected task path")
            return "task.md"
        }

        override fun execute(tool: WorkspaceTool, claimedExecutionId: String?): ToolOutcome {
            val read = tool as? WorkspaceTool.Read
                ?: throw IllegalArgumentException("Only read is supported by this stub")
            if (read.path != "task.md") throw IllegalArgumentException("Unexpected read path")
            val receipt = "tool-unit-" + (++readCount)
            val started = clock.instant()
            onStart(receipt, tool.name, started)
            onRead()
            val data = if (denyRead) null else ToolData.FileText(
                "task.md", taskText, taskText.toByteArray(Charsets.UTF_8).size, digest(taskText))
            val code = if (denyRead) "TEST_READ_DENIED" else null
            onAudit(ToolAudit(receipt, tool.name, started, code == null, code, data?.bytes ?: 0,
                data?.sha256))
            return ToolOutcome(receipt, tool.name, data, code)
        }

        override fun close() {
            closed = true
            if (failClose) error("Injected workspace close failure")
        }

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private class StubGateway(
        private val respond: (Path, Pair<Int, String>?, Boolean) -> ModelTurn = { task, seen, _ ->
            if (seen == null) ModelTurn(ModelDecision.Read(task), ModelUsage(3, 2))
            else ModelTurn(ModelDecision.Done(seen.first, seen.second), ModelUsage(4, 1))
        }
    ) : ModelGateway {
        var negotiations = 0
        var turns = 0
        val deadlines = mutableListOf<Instant>()
        val retryPermissions = mutableListOf<Boolean>()
        var onNext: (Cancellation, Instant) -> Unit = { _, _ -> }

        override fun negotiate(
            config: ModelConfig, cancellation: Cancellation, deadline: Instant
        ): GatewayCapabilities {
            negotiations++
            deadlines += deadline
            return GatewayCapabilities(setOf("tool_calls"), config.contextTokens, config.maxOutputTokens)
        }

        override fun next(
            config: ModelConfig, task: Path, observed: Pair<Int, String>?,
            cancellation: Cancellation, deadline: Instant, allowRetry: Boolean
        ): ModelTurn {
            turns++
            deadlines += deadline
            retryPermissions += allowRetry
            onNext(cancellation, deadline)
            return respond(task, observed, allowRetry)
        }
    }

    private fun run(request: SessionRequest, model: StubGateway, workspace: StubWorkspace) =
        SessionController(request, model, workspaceOpener = WorkspaceOpener { _, audit, start ->
            workspace.attach(audit, start)
        }, eventSinkFactory = eventSinkFactory).run()

    private fun runWithClock(
        request: SessionRequest, model: StubGateway, workspace: StubWorkspace,
        clock: Clock, scheduler: ManualDeadlineScheduler,
        cancellation: Cancellation = Cancellation(clock)
    ): JsonObject = SessionController(
        request, cancellation,
        DefaultRunSessionUseCase(
            model, WorkspaceOpener { _, audit, start -> workspace.attach(audit, start) },
            eventSinkFactory, clock, deadlineScheduler = scheduler
        )
    ).run()

    private fun events(request: SessionRequest): List<JsonObject> {
        // The Session layer can observe event semantics without a physical journal.
        assertFalse(Files.exists(request.output("eventsPath")))
        return eventSink.events.toList()
    }

    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content

    private fun resumeRequest(request: SessionRequest): SessionRequest =
        withField(request, "resume", buildJsonObject {
            put("mode", "resume")
            put("fenceGeneration", 2)
            put("checkpoint", JsonNull)
        })

    private fun withResumeOutput(request: SessionRequest, name: String): SessionRequest {
        val root = temp.resolve(name)
        return withField(request, "outputs", buildJsonObject {
            put("rootDir", root.toString())
            put("eventsPath", root.resolve("events.jsonl").toString())
            put("resultPath", root.resolve("result.json").toString())
            put("diagnosticsPath", root.resolve("diagnostics.json").toString())
            put("checkpointDir", root.resolve("checkpoints").toString())
        })
    }

    private fun denyResume(
        request: SessionRequest,
        adapter: SessionResumePreflight,
        cancellation: Cancellation = Cancellation(),
        runner: ResumePreflightRunner = ResumePreflightRunner()
    ): JsonObject {
        val model = StubGateway()
        val result = DefaultRunSessionUseCase(
            model, WorkspaceOpener { _, _, _ ->
                error("Workspace must not open while resume is denied")
            }, eventSinkFactory, resumePreflight = adapter,
            resumePreflightRunner = runner
        ).invoke(request, cancellation).protocol
        assertEquals(0, model.negotiations)
        assertEquals(0, model.turns)
        return result
    }

    @Test fun nonfatalResumeAdapterErrorHasTypedSafeCodeAndNoHostExceptionText() {
        val request = resumeRequest(fixture())
        val result = denyResume(request,
            SessionResumePreflight { throw LinkageError("/private/HOST_LINKAGE_SECRET") })
        assertEquals("rejected", result.value("status"))
        val error = result.getValue("error").jsonObject
        assertEquals("RESUME_ADMISSION_UNCONFIRMED", error.value("code"))
        assertEquals("checkpoint", error.value("category"))
        assertEquals("Resume admission unavailable", error.value("safeMessage"))
        assertFalse(result.toString().contains("HOST_LINKAGE_SECRET"))
    }

    @Test fun resumeWithUnsupportedCapabilityPreservesOriginalV1DenialCode() {
        val request = withField(resumeRequest(fixture()), "requiredCapabilities",
            buildJsonArray { add("unknown-capability") })
        val result = denyResume(request,
            SessionResumePreflight { SessionResumePreflightDenial.NotEnabled })
        assertEquals("UNSUPPORTED_RESUME",
            result.getValue("error").jsonObject.value("code"))
    }

    @Test fun unsupportedProtocolWithResumeNeverInvokesFilesystemPreflight() {
        val request = withField(resumeRequest(fixture()), "version", JsonPrimitive("1.1"))
        var probes = 0
        val result = denyResume(request, SessionResumePreflight {
            probes++
            SessionResumePreflightDenial.NotEnabled
        })
        assertEquals("UNSUPPORTED_PROTOCOL",
            result.getValue("error").jsonObject.value("code"))
        assertEquals(0, probes)
    }

    @Test fun cancelledResumeDoesNotInvokeFilesystemPreflight() {
        val request = resumeRequest(fixture())
        val cancelled = Cancellation().also { it.cancel() }
        var probes = 0
        val result = denyResume(request, SessionResumePreflight {
            probes++
            SessionResumePreflightDenial.NotEnabled
        }, cancelled)
        assertEquals("cancelled", result.value("status"))
        assertEquals("SESSION_CANCELLED",
            result.getValue("error").jsonObject.value("code"))
        assertEquals(0, probes)
    }

    @Test fun uninterruptibleResumeLookupIsBoundedAndFailsClosed() {
        val request = resumeRequest(fixture())
        val blocker = java.util.concurrent.CountDownLatch(1)
        val startedAt = System.nanoTime()
        try {
            val result = denyResume(request, SessionResumePreflight {
                try {
                    blocker.await(5L, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Simulate a native lookup that does not honor interrupt.
                    blocker.await(5L, java.util.concurrent.TimeUnit.SECONDS)
                }
                SessionResumePreflightDenial.NotEnabled
            }, runner = ResumePreflightRunner(120L))
            assertEquals("RESUME_ADMISSION_UNCONFIRMED",
                result.getValue("error").jsonObject.value("code"))
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt) < 2500L)
        } finally {
            blocker.countDown()
        }
    }

    @Test fun disabledResumePathRetainsV1CodeWhenDiagnosticWorkerIsWedged() {
        val request = resumeRequest(fixture())
        val blocker = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        try {
            val first = denyResume(request, SessionResumePreflight {
                started.countDown()
                try {
                    blocker.await(5L, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    blocker.await(5L, java.util.concurrent.TimeUnit.SECONDS)
                }
                SessionResumePreflightDenial.NotEnabled
            })
            assertEquals("RESUME_ADMISSION_UNCONFIRMED",
                first.getValue("error").jsonObject.value("code"))
            assertEquals(0L, started.count)

            // A second opt-in runtime must have its OWN worker even while
            // the first runtime's noninterruptible lookup is still stuck.
            val isolated = withResumeOutput(request, "independent-probe-output")
            val optInResult = denyResume(isolated, SessionResumePreflight {
                SessionResumePreflightDenial.NotEnabled
            })
            assertEquals("UNSUPPORTED_RESUME",
                optInResult.getValue("error").jsonObject.value("code"))

            val separate = withResumeOutput(request, "default-output")
            val model = StubGateway()
            val result = DefaultRunSessionUseCase(
                model, WorkspaceOpener { _, _, _ -> error("Unexpected workspace access") },
                eventSinkFactory
            ).invoke(separate, Cancellation()).protocol
            assertEquals("UNSUPPORTED_RESUME",
                result.getValue("error").jsonObject.value("code"))
            assertEquals(0, model.negotiations)
            assertEquals(0, model.turns)
        } finally {
            blocker.countDown()
        }
    }

    @Test fun interruptedHostProbeWaitRestoresTheCallerInterruptFlag() {
        val request = resumeRequest(fixture())
        val release = java.util.concurrent.CountDownLatch(1)
        val runner = ResumePreflightRunner(1000L)
        try {
            Thread.currentThread().interrupt()
            val denial = runner.deny(request, SessionResumePreflight {
                // Do not let the worker complete before Future.get checks
                // the calling thread's pre-existing interrupt signal.
                release.await(5L, java.util.concurrent.TimeUnit.SECONDS)
                SessionResumePreflightDenial.NotEnabled
            }, 1000L)
            assertEquals(SessionResumePreflightDenial.Unavailable, denial)
            assertTrue(Thread.currentThread().isInterrupted,
                "Future.get interruption must not be swallowed")
        } finally {
            Thread.interrupted() // Clear test thread after verifying flag.
            release.countDown()
        }
    }

    @Test fun eachControllerReceivesItsOwnMemoryEventSink() {
        val first = eventSinkFactory.open(temp.resolve("first.jsonl"))
        val second = eventSinkFactory.open(temp.resolve("second.jsonl"))
        assertNotSame(first, second)
        first.append(buildJsonObject { put("type", "first") })
        assertTrue((second as InMemoryEventSink).events.isEmpty())
        first.close()
        second.close()
    }

    @Test fun successfulReadUsesOnlyInjectedPortsAndPreservesEventOrder() {
        val request = fixture()
        val model = StubGateway()
        val workspace = StubWorkspace(request, "Read the isolated task")
        val result = run(request, model, workspace)

        assertEquals("succeeded", result.value("status"))
        assertEquals(JsonNull, result["error"])
        assertEquals(1, workspace.verificationCount)
        assertEquals(1, workspace.readCount)
        assertTrue(workspace.closed)
        assertEquals(1, model.negotiations)
        assertEquals(2, model.turns)
        assertEquals(listOf(false, false), model.retryPermissions)
        assertEquals(10, result.getValue("usage").jsonObject.getValue("totalTokens").jsonPrimitive.int)

        val entries = events(request)
        assertEquals((1..entries.size).toList(), entries.map {
            it.getValue("sequence").jsonPrimitive.int
        })
        assertEquals(entries.size, result.getValue("lastEventSeq").jsonPrimitive.int)
        assertEquals(1, entries.count { it.value("type") == "tool.started" })
        assertEquals(1, entries.count { it.value("type") == "tool.completed" })
        assertEquals(2, entries.count { it.value("type") == "llm.response" })
    }

    @Test fun fixedClockControllerKeepsUtcEventTimestampsAndRequestDeadline() {
        val now = Instant.parse("2026-10-13T10:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val scheduler = ManualDeadlineScheduler()
        val deadline = now.plusSeconds(5)
        val request = withDeadline(fixture(), deadline)
        val model = StubGateway()
        val workspace = StubWorkspace(request, "Read the isolated task", clock)

        val result = runWithClock(request, model, workspace, clock, scheduler)

        assertEquals("succeeded", result.value("status"))
        assertEquals(now.toString(), result.value("startedAt"))
        assertEquals(now.toString(), result.value("finishedAt"))
        assertEquals(0L, result.getValue("durationMs").jsonPrimitive.long)
        assertEquals(5000L, scheduler.delayMs)
        assertTrue(scheduler.closed)
        assertEquals(List(SESSION_MODEL_TURNS + 1) { deadline }, model.deadlines)
        val entries = events(request)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.value("timestamp") == now.toString() })
        val durations = entries.mapNotNull { it["timing"]?.jsonObject
            ?.getValue("durationMs")?.jsonPrimitive?.long }
        assertTrue(durations.isNotEmpty())
        assertTrue(durations.all { it == 0L })
    }

    @Test fun defaultCancellationInheritsInjectedClockInCompatibilityConstructor() {
        val instant = Instant.parse("2045-02-02T10:00:00Z")
        val clock = Clock.fixed(instant, ZoneOffset.UTC)
        val request = withDeadline(fixture(), instant.plusSeconds(30))
        val model = StubGateway().apply {
            // Exercise the same one-argument API as the production gateway:
            // it must not consult system time when the controller clock is fixed.
            onNext = { cancellation, deadline -> cancellation.check(deadline) }
        }
        val workspace = StubWorkspace(request, "Read the isolated task", clock)

        val result = SessionController(
            request, model,
            workspaceOpener = WorkspaceOpener { _, audit, start ->
                workspace.attach(audit, start)
            },
            eventSinkFactory = eventSinkFactory, clock = clock
        ).run()

        assertEquals("succeeded", result.value("status"))
        assertEquals(2, model.turns)
        assertEquals(instant.toString(), result.value("startedAt"))
        assertEquals(instant.toString(), result.value("finishedAt"))
        assertTrue(events(request).all { it.value("timestamp") == instant.toString() })
    }

    @Test fun schedulerFailureClosesJournalWithoutPublishingResult() {
        val request = fixture()
        val schedulerFailure = IllegalStateException("Injected watchdog scheduling failure")
        val scheduler = SessionDeadlineScheduler { _, _ -> throw schedulerFailure }
        val model = StubGateway()
        val runtime = DefaultRunSessionUseCase(
            model, WorkspaceOpener { _, _, _ ->
                error("Scheduler failure must precede workspace admission")
            }, eventSinkFactory, deadlineScheduler = scheduler
        )
        val thrown = assertFailsWith<IllegalStateException> {
            runtime(request, Cancellation())
        }

        assertSame(schedulerFailure, thrown)
        assertTrue(eventSink.closed)
        assertEquals(0, model.negotiations)
        assertFalse(Files.exists(request.output("resultPath")))
    }

    @Test fun advancingClockControllerAccountsForToolModelAndSessionDurations() {
        val started = Instant.parse("2026-10-13T10:00:00Z")
        val clock = MutableClock(started)
        val scheduler = ManualDeadlineScheduler()
        val deadline = started.plusSeconds(60)
        val request = withDeadline(fixture(), deadline)
        val model = StubGateway { task, seen, _ ->
            clock.advanceMillis(25)
            if (seen == null) ModelTurn(ModelDecision.Read(task), ModelUsage(3, 2))
            else ModelTurn(ModelDecision.Done(seen.first, seen.second), ModelUsage(4, 1))
        }
        val workspace = StubWorkspace(request, "Read the isolated task", clock).apply {
            onRead = { clock.advanceMillis(40) }
        }

        val result = runWithClock(request, model, workspace, clock, scheduler)

        assertEquals("succeeded", result.value("status"))
        assertEquals(started.toString(), result.value("startedAt"))
        assertEquals(started.plusMillis(90).toString(), result.value("finishedAt"))
        assertEquals(90L, result.getValue("durationMs").jsonPrimitive.long)
        assertEquals(12000L, scheduler.delayMs) // wall budget precedes request deadline
        assertEquals(List(SESSION_MODEL_TURNS + 1) { started.plusSeconds(12) }, model.deadlines)
        assertTrue(scheduler.closed)
        val entries = events(request)
        assertEquals(listOf(25L, 25L), entries.filter { it.value("type") == "llm.response" }
            .map { it.getValue("timing").jsonObject.getValue("durationMs").jsonPrimitive.long })
        assertEquals(40L, entries.single { it.value("type") == "tool.completed" }
            .getValue("timing").jsonObject.getValue("durationMs").jsonPrimitive.long)
        assertEquals(started.plusMillis(90).toString(), entries.last().value("timestamp"))
        assertTrue(entries.mapNotNull { it["timing"]?.jsonObject
            ?.getValue("durationMs")?.jsonPrimitive?.long }.all { it >= 0L })
    }

    @Test fun simultaneousManualExpirationAndCancellationTimeoutWinsBeforeToolDispatch() {
        val started = Instant.parse("2026-10-13T10:00:00Z")
        val clock = MutableClock(started)
        val scheduler = ManualDeadlineScheduler()
        val cancellation = Cancellation(clock)
        val request = withDeadline(fixture(), started.plusSeconds(8))
        val model = StubGateway { task, _, _ ->
            clock.advanceMillis(8000)
            cancellation.cancel()
            scheduler.fire()
            // Model adapters may consume the interrupt; policy still sees both flags.
            Thread.interrupted()
            ModelTurn(ModelDecision.Read(task), ModelUsage(3, 2))
        }
        val workspace = StubWorkspace(request, "Read the isolated task", clock)

        val result = try {
            runWithClock(request, model, workspace, clock, scheduler, cancellation)
        } finally {
            Thread.interrupted() // cancel()/expire() interrupt the bound test thread.
        }

        assertEquals("timed_out", result.value("status"))
        assertEquals("SESSION_DEADLINE_EXCEEDED",
            result.getValue("error").jsonObject.value("code"))
        assertEquals(8000L, result.getValue("durationMs").jsonPrimitive.long)
        assertEquals(0, workspace.readCount)
        assertEquals(1, model.turns)
        assertTrue(scheduler.closed)
        assertTrue(events(request).none { it.value("type") == "tool.started" })
    }

    @Test fun cancellationBeforeDeadlineDoesNotBecomeTimeoutWithManualScheduler() {
        val started = Instant.parse("2026-10-13T10:00:00Z")
        val clock = Clock.fixed(started, ZoneOffset.UTC)
        val scheduler = ManualDeadlineScheduler()
        val cancellation = Cancellation(clock)
        val request = withDeadline(fixture(), started.plusSeconds(10))
        val model = StubGateway { task, _, _ ->
            cancellation.cancel()
            Thread.interrupted()
            ModelTurn(ModelDecision.Read(task), ModelUsage(3, 2))
        }
        val workspace = StubWorkspace(request, "Read the isolated task", clock)
        val result = try {
            runWithClock(request, model, workspace, clock, scheduler, cancellation)
        } finally {
            Thread.interrupted()
        }

        assertEquals("cancelled", result.value("status"))
        assertEquals("SESSION_CANCELLED", result.getValue("error").jsonObject.value("code"))
        assertEquals(0, workspace.readCount)
        assertTrue(scheduler.closed)
    }

    @Test fun secondToolRequestIsRejectedBeforeTrustedDispatchInFixedTwoTurnFlow() {
        // With admission requiring maxToolCalls >= 1 and only turn zero
        // permitting Read, recordTool() cannot deny within protocol v1.
        // A later multi-tool controller must test quota exhaustion itself.
        val request = fixture()
        val model = StubGateway { task, _, _ ->
            ModelTurn(ModelDecision.Read(task), ModelUsage(3, 2))
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("INVALID_SESSION_REQUEST", result.getValue("error").jsonObject.value("code"))
        assertEquals(2, model.turns)
        assertEquals(1, workspace.readCount)
        assertEquals(1, events(request).count { it.value("type") == "tool.started" })
        assertEquals(1, events(request).count { it.value("type") == "tool.completed" })
    }

    @Test fun unsupportedProtocolIsRejectedBeforeOpeningWorkspaceOrCallingModel() {
        val request = withField(fixture(), "version", JsonPrimitive("1.1"))
        val model = StubGateway()
        val forbiddenOpener = WorkspaceOpener { _, _, _ ->
            throw AssertionError("WorkspaceOpener must not be called for unsupported protocol")
        }

        val result = SessionController(
            request, model, workspaceOpener = forbiddenOpener,
            eventSinkFactory = eventSinkFactory
        ).run()

        assertEquals("rejected", result.value("status"))
        assertEquals("UNSUPPORTED_PROTOCOL", result.getValue("error").jsonObject.value("code"))
        assertEquals(0, model.negotiations)
        assertEquals(0, model.turns)
    }

    @Test fun digestMismatchClosesAdmittedWorkspaceBeforeNegotiation() {
        val request = fixture()
        val model = StubGateway()
        val workspace = StubWorkspace(request, "Changed after request construction")

        val result = run(request, model, workspace)

        assertEquals("rejected", result.value("status"))
        assertEquals("TASK_DIGEST_MISMATCH", result.getValue("error").jsonObject.value("code"))
        assertEquals(1, workspace.verificationCount)
        assertTrue(workspace.closed)
        assertEquals(0, model.negotiations)
    }

    @Test fun deniedReadFailsClosedWithoutInvokingConfirmationTurn() {
        val request = fixture()
        val model = StubGateway()
        val workspace = StubWorkspace(request, "Read the isolated task").apply { denyRead = true }

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("TEST_READ_DENIED", result.getValue("error").jsonObject.value("code"))
        assertEquals(1, workspace.readCount)
        assertEquals(1, model.turns)
        assertTrue(workspace.closed)
        assertEquals(1, events(request).count { it.value("type") == "policy.denied" })
    }

    @Test fun modelCompletionMustMatchTrustedReadObservation() {
        val request = fixture()
        val model = StubGateway { task, seen, _ ->
            if (seen == null) ModelTurn(ModelDecision.Read(task))
            else ModelTurn(ModelDecision.Done(seen.first + 1, seen.second))
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("MODEL_INVALID_COMPLETION", result.getValue("error").jsonObject.value("code"))
        assertEquals(1, workspace.readCount)
        assertEquals(2, model.turns)
    }

    @Test fun retryCannotConsumeRequestReservedForConfirmation() {
        val request = fixture()
        val model = StubGateway { task, _, _ ->
            ModelTurn(ModelDecision.Read(task), ModelUsage(1, 1), attempts = 2)
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("MODEL_REQUEST_BUDGET_EXCEEDED", result.getValue("error").jsonObject.value("code"))
        assertEquals(listOf(false), model.retryPermissions)
        assertEquals(0, workspace.readCount)
    }

    @Test fun spareRequestAllowsOneRetryWithoutUsingFinalTurnBudget() {
        val request = withBudget(fixture(), "maxModelRequests", 3L)
        val model = StubGateway { task, seen, _ ->
            if (seen == null) ModelTurn(ModelDecision.Read(task), ModelUsage(2, 1), attempts = 2)
            else ModelTurn(ModelDecision.Done(seen.first, seen.second), ModelUsage(2, 1))
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("succeeded", result.value("status"))
        assertEquals(listOf(true, false), model.retryPermissions)
        assertEquals(1, workspace.readCount)
        val responses = events(request).filter { it.value("type") == "llm.response" }
        assertEquals(2, responses.first().getValue("metadata").jsonObject.getValue("attempts").jsonPrimitive.int)
        assertEquals(1, responses.last().getValue("metadata").jsonObject.getValue("attempts").jsonPrimitive.int)
    }

    @Test fun journalAppendFailureNeverPublishesSuccessResult() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task")
        eventSink.failAppend = true

        assertFailsWith<EventSinkFailure> { run(request, StubGateway(), workspace) }
        assertFalse(Files.exists(request.output("resultPath")))
        assertTrue(workspace.closed)
        assertTrue(eventSink.closed)
    }

    @Test fun journalFlushFailureNeverPublishesSuccessResult() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task")
        eventSink.failFlush = true

        assertFailsWith<EventSinkFailure> { run(request, StubGateway(), workspace) }
        assertFalse(Files.exists(request.output("resultPath")))
        // append reached the in-memory buffer before flush failed; it is not committed.
        assertEquals(1, eventSink.events.size)
        assertEquals(1, eventSink.events.single().getValue("sequence").jsonPrimitive.int)
        assertTrue(workspace.closed)
    }

    @Test fun journalErrorSurvivesAnotherFailureDuringCleanup() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task").apply { failClose = true }
        eventSink.failFlush = true

        val original = assertFailsWith<EventSinkFailure> {
            run(request, StubGateway(), workspace)
        }
        assertEquals("Injected flush failure", original.cause?.message)
        assertEquals(1, original.suppressed.size)
        assertEquals("Injected workspace close failure", original.suppressed.single().message)
        assertTrue(workspace.closed)
        assertTrue(eventSink.closed)
        assertFalse(Files.exists(request.output("resultPath")))
    }

    @Test fun journalCloseFailureNeverPublishesSuccessResult() {
        val request = fixture()
        val workspace = StubWorkspace(request, "Read the isolated task")
        eventSink.failClose = true

        assertFailsWith<EventSinkFailure> { run(request, StubGateway(), workspace) }
        assertFalse(Files.exists(request.output("resultPath")))
        assertTrue(eventSink.events.isNotEmpty())
        assertTrue(workspace.closed)
    }

    @Test fun excessiveTokenUsageIsRejectedBeforeAnyToolSideEffect() {
        val request = withBudget(fixture(), "maxTotalTokens", 3L)
        val model = StubGateway { task, _, _ ->
            ModelTurn(ModelDecision.Read(task), ModelUsage(4, 1))
        }
        val workspace = StubWorkspace(request, "Read the isolated task")

        val result = run(request, model, workspace)

        assertEquals("failed", result.value("status"))
        assertEquals("TOKEN_BUDGET_EXCEEDED", result.getValue("error").jsonObject.value("code"))
        assertEquals(0, workspace.readCount)
        assertTrue(workspace.closed)
    }

    @Test fun runSessionInterfaceExecutesWithInjectedPorts() {
        val request = fixture()
        val gateway = StubGateway()
        val workspace = StubWorkspace(request, "Read the isolated task")
        val useCase: RunSessionUseCase = DefaultRunSessionUseCase(
            gateway, WorkspaceOpener { _, audit, start -> workspace.attach(audit, start) },
            eventSinkFactory
        )
        val result = useCase(request, Cancellation())
        assertEquals(SessionOutcome.SUCCEEDED, result.outcome)
        assertEquals(Files.readString(request.output("resultPath")), result.protocol.toString())
        assertEquals(1, workspace.readCount)
        assertEquals(2, gateway.turns)
        assertTrue(workspace.closed)
    }

    @Test fun useCaseRejectsBeforeOpeningWorkspace() {
        val invalid = withField(fixture(), "version", JsonPrimitive("1.1"))
        val gateway = StubGateway()
        val useCase: RunSessionUseCase = DefaultRunSessionUseCase(
            gateway, WorkspaceOpener { _, _, _ ->
                throw AssertionError("Admission failure must precede opening a workspace")
            }, eventSinkFactory
        )
        val result = useCase(invalid, Cancellation())
        assertEquals(SessionOutcome.REJECTED, result.outcome)
        assertEquals(0, gateway.negotiations)
    }

    @Test fun resumeRequestRejectionDoesNotExposePrivateCheckpointPath() {
        val fresh = fixture()
        val privateCheckpoint = temp.resolve("host-only-sensitive-checkpoint")
            .resolve("generation-0002.rcp").toAbsolutePath()
        val request = withField(fresh, "resume", buildJsonObject {
            put("mode", "resume")
            put("fenceGeneration", 2)
            put("checkpoint", buildJsonObject {
                put("path", privateCheckpoint.toString())
                put("sha256", "e".repeat(64))
            })
        })
        // Positive control: the request really carries a private path.
        assertTrue(request.raw.toString().contains(privateCheckpoint.toString()))
        val model = StubGateway()
        val runtime = DefaultRunSessionUseCase(model,
            WorkspaceOpener { _, _, _ ->
                throw AssertionError("Unsupported resume must not open a workspace")
            }, eventSinkFactory)

        val response = runtime.invoke(request, Cancellation()).protocol
        val failure = response.getValue("error").jsonObject
        assertEquals("rejected", response.value("status"))
        assertEquals("UNSUPPORTED_RESUME", failure.value("code"))
        assertEquals("Resume is not supported", failure.value("safeMessage"))
        val resultText = Files.readString(request.output("resultPath"))
        assertEquals(response.toString(), resultText)
        assertTrue(Files.exists(request.output("diagnosticsPath")))
        assertTrue(eventSink.events.isNotEmpty())
        val observable = resultText + eventSink.events.toString() +
            Files.readString(request.output("diagnosticsPath"))
        assertFalse(observable.contains(privateCheckpoint.toString()))
        assertFalse(observable.contains(privateCheckpoint.parent.fileName.toString()))
        assertFalse(observable.contains("FileSystemException"))
        assertFalse(observable.contains("\tat "))
        assertFalse(observable.contains("at ai.yuriilab"))
        assertEquals(0, model.negotiations)
        assertEquals(0, model.turns)
    }

    @Test fun hostExceptionAndSuppressedPathsStayOutOfRuntimeFailureResponse() {
        val request = fixture()
        val privateCheckpoint = temp.resolve("host-only-sensitive-checkpoint")
            .resolve("generation-0002.rcp").toAbsolutePath()
        val privateLedger = temp.resolve("host-only-sensitive-ledger")
            .resolve("events.ledger").toAbsolutePath()
        val cause = java.nio.file.FileSystemException(privateCheckpoint.toString(),
            privateLedger.toString(), "private filesystem diagnostic")
        cause.addSuppressed(java.io.IOException(
            "private suppressed error for " + privateLedger))
        // Positive controls: both host paths are genuinely present in the
        // exception, including a suppressed reason that must never be emitted.
        assertContains(cause.message.orEmpty(), privateCheckpoint.toString())
        assertContains(cause.message.orEmpty(), privateLedger.toString())
        assertContains(cause.suppressed.single().message.orEmpty(), privateLedger.toString())
        val model = StubGateway()
        val runtime = DefaultRunSessionUseCase(model,
            WorkspaceOpener { _, _, _ -> throw cause }, eventSinkFactory)

        val response = runtime.invoke(request, Cancellation()).protocol
        val failure = response.getValue("error").jsonObject
        assertEquals("failed", response.value("status"))
        assertEquals("SESSION_EXECUTION_FAILED", failure.value("code"))
        assertEquals("Session execution failed", failure.value("safeMessage"))
        val resultText = Files.readString(request.output("resultPath"))
        assertEquals(response.toString(), resultText)
        val observable = resultText + eventSink.events.toString() +
            Files.readString(request.output("diagnosticsPath"))
        assertFalse(observable.contains(privateCheckpoint.toString()))
        assertFalse(observable.contains(privateCheckpoint.parent.fileName.toString()))
        assertFalse(observable.contains(privateLedger.toString()))
        assertFalse(observable.contains(privateLedger.parent.fileName.toString()))
        assertFalse(observable.contains("private filesystem diagnostic"))
        assertFalse(observable.contains("private suppressed error"))
        assertFalse(observable.contains("FileSystemException"))
        assertFalse(observable.contains("\tat "))
        assertFalse(observable.contains("at ai.yuriilab"))
        assertEquals(0, model.negotiations)
        assertEquals(0, model.turns)
    }

    @Test fun compatibilityFacadeAcceptsSubstitutedUseCaseWithoutReencoding() {
        val request = fixture()
        val expected = buildJsonObject { put("status", "succeeded") }
        var invoked = false
        val substitute = object : RunSessionUseCase {
            override fun invoke(requestArg: SessionRequest, cancellation: Cancellation): SessionExecutionResult {
                assertSame(request, requestArg)
                invoked = true
                return SessionExecutionResult(SessionOutcome.SUCCEEDED, expected)
            }
        }
        val controller = SessionController(request, Cancellation(), substitute)
        assertSame(expected, controller.run())
        assertTrue(invoked)
        assertTrue(controller.awaitFinished(10))
    }
}
