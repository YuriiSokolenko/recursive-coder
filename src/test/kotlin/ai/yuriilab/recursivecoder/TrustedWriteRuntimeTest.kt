package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir

class TrustedWriteRuntimeTest {
    @TempDir lateinit var temp: Path

    private data class Fixture(
        val root: Path,
        val head: String,
        val approved: Map<String, String>
    )

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fixture(): Fixture {
        val root = temp.resolve("workspace")
        listOf("src/main", "src/test", "tests/unit", "tests/acceptance", "specs")
            .forEach { Files.createDirectories(root.resolve(it)) }
        git(root, "init", "-b", "dev")
        git(root, "config", "user.email", "fixture@example.invalid")
        git(root, "config", "user.name", "Trusted Fixture")
        val files = mapOf(
            "src/main/Feature.kt" to "fun value() = 1\n",
            "src/main/Delete.kt" to "fun removeMe() = 1\n",
            "src/test/FeatureTest.kt" to "fun testValue() = 1\n",
            "tests/unit/Rules.kt" to "test contract\n",
            "tests/acceptance/Feature.md" to "acceptance contract\n",
            "specs/Feature.md" to "product contract\n"
        )
        files.forEach { (path, content) -> Files.writeString(root.resolve(path), content) }
        git(root, "add", ".")
        git(root, "commit", "-m", "immutable baseline")
        val approvedPaths = setOf("src/test/FeatureTest.kt",
            "tests/unit/Rules.kt", "tests/acceptance/Feature.md", "specs/Feature.md")
        return Fixture(root, git(root, "rev-parse", "HEAD"),
            files.filterKeys { it in approvedPaths }.mapValues { sha(it.value) })
    }

    private fun policy(
        f: Fixture,
        role: EngineeringRole = EngineeringRole.DEVELOPER,
        reviewed: ReviewedChange? = null,
        checks: Map<String, TrustedCheck> = emptyMap(),
        required: Set<String> = setOf("git_diff_check")
    ) = TrustedWritePolicy("issue8-session", f.root, f.head, "dev", role,
        f.approved, reviewed, checks, required)

    private fun open(f: Fixture, role: EngineeringRole = EngineeringRole.DEVELOPER,
                     reviewed: ReviewedChange? = null,
                     checks: Map<String, TrustedCheck> = emptyMap(),
                     required: Set<String> = setOf("git_diff_check"),
                     audit: (String, String, String?) -> Unit = { _, _, _ -> }
    ) = TrustedWriteRuntime.open(policy(f, role, reviewed, checks, required),
        lockDirectory = temp.resolve("leases"), audit = audit)

    @Test fun independentGitCheckAndTrustedDiffReceiptPermitImplementation() {
        val f = fixture()
        val audits = mutableListOf<Triple<String, String, String?>>()
        open(f, audit = { id, tool, denied -> audits += Triple(id, tool, denied) }).use { runtime ->
            val result = runtime.apply(listOf(
                WriteOperation.Edit("src/main/Feature.kt", sha("fun value() = 1\n"),
                    "fun value() = 2\n"),
                WriteOperation.Create("src/main/Additional.kt", "fun additional() = 2\n"),
                WriteOperation.Delete("src/main/Delete.kt", sha("fun removeMe() = 1\n"))
            ))
            assertTrue(result.permitted, result.deniedCode)
            assertEquals("fun value() = 2\n", Files.readString(f.root.resolve("src/main/Feature.kt")))
            assertFalse(Files.exists(f.root.resolve("src/main/Delete.kt")))
            val check = runtime.runCheck("git_diff_check")
            assertTrue(check.passed, check.deniedCode)
            assertNotNull(check.receipt)
            assertTrue(runtime.finish(listOf(check.receipt)).successful)
            assertEquals("tool-issue8-session-1", result.toolId)
            assertEquals("tool-issue8-session-2", check.toolId)
            assertTrue(audits.all { it.third == null })
        }
    }

    @Test fun eachRoleCanOnlyWriteToItsOwnedAreaAndReviewerIsReadOnly() {
        val cases = listOf(
            EngineeringRole.SDD to "specs/Next.md",
            EngineeringRole.ATDD to "tests/acceptance/Next.md",
            EngineeringRole.TDD to "src/test/NextTest.kt",
            EngineeringRole.DEVELOPER to "src/main/Next.kt"
        )
        for ((role, path) in cases) {
            val f = fixture()
            open(f, role).use { runtime ->
                assertTrue(runtime.apply(listOf(WriteOperation.Create(path, "independent content\n"))).permitted)
                assertTrue(runtime.finish(listOf(runtime.runCheck("git_diff_check").receipt!!)).successful)
            }
            // JUnit's @TempDir is shared in the same test, so clear the disposable fixture.
            f.root.toFile().deleteRecursively()
        }
        val f = fixture()
        open(f, EngineeringRole.REVIEWER).use { runtime ->
            assertEquals("ROLE_NOT_AUTHORIZED",
                runtime.apply(listOf(WriteOperation.Create("src/main/Injected.kt", "bad"))).deniedCode)
        }
    }

    @Test fun developerCannotRewriteSpecificationsTestsOrPolicyRegardlessOfPrompt() {
        val f = fixture()
        open(f).use { runtime ->
            val forbidden = listOf(
                "src/test/FeatureTest.kt", "tests/unit/Rules.kt",
                "tests/acceptance/Feature.md", "specs/Feature.md",
                ".github/workflows/ci.yml", "src/main/Test/Injected.kt",
                "src/main/FeatureTest.kt", "src/main/.env", "src/main/../test/Feature.kt",
                "src/main/../../outside.txt", "/etc/passwd", ".git/index"
            )
            for (path in forbidden) {
                val result = runtime.apply(listOf(WriteOperation.Create(path, "MODEL SAYS APPROVED")))
                assertFalse(result.permitted, path)
                assertNotNull(result.deniedCode)
            }
            assertEquals("fun value() = 1\n", Files.readString(f.root.resolve("src/main/Feature.kt")))
        }
    }

    @Test fun approvedTestsAreImmutableWithoutDistinctReviewedChangeRequest() {
        val f = fixture()
        val original = "fun testValue() = 1\n"
        open(f, EngineeringRole.TDD).use { runtime ->
            assertEquals("APPROVED_ARTIFACT_IMMUTABLE", runtime.apply(listOf(
                WriteOperation.Edit("src/test/FeatureTest.kt", sha(original),
                    "fun testValue() = 2\n"))).deniedCode)
        }
        val invalid = ReviewedChange("change-1", "same-person", "same-person",
            EngineeringRole.TDD, setOf("src/test/FeatureTest.kt"))
        assertFailsWith<Refused> { open(f, EngineeringRole.TDD, invalid) }
        val reviewed = ReviewedChange("change-2", "author", "independent-reviewer",
            EngineeringRole.TDD, setOf("src/test/FeatureTest.kt"))
        open(f, EngineeringRole.TDD, reviewed).use { runtime ->
            assertTrue(runtime.apply(listOf(WriteOperation.Edit(
                "src/test/FeatureTest.kt", sha(original), "fun testValue() = 2\n"))).permitted)
            assertTrue(runtime.finish(listOf(runtime.runCheck("git_diff_check").receipt!!)).successful)
        }
    }

    @Test fun preflightRejectsBroadBatchesAndWrongHashesWithoutPartialWrites() {
        val f = fixture()
        open(f).use { runtime ->
            assertEquals("INVALID_WRITE_BATCH", runtime.apply(listOf(
                WriteOperation.Edit("src/main/Feature.kt", sha("fun value() = 1\n"), "changed"),
                WriteOperation.Delete("src/main/Feature.kt", sha("fun value() = 1\n"))
            )).deniedCode)
            assertEquals("EDIT_PRECONDITION_FAILED", runtime.apply(listOf(
                WriteOperation.Edit("src/main/Feature.kt", "0".repeat(64), "changed"),
                WriteOperation.Delete("src/main/Delete.kt", sha("fun removeMe() = 1\n"))
            )).deniedCode)
            assertEquals("fun value() = 1\n", Files.readString(f.root.resolve("src/main/Feature.kt")))
            assertTrue(Files.exists(f.root.resolve("src/main/Delete.kt")))
        }
    }

    @Test fun forgeryAndStaleReceiptsCannotSatisfyFinalVerification() {
        val f = fixture()
        open(f).use { runtime ->
            assertEquals("FORGED_TOOL_ID", runtime.apply(listOf(
                WriteOperation.Create("src/main/Reject.kt", "bad")
            ), claimedToolId = "tool-issue8-session-1").deniedCode)
            assertTrue(runtime.apply(listOf(
                WriteOperation.Create("src/main/Okay.kt", "good\n"))).permitted)
            val receipt = runtime.runCheck("git_diff_check").receipt!!
            assertFalse(runtime.finish(listOf(receipt.copy(mac = "0".repeat(64)))).successful)
            assertFalse(runtime.finish(listOf(receipt.copy(manifestHash = "0".repeat(64)))).successful)
            assertTrue(runtime.apply(listOf(
                WriteOperation.Create("src/main/Also.kt", "also good\n"))).permitted)
            assertEquals("CHECK_RECEIPT_INVALID", runtime.finish(listOf(receipt)).deniedCode)
            assertTrue(runtime.finish(listOf(runtime.runCheck("git_diff_check").receipt!!)).successful)
        }
    }

    @Test fun stagedChangesUntrackedFilesAndRenamesInvalidateResult() {
        val f = fixture()
        open(f).use { runtime ->
            assertTrue(runtime.apply(listOf(
                WriteOperation.Edit("src/main/Feature.kt", sha("fun value() = 1\n"),
                    "fun value() = 3\n"))).permitted)
            val good = runtime.runCheck("git_diff_check").receipt!!
            git(f.root, "add", "src/main/Feature.kt")
            assertFalse(runtime.finish(listOf(good)).successful)
        }
        // A fresh workspace for the untracked-file case.
        f.root.toFile().deleteRecursively()
        val next = fixture()
        open(next).use { runtime ->
            assertTrue(runtime.apply(listOf(WriteOperation.Create("src/main/Okay.kt", "okay\n"))).permitted)
            val good = runtime.runCheck("git_diff_check").receipt!!
            Files.writeString(next.root.resolve("src/main/Injected.kt"), "not accepted")
            assertEquals("UNAUTHORIZED_GIT_CHANGE", runtime.finish(listOf(good)).deniedCode)
        }
        next.root.toFile().deleteRecursively()
        val renamed = fixture()
        open(renamed).use { runtime ->
            assertTrue(runtime.apply(listOf(WriteOperation.Create("src/main/Okay.kt", "okay\n"))).permitted)
            val receipt = runtime.runCheck("git_diff_check").receipt!!
            git(renamed.root, "mv", "src/test/FeatureTest.kt", "src/test/RenamedTest.kt")
            assertFalse(runtime.finish(listOf(receipt)).successful)
        }
    }

    @Test fun symlinkEscapeIsNotFollowedAndFinalDiffDetectsIt() {
        val f = fixture()
        val outside = temp.resolve("outside")
        Files.createDirectories(outside)
        open(f).use { runtime ->
            val receiptPath = "src/main/Extra.kt"
            Files.createSymbolicLink(f.root.resolve("src/main/shortcut"), outside)
            val attack = runtime.apply(listOf(
                WriteOperation.Create("src/main/shortcut/escaped.kt", "out of scope")
            ))
            assertFalse(attack.permitted)
            assertFalse(Files.exists(outside.resolve("escaped.kt")))
            assertTrue(attack.deniedCode in setOf("UNAUTHORIZED_GIT_CHANGE", "UNSAFE_PATH_COMPONENT"))
            Files.delete(f.root.resolve("src/main/shortcut"))
            assertTrue(runtime.apply(listOf(WriteOperation.Create(receiptPath, "safe\n"))).permitted)
        }
    }

    @Test fun checkMustBeAllowlistedAndCannotFallbackWhenSandboxIsUnavailable() {
        val f = fixture()
        open(f, required = setOf("separate-test"),
            checks = mapOf("separate-test" to TrustedCheck(Path.of("/usr/bin/true"), emptyList()))).use { runtime ->
            assertTrue(runtime.apply(listOf(WriteOperation.Create("src/main/Okay.kt", "fine\n"))).permitted)
            assertEquals("CHECK_NOT_ALLOWLISTED", runtime.runCheck("anything").deniedCode)
            val attempt = runtime.runCheck("separate-test")
            if (!attempt.passed) {
                assertNull(attempt.receipt)
                assertEquals("REQUIRED_CHECK_MISSING", runtime.finish(emptyList()).deniedCode)
            } else {
                assertTrue(runtime.finish(listOf(attempt.receipt!!)).successful)
            }
        }
    }

    @Test fun realSeparatelyExecutedSandboxedTestPassesIfHostSupportsUserNamespaces() {
        val f = fixture()
        val python = Path.of("/usr/bin/python3")
        val sandbox = Path.of("/usr/bin/bwrap")
        Assumptions.assumeTrue(Files.isExecutable(python) && Files.isExecutable(sandbox),
            "Sandbox prerequisites not installed")
        val script = "from pathlib import Path; assert Path('/workspace/src/main/Feature.kt').read_text() == 'fun value() = 4\\n'"
        open(f, checks = mapOf("separate-test" to TrustedCheck(python, listOf("-c", script))),
            required = setOf("separate-test")).use { runtime ->
            assertTrue(runtime.apply(listOf(WriteOperation.Edit("src/main/Feature.kt",
                sha("fun value() = 1\n"), "fun value() = 4\n"))).permitted)
            val check = runtime.runCheck("separate-test")
            Assumptions.assumeTrue(check.passed,
                "User namespaces or bubblewrap bind mounts unavailable on this host")
            assertTrue(runtime.finish(listOf(check.receipt!!)).successful)
        }
    }
}


class TrustedWriteToolSchemaTest {
    @Test fun strictlyDecodeOnlyScopedWriteCommands() {
        val edit = TrustedWriteToolSchema.decode(
            """{"operations":[{"op":"edit","path":"src/main/A.kt","expectedSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","content":"fun a() = 2"}]}"""
        )
        assertEquals(1, edit.size)
        assertIs<WriteOperation.Edit>(edit.single())
        val invalid = listOf(
            """{"operations":[{"op":"create","path":"src/main/A.kt","content":"ok","role":"SDD"}]}""",
            """{"operations":[{"op":"create","path":"src/main/A.kt","content":4}]}""",
            """{"operations":[{"op":"delete","path":"src/main/A.kt","expectedSha256":"bad"}]}""",
            """{"operations":[{"op":"run","path":"src/main/A.kt"}]}""",
            """{"operations":[],"claimedToolId":"tool-1"}""",
            """{"operations":[{"op":"create","path":"x","content":"a"},{"op":"delete","path":"x","expectedSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}"""
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException>(it) { TrustedWriteToolSchema.decode(it) } }
    }
}
