package ai.yuriilab.recursivecoder

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * The actual core SessionRuntime producer has an inadmissible prefix even at
 * its first accepted transition. This is an explicit #104/#105 integration
 * gap, not a request to relax issue #114's metadata-only recovery policy.
 */
@Tag("integration")
class RuntimePreExecutionBoundaryIntegrationTest {
    @TempDir lateinit var temp: Path

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test fun realSessionRuntimeAcceptedPrefixCannotMintFencedResumeLease() {
        val supported = System.getProperty("os.name") == "Linux" &&
            FileSystems.getDefault().supportedFileAttributeViews().contains("unix") &&
            LinuxCheckpointOwner.effectiveUid() != null
        if (System.getenv("CI") != null) assertTrue(supported,
            "Real checkpoint admission requires Linux procfs and unix attributes")
        else assumeTrue(supported)

        val workspace = temp.resolve("workspace")
        Files.createDirectories(workspace)
        val task = workspace.resolve("task.md")
        Files.writeString(task, "Inspect the provided task")
        val taskHash = digest(Files.readAllBytes(task))
        val output = temp.resolve("runtime-out")
        val eventFile = output.resolve("events.ledger")
        val raw = buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "req-one")
            put("sessionId", "sess-one"); put("traceId", "trace-one")
            put("invocation", buildJsonObject {
                put("source", "cli"); put("invocationId", "invoke-one")
                put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-one")
                put("rootPath", workspace.toString())
                put("baselineSha", "a".repeat(40)); put("headSha", "a".repeat(40))
                put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", "task-one")
                put("taskInputRef", buildJsonObject {
                    put("path", task.toString()); put("sha256", taskHash)
                })
            })
            put("model", buildJsonObject {
                put("provider", "fake"); put("modelId", "fake-v1")
                put("baseUrl", "http://fake.invalid/v1")
                put("credentialRef", "env:FAKE_TOKEN")
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
                put("deadlineAt", Instant.now().plusSeconds(60).toString())
                put("maxWallTimeMs", 12000); put("maxTotalTokens", 10000)
                put("maxToolCalls", 1); put("maxChildAgents", 0); put("maxDepth", 0)
                put("maxModelRequests", 2)
            })
            put("outputs", buildJsonObject {
                put("rootDir", output.toString())
                put("resultPath", output.resolve("result.json").toString())
                put("eventsPath", eventFile.toString())
                put("checkpointDir", output.resolve("checkpoints").toString())
                put("diagnosticsPath", output.resolve("diagnostics.json").toString())
            })
            put("resume", buildJsonObject {
                put("mode", "fresh"); put("fenceGeneration", 1)
                put("checkpoint", JsonNull)
            })
            put("requiredCapabilities", buildJsonArray {
                add("session-events-v1"); add("fake-model-v1")
                add("read-only-tools-v1")
            })
        }
        val request = SessionRequest.parse(raw.toString())
        val openedAt = Instant.now()
        val model = object : ModelGateway {
            override fun negotiate(config: ModelConfig, cancellation: Cancellation,
                                   deadline: Instant): GatewayCapabilities =
                GatewayCapabilities(setOf("tool_calls"), config.contextTokens,
                    config.maxOutputTokens)

            override fun next(config: ModelConfig, task: Path,
                              observed: Pair<Int, String>?, cancellation: Cancellation,
                              deadline: Instant, allowRetry: Boolean): ModelTurn =
                ModelTurn(ModelDecision.Refusal)
        }
        val opener = WorkspaceOpener { _, onAudit, onStart ->
            // Runtime's real callbacks generate the pre-admission audit events,
            // rather than the test constructing them as synthetic JSON.
            onStart("receipt-preflight", "read", openedAt)
            onAudit(ToolAudit("receipt-preflight", "read", openedAt,
                true, null, 0, null))
            object : WorkspaceSession {
                override fun verifyTask(path: Path, expectedSha: String) {
                    assertEquals(task, path)
                    assertEquals(taskHash, expectedSha)
                }
                override fun taskRelativePath(path: Path): String = "task.md"
                override fun execute(tool: WorkspaceTool, claimedExecutionId: String?): ToolOutcome =
                    error("Model refusal must not execute a workspace tool")
                override fun close() {}
            }
        }
        val runtime = DefaultRunSessionUseCase(model, opener,
            EventSinkFactory { path ->
                DurableSessionEventLedger.create(path, workspace)
            })
        runtime.invoke(request, Cancellation())
        val emitted = DurableSessionEventLedger.inspect(eventFile).events
        val acceptedIndex = emitted.indexOfFirst { event ->
            (event["type"] as? JsonPrimitive)?.content == "session.transition" &&
                (event["transition"] as? JsonObject)?.get("to")?.jsonPrimitive?.content ==
                    SessionPhaseVocabulary.acceptedWire
        }
        assertTrue(acceptedIndex >= 3, "Runtime must reach accepted after workspace/model audits")
        val prefixEvents = emitted.take(acceptedIndex + 1)
        assertEquals(listOf("tool.started", "tool.completed", "llm.capabilities"),
            prefixEvents.take(3).map { it["type"]!!.jsonPrimitive.content })
        assertNull(PreExecutionPhasePolicy.phaseFromVerifiedHistory(
            SessionPhaseVocabulary.acceptedWire, prefixEvents))

        // Reframe these *actual emitted events* as a crash-time physical
        // prefix so that admission can reach its policy boundary independently
        // of the later runtime RUNNING / model-refusal terminal events.
        val prefixFile = temp.resolve("accepted-prefix.ledger")
        DurableSessionEventLedger.create(prefixFile, workspace).use { writer ->
            prefixEvents.forEach { writer.append(it); writer.flush() }
        }
        val verified = DurableSessionEventLedger.inspect(prefixFile)
        val context = CheckpointHostContext(workspace, "b".repeat(64), "a".repeat(40),
            "refs/heads/dev", "c".repeat(64), "graph-root", 5, "d".repeat(64))
        val store = temp.resolve("checkpoints")
        Files.createDirectory(store)
        Files.setPosixFilePermissions(store, setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE))
        val checkpoint = SessionCheckpoint(
            sequence = 1, previousSha256 = null, binding = context.binding(),
            sessionId = "sess-one", traceId = "trace-one",
            phase = SessionPhaseVocabulary.acceptedWire, taskId = "task-one",
            taskAttempt = 0, taskState = "ready",
            children = emptyList(), acceptedArtifacts = emptyList(),
            // Snapshot equality after canonical readback requires scope/kind order.
            hostAssertedUsage = listOf(
                HostAssertedCheckpointUsage("task-one", "model", "known", 0L),
                HostAssertedCheckpointUsage("task-one", "tool", "known", 0L)),
            pendingEffects = emptyList(),
            journalSequence = verified.lastSequence, journalHeadSha256 = verified.headSha256,
            journalFileSha256 = verified.fileSha256,
            lastTrustedJournalOffset = verified.lastTrustedOffset)
        SessionCheckpointStore.save(store, context, prefixFile, checkpoint, null)
        val attestation = ResumeHostAttestation(0L,
            ReadPolicyFacts("read-only", ToolAuthorizationPolicy.knownReadTools,
                writablePathsPresent = false, immutableInputsPresent = false),
            externalActionsReconciled = true)
        assertEquals("RESUME_UNSAFE_BOUNDARY",
            assertFailsWith<SessionResumeUnconfirmed> {
                SessionResumeAdmission.admit(store, context, prefixFile, attestation)
            }.code)
        assertFalse(Files.exists(store.resolve("resume-000000000001.rcf")))
    }
}
