package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Tests for infrastructure-owned validation and workspace access, without session/model modules. */
@Tag("integration")
class WorkspaceBoundaryUnitTest {
    @TempDir lateinit var temp: Path

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun git(root: Path, vararg args: String): String {
        // Do not inherit signing, hooks, templates or Git settings from the developer machine.
        val home = temp.resolve("git-home").also { Files.createDirectories(it) }
        val hooks = temp.resolve("empty-git-hooks").also { Files.createDirectories(it) }
        val templates = temp.resolve("empty-git-templates").also { Files.createDirectories(it) }
        val processBuilder = ProcessBuilder(
            listOf("git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=$hooks") + args
        ).directory(root.toFile()).redirectErrorStream(true)
        processBuilder.environment().apply {
            keys.removeAll { it.startsWith("GIT_") }
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", home.resolve("config").toString())
            put("GIT_TEMPLATE_DIR", templates.toString())
            put("HOME", home.toString())
            put("XDG_CONFIG_HOME", home.toString())
        }
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")}: $output")
        return output.trim()
    }

    private fun fixture(): SessionRequest {
        val root = temp.resolve("workspace")
        Files.createDirectories(root)
        Files.writeString(root.resolve("task.md"), "Read an isolated workspace")
        Files.writeString(root.resolve(".env"), "SECRET_NOT_FOR_TOOLS")
        // symbolic-ref works on Git versions older than 'git init -b' (2.28).
        git(root, "init")
        git(root, "symbolic-ref", "HEAD", "refs/heads/dev")
        git(root, "config", "user.email", "test@example.invalid")
        git(root, "config", "user.name", "Workspace unit test")
        git(root, "add", ".")
        git(root, "commit", "-m", "workspace fixture")
        val head = git(root, "rev-parse", "HEAD")
        val out = temp.resolve("out")
        val raw = buildJsonObject {
            put("kind", "session-request"); put("version", "1.0")
            put("requestId", "req-workspace-unit")
            put("sessionId", "session-workspace-unit")
            put("traceId", "trace-workspace-unit")
            put("invocation", buildJsonObject {
                put("source", "cli")
                put("invocationId", "invoke-workspace-unit")
                put("attempt", 1)
            })
            put("workspace", buildJsonObject {
                put("workspaceId", "workspace-unit")
                put("rootPath", root.toString())
                put("baselineSha", head); put("headSha", head)
                put("branch", "dev")
            })
            put("task", buildJsonObject {
                put("stage", "sample"); put("role", "reader"); put("taskId", "task-unit")
                put("taskInputRef", buildJsonObject {
                    put("path", root.resolve("task.md").toString())
                    put("sha256", sha256("Read an isolated workspace"))
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
                put("allowedTools", buildJsonArray { add("read"); add("list") })
                put("deniedTools", buildJsonArray { add("safe_edit") })
                put("protectedPaths", buildJsonArray { add("private/**") })
                put("writablePaths", buildJsonArray {})
                put("policySha256", "b".repeat(64))
            })
            put("budgets", buildJsonObject {
                put("deadlineAt", Instant.now().plusSeconds(30).toString())
                put("maxWallTimeMs", 12000); put("maxTotalTokens", 10000)
                put("maxToolCalls", 4); put("maxChildAgents", 0)
                put("maxDepth", 0); put("maxModelRequests", 2)
            })
            put("outputs", buildJsonObject {
                put("rootDir", out.toString())
                put("resultPath", out.resolve("result.json").toString())
                put("eventsPath", out.resolve("events.jsonl").toString())
                put("checkpointDir", out.resolve("checkpoints").toString())
                put("diagnosticsPath", out.resolve("diagnostics.json").toString())
            })
            put("resume", buildJsonObject {
                put("mode", "fresh"); put("fenceGeneration", 1)
                put("checkpoint", JsonNull)
            })
            put("requiredCapabilities", buildJsonArray {
                add("session-events-v1"); add("fake-model-v1"); add("read-only-tools-v1")
            })
        }
        return SessionRequest.parse(raw.toString())
    }

    private fun withPolicy(request: SessionRequest, policy: JsonObject): SessionRequest =
        SessionRequest.parse(JsonObject(request.raw + ("toolPolicy" to policy)).toString())

    private fun writeOperations(vararg ops: JsonObject): String =
        buildJsonObject {
            put("operations", buildJsonArray { ops.forEach { add(it) } })
        }.toString()

    private fun create(path: String, text: String): JsonObject = buildJsonObject {
        put("op", "create"); put("path", path); put("content", text)
    }

    @Test fun writeToolDecoderPreservesTypedOperationsAndUtf8Payloads() {
        val expected = "Привет · hello"
        val payload = writeOperations(
            create("docs/readme.md", expected),
            buildJsonObject {
                put("op", "edit"); put("path", "src/Main.kt")
                put("expectedSha256", "a".repeat(64)); put("content", "updated")
            },
            buildJsonObject {
                put("op", "delete"); put("path", "legacy.txt")
                put("expectedSha256", "b".repeat(64))
            }
        )

        val operations = TrustedWriteToolSchema.decode(payload)

        assertEquals(3, operations.size)
        assertEquals(WriteOperation.Create("docs/readme.md", expected), operations[0])
        assertEquals(WriteOperation.Edit("src/Main.kt", "a".repeat(64), "updated"), operations[1])
        assertEquals(WriteOperation.Delete("legacy.txt", "b".repeat(64)), operations[2])
    }

    @Test fun writeToolDecoderRejectsUnknownFieldsTypesAndDuplicates() {
        val invalid = listOf(
            "missing operations" to "{}",
            "empty batch" to writeOperations(),
            "duplicate paths" to writeOperations(create("same.md", "a"), create("same.md", "b")),
            "non-string content" to writeOperations(buildJsonObject {
                put("op", "create"); put("path", "task.md"); put("content", 123)
            }),
            "unexpected field" to writeOperations(buildJsonObject {
                put("op", "create"); put("path", "task.md")
                put("content", "ok"); put("unexpected", true)
            }),
            "uppercase digest" to writeOperations(buildJsonObject {
                put("op", "edit"); put("path", "task.md")
                put("content", "ok"); put("expectedSha256", "A".repeat(64))
            }),
            "missing digest" to writeOperations(buildJsonObject {
                put("op", "delete"); put("path", "task.md")
            }),
            "nine operations" to writeOperations(*Array(9) { index ->
                create("file" + index + ".md", "ok")
            })
        )

        invalid.forEach { (case, payload) ->
            val error = assertFailsWith<IllegalArgumentException>("Case: $case") {
                TrustedWriteToolSchema.decode(payload)
            }
            assertEquals("INVALID_WRITE_TOOL_SCHEMA", error.message, "Case: $case")
        }
    }

    @Test fun writeToolDecoderAcceptsMaximumBatchOfEightOperations() {
        val batch = Array(8) { index -> create("file$index.md", "content-$index") }

        val decoded = TrustedWriteToolSchema.decode(writeOperations(*batch))

        assertEquals((0 until 8).map { index ->
            WriteOperation.Create("file$index.md", "content-$index")
        }, decoded)
    }

    @Test fun writeToolDecoderLimitsUtf8ByteCountAndRejectsNul() {
        val atLimit = "é".repeat(32 * 1024)
        assertEquals(atLimit,
            assertIs<WriteOperation.Create>(
                TrustedWriteToolSchema.decode(writeOperations(create("safe.md", atLimit))).single()
            ).content)

        val oversized = "é".repeat(32 * 1024 + 1)
        assertFailsWith<IllegalArgumentException> {
            TrustedWriteToolSchema.decode(writeOperations(create("too-big.md", oversized)))
        }
        assertFailsWith<IllegalArgumentException> {
            TrustedWriteToolSchema.decode(writeOperations(create("nul.md", "before\u0000after")))
        }
    }

    @Test fun canonicalPolicyHashIsIndependentOfJsonFieldInsertionOrder() {
        val request = fixture()
        val reversed = JsonObject(request.policy.entries.reversed().associate { it.key to it.value })
        val reordered = withPolicy(request, reversed)
        val lockDir = temp.resolve("leases")

        val firstHash = WorkspaceManager.open(request, lockDirectory = lockDir).use {
            it.policyHash
        }
        val secondHash = WorkspaceManager.open(reordered, lockDirectory = lockDir).use {
            it.policyHash
        }

        assertEquals(firstHash, secondHash)
        assertTrue(firstHash.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun committedConfidentialFileIsNotDiscoverableAndAuditsRemainMetadataOnly() {
        val request = fixture()
        val audits = mutableListOf<ToolAudit>()
        val starts = mutableListOf<String>()
        WorkspaceManager.open(
            request, onAudit = audits::add,
            onStart = { id, _, _ -> starts += id },
            lockDirectory = temp.resolve("leases")
        ).use { workspace ->
            val listing = workspace.execute(WorkspaceTool.ListFiles())
            assertEquals(listOf("task.md"), assertIs<ToolData.Paths>(listing.data).paths)

            val denial = workspace.execute(WorkspaceTool.Read(".env"))
            assertEquals("PROTECTED_PATH", denial.deniedCode)
            assertNull(denial.data)
        }

        assertEquals(2, starts.size)
        assertEquals(starts, audits.map { it.receiptId })
        assertEquals(listOf(true, false), audits.map { it.permitted })
        assertNull(audits.last().sha256)
        assertEquals(0, audits.last().outputBytes)
        assertFalse(audits.joinToString().contains("SECRET_NOT_FOR_TOOLS"))
    }

    @Test fun invalidProtectedPatternIsRejectedBeforeCreatingWorkspaceLease() {
        val request = fixture()
        val broken = JsonObject(request.policy + (
            "protectedPaths" to buildJsonArray { add("bad\u0000glob") }
        ))
        val invalidRequest = withPolicy(request, broken)
        val lockDir = temp.resolve("leases")

        val error = assertFailsWith<Refused> {
            WorkspaceManager.open(invalidRequest, lockDirectory = lockDir)
        }

        assertEquals("INVALID_TOOL_POLICY", error.code)
        assertFalse(Files.exists(lockDir))
    }
}
