package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
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
@OptIn(TrustedPreExecutionHandoff::class, TrustedSessionPreExecutionApi::class)
class RuntimeTrustedCreatedAcceptedHandoffIntegrationTest : SessionCheckpointTestFixture() {
    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun request(
        taskId: String = "task-one",
        policySha: String = "a".repeat(64),
        resumeMode: String = "fresh",
        headSha: String = "b".repeat(40)
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
            put("sessionId", "sess-one")
            put("traceId", "trace-one")
            put("invocation", buildJsonObject {
                put("source", "cli")
                put("invocationId", "invoke-one")
                put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-one")
                put("rootPath", root.toString())
                put("baselineSha", "c".repeat(40))
                put("headSha", headSha)
                put("branch", "dev")
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
                put("deadlineAt", Instant.now().plusSeconds(120).toString())
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
                put("eventsPath", ledger().toString())
                put("checkpointDir", store().toString())
                put("diagnosticsPath", temp.resolve("diagnostics.json").toString())
            })
            put("resume", buildJsonObject {
                put("mode", resumeMode)
                put("fenceGeneration", 1)
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

    private fun runtime() = DefaultRunSessionUseCase(
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
        }
    )

    private fun host(expected: Long = 0L) = DurableRuntimeCreatedAcceptedHost(
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
}
