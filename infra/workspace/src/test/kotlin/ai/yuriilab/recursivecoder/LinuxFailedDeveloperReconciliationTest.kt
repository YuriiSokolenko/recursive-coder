package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Actual Git index and nofollow Linux operations, with no live model or network. */
@EnabledOnOs(OS.LINUX)
@Tag("integration")
class LinuxFailedDeveloperReconciliationTest {
    @TempDir lateinit var temp: Path

    private val oldSource = "fun isolated() = 1\n"
    private val newSource = "fun isolated() = 2\n"
    private val created = "source-not-accepted\n"

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(value: String): String = sha(value.toByteArray(Charsets.UTF_8))

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("/usr/bin/git", "-C", root.toString()) +
            args).redirectErrorStream(true).start()
        val stdout = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), stdout)
        return stdout.trim()
    }

    private data class Fixture(
        val root: Path,
        val baseline: String,
        val reconciler: LinuxStageGitBaselineReconciler,
        val request: StageGitFailedWriteRequest
    )

    private fun checkout(name: String, partial: Boolean = false): Fixture {
        val root = temp.resolve(name)
        Files.createDirectories(root)
        git(root, "init", "-b", "dev")
        git(root, "config", "user.name", "Recovery Test")
        git(root, "config", "user.email", "recovery@example.invalid")
        for (path in listOf("specs", "tests/acceptance", "tests/unit", "src/main"))
            Files.createDirectories(root.resolve(path))
        val frozen = mapOf(
            "specs/Frozen.md" to "Frozen requirements\n",
            "tests/acceptance/Frozen.feature" to "Scenario: frozen\n",
            "tests/unit/FrozenTest.kt" to "fun frozen() = Unit\n"
        )
        frozen.forEach { (path, text) -> Files.writeString(root.resolve(path), text) }
        Files.writeString(root.resolve("src/main/Feature.kt"), oldSource)
        git(root, "add", ".")
        git(root, "commit", "-m", "trusted baseline")
        val baseline = git(root, "rev-parse", "HEAD")
        val request = StageGitFailedWriteRequest(baseline, "dev",
            StageRunId("failed-dev-$name"), "generation-$name",
            listOf(
                StageGitUnacceptedFile("src/main/Feature.kt", oldSource.toByteArray(), sha(newSource)),
                StageGitUnacceptedFile("src/main/Implementation.txt", null, sha(created))
            ), frozen.mapValues { sha(it.value) })
        Files.writeString(root.resolve("src/main/Feature.kt"), newSource)
        if (!partial) Files.writeString(root.resolve("src/main/Implementation.txt"), created)
        return Fixture(root, baseline, LinuxStageGitBaselineReconciler(
            root, temp.resolve("locks-$name"), Path.of("/usr/bin/git")), request)
    }

    private fun assertRestored(f: Fixture, expected: Int) {
        val result = assertIs<StageGitFailedWriteResult.Recovered>(
            f.reconciler.recoverFailedDeveloper(f.request))
        assertEquals(expected, result.restoredPaths)
        assertTrue(Regex("[0-9a-f]{64}").matches(result.auditSha256))
        assertEquals(oldSource, Files.readString(f.root.resolve("src/main/Feature.kt")))
        assertFalse(Files.exists(f.root.resolve("src/main/Implementation.txt")))
        assertEquals(StageGitBaselineResult.Clean,
            f.reconciler.inspectCleanBaseline(f.baseline, "dev"))
        // Idempotent verification succeeds but never repeats a physical mutation.
        assertEquals(0, assertIs<StageGitFailedWriteResult.Recovered>(
            f.reconciler.recoverFailedDeveloper(f.request)).restoredPaths)
    }

    @Test fun checkFailureCancellationTimeoutAndHostRejectionRestoreOnlyKnownChanges() {
        for (reason in listOf("check-failed", "cancelled", "timeout", "host-rejected")) {
            val f = checkout(reason)
            assertRestored(f, 2)
        }
    }

    @Test fun partiallyAppliedTrustedWriteIsRecoveredWithoutDeletingOtherFiles() {
        assertRestored(checkout("partial", partial = true), 1)
    }

    @Test fun foreignContentOnAttemptedPathNeverGetsClobbered() {
        val f = checkout("foreign")
        Files.writeString(f.root.resolve("src/main/Feature.kt"), "external-work\n")
        assertEquals("FOREIGN_WORKTREE_EDIT", assertIs<StageGitFailedWriteResult.NeedsHuman>(
            f.reconciler.recoverFailedDeveloper(f.request)).code)
        assertEquals("external-work\n", Files.readString(f.root.resolve("src/main/Feature.kt")))
        assertTrue(Files.exists(f.root.resolve("src/main/Implementation.txt")))
    }

    @Test fun unexpectedUntrackedFileIsPreservedAndStopsRecovery() {
        val f = checkout("untracked")
        val foreign = f.root.resolve("src/main/External.kt")
        Files.writeString(foreign, "external-work\n")
        assertEquals("FOREIGN_GIT_OR_INDEX_EDIT", assertIs<StageGitFailedWriteResult.NeedsHuman>(
            f.reconciler.recoverFailedDeveloper(f.request)).code)
        assertEquals("external-work\n", Files.readString(foreign))
        assertEquals(newSource, Files.readString(f.root.resolve("src/main/Feature.kt")))
    }

    @Test fun stagedIndexMutationStopsBeforeAnyRollback() {
        val f = checkout("staged")
        git(f.root, "add", "src/main/Feature.kt")
        assertEquals("UNEXPECTED_GIT_CHANGE", assertIs<StageGitFailedWriteResult.NeedsHuman>(
            f.reconciler.recoverFailedDeveloper(f.request)).code)
        assertEquals(newSource, Files.readString(f.root.resolve("src/main/Feature.kt")))
    }

    @Test fun frozenInputDriftStopsBeforeAnyRollback() {
        val f = checkout("frozen")
        Files.writeString(f.root.resolve("specs/Frozen.md"), "external new requirement\n")
        assertEquals("FROZEN_ARTIFACT_CHANGED", assertIs<StageGitFailedWriteResult.NeedsHuman>(
            f.reconciler.recoverFailedDeveloper(f.request)).code)
        assertEquals(newSource, Files.readString(f.root.resolve("src/main/Feature.kt")))
    }

    @Test fun symlinkedCreateTargetIsNotFollowedOrDeleted() {
        val f = checkout("symlink")
        val outside = temp.resolve("outside-data.txt")
        Files.writeString(outside, "do-not-touch\n")
        Files.delete(f.root.resolve("src/main/Implementation.txt"))
        Files.createSymbolicLink(f.root.resolve("src/main/Implementation.txt"), outside)
        assertEquals("UNSAFE_STAGE_FILE", assertIs<StageGitFailedWriteResult.NeedsHuman>(
            f.reconciler.recoverFailedDeveloper(f.request)).code)
        assertEquals("do-not-touch\n", Files.readString(outside))
        assertTrue(Files.isSymbolicLink(f.root.resolve("src/main/Implementation.txt")))
    }

    @Test fun traversalAndSubmoduleGitlinkAreRejected() {
        val f = checkout("traversal")
        val invalid = f.request.copy(files = listOf(
            StageGitUnacceptedFile("../outside.kt", null, sha("outside"))))
        assertEquals("INVALID_FAILED_WRITE_EVIDENCE",
            assertIs<StageGitFailedWriteResult.NeedsHuman>(
                f.reconciler.recoverFailedDeveloper(invalid)).code)
        assertEquals(newSource, Files.readString(f.root.resolve("src/main/Feature.kt")))
        git(f.root, "update-index", "--add", "--cacheinfo",
            "160000," + f.baseline + ",src/main/Submodule")
        assertEquals("UNSAFE_GIT_INDEX", assertIs<StageGitFailedWriteResult.NeedsHuman>(
            f.reconciler.recoverFailedDeveloper(f.request)).code)
        assertEquals(newSource, Files.readString(f.root.resolve("src/main/Feature.kt")))
    }

    @Test fun changedHeadNeverGrantsRollbackOfFailedGeneration() {
        val f = checkout("cas-uncertain")
        git(f.root, "commit", "--allow-empty", "-m", "concurrent authority update")
        assertEquals("STAGE_BASELINE_CHANGED", assertIs<StageGitFailedWriteResult.NeedsHuman>(
            f.reconciler.recoverFailedDeveloper(f.request)).code)
        assertEquals(newSource, Files.readString(f.root.resolve("src/main/Feature.kt")))
    }
}
