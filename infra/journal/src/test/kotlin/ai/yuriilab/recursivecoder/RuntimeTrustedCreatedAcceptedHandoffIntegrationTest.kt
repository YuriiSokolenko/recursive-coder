package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Tests the REAL DefaultRunSessionUseCase opt-in boundary against the physical
 * C1 -> fenced append -> C2 adapter. This deliberately cannot run a model,
 * tool or normal session journal. No live-model or network access.
 */
@Tag("integration")
@OptIn(TrustedPreExecutionHandoff::class, TrustedSessionPreExecutionApi::class,
    TrustedRunningBoundaryApi::class)
class RuntimeTrustedCreatedAcceptedHandoffIntegrationTest : SessionCheckpointTestFixture() {
    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun request(
        taskId: String = "task-one",
        policySha: String = "a".repeat(64),
        resumeMode: String = "fresh",
        headSha: String = "b".repeat(40),
        requestedRoot: Path = workspace(),
        requestedBranch: String? = "dev",
        explicitNullBranch: Boolean = false,
        checkpointDir: Path = store(),
        eventsPath: Path = ledger(),
        fenceGeneration: Long = 1L,
        sessionId: String = "sess-one",
        traceId: String = "trace-one",
        deadlineAt: Instant = Instant.now().plusSeconds(120)
    ): SessionRequest {
        val root = workspace()
        val task = root.resolve("task.md")
        if (!Files.exists(task)) Files.writeString(task, "Inspect the task")
        // Both durable paths are under outputRoot for Session Protocol v1;
        // they are separately owned by this trusted test host.
        val raw = buildJsonObject {
            put("kind", "session-request")
            put("version", "1.0")
            put("requestId", "req-one")
            put("sessionId", sessionId)
            put("traceId", traceId)
            put("invocation", buildJsonObject {
                put("source", "cli")
                put("invocationId", "invoke-one")
                put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-one")
                put("rootPath", requestedRoot.toString())
                put("baselineSha", "c".repeat(40))
                put("headSha", headSha)
                when {
                    explicitNullBranch -> put("branch", JsonNull)
                    requestedBranch != null -> put("branch", requestedBranch)
                }
            })
            put("task", buildJsonObject {
                put("stage", "sample")
                put("role", "reader")
                put("taskId", taskId)
                put("taskInputRef", buildJsonObject {
                    put("path", task.toString())
                    put("sha256", sha256(task))
                })
            })
            put("model", buildJsonObject {
                put("provider", "fake")
                put("modelId", "fake-v1")
                put("baseUrl", "http://fake.invalid/v1")
                put("credentialRef", "env:FAKE_TOKEN")
                put("contextTokens", 8192)
                put("maxOutputTokens", 128)
                put("features", buildJsonArray { add("tool_calls") })
            })
            put("toolPolicy", buildJsonObject {
                put("mode", "read-only")
                put("allowedTools", buildJsonArray { add("read") })
                put("deniedTools", buildJsonArray { add("safe_edit") })
                put("protectedPaths", buildJsonArray { add(".git/**") })
                put("writablePaths", buildJsonArray {})
                put("policySha256", policySha)
            })
            put("budgets", buildJsonObject {
                put("deadlineAt", deadlineAt.toString())
                put("maxWallTimeMs", 120000)
                put("maxTotalTokens", 10000)
                put("maxToolCalls", 1)
                put("maxChildAgents", 0)
                put("maxDepth", 0)
                put("maxModelRequests", 2)
            })
            put("outputs", buildJsonObject {
                put("rootDir", temp.toString())
                put("resultPath", temp.resolve("result.json").toString())
                put("eventsPath", eventsPath.toString())
                put("checkpointDir", checkpointDir.toString())
                put("diagnosticsPath", temp.resolve("diagnostics.json").toString())
            })
            put("resume", buildJsonObject {
                put("mode", resumeMode)
                put("fenceGeneration", fenceGeneration)
                put("checkpoint", JsonNull)
            })
            put("requiredCapabilities", buildJsonArray {
                add("session-events-v1")
                add("fake-model-v1")
                add("read-only-tools-v1")
            })
        }
        return SessionRequest.parse(raw.toString())
    }

    private var modelCalls = 0
    private var workspaceCalls = 0
    private var normalSinkCalls = 0

    private fun runtime(clock: Clock = Clock.systemUTC()) = DefaultRunSessionUseCase(
        object : ModelGateway {
            override fun negotiate(config: ModelConfig, cancellation: Cancellation,
                                   deadline: Instant): GatewayCapabilities {
                modelCalls++
                error("Model negotiation must never run before/after C2")
            }
            override fun next(config: ModelConfig, task: Path,
                              observed: Pair<Int, String>?, cancellation: Cancellation,
                              deadline: Instant, allowRetry: Boolean): ModelTurn {
                modelCalls++
                error("Model inference must never run before/after C2")
            }
        },
        WorkspaceOpener { _, _, _ ->
            workspaceCalls++
            error("Workspace opener must never run before/after C2")
        },
        EventSinkFactory { _ ->
            normalSinkCalls++
            error("Normal runtime journal must never be opened for fenced C1/C2")
        },
        clock = clock
    )

    private fun host(expected: Long = 0L) = DurableRuntimeCreatedAcceptedHost(
        store(), ledger(), { context() }, { resumeAttestation(expected = expected) }
    )

    private fun runningHost(expected: Long = 1L) = DurableRuntimeAcceptedRunningHost(
        store(), ledger(), { context() }, { resumeAttestation(expected = expected) }
    )

    private fun assertNoExecutionOrResult() {
        assertEquals(0, modelCalls)
        assertEquals(0, workspaceCalls)
        assertEquals(0, normalSinkCalls)
        assertFalse(Files.exists(temp.resolve("result.json")))
        assertFalse(Files.exists(temp.resolve("diagnostics.json")))
    }

    @Test fun realRuntimeHandoffProducesOnlyDurableAcceptedPrefix() {
        val c1 = createPreExecutionCheckpoint()
        val receipt = runtime().publishTrustedCreatedAccepted(
            request(), Cancellation(), host())

        val c2 = inspect()
        assertEquals(c1.sha256, c2.snapshot.previousSha256)
        assertEquals("accepted", c2.snapshot.phase)
        assertEquals("ready", c2.snapshot.taskState)
        assertEquals(2L, receipt.checkpointSequence)
        assertEquals(c2.sha256, receipt.checkpointSha256)
        assertEquals(1L, SessionResumeAdmission.currentGeneration(
            store(), context(), ledger()))
        val events = DurableSessionEventLedger.inspect(ledger()).events
        assertEquals(listOf("checkpoint.saved", "session.transition"),
            events.map { it["type"]!!.jsonPrimitive.content })
        assertTrue(events.none { it["type"]!!.jsonPrimitive.content in
            setOf("tool.started", "tool.completed", "llm.capabilities", "llm.request",
                "llm.response", "agent.started", "agent.completed") })
        assertEquals("accepted",
            PreExecutionPhasePolicy.phaseFromVerifiedHistory("accepted", events))
        assertNoExecutionOrResult()

        // C2 is physical evidence; a new generation may read this prefix
        // under admission, but still cannot authorize runtime execution.
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { next ->
            assertEquals(2L, next.generation)
        }
        assertNoExecutionOrResult()
    }

    @Test fun mismatchedRequestIdentityAndPolicyRefuseWithoutMintingFence() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val runner = runtime()
        for (bad in listOf(
            request(taskId = "other-task"),
            request(policySha = "e".repeat(64)),
            request(headSha = "e".repeat(40))
        )) {
            val refused = assertFailsWith<SessionResumeUnconfirmed> {
                runner.publishTrustedCreatedAccepted(bad, Cancellation(), host())
            }
            assertEquals("RESUME_CONTEXT_CHANGED", refused.code)
            assertEquals(c1, inspect())
            assertContentEquals(original, Files.readAllBytes(ledger()))
            assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        }
        assertNoExecutionOrResult()
    }

    @Test fun wrongFenceCASFailsWithoutPublishingC2() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val denied = assertFailsWith<SessionResumeUnconfirmed> {
            runtime().publishTrustedCreatedAccepted(request(), Cancellation(), host(expected = 5))
        }
        assertEquals("RESUME_FENCE_CAS_FAILED", denied.code)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("000000000002.rcp")))
        assertNoExecutionOrResult()
    }


    @Test fun allContextMismatchBranchesRefuseBeforeFenceOrLedgerMutation() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val alternativeRoot = temp.resolve("other-root")
        Files.createDirectories(alternativeRoot)
        val runner = runtime()
        val mismatches = listOf(
            request(checkpointDir = temp.resolve("redirected-checkpoints")),
            request(eventsPath = temp.resolve("redirected-events.ledger")),
            request(requestedRoot = alternativeRoot),
            request(requestedBranch = "other"),
            request(sessionId = "different-session"),
            request(traceId = "different-trace"),
            request(taskId = "different-task"),
            request(headSha = "e".repeat(40)),
            request(policySha = "e".repeat(64))
        )
        for (bad in mismatches) {
            val refused = assertFailsWith<SessionResumeUnconfirmed> {
                runner.publishTrustedCreatedAccepted(bad, Cancellation(), host())
            }
            assertEquals("RESUME_CONTEXT_CHANGED", refused.code)
            assertEquals("RESUME_CONTEXT_CHANGED", refused.message)
            assertNull(refused.cause)
            assertEquals(c1, inspect())
            assertContentEquals(original, Files.readAllBytes(ledger()))
            assertFalse(Files.exists(store().resolve("resume.lock")))
            assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        }
        assertNoExecutionOrResult()
    }

    @Test fun missingWorkspacePathDoesNotLeakItsNameOrCreateFence() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val privatePath = temp.resolve("hidden-parent-secret-workspace")
        val refused = assertFailsWith<SessionResumeUnconfirmed> {
            runtime().publishTrustedCreatedAccepted(
                request(requestedRoot = privatePath), Cancellation(), host())
        }
        assertEquals("RESUME_CONTEXT_CHANGED", refused.code)
        assertEquals("RESUME_CONTEXT_CHANGED", refused.message)
        assertNull(refused.cause)
        assertFalse(refused.toString().contains("hidden-parent-secret-workspace"))
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        assertNoExecutionOrResult()
    }

    @Test fun nonInitialFenceGenerationPreservesRuntimeDenialPrecedence() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val bad = request(fenceGeneration = 2L)
        val invoked = assertFailsWith<Refused> {
            runtime().publishTrustedCreatedAccepted(bad, Cancellation(), host())
        }
        // Protocol admission rejects a non-fresh generation before host I/O.
        assertEquals("UNSUPPORTED_RESUME", invoked.code)
        // Direct trusted-host call independently refuses a mismatched request.
        val direct = assertFailsWith<SessionResumeUnconfirmed> {
            host().publishCreatedAccepted(bad, Cancellation(),
                Instant.now().plusSeconds(120), Clock.systemUTC())
        }
        assertEquals("RESUME_CONTEXT_CHANGED", direct.code)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertNoExecutionOrResult()
    }

    @Test fun preCancelledAndExpiredDeadlineNeverInvokeHost() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        var hostCalls = 0
        val spy = TrustedSessionPreExecutionHost { _, _, _, _ ->
            hostCalls++
            error("Host must not be invoked")
        }
        val cancelled = Cancellation()
        cancelled.cancel()
        val stopped = assertFailsWith<Stopped> {
            runtime().publishTrustedCreatedAccepted(request(), cancelled, spy)
        }
        assertFalse(stopped.timedOut)
        assertFalse(Thread.currentThread().isInterrupted,
            "Cooperative cancellation must not interrupt the test/caller thread")
        val expired = assertFailsWith<Stopped> {
            runtime().publishTrustedCreatedAccepted(
                request(deadlineAt = Instant.now().minusSeconds(5)),
                Cancellation(), spy)
        }
        assertTrue(expired.timedOut)
        assertEquals(0, hostCalls)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertNoExecutionOrResult()
    }

    @Test fun hostRechecksCancellationAfterTrustedContextCallback() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val cancellation = Cancellation()
        var attestationCalls = 0
        val cancellingHost = DurableRuntimeCreatedAcceptedHost(
            store(), ledger(),
            {
                cancellation.cancel()
                context()
            },
            {
                attestationCalls++
                resumeAttestation()
            }
        )
        val stopped = assertFailsWith<Stopped> {
            runtime().publishTrustedCreatedAccepted(
                request(), cancellation, cancellingHost)
        }
        assertFalse(stopped.timedOut)
        assertFalse(Thread.currentThread().isInterrupted,
            "A callback cancellation must not bind or interrupt this thread")
        assertEquals(0, attestationCalls)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertNoExecutionOrResult()
    }

    @Test fun hostUsesRuntimeClockToEnforceDeadlineBetweenGates() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val start = Instant.parse("2026-11-20T12:00:00Z")
        val observed = AtomicReference(start)
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = observed.get()
        }
        var attestationCalls = 0
        val expiringHost = DurableRuntimeCreatedAcceptedHost(
            store(), ledger(),
            {
                observed.set(start.plusSeconds(20))
                context()
            },
            {
                attestationCalls++
                resumeAttestation()
            }
        )
        val stopped = assertFailsWith<Stopped> {
            runtime(clock).publishTrustedCreatedAccepted(
                request(deadlineAt = start.plusSeconds(5)),
                Cancellation(), expiringHost)
        }
        assertTrue(stopped.timedOut)
        assertEquals(0, attestationCalls)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertNoExecutionOrResult()
    }


    @Test fun interruptInsideAttestationIsPreservedWithoutAnyLedgerMutation() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val interruptingHost = DurableRuntimeCreatedAcceptedHost(
            store(), ledger(), { context() }, {
                Thread.currentThread().interrupt()
                resumeAttestation()
            }
        )
        try {
            val stopped = assertFailsWith<Stopped> {
                runtime().publishTrustedCreatedAccepted(
                    request(), Cancellation(), interruptingHost)
            }
            assertFalse(stopped.timedOut)
            assertTrue(Thread.currentThread().isInterrupted,
                "The host must preserve a pre-publication shutdown interrupt")
        } finally {
            // JUnit must not inherit the caller's deliberately preserved flag.
            Thread.interrupted()
        }
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()),
            "No uncovered accepted event is permitted without durable C2")
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        assertFalse(Files.exists(store().resolve("000000000002.rcp")))
        assertNoExecutionOrResult()
    }

    @Test fun expiredDeadlineAndPendingInterruptPreserveTimeoutAndShutdownSignal() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val badDeadline = Instant.now().minusSeconds(5)
        try {
            Thread.currentThread().interrupt()
            val stopped = assertFailsWith<Stopped> {
                host().publishCreatedAccepted(request(), Cancellation(),
                    badDeadline, Clock.systemUTC())
            }
            assertTrue(stopped.timedOut, "Deadline retains priority over cancellation")
            assertTrue(Thread.currentThread().isInterrupted,
                "Even a timeout refusal must preserve the existing interrupt")
        } finally {
            Thread.interrupted()
        }
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertFalse(Files.exists(store().resolve("resume-000000000001.rcf")))
        assertNoExecutionOrResult()
    }

    @Test fun crossThreadCancelAtFinalGateCannotInterruptC2Publication() {
        createPreExecutionCheckpoint()
        val cancellation = Cancellation()
        val instant = Instant.parse("2026-11-20T12:00:00Z")
        val reads = AtomicInteger(0)
        val cancellationDelivered = AtomicReference(false)
        val raceClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant {
                // The ordering here deliberately targets the final gate:
                // signals() is read before clock.instant(), so cancellation
                // at this clock read happens after the signal snapshot.
                // Keep this test in sync if the number of clock reads changes.
                // A production test hook would add an unnecessary callback
                // inside the trusted, security-critical publication boundary.
                if (reads.incrementAndGet() == 8) {
                    val canceller = Thread({ cancellation.cancel() },
                        "handoff-cancellation-race")
                    canceller.start()
                    canceller.join(5000)
                    assertFalse(canceller.isAlive, "Cancel thread must finish")
                    cancellationDelivered.set(true)
                }
                return instant
            }
        }
        val receipt = runtime(raceClock).publishTrustedCreatedAccepted(
            request(deadlineAt = instant.plusSeconds(120)), cancellation, host())
        assertTrue(cancellationDelivered.get())
        assertEquals(8, reads.get(), "The cancellation must hit the final gate")
        assertTrue(cancellation.signals().requested)
        assertFalse(Thread.currentThread().isInterrupted,
            "A late cancellation must not interrupt the durable FileChannels")
        val c2 = inspect()
        assertEquals(c2.sha256, receipt.checkpointSha256)
        assertEquals("accepted", c2.snapshot.phase)
        assertEquals(2L, c2.snapshot.sequence)
        assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        assertTrue(Files.exists(store().resolve("000000000002.rcp")))
        assertNoExecutionOrResult()
    }

    @Test fun cancellingAfterHandoffReturnsDoesNotInterruptCallerThread() {
        createPreExecutionCheckpoint()
        val cancellation = Cancellation()
        val receipt = runtime().publishTrustedCreatedAccepted(
            request(), cancellation, host())
        assertEquals(2L, receipt.checkpointSequence)
        val canceller = Thread({ cancellation.cancel() },
            "post-handoff-cancellation")
        canceller.start()
        canceller.join(5000)
        assertFalse(canceller.isAlive)
        assertTrue(cancellation.signals().requested)
        assertFalse(Thread.currentThread().isInterrupted,
            "A completed host-only invocation must never leave a bound worker")
        assertEquals("accepted", inspect().snapshot.phase)
        assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        assertNoExecutionOrResult()
    }

    @Test fun missingBranchUsesTrustedHostContextButExplicitNullIsInvalid() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val invalid = assertFailsWith<Refused> {
            request(explicitNullBranch = true)
        }
        assertEquals("INVALID_SESSION_REQUEST", invalid.code)
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))

        // An absent branch is legal: trusted context owns the actual branch.
        val receipt = runtime().publishTrustedCreatedAccepted(
            request(requestedBranch = null), Cancellation(), host())
        assertEquals(2L, receipt.checkpointSequence)
        assertEquals(c1.sha256, inspect().snapshot.previousSha256)
        assertEquals("accepted", inspect().snapshot.phase)
        assertEquals(2, DurableSessionEventLedger.inspect(ledger()).lastSequence)
        assertNoExecutionOrResult()
    }

    @Test fun canonicalFrameBuilderMatchesJournalEmissionAndCausation() {
        val now = Instant.parse("2026-11-20T12:00:00Z")
        val emitted = mutableListOf<JsonObject>()
        val sink = object : EventSink {
            override fun append(event: JsonObject) { emitted.add(event) }
            override fun flush() = Unit
            override fun sha256(): String = "a".repeat(64)
            override fun close() = Unit
        }
        val journal = SessionEventJournal("sess-one", "trace-one",
            "req-one", Clock.fixed(now, ZoneOffset.UTC), sink)
        val metadata = buildJsonObject { put("graphId", "graph-root") }
        journal.append("checkpoint.saved", "completed", extra = metadata)
        journal.append("session.transition", "completed",
            transition = "created" to "accepted", extra = metadata)
        journal.finish()
        val expected = SessionEventJournal.frame(
            sessionId = "sess-one", traceId = "trace-one", requestId = "req-one",
            sequence = 2, timestamp = now, type = "session.transition",
            status = "completed", transition = "created" to "accepted",
            extra = metadata)
        assertEquals(expected, emitted[1])
        assertEquals(SessionEventJournal.eventId("sess-one", 1),
            emitted[1]["causationId"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, emitted[0]["causationId"])
        assertEquals(SessionEventJournal.emittedFields - setOf("timing"),
            emitted[1].keys)
        assertTrue(ToolAuthorizationPolicy.validHostIdentifier(
            emitted[1]["correlationId"]!!.jsonPrimitive.content))
    }

    @Test fun existingUnsupportedResumePrecedencePreventsHostAdmission() {
        createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val refused = assertFailsWith<Refused> {
            runtime().publishTrustedCreatedAccepted(
                request(resumeMode = "resume"), Cancellation(), host())
        }
        assertEquals("UNSUPPORTED_RESUME", refused.code)
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertNoExecutionOrResult()
    }
    @Test fun realRuntimeC2ToC3IsDurableButNeverAnExecutionGrant() {
        createPreExecutionCheckpoint()
        val runtime = runtime()
        runtime.publishTrustedCreatedAccepted(request(), Cancellation(), host())
        val c2 = inspect()

        val receipt = runtime.publishTrustedAcceptedRunning(
            request(), Cancellation(), runningHost(expected = 1L))

        val c3 = inspect()
        assertEquals(c2.sha256, c3.snapshot.previousSha256)
        assertEquals(3L, receipt.checkpointSequence)
        assertEquals(c3.sha256, receipt.checkpointSha256)
        assertEquals("running", c3.snapshot.phase)
        assertEquals("ready", c3.snapshot.taskState)
        assertEquals(0, c3.snapshot.taskAttempt)
        assertEquals(c2.snapshot.children, c3.snapshot.children)
        assertEquals(c2.snapshot.hostAssertedUsage, c3.snapshot.hostAssertedUsage)
        assertTrue(c3.snapshot.pendingEffects.isEmpty())
        val physical = DurableSessionEventLedger.inspect(ledger())
        assertEquals(3, physical.lastSequence)
        assertEquals(listOf("checkpoint.saved", "session.transition", "session.transition"),
            physical.events.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("running", TrustedRunningBoundaryPolicy.phaseFromVerifiedHistory(
            "running", physical.events))
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory("running", physical.events))
        assertNoExecutionOrResult()

        // A real C3 is intentionally NOT a safe restart boundary: #105 gates
        // later execution and independently reconciled receipts.
        val denied = assertFailsWith<SessionResumeUnconfirmed> {
            SessionResumeAdmission.admit(store(), context(), ledger(),
                resumeAttestation(expected = 2L))
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY", denied.code)
        assertFalse(Files.exists(store().resolve("resume-000000000003.rcf")))
        assertNoExecutionOrResult()
    }

    @Test fun runningBoundaryCasFailureDoesNotAppendOrPublishC3() {
        createPreExecutionCheckpoint()
        val runtime = runtime()
        runtime.publishTrustedCreatedAccepted(request(), Cancellation(), host())
        val c2 = inspect()
        val prefix = Files.readAllBytes(ledger())
        val failed = assertFailsWith<SessionResumeUnconfirmed> {
            runtime.publishTrustedAcceptedRunning(
                request(), Cancellation(), runningHost(expected = 0L))
        }
        assertEquals("RESUME_FENCE_CAS_FAILED", failed.code)
        assertEquals(c2, inspect())
        assertContentEquals(prefix, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("000000000003.rcp")))
        assertFalse(Files.exists(store().resolve("resume-000000000002.rcf")))
        assertNoExecutionOrResult()
    }

    @Test fun createdCheckpointCannotSkipAcceptedBeforeRunning() {
        val c1 = createPreExecutionCheckpoint()
        val original = Files.readAllBytes(ledger())
        val denied = assertFailsWith<SessionResumeUnconfirmed> {
            runtime().publishTrustedAcceptedRunning(request(), Cancellation(), runningHost(expected = 0L))
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY", denied.code)
        assertEquals(c1, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertFalse(Files.exists(store().resolve("resume.lock")))
        assertNoExecutionOrResult()
    }

    @Test fun ordinaryFencedAppendStillRefusesRunningEdge() {
        createPreExecutionCheckpoint()
        runtime().publishTrustedCreatedAccepted(request(), Cancellation(), host())
        val c2 = inspect()
        val original = Files.readAllBytes(ledger())
        SessionResumeAdmission.admit(store(), context(), ledger(),
            resumeAttestation(expected = 1L)).use { lease ->
            DurableSessionEventLedger.reopenForAppend(
                ledger(), workspace(), store(), context(), lease
            ).use { writer ->
                val forbidden = SessionEventJournal.frame(
                    "sess-one", "trace-one", "req-one", 3, Instant.now(),
                    "session.transition", "completed",
                    "accepted" to "running",
                    extra = buildJsonObject { put("graphId", "graph-root") })
                val failure = assertFailsWith<SessionLedgerUnconfirmed> {
                    writer.appendFenced(forbidden)
                }
                assertEquals("LEDGER_REOPEN_EVENT_REFUSED", failure.code)
            }
        }
        assertEquals(c2, inspect())
        assertContentEquals(original, Files.readAllBytes(ledger()))
        assertNoExecutionOrResult()
    }

    @Test fun secondRunningPublicationRefusesBeforeAnyNewFenceOrLedgerMutation() {
        createPreExecutionCheckpoint()
        val runtime = runtime()
        runtime.publishTrustedCreatedAccepted(request(), Cancellation(), host())
        runtime.publishTrustedAcceptedRunning(
            request(), Cancellation(), runningHost(expected = 1L))
        val durableC3 = inspect()
        val bytes = Files.readAllBytes(ledger())
        val allBefore = Files.list(store()).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        }

        val rejected = assertFailsWith<SessionResumeUnconfirmed> {
            runtime.publishTrustedAcceptedRunning(
                request(), Cancellation(), runningHost(expected = 2L))
        }
        assertEquals("RESUME_UNSAFE_BOUNDARY", rejected.code)
        assertEquals(durableC3, inspect())
        assertContentEquals(bytes, Files.readAllBytes(ledger()))
        assertEquals(allBefore, Files.list(store()).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        })
        assertFalse(Files.exists(store().resolve("resume-000000000003.rcf")))
        assertNoExecutionOrResult()
    }

}
