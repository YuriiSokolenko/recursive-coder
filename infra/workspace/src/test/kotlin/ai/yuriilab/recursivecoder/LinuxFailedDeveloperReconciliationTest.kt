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

    private data class RecoveryState(
        val index: ByteArray,
        val head: ByteArray,
        val branchRef: ByteArray,
        val status: String,
        val files: Map<String, ByteArray>
    )

    private fun captureState(f: Fixture): RecoveryState = RecoveryState(
        Files.readAllBytes(f.root.resolve(".git/index")),
        Files.readAllBytes(f.root.resolve(".git/HEAD")),
        Files.readAllBytes(f.root.resolve(".git/refs/heads/dev")),
        git(f.root, "--no-optional-locks", "status", "--porcelain"),
        (f.request.frozenArtifacts.keys + f.request.files.map { it.path })
            .associateWith { Files.readAllBytes(f.root.resolve(it)) }
    )

    private fun assertGitUnchanged(f: Fixture, before: RecoveryState) {
        assertContentEquals(before.index, Files.readAllBytes(f.root.resolve(".git/index")))
        assertContentEquals(before.head, Files.readAllBytes(f.root.resolve(".git/HEAD")))
        assertContentEquals(before.branchRef,
            Files.readAllBytes(f.root.resolve(".git/refs/heads/dev")))
        assertEquals(f.baseline, git(f.root, "rev-parse", "HEAD"))
        assertEquals(f.baseline, git(f.root, "rev-parse", "refs/heads/dev"))
        assertEquals("refs/heads/dev", git(f.root, "symbolic-ref", "HEAD"))
    }

    private fun assertUnchanged(f: Fixture, before: RecoveryState) {
        assertGitUnchanged(f, before)
        assertEquals(before.status, git(f.root, "--no-optional-locks", "status", "--porcelain"))
        before.files.forEach { (path, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(f.root.resolve(path)), path)
        }
    }

    private fun expectedAudit(f: Fixture): String = sha(
        "recursive-coder/developer-failed-write-recovery/v1\u0000" +
            f.request.stageRunId.value + "\u0000" + f.request.leaseGeneration +
            "\u0000" + f.baseline + "\u0000" +
            "src/main/Feature.kt:" + sha(oldSource) + ":" + sha(newSource) +
            "\u0000src/main/Implementation.txt:CREATE:" + sha(created)
    )

    private fun assertSnapshotRecovered(f: Fixture, before: RecoveryState,
        result: StageGitFailedWriteResult) {
        assertEquals(StageGitFailedWriteResult.Recovered(2, expectedAudit(f)), result)
        assertGitUnchanged(f, before)
        assertContentEquals(oldSource.toByteArray(),
            Files.readAllBytes(f.root.resolve("src/main/Feature.kt")))
        assertFalse(Files.exists(f.root.resolve("src/main/Implementation.txt")))
        before.files.filterKeys { !it.startsWith("src/main/") }.forEach { (path, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(f.root.resolve(path)), path)
        }
        assertEquals("", git(f.root, "--no-optional-locks", "status", "--porcelain"))
        assertEquals(StageGitBaselineResult.Clean,
            f.reconciler.inspectCleanBaseline(f.baseline, "dev"))
        assertGitUnchanged(f, before)
    }

    private fun withObserver(f: Fixture, name: String, observer: StageGitTestObserver) =
        LinuxStageGitBaselineReconciler(f.root, temp.resolve("locks-$name"),
            Path.of("/usr/bin/git"), observer)

    @Test fun callerBeforeImageMutationAfterBlobValidationCannotChangeRestorationOrAudit() {
        val name = "before-image-snapshot"
        val f = checkout(name)
        val before = captureState(f)
        val callerBytes = requireNotNull(f.request.files.first().beforeBytes)
        var reached = 0
        val reconciler = withObserver(f, name, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.BEFORE_FAILED_WRITE_RESTORE) {
                reached++
                // The original array already passed Git blob comparison.
                callerBytes.fill('X'.code.toByte())
            }
        })
        val result = reconciler.recoverFailedDeveloper(f.request)
        assertEquals(1, reached)
        assertTrue(callerBytes.all { it == 'X'.code.toByte() })
        assertSnapshotRecovered(f, before, result)
    }

    @Test fun callerListMutationAfterValidationCannotChangePostRestoreChecksOrAudit() {
        val name = "file-list-snapshot"
        val f = checkout(name)
        val before = captureState(f)
        val files = f.request.files.toMutableList()
        var reached = 0
        val reconciler = withObserver(f, name, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.BEFORE_FAILED_WRITE_RESTORE) {
                reached++
                files.clear()
                files.add(StageGitUnacceptedFile("../outside.kt", null, sha("outside")))
            }
        })
        val result = reconciler.recoverFailedDeveloper(f.request.copy(files = files))
        assertEquals(1, reached)
        assertEquals("../outside.kt", files.single().path)
        assertSnapshotRecovered(f, before, result)
    }

    @Test fun callerBeforeImageMutationAfterEvidenceValidationCannotChangeBlobVerification() {
        val name = "early-before-image-snapshot"
        val f = checkout(name)
        val before = captureState(f)
        val callerBytes = requireNotNull(f.request.files.first().beforeBytes)
        var reached = 0
        val reconciler = withObserver(f, name, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.AFTER_FAILED_WRITE_EVIDENCE_VALIDATION) {
                reached++
                callerBytes.fill('X'.code.toByte())
            }
        })
        val result = reconciler.recoverFailedDeveloper(f.request)
        assertEquals(1, reached)
        assertTrue(callerBytes.all { it == 'X'.code.toByte() })
        assertSnapshotRecovered(f, before, result)
    }

    @Test fun callerListMutationAfterEvidenceValidationCannotChangeGitPreflight() {
        val name = "early-file-list-snapshot"
        val f = checkout(name)
        val before = captureState(f)
        val files = f.request.files.toMutableList()
        var reached = 0
        val reconciler = withObserver(f, name, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.AFTER_FAILED_WRITE_EVIDENCE_VALIDATION) {
                reached++
                files.clear()
                files.add(StageGitUnacceptedFile("../outside.kt", null, sha("outside")))
            }
        })
        val result = reconciler.recoverFailedDeveloper(f.request.copy(files = files))
        assertEquals(1, reached)
        assertEquals("../outside.kt", files.single().path)
        assertSnapshotRecovered(f, before, result)
    }

    @Test fun callerListIsTraversedOnlyOnceBeforeAuthorization() {
        val name = "single-capture-traversal"
        val f = checkout(name)
        val before = captureState(f)
        val valid = f.request.files
        val invalid = valid.map { it.copy(path = "../outside.kt") }
        var traversals = 0
        val files = object : AbstractList<StageGitUnacceptedFile>() {
            override val size = valid.size
            override fun get(index: Int): StageGitUnacceptedFile = invalid[index]
            override fun iterator(): Iterator<StageGitUnacceptedFile> {
                traversals++
                return (if (traversals == 1) valid else invalid).iterator()
            }
        }
        val result = f.reconciler.recoverFailedDeveloper(f.request.copy(files = files))
        assertEquals(1, traversals)
        assertSnapshotRecovered(f, before, result)
    }

    @Test fun capturedFileCountIsCheckedEvenWhenCallerReportsSmallerSize() {
        val name = "captured-size-drift"
        val f = checkout(name)
        val before = captureState(f)
        // All nine entries are otherwise valid; the extra CREATE paths are absent.
        // Without the second size gate they could reach the workspace lease.
        val entries = f.request.files + List(7) { index ->
            StageGitUnacceptedFile("src/main/Extra$index.kt", null, sha("extra-$index"))
        }
        var traversals = 0
        val files = object : AbstractList<StageGitUnacceptedFile>() {
            override val size = 2
            override fun get(index: Int): StageGitUnacceptedFile = entries[index]
            override fun iterator(): Iterator<StageGitUnacceptedFile> {
                traversals++
                return entries.iterator()
            }
        }
        assertEquals(StageGitFailedWriteResult.NeedsHuman("INVALID_FAILED_WRITE_EVIDENCE"),
            f.reconciler.recoverFailedDeveloper(f.request.copy(files = files)))
        assertEquals(1, traversals)
        assertUnchanged(f, before)
        assertFalse(Files.exists(temp.resolve("locks-$name")))
    }

    @Test fun callerFrozenFactsCannotChangeAfterEvidenceValidationOrFinalBaselineRead() {
        val name = "frozen-map-snapshot"
        val f = checkout(name)
        val before = captureState(f)
        val frozen = f.request.frozenArtifacts.toMutableMap()
        var early = 0
        var late = 0
        val reconciler = withObserver(f, name, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.AFTER_FAILED_WRITE_EVIDENCE_VALIDATION) {
                early++
                frozen.clear()
                frozen["specs/Frozen.md"] = sha("caller substituted digest")
            }
            if (checkpoint == StageGitTestCheckpoint.BEFORE_BASELINE_FINAL_READ) {
                late++
                frozen.clear()
                frozen["../outside.md"] = sha("outside")
            }
        })
        val result = reconciler.recoverFailedDeveloper(f.request.copy(frozenArtifacts = frozen))
        assertEquals(1, early)
        assertEquals(1, late)
        assertEquals(setOf("../outside.md"), frozen.keys)
        assertSnapshotRecovered(f, before, result)
    }

    @Test fun removingFrozenFactsAfterEvidenceValidationCannotAuthorizeRecoveryOfDrift() {
        val name = "frozen-drift-snapshot"
        val f = checkout(name)
        Files.writeString(f.root.resolve("specs/Frozen.md"), "external edit\n")
        val before = captureState(f)
        val frozen = f.request.frozenArtifacts.toMutableMap()
        var reached = 0
        val reconciler = withObserver(f, name, StageGitTestObserver { checkpoint ->
            if (checkpoint == StageGitTestCheckpoint.AFTER_FAILED_WRITE_EVIDENCE_VALIDATION) {
                reached++
                frozen.clear()
            }
        })
        val result = reconciler.recoverFailedDeveloper(f.request.copy(frozenArtifacts = frozen))
        assertEquals(1, reached)
        assertTrue(frozen.isEmpty())
        assertEquals(StageGitFailedWriteResult.NeedsHuman("FROZEN_ARTIFACT_CHANGED"), result)
        assertUnchanged(f, before)
    }

    @Test fun captureFailuresAreFailClosedBeforeWorkspaceLeaseOrMutation() {
        val name = "capture-failure"
        val f = checkout(name)
        val before = captureState(f)
        var listRead = false
        val files = object : AbstractList<StageGitUnacceptedFile>() {
            override val size = 2
            override fun get(index: Int): StageGitUnacceptedFile {
                listRead = true
                error("caller list traversal failed")
            }
        }
        var mapRead = false
        val frozen = object : AbstractMap<String, String>() {
            override val size = 2
            override val entries: Set<Map.Entry<String, String>>
                get() {
                    mapRead = true
                    error("caller map traversal failed")
                }
        }
        for (request in listOf(f.request.copy(files = files),
                f.request.copy(frozenArtifacts = frozen))) {
            assertEquals(StageGitFailedWriteResult.NeedsHuman("RECOVERY_UNAVAILABLE"),
                f.reconciler.recoverFailedDeveloper(request))
            assertUnchanged(f, before)
            assertFalse(Files.exists(temp.resolve("locks-$name")))
        }
        assertTrue(listRead)
        assertTrue(mapRead)
    }

    @Test fun snapshotPreservesBoundsAndBaselineDenialPrecedenceBeforeLease() {
        val name = "snapshot-bounds"
        val f = checkout(name)
        val before = captureState(f)
        val oversized = f.request.files.first().copy(beforeBytes = ByteArray(4 * 1024 * 1024 + 1))
        val requests = listOf(
            f.request.copy(files = emptyList()),
            f.request.copy(files = List(9) { f.request.files.first() }),
            f.request.copy(files = listOf(oversized)),
            f.request.copy(files = listOf(
                StageGitUnacceptedFile("../outside.kt", null, sha("outside")))),
            f.request.copy(frozenArtifacts = mapOf("../outside.md" to sha("outside")))
        )
        for (request in requests) {
            assertEquals(StageGitFailedWriteResult.NeedsHuman("INVALID_GIT_BASELINE"),
                f.reconciler.recoverFailedDeveloper(request.copy(expectedHead = "invalid")))
            assertEquals(StageGitFailedWriteResult.NeedsHuman("INVALID_FAILED_WRITE_EVIDENCE"),
                f.reconciler.recoverFailedDeveloper(request))
            assertUnchanged(f, before)
            assertFalse(Files.exists(temp.resolve("locks-$name")))
        }
    }

    @Test fun snapshotPreservesFrozenFactDenialOrder() {
        val f = checkout("snapshot-order")
        val before = captureState(f)
        val wrong = "specs/Frozen.md" to sha("wrong")
        val missing = "specs/Missing.md" to sha("missing")
        for ((frozen, code) in listOf(
                linkedMapOf(wrong, missing) to "FROZEN_ARTIFACT_CHANGED",
                linkedMapOf(missing, wrong) to "UNSAFE_STAGE_FILE")) {
            assertEquals(StageGitFailedWriteResult.NeedsHuman(code),
                f.reconciler.recoverFailedDeveloper(f.request.copy(frozenArtifacts = frozen)))
            assertUnchanged(f, before)
        }
    }
}
