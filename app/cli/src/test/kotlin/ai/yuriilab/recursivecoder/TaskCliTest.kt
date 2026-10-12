package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir

class TaskCliTest {
    @TempDir lateinit var temp: Path

    private fun service() = TaskService(TaskStore(temp.resolve("store")))
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(temp.resolve("workspace").toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }
    private fun workspace(): Triple<Path, String, String> {
        val root = temp.resolve("workspace")
        if (!Files.exists(root.resolve(".git"))) {
            Files.createDirectories(root)
            git("init", "-b", "dev")
            git("config", "user.email", "test@example.invalid")
            git("config", "user.name", "Test")
            Files.writeString(root.resolve("task.md"), "Observe the committed task")
            git("add", "task.md")
            git("commit", "-m", "task fixture")
        }
        val bytes = Files.readAllBytes(root.resolve("task.md"))
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return Triple(root, git("rev-parse", "HEAD"), hash)
    }

    private fun request(id: String): String {
        val (root, head, digest) = workspace()
        val out = temp.resolve("unused-" + id)
        return buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "request-" + id)
            put("sessionId", "original-" + id)
            put("traceId", "trace-" + id)
            put("invocation", buildJsonObject {
                put("source", "cli"); put("invocationId", "original-" + id)
                put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-" + id)
                put("rootPath", root.toString()); put("baselineSha", head)
                put("headSha", head); put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", id)
                put("taskInputRef", buildJsonObject {
                    put("path", root.resolve("task.md").toString()); put("sha256", digest)
                })
                put("parentAgentId", JsonNull)
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
                put("writablePaths", buildJsonArray { })
                put("policySha256", "a".repeat(64))
            })
            put("budgets", buildJsonObject {
                put("deadlineAt", Instant.now().plusSeconds(120).toString())
                put("maxWallTimeMs", 30000); put("maxTotalTokens", 10000)
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
        }.toString()
    }


    @Test fun taskCliShimRejectsMissingCommandWithoutCreatingStore() {
        val directory = temp.resolve("missing-command-store")
        assertEquals(2, TaskCli.execute(emptyList()))
        assertEquals(2, TaskCli.execute(listOf("--store", directory.toString())))
        assertFalse(Files.exists(directory))
        assertEquals(0, TaskCli.execute(listOf("--store", directory.toString(), "--help")))
        assertFalse(Files.exists(directory))
    }

    @Test fun taskCliUsesSameDurableService() {
        val requestFile = temp.resolve("request.json")
        Files.writeString(requestFile, request("cli"))
        val dir = temp.resolve("cli-store")
        val prefix = listOf("--store", dir.toString())
        assertEquals(0, TaskCli.execute(prefix + listOf("enqueue", requestFile.toString(), "--priority", "70")))
        assertEquals(TaskState.READY, TaskService(TaskStore(dir)).show("cli").state)
        assertEquals(0, TaskCli.execute(prefix + listOf("list")))
        assertEquals(0, TaskCli.execute(prefix + listOf("show", "cli")))
        assertEquals(0, TaskCli.execute(prefix + listOf("pause")))
        assertEquals(TaskState.PAUSED, TaskService(TaskStore(dir)).show("cli").state)
        assertEquals(0, TaskCli.execute(prefix + listOf("resume")))
        assertEquals(0, TaskCli.execute(prefix + listOf("cancel", "cli")))
        assertEquals(0, TaskCli.execute(prefix + listOf("retry", "cli")))
        assertEquals(0, TaskCli.execute(prefix + listOf("run")))
        assertEquals(TaskState.SUCCEEDED, TaskService(TaskStore(dir)).show("cli").state)
    }


    @Test fun invalidCliPriorityAndConcurrencyReturnUsageError() {
        val path = temp.resolve("request.json")
        Files.writeString(path, request("valid"))
        val directory = temp.resolve("cli-data")
        val prefix = listOf("--store", directory.toString())
        assertEquals(2, TaskCli.execute(prefix + listOf("enqueue", path.toString(), "--priority", "NaN")))
        assertEquals(2, TaskCli.execute(prefix + listOf("run", "--concurrency", "NaN")))
        assertTrue(TaskService(TaskStore(directory)).list().isEmpty())
    }

}
