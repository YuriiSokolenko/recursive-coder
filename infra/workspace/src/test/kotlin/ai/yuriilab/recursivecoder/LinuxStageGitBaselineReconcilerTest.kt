package ai.yuriilab.recursivecoder

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Live Git plumbing; does not authenticate upstream write/check receipts. */
@EnabledOnOs(OS.LINUX)
@Tag("integration")
class LinuxStageGitBaselineReconcilerTest {
    @TempDir lateinit var temporary: Path

    private data class Repo(val root: Path, val locks: Path, val head: String)

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun write(root: Path, path: String, content: String): String {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        val bytes = content.toByteArray()
        Files.write(file, bytes)
        return sha(bytes)
    }

    private fun createRepo(): Repo {
        val root = temporary.resolve("repo")
        Files.createDirectories(root)
        git(root, "init", "-b", "dev")
        git(root, "config", "user.email", "fixture@example.invalid")
        git(root, "config", "user.name", "Reconciliation Fixture")
        write(root, "README.md", "baseline\n")
        git(root, "add", "README.md")
        git(root, "commit", "-m", "baseline")
        return Repo(root, temporary.resolve("locks"), git(root, "rev-parse", "HEAD"))
    }

    private fun reconcile(repo: Repo, head: String, role: EngineeringRole,
        files: Map<String, String>, frozen: Map<String, String> = emptyMap()) =
        LinuxStageGitBaselineReconciler(repo.root, repo.locks).reconcile(
            StageGitReconciliationRequest(head, "dev", role,
                files.map { (path, sha) -> StageGitAcceptedFile(path, sha) }, frozen))

    @Test fun acceptedStagesCreateCleanIncrementalBaselines() {
        val repo = createRepo()
        val specHash = write(repo.root, "specs/Feature.md", "requirements\n")
        val first = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf("specs/Feature.md" to specHash)))
        assertEquals(repo.head, first.oldHead)
        assertEquals(first.newHead, git(repo.root, "rev-parse", "HEAD"))
        assertEquals("", git(repo.root, "status", "--porcelain"))

        val testHash = write(repo.root, "tests/acceptance/Feature.feature", "Given approved\n")
        val second = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, first.newHead, EngineeringRole.ATDD,
                mapOf("tests/acceptance/Feature.feature" to testHash),
                mapOf("specs/Feature.md" to specHash)))
        assertEquals(first.newHead, second.oldHead)
        assertEquals(second.newHead, git(repo.root, "rev-parse", "HEAD"))
        assertEquals("", git(repo.root, "status", "--porcelain"))
        assertEquals("requirements\n", Files.readString(repo.root.resolve("specs/Feature.md")))

        val sourceHash = write(repo.root, "src/main/Feature.kt", "fun feature() = 2\n")
        val third = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, second.newHead, EngineeringRole.DEVELOPER,
                mapOf("src/main/Feature.kt" to sourceHash),
                mapOf("specs/Feature.md" to specHash,
                    "tests/acceptance/Feature.feature" to testHash)))
        assertEquals(third.newHead, git(repo.root, "rev-parse", "HEAD"))
        assertEquals("", git(repo.root, "status", "--porcelain"))
        assertEquals(4, git(repo.root, "ls-files").lines().size)
    }

    @Test fun unchangedHeadOnFrozenTamperingAndUnexpectedFiles() {
        val repo = createRepo()
        val original = write(repo.root, "specs/Feature.md", "original\n")
        val committed = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf("specs/Feature.md" to original)))
        write(repo.root, "specs/Feature.md", "tampered\n")
        val acceptance = write(repo.root, "tests/acceptance/A.feature", "accepted\n")
        val tampered = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, committed.newHead, EngineeringRole.ATDD,
                mapOf("tests/acceptance/A.feature" to acceptance),
                mapOf("specs/Feature.md" to original)))
        assertEquals("UNEXPECTED_GIT_CHANGE", tampered.code)
        assertEquals(committed.newHead, git(repo.root, "rev-parse", "HEAD"))

        // Even an unrelated untracked file must block the next commit.
        write(repo.root, "specs/Feature.md", "original\n")
        write(repo.root, "unapproved.txt", "not in the allowlist\n")
        val result = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, committed.newHead, EngineeringRole.ATDD,
                mapOf("tests/acceptance/A.feature" to acceptance),
                mapOf("specs/Feature.md" to original)))
        assertEquals("UNEXPECTED_GIT_CHANGE", result.code)
        assertEquals(committed.newHead, git(repo.root, "rev-parse", "HEAD"))
    }

    @Test fun refusesRoleEscapeDigestMismatchAndStaleBaseline() {
        val repo = createRepo()
        val hash = write(repo.root, "tests/unit/ProtectedTest.kt", "test\n")
        val denied = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.DEVELOPER,
                mapOf("tests/unit/ProtectedTest.kt" to hash)))
        assertEquals("INVALID_STAGE_CHANGESET", denied.code)
        assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))

        val expected = write(repo.root, "src/main/Feature.kt", "source\n")
        Files.delete(repo.root.resolve("tests/unit/ProtectedTest.kt"))
        val mismatch = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.DEVELOPER,
                mapOf("src/main/Feature.kt" to "0".repeat(64))))
        assertEquals("STAGE_ARTIFACT_CHANGED", mismatch.code)
        assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))

        val stale = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, "f".repeat(40), EngineeringRole.DEVELOPER,
                mapOf("src/main/Feature.kt" to expected)))
        assertEquals("STAGE_BASELINE_CHANGED", stale.code)
        assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))
    }

    @Test fun usesSameExclusiveWorkspaceLockAsTrustedWriter() {
        val repo = createRepo()
        val expected = write(repo.root, "specs/Feature.md", "locked\n")
        Files.createDirectories(repo.locks)
        val hash = sha(repo.root.toRealPath().toString().toByteArray())
        FileChannel.open(repo.locks.resolve("$hash.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val denied = assertIs<StageGitReconciliationResult.Denied>(
                    reconcile(repo, repo.head, EngineeringRole.SDD,
                        mapOf("specs/Feature.md" to expected)))
                assertEquals("WORKSPACE_LOCKED", denied.code)
                assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))
            }
        }
    }

    @Test fun rejectsSymlinkArtifactAndUnsafeGitConfiguration() {
        val repo = createRepo()
        val outside = temporary.resolve("outside.txt")
        Files.writeString(outside, "outside\n")
        Files.createDirectories(repo.root.resolve("specs"))
        Files.createSymbolicLink(repo.root.resolve("specs/Feature.md"), outside)
        val denied = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf("specs/Feature.md" to sha(Files.readAllBytes(outside)))))
        assertEquals("UNSAFE_STAGE_FILE", denied.code)
        assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))
        assertTrue(Files.isSymbolicLink(repo.root.resolve("specs/Feature.md")))
        Files.delete(repo.root.resolve("specs/Feature.md"))
        val accepted = write(repo.root, "specs/Feature.md", "safe\n")
        git(repo.root, "config", "core.hooksPath", "/tmp/hooks")
        val blocked = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf("specs/Feature.md" to accepted)))
        assertEquals("UNSAFE_GIT_CONFIGURATION", blocked.code)
    }

    @Test fun frozenInputsMayUse0600Or0664WithoutChangingGitBaseline() {
        val repo = createRepo()
        val specPath = "specs/Feature.md"
        val specHash = write(repo.root, specPath, "requirements\n")
        val sdd = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf(specPath to specHash)))

        // Changing read/write permission bits does not alter Git's executable
        // mode: a pinned SDD input remains byte-identical at umask 077.
        Files.setPosixFilePermissions(repo.root.resolve(specPath),
            PosixFilePermissions.fromString("rw-------"))
        assertEquals("", git(repo.root, "status", "--porcelain"))
        val acceptancePath = "tests/acceptance/Feature.feature"
        val acceptanceHash = write(repo.root, acceptancePath, "Given accepted\n")
        val atdd = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, sdd.newHead, EngineeringRole.ATDD,
                mapOf(acceptancePath to acceptanceHash),
                mapOf(specPath to specHash)))

        // Common umask 002 and 077 files coexist as immutable inputs.
        Files.setPosixFilePermissions(repo.root.resolve(specPath),
            PosixFilePermissions.fromString("rw-rw-r--"))
        Files.setPosixFilePermissions(repo.root.resolve(acceptancePath),
            PosixFilePermissions.fromString("rw-------"))
        assertEquals("", git(repo.root, "status", "--porcelain"))
        val unitPath = "tests/unit/FeatureTest.kt"
        val unitHash = write(repo.root, unitPath, "fun testFeature() = Unit\n")
        val tdd = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, atdd.newHead, EngineeringRole.TDD,
                mapOf(unitPath to unitHash),
                mapOf(specPath to specHash, acceptancePath to acceptanceHash)))
        assertEquals(tdd.newHead, git(repo.root, "rev-parse", "HEAD"))
        assertEquals("", git(repo.root, "status", "--porcelain"))
    }

    @Test fun acceptedCandidateUsesGitModeAcrossDifferentUmasks() {
        val repo = createRepo()
        val path = "specs/Feature.md"
        val hash = write(repo.root, path, "requirements\n")
        // CREATE_NEW under umask 002 can produce 0664: Git still records 100644.
        Files.setPosixFilePermissions(repo.root.resolve(path),
            PosixFilePermissions.fromString("rw-rw-r--"))
        val committed = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, repo.head, EngineeringRole.SDD, mapOf(path to hash)))
        assertEquals("", git(repo.root, "status", "--porcelain"))

        // A later edit under umask 077 may have 0600, also Git mode 100644.
        val updated = write(repo.root, path, "updated requirements\n")
        Files.setPosixFilePermissions(repo.root.resolve(path),
            PosixFilePermissions.fromString("rw-------"))
        val edited = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, committed.newHead, EngineeringRole.SDD,
                mapOf(path to updated)))
        assertEquals("100644",
            git(repo.root, "ls-tree", "HEAD", "--", path).substringBefore(' '))
        assertEquals("", git(repo.root, "status", "--porcelain"))

        // World-writable accepted files remain forbidden even without execute bit.
        val riskyHash = write(repo.root, path, "unsafe permissions\n")
        Files.setPosixFilePermissions(repo.root.resolve(path),
            PosixFilePermissions.fromString("rw-rw-rw-"))
        val denied = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, edited.newHead, EngineeringRole.SDD,
                mapOf(path to riskyHash)))
        assertEquals("UNSAFE_STAGE_FILE_MODE", denied.code)
        assertEquals(edited.newHead, git(repo.root, "rev-parse", "HEAD"))
    }

    @Test fun executableGitHooksAreDisabledEvenWithoutLocalHookConfig() {
        val repo = createRepo()
        val marker = temporary.resolve("git-hook-executed")
        val hooks = repo.root.resolve(".git/hooks")
        Files.createDirectories(hooks)
        for (name in listOf("reference-transaction", "post-index-change")) {
            val hook = hooks.resolve(name)
            Files.writeString(hook, "#!/bin/sh\\nprintf 'hook\\n' >> '${marker}'\\n".replace("\\n", "\n"))
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val digest = write(repo.root, "specs/Feature.md", "safe without hooks\n")
        val result = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf("specs/Feature.md" to digest)))
        assertEquals(result.newHead, git(repo.root, "rev-parse", "HEAD"))
        assertFalse(Files.exists(marker), "Git hooks must never execute in the host")
    }

    @Test fun executableModeChangesAreRejectedBeforeUpdatingBranch() {
        val repo = createRepo()
        val path = "src/main/Feature.kt"
        val initial = write(repo.root, path, "fun feature() = 1\n")
        Files.setPosixFilePermissions(repo.root.resolve(path),
            PosixFilePermissions.fromString("rwxr-xr-x"))
        val newMode = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.DEVELOPER,
                mapOf(path to initial)))
        assertEquals("UNSAFE_STAGE_FILE_MODE", newMode.code)
        assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))

        Files.setPosixFilePermissions(repo.root.resolve(path),
            PosixFilePermissions.fromString("rw-r--r--"))
        val first = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, repo.head, EngineeringRole.DEVELOPER,
                mapOf(path to initial)))
        val editedHash = write(repo.root, path, "fun feature() = 2\n")
        Files.setPosixFilePermissions(repo.root.resolve(path),
            PosixFilePermissions.fromString("rwxr-xr-x"))
        val drift = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, first.newHead, EngineeringRole.DEVELOPER,
                mapOf(path to editedHash)))
        assertEquals("UNSAFE_STAGE_FILE_MODE", drift.code)
        assertEquals(first.newHead, git(repo.root, "rev-parse", "HEAD"))
    }

    @Test fun gitOwnerExecuteSemanticsRejectUnusualTrackedModesBeforeCas() {
        val repo = createRepo()
        val executable = "src/main/Executable.kt"
        write(repo.root, executable, "fun runnable() = 1\n")
        Files.setPosixFilePermissions(repo.root.resolve(executable),
            PosixFilePermissions.fromString("rwxr-xr-x"))
        git(repo.root, "add", executable)
        git(repo.root, "commit", "-m", "track executable")
        val executableHead = git(repo.root, "rev-parse", "HEAD")
        assertEquals("100755",
            git(repo.root, "ls-tree", "HEAD", "--", executable).substringBefore(' '))

        // chmod 655 removes OWNER_EXECUTE but keeps GROUP/OTHERS_EXECUTE.
        // Git sees 100644; the old any-execute-bit derivation incorrectly
        // treated it as 100755 and failed only after advancing the branch.
        val changedExecutable = write(repo.root, executable, "fun runnable() = 2\n")
        Files.setPosixFilePermissions(repo.root.resolve(executable),
            PosixFilePermissions.fromString("rw-r-xr-x"))
        val changedMode = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, executableHead, EngineeringRole.DEVELOPER,
                mapOf(executable to changedExecutable)))
        assertEquals("UNSAFE_STAGE_FILE_MODE", changedMode.code)
        assertEquals(executableHead, git(repo.root, "rev-parse", "HEAD"))

        // Restore tracked fixture contents before creating the second baseline.
        git(repo.root, "restore", "--source=HEAD", "--worktree", "--", executable)
        val regular = "src/main/Regular.kt"
        write(repo.root, regular, "fun ordinary() = 1\n")
        git(repo.root, "add", regular)
        git(repo.root, "commit", "-m", "track regular")
        val regularHead = git(repo.root, "rev-parse", "HEAD")
        assertEquals("100644",
            git(repo.root, "ls-tree", "HEAD", "--", regular).substringBefore(' '))

        // chmod 654 grants GROUP_EXECUTE but not OWNER_EXECUTE. Git mode
        // is still 100644; reject the inconsistent candidate permissions.
        val changedRegular = write(repo.root, regular, "fun ordinary() = 2\n")
        Files.setPosixFilePermissions(repo.root.resolve(regular),
            PosixFilePermissions.fromString("rw-r-xr--"))
        val groupOnlyExec = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, regularHead, EngineeringRole.DEVELOPER,
                mapOf(regular to changedRegular)))
        assertEquals("UNSAFE_STAGE_FILE_MODE", groupOnlyExec.code)
        assertEquals(regularHead, git(repo.root, "rev-parse", "HEAD"))
    }

    @Test fun trackedEditSucceedsAndLeavesCleanWorktree() {
        val repo = createRepo()
        val path = "src/main/Feature.kt"
        val original = write(repo.root, path, "fun feature() = 1\n")
        val first = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, repo.head, EngineeringRole.DEVELOPER, mapOf(path to original)))
        val editedHash = write(repo.root, path, "fun feature() = 2\n")
        val second = assertIs<StageGitReconciliationResult.Committed>(
            reconcile(repo, first.newHead, EngineeringRole.DEVELOPER,
                mapOf(path to editedHash)))
        assertEquals("", git(repo.root, "status", "--porcelain"))
        assertEquals("100644", git(repo.root, "ls-tree", "HEAD", "--", path).substringBefore(' '))
        assertEquals(second.newHead, git(repo.root, "rev-parse", "HEAD"))
    }

    @Test fun rejectsUnsafeLocalConfigIncludingCoreFileModeFalse() {
        val repo = createRepo()
        val hash = write(repo.root, "specs/Feature.md", "requirements\n")
        for ((key, value) in listOf("core.filemode" to "false",
            "core.attributesfile" to "/tmp/attributes", "alias.status" to "echo spoofed",
            "commit.gpgsign" to "true")) {
            git(repo.root, "config", "--local", key, value)
            val denied = assertIs<StageGitReconciliationResult.Denied>(
                reconcile(repo, repo.head, EngineeringRole.SDD,
                    mapOf("specs/Feature.md" to hash)))
            assertEquals("UNSAFE_GIT_CONFIGURATION", denied.code, key)
            if (key == "core.filemode")
                git(repo.root, "config", "--local", "core.filemode", "true")
            else git(repo.root, "config", "--local", "--unset", key)
        }
        assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))
    }

    private fun withFaultObserver(repo: Repo, observer: StageGitTestObserver):
        LinuxStageGitBaselineReconciler =
        LinuxStageGitBaselineReconciler(repo.root, repo.locks,
            LinuxStageGitBaselineReconciler.findGit(), observer)

    @Test fun postCasFailureIsUncertainEvenWhenRefAlreadyMoved() {
        val repo = createRepo()
        val path = "specs/Feature.md"
        val hash = write(repo.root, path, "accepted requirements\n")
        var headInsideFailure: String? = null
        val reconciler = withFaultObserver(repo, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.AFTER_REF_UPDATE) {
                headInsideFailure = git(repo.root, "rev-parse", "HEAD")
                error("injected process failure after update-ref succeeded")
            }
        })
        val outcome = assertIs<StageGitReconciliationResult.ReconciliationRequired>(
            reconciler.reconcile(StageGitReconciliationRequest(repo.head, "dev",
                EngineeringRole.SDD, listOf(StageGitAcceptedFile(path, hash)))))
        assertEquals("GIT_HANDOFF_UNCONFIRMED", outcome.code)
        assertTrue(headInsideFailure != null && headInsideFailure != repo.head,
            "the fault must occur after the real branch ref moved")
        assertEquals(headInsideFailure, git(repo.root, "rev-parse", "HEAD"))
        assertTrue(git(repo.root, "status", "--porcelain").isNotBlank(),
            "the real index is not yet aligned; do not admit another stage")

        val retry = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.SDD, mapOf(path to hash)))
        assertEquals("STAGE_BASELINE_CHANGED", retry.code)
    }

    @Test fun externalRefAdvanceDuringCasCannotOverwriteTheCompetingHead() {
        val repo = createRepo()
        val path = "specs/Feature.md"
        val hash = write(repo.root, path, "accepted requirements\n")
        var competingHead: String? = null
        val reconciler = withFaultObserver(repo, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.BEFORE_REF_UPDATE) {
                val baselineTree = git(repo.root, "rev-parse", "HEAD^{tree}")
                competingHead = git(repo.root, "commit-tree", baselineTree,
                    "-p", repo.head, "-m", "independent competing host")
                git(repo.root, "update-ref", "refs/heads/dev",
                    requireNotNull(competingHead), repo.head)
            }
        })
        val outcome = assertIs<StageGitReconciliationResult.ReconciliationRequired>(
            reconciler.reconcile(StageGitReconciliationRequest(repo.head, "dev",
                EngineeringRole.SDD, listOf(StageGitAcceptedFile(path, hash)))))
        assertEquals("GIT_COMMAND_DENIED", outcome.code)
        assertTrue(competingHead != null && competingHead != repo.head)
        assertEquals(competingHead, git(repo.root, "rev-parse", "HEAD"),
            "the stale-old-value CAS must never overwrite an external writer")
        assertTrue(git(repo.root, "status", "--porcelain").isNotBlank(),
            "uncertain handoff cannot be treated as a clean, admitted stage")
    }

    @Test fun rejectsStagedIndexIgnoredFileAndWrongBranch() {
        val repo = createRepo()
        val hash = write(repo.root, "specs/Feature.md", "requirements\n")
        val wrongBranch = assertIs<StageGitReconciliationResult.Denied>(
            LinuxStageGitBaselineReconciler(repo.root, repo.locks).reconcile(
                StageGitReconciliationRequest(repo.head, "other", EngineeringRole.SDD,
                    listOf(StageGitAcceptedFile("specs/Feature.md", hash)))))
        assertEquals("STAGE_BRANCH_CHANGED", wrongBranch.code)
        git(repo.root, "add", "specs/Feature.md")
        val staged = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf("specs/Feature.md" to hash)))
        assertEquals("UNEXPECTED_GIT_CHANGE", staged.code)
        git(repo.root, "reset", "--mixed", "HEAD")
        Files.writeString(repo.root.resolve(".git/info/exclude"), "ignored-fixture\n")
        write(repo.root, "ignored-fixture", "should be rejected\n")
        val ignored = assertIs<StageGitReconciliationResult.Denied>(
            reconcile(repo, repo.head, EngineeringRole.SDD,
                mapOf("specs/Feature.md" to hash)))
        assertEquals("UNEXPECTED_GIT_CHANGE", ignored.code)
        assertEquals(repo.head, git(repo.root, "rev-parse", "HEAD"))
    }
}
