package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private val workspaceTestOpener = WorkspaceOpener { request, audit, start ->
    WorkspaceManager.open(request, audit, start)
}

@Tag("integration")
class WorkspaceManagerTest {
    @TempDir lateinit var temp: Path

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile())
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), text)
        return text.trim()
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fixture(
        allowed: List<String> = listOf("read", "list", "find", "search", "git_status", "git_diff"),
        denied: List<String> = listOf("safe_edit", "git_push"),
        mode: String = "read-only",
        quota: Int = 20
    ): Pair<Path, SessionRequest> {
        val root = temp.resolve("workspace")
        Files.createDirectories(root.resolve("src"))
        git(root, "init", "-b", "dev")
        git(root, "config", "user.email", "test@example.invalid")
        git(root, "config", "user.name", "Fixture")
        Files.writeString(root.resolve("task.md"), "Implement the workspace guard")
        Files.writeString(root.resolve("src/code.kt"), "fun hello() = 7\n")
        Files.writeString(root.resolve(".env"), "TEST_CREDENTIAL_DO_NOT_LEAK")
        Files.writeString(root.resolve("secret.pem"), "TEST_PRIVATE_KEY_DO_NOT_LEAK")
        Files.createDirectories(root.resolve("protected"))
        Files.writeString(root.resolve("protected/spec.md"), "Protected acceptance specification")
        git(root, "add", ".")
        git(root, "commit", "-m", "baseline")
        val head = git(root, "rev-parse", "HEAD")
        val out = temp.resolve("out")
        val raw = buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "req-five"); put("sessionId", "session-five"); put("traceId", "trace-five")
            put("invocation", buildJsonObject {
                put("source", "cli"); put("invocationId", "invoke-five"); put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-five"); put("rootPath", root.toString())
                put("headSha", head); put("baselineSha", head); put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", "task-five")
                put("taskInputRef", buildJsonObject {
                    put("path", root.resolve("task.md").toString())
                    put("sha256", hash("Implement the workspace guard"))
                })
            })
            put("model", buildJsonObject {
                put("provider", "fake"); put("modelId", "fake-v1")
                put("baseUrl", "http://fake.invalid/v1"); put("credentialRef", "env:FAKE_TOKEN")
                put("contextTokens", 8192); put("maxOutputTokens", 128)
                put("features", buildJsonArray { add("tool_calls") })
            })
            put("toolPolicy", buildJsonObject {
                put("mode", mode)
                put("allowedTools", buildJsonArray { allowed.forEach { add(it) } })
                put("deniedTools", buildJsonArray { denied.forEach { add(it) } })
                put("protectedPaths", buildJsonArray { add("protected/**") })
                put("writablePaths", buildJsonArray {})
                put("policySha256", "b".repeat(64))
            })
            put("budgets", buildJsonObject {
                put("deadlineAt", Instant.now().plusSeconds(60).toString())
                put("maxWallTimeMs", 30000); put("maxTotalTokens", 10000)
                put("maxToolCalls", quota); put("maxModelRequests", 2)
                put("maxChildAgents", 0); put("maxDepth", 0)
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
        return root to SessionRequest.parse(raw.toString())
    }

    private fun open(request: SessionRequest, audits: MutableList<ToolAudit> = mutableListOf()) =
        WorkspaceManager.open(request, audits::add, lockDirectory = temp.resolve("leases"))

    @Test fun typedToolsReadOnlyCommittedPublicFilesWithBoundedResults() {
        val (root, request) = fixture()
        open(request).use { workspace ->
            workspace.verifyTask(root.resolve("task.md"), hash("Implement the workspace guard"))
            val read = workspace.execute(WorkspaceTool.Read("src/code.kt"))
            assertTrue(read.permitted)
            val data = assertIs<ToolData.FileText>(read.data)
            assertEquals("fun hello() = 7\n", data.text)
            assertEquals(hash(data.text), data.sha256)
            val paths = assertIs<ToolData.Paths>(workspace.execute(WorkspaceTool.ListFiles("src")).data)
            assertEquals(listOf("src/code.kt"), paths.paths)
            val found = assertIs<ToolData.Paths>(workspace.execute(WorkspaceTool.Find("code")).data)
            assertEquals(listOf("src/code.kt"), found.paths)
            val matches = assertIs<ToolData.Matches>(workspace.execute(WorkspaceTool.Search("hello")).data)
            assertEquals(ToolData.Match("src/code.kt", 1, "fun hello() = 7"), matches.matches.single())
            Files.writeString(root.resolve("src/code.kt"), "fun hello() = 9\n")
            val status = assertIs<ToolData.Status>(workspace.execute(WorkspaceTool.GitStatus).data)
            assertTrue(status.entries.any { it.path == "src/code.kt" && it.worktree == 'M' })
            val diff = assertIs<ToolData.Diff>(workspace.execute(WorkspaceTool.GitDiff("src/code.kt")).data)
            assertContains(diff.patch, "+fun hello() = 9")
            assertEquals(request.workspace["baselineSha"]!!.jsonPrimitive.content, workspace.baselineHead)
            assertTrue(workspace.policyHash.matches(Regex("[a-f0-9]{64}")))
        }
    }

    @Test fun pathTraversalSecretsAndUntrackedPathsAreDenied() {
        val (root, request) = fixture()
        Files.writeString(root.resolve("untracked.txt"), "not admitted")
        Files.writeString(temp.resolve("outside.txt"), "outside secret")
        val probes = listOf("../outside.txt", "/etc/passwd", "src/../../outside.txt",
            ".git/config", ".env", "secret.pem", "protected/spec.md", "untracked.txt")
        val audits = mutableListOf<ToolAudit>()
        open(request, audits).use { workspace ->
            probes.forEach { path ->
                val result = workspace.execute(WorkspaceTool.Read(path))
                assertFalse(result.permitted, path)
                assertNull(result.data)
                assertNotNull(result.deniedCode)
            }
            assertEquals(probes.size, audits.size)
            assertEquals(probes.size, audits.map { it.receiptId }.toSet().size)
            assertTrue(audits.all { !it.permitted && it.outputBytes == 0 })
            assertFalse(audits.joinToString().contains("outside secret"))
        }
    }

    @Test fun deniedToolsBeatGrantsAndForgedExecutionIdsCannotEscalate() {
        val (_, request) = fixture(denied = listOf("read"), quota = 2)
        val audits = mutableListOf<ToolAudit>()
        open(request, audits).use { workspace ->
            assertEquals("TOOL_NOT_AUTHORIZED",
                workspace.execute(WorkspaceTool.Read("task.md")).deniedCode)
            assertEquals("FORGED_TOOL_ID",
                workspace.execute(WorkspaceTool.ListFiles(), "tool-session-five-1").deniedCode)
            assertEquals("TOOL_QUOTA_EXCEEDED",
                workspace.execute(WorkspaceTool.ListFiles()).deniedCode)
            assertEquals(3, audits.size)
            assertEquals(3, audits.map { it.receiptId }.distinct().size)
        }
    }

    @Test fun symlinkAndSubmoduleBoundariesFailClosed() {
        val (root, request) = fixture()
        val outside = temp.resolve("external")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("code.kt"), "OUTSIDE_TEST_SECRET")
        Files.delete(root.resolve("src/code.kt"))
        Files.delete(root.resolve("src"))
        Files.createSymbolicLink(root.resolve("src"), outside)
        open(request).use { workspace ->
            val result = workspace.execute(WorkspaceTool.Read("src/code.kt"))
            assertFalse(result.permitted)
            assertNull(result.data)
        }

        // A tracked Git symlink remains forbidden even if it resolves inside the repository.
        Files.delete(root.resolve("src"))
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/code.kt"), "fun hello() = 7\n")
        Files.createSymbolicLink(root.resolve("alias.md"), Path.of("task.md"))
        git(root, "add", "alias.md")
        git(root, "commit", "-m", "symlink")
        val next = fixtureForSameRoot(root, request)
        open(next).use { workspace ->
            assertEquals("UNTRACKED_OR_NONREGULAR_FILE",
                workspace.execute(WorkspaceTool.Read("alias.md")).deniedCode)
        }
        val head = git(root, "rev-parse", "HEAD")
        git(root, "update-index", "--add", "--cacheinfo", "160000,$head,vendor")
        git(root, "commit", "-m", "gitlink")
        Files.createDirectories(root.resolve("vendor"))
        Files.writeString(root.resolve("vendor/private.txt"), "must remain inaccessible")
        val submodule = fixtureForSameRoot(root, request)
        open(submodule).use { workspace ->
            assertEquals("SUBMODULE_BOUNDARY",
                workspace.execute(WorkspaceTool.Read("vendor/private.txt")).deniedCode)
        }
    }

    private fun fixtureForSameRoot(root: Path, request: SessionRequest): SessionRequest {
        val head = git(root, "rev-parse", "HEAD")
        val workspace = request.workspace
        val next = JsonObject(workspace + mapOf(
            "headSha" to JsonPrimitive(head), "baselineSha" to JsonPrimitive(head)))
        return SessionRequest.parse(JsonObject(request.raw + ("workspace" to next)).toString())
    }

    @Test fun logicalRootWithSymlinkedAncestorIsAcceptedLikeMacOsVar() {
        val (root, request) = fixture()
        val aliasParent = temp.resolve("linked-parent")
        Files.createSymbolicLink(aliasParent, temp)
        val logicalRoot = aliasParent.resolve(root.fileName)
        assertFalse(Files.isSymbolicLink(logicalRoot))
        assertEquals(root.toRealPath(), logicalRoot.toRealPath())
        val workspace = JsonObject(request.workspace + ("rootPath" to JsonPrimitive(logicalRoot.toString())))
        val ref = request.task["taskInputRef"]!!.jsonObject
        val aliasedRef = JsonObject(ref +
            ("path" to JsonPrimitive(logicalRoot.resolve("task.md").toString())))
        val task = JsonObject(request.task + ("taskInputRef" to aliasedRef))
        val logical = SessionRequest.parse(JsonObject(request.raw +
            mapOf("workspace" to workspace, "task" to task)).toString())
        open(logical).use { manager ->
            manager.verifyTask(logical.taskPath, hash("Implement the workspace guard"))
            assertEquals("task.md", manager.taskRelativePath(logical.taskPath))
            assertTrue(manager.execute(WorkspaceTool.Read("task.md")).permitted)
        }
        assertEquals("succeeded", SessionController(logical, FakeModel(), workspaceOpener = workspaceTestOpener, eventSinkFactory = EventSinkFactory { path -> JsonlEventSink.open(path) }).run()["status"]!!.jsonPrimitive.content)

        // A canonical output path inside the same workspace must never bypass an aliased root.
        val inside = root.resolve("forbidden-output")
        val previousOutputs = logical.outputs
        val escapedOutputs = buildJsonObject {
            put("rootDir", inside.toString())
            put("resultPath", inside.resolve("result.json").toString())
            put("eventsPath", inside.resolve("events.jsonl").toString())
            put("checkpointDir", inside.resolve("checkpoints").toString())
            put("diagnosticsPath", inside.resolve("diagnostics.json").toString())
        }
        assertNotEquals(previousOutputs, escapedOutputs)
        val unsafe = SessionRequest.parse(JsonObject(logical.raw +
            ("outputs" to escapedOutputs)).toString())
        assertEquals("UNSAFE_OUTPUT_PATH",
            assertFailsWith<Refused> { SessionController(unsafe, FakeModel(), workspaceOpener = workspaceTestOpener, eventSinkFactory = EventSinkFactory { path -> JsonlEventSink.open(path) }).run() }.code)
        assertFalse(Files.exists(inside), "Unsafe output directory must not be created")
    }

    @Test fun searchSkipsBinaryOversizedAndDeletedFilesAndTruncatesLargeTrees() {
        val (root, request) = fixture(quota = 5)
        Files.write(root.resolve("src/binary.bin"), byteArrayOf(0, 65, 0, 66))
        Files.writeString(root.resolve("src/large.txt"), "a".repeat(40_000))
        repeat(270) { i ->
            Files.writeString(root.resolve("src/z%03d.txt".format(i)), "quiet file $i")
        }
        git(root, "add", ".")
        git(root, "commit", "-m", "mixed tracked files")
        Files.delete(root.resolve("src/z000.txt"))
        val next = fixtureForSameRoot(root, request)
        open(next).use { manager ->
            val outcome = manager.execute(WorkspaceTool.Search("hello"))
            assertTrue(outcome.permitted, outcome.deniedCode ?: "")
            val found = assertIs<ToolData.Matches>(outcome.data)
            assertEquals(listOf("src/code.kt"), found.matches.map { it.path })
            assertTrue(found.skippedFiles >= 3, "Binary, oversized and deleted files must be skipped")
            assertTrue(found.truncated, "More than 256 files must yield bounded partial matches")
        }
    }

    @Test fun symlinkSwapRaceNeverExposesDataOutsideWorkspace() {
        val (root, request) = fixture(quota = 220)
        val outside = temp.resolve("escape")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("code.kt"), "EXTERNAL_SECRET_NEVER_VISIBLE")
        val normal = root.resolve("src")
        val parked = root.resolve("src-parked")
        val failure = AtomicReference<Throwable?>()
        open(request).use { workspace ->
            val attacker = thread {
                try {
                    repeat(40) {
                        Files.move(normal, parked)
                        Files.createSymbolicLink(normal, outside)
                        Thread.yield()
                        Files.delete(normal)
                        Files.move(parked, normal)
                    }
                } catch (e: Throwable) { failure.set(e) }
            }
            try {
                repeat(160) {
                    val result = workspace.execute(WorkspaceTool.Read("src/code.kt"))
                    if (result.permitted) {
                        assertEquals("fun hello() = 7\n", assertIs<ToolData.FileText>(result.data).text)
                    } else {
                        assertNull(result.data)
                    }
                }
            } finally { attacker.join() }
            failure.get()?.let { throw AssertionError("Concurrent swap fixture failed", it) }
        }
    }

    @Test fun lockIsExclusiveAndBaselineChangesAreRejected() {
        val (root, request) = fixture()
        val first = open(request)
        try {
            val error = assertFailsWith<Refused> { open(request) }
            assertEquals("WORKSPACE_LOCKED", error.code)
            Files.writeString(root.resolve("task.md"), "changed")
            git(root, "add", "task.md")
            git(root, "commit", "-m", "advance")
            assertEquals("WORKSPACE_BASELINE_MISMATCH",
                first.execute(WorkspaceTool.Read("src/code.kt")).deniedCode)
        } finally { first.close() }
        assertEquals("WORKSPACE_BASELINE_MISMATCH",
            assertFailsWith<Refused> { open(request) }.code)
    }

    @Test fun executableGitFilterConfigurationIsRefusedAtAdmission() {
        val (root, request) = fixture()
        git(root, "config", "filter.suspicious.clean", "sh -c 'exit 1'")
        assertEquals("UNSAFE_GIT_CONFIGURATION",
            assertFailsWith<Refused> { open(request) }.code)
    }

    @Test fun unsupportedModeAndLargeFilesFailClosed() {
        val (_, unsafe) = fixture(mode = "scoped-write")
        assertEquals("UNSUPPORTED_TOOL_POLICY",
            assertFailsWith<Refused> { open(unsafe) }.code)
        val (root, safe) = fixtureForFreshTest()
        val large = "x".repeat(40_000)
        Files.writeString(root.resolve("large.txt"), large)
        git(root, "add", "large.txt")
        git(root, "commit", "-m", "large")
        val next = fixtureForSameRoot(root, safe)
        open(next).use { workspace ->
            assertEquals("UNSAFE_FILE",
                workspace.execute(WorkspaceTool.Read("large.txt")).deniedCode)
        }
    }

    private fun fixtureForFreshTest(): Pair<Path, SessionRequest> {
        Files.walk(temp.resolve("workspace")).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
        return fixture()
    }
}
