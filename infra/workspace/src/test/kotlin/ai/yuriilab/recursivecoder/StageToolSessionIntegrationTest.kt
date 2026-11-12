package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Exercise the stage port against a real Git tree, not a mocked write runtime. */
@EnabledOnOs(OS.LINUX)
@Tag("integration")
class StageToolSessionIntegrationTest {
    @TempDir lateinit var temp: Path

    private data class Fixture(val root: Path, val head: String, val contents: Map<String, String>)

    private val paths = mapOf(
        StageArtifactKind.REQUIREMENTS to "specs/Feature.md",
        StageArtifactKind.ACCEPTANCE_TESTS to "tests/acceptance/Feature.md",
        StageArtifactKind.UNIT_TESTS to "tests/unit/FeatureTest.kt",
        StageArtifactKind.IMPLEMENTATION to "src/main/Feature.kt"
    )
    private val kinds = mapOf(
        StageArtifactKind.REQUIREMENTS to StageKind.SDD,
        StageArtifactKind.ACCEPTANCE_TESTS to StageKind.ATDD,
        StageArtifactKind.UNIT_TESTS to StageKind.TDD,
        StageArtifactKind.IMPLEMENTATION to StageKind.DEVELOP
    )

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun git(root: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile())
            .redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), result)
        return result.trim()
    }

    private fun fixture(name: String, withSymlink: Boolean = false): Fixture {
        val root = temp.resolve(name)
        listOf("specs", "tests/acceptance", "tests/unit", "src/test", "src/main")
            .forEach { Files.createDirectories(root.resolve(it)) }
        git(root, "init", "-b", "dev")
        git(root, "config", "user.email", "fixture@example.invalid")
        git(root, "config", "user.name", "Stage Tool Fixture")
        val contents = mapOf(
            "specs/Feature.md" to "agreed requirements\n",
            "tests/acceptance/Feature.md" to "accepted scenarios\n",
            "tests/unit/FeatureTest.kt" to "fun testFeature() = 1\n",
            "src/main/Feature.kt" to "fun feature() = 1\n"
        )
        contents.forEach { (path, text) -> Files.writeString(root.resolve(path), text) }
        if (withSymlink) {
            val outside = temp.resolve("outside-$name")
            Files.createDirectories(outside)
            Files.createSymbolicLink(root.resolve("src/main/escape"), outside)
        }
        git(root, "add", ".")
        git(root, "commit", "-m", "trusted baseline")
        return Fixture(root, git(root, "rev-parse", "HEAD"), contents)
    }

    private fun stageId(kind: StageKind) = StageRunId("stage-" + kind.name.lowercase())
    private fun agentId(kind: StageKind) = AgentId("agent-" + kind.name.lowercase())
    private fun artifactId(kind: StageArtifactKind) = ArtifactId("artifact-" + kind.name.lowercase())

    private fun graph(target: StageKind, fixture: Fixture, freezeOwn: Boolean = false): StageGraph {
        val previous = StageKind.entries.takeWhile { it != target }.toSet()
        val manifestKinds = kinds.filterValues { it in previous ||
            (freezeOwn && it == target) }.keys
        val manifests = manifestKinds.map { kind ->
            val owner = kinds.getValue(kind)
            StageArtifactManifest(
                artifactId(kind), kind, paths.getValue(kind),
                digest(fixture.contents.getValue(paths.getValue(kind))),
                owner.role, stageId(owner), 1, ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN
            )
        }
        val nodes = StageKind.entries.map { kind ->
            StageNode(
                correlation = StageCorrelation("session-74", GraphId("graph-74"),
                    stageId(kind), "request-" + kind.name.lowercase(),
                    "corr-" + kind.name.lowercase()),
                kind = kind,
                state = when {
                    kind in previous -> StageState.ACCEPTED
                    kind == target -> StageState.READY
                    else -> StageState.CREATED
                },
                agentId = agentId(kind),
                dependencies = kind.requiredInputs.map { stageId(kinds.getValue(it)) }.toSet(),
                inputArtifacts = if (kind == target)
                    kind.requiredInputs.map(::artifactId).toSet() else emptySet(),
                outputArtifacts = kind.output?.let { out ->
                    if (out in manifestKinds) setOf(artifactId(out)) else emptySet()
                } ?: emptySet()
            )
        }
        // Accepted previous stages must retain their accepted/frozen output.
        return StageGraph(
            GraphCorrelation("session-74", GraphId("graph-74"), "corr-graph"),
            StageGraphState.RUNNING, nodes, manifests, StageBudget(100_000, 60_000, 2, 2)
        )
    }

    private fun snapshot(
        target: StageKind, fixture: Fixture,
        graph: StageGraph = graph(target, fixture),
        independentCheck: StageIsolatedCheck = StageIsolatedCheck(Path.of("/usr/bin/true"))
    ): StageToolHostSnapshot {
        val developer = target == StageKind.DEVELOP
        return StageToolHostSnapshot(
            graph = graph, stageRunId = stageId(target),
            requestId = "request-" + target.name.lowercase(),
            hostAcceptedArtifactIds = graph.artifacts.map { it.id }.toSet(),
            root = fixture.root, baselineHead = fixture.head, branch = "dev",
            checks = if (developer) mapOf("independent-test" to independentCheck) else emptyMap(),
            requiredChecks = if (developer)
                setOf("git_diff_check", "independent-test") else setOf("git_diff_check")
        )
    }

    private fun open(snapshot: StageToolHostSnapshot): StageToolSession {
        val result = LinuxStageToolSessionOpener(temp.resolve("locks"))
            .open(StageToolAuthoritySource { snapshot })
        return (result as? StageToolOpenResult.Granted)?.session
            ?: fail("Stage denied: $result")
    }

    @Test fun everyStageCanWriteOnlyItsOwnedTree() {
        val owned = mapOf(
            StageKind.SDD to "specs/New.md",
            StageKind.ATDD to "tests/acceptance/New.md",
            StageKind.TDD to "tests/unit/New.kt",
            StageKind.DEVELOP to "src/main/New.kt"
        )
        for ((role, path) in owned) {
            val fixture = fixture("role-" + role.name.lowercase())
            open(snapshot(role, fixture)).use { session ->
                for ((other, otherPath) in owned) {
                    if (other != role) {
                        val denied = session.write(listOf(StageWrite.Create(otherPath, "forged\n")))
                        assertEquals("ROLE_NOT_AUTHORIZED", denied.deniedCode,
                            "$role must not mutate $other")
                    }
                }
                assertTrue(session.write(listOf(StageWrite.Create(path, "accepted\n"))).permitted)
                val diff = session.runCheck("git_diff_check")
                assertTrue(diff.passed, diff.deniedCode)
                val verification = session.verify()
                if (role == StageKind.DEVELOP) {
                    assertFalse(verification.successful)
                    assertEquals("REQUIRED_CHECK_MISSING", verification.deniedCode)
                } else {
                    assertTrue(verification.successful, verification.deniedCode)
                    assertEquals(stageId(role), verification.correlation.stageRunId)
                    assertEquals(agentId(role), verification.actor)
                    assertEquals(listOf("git_diff_check"),
                        verification.requiredChecks.map { it.checkId })
                    assertNotNull(verification.requiredChecks.single().manifestHash)
                }
            }
        }
    }

    @Test fun frozenArtifactCannotBeRewrittenWithoutReviewedExactRevision() {
        val fixture = fixture("frozen")
        val ownFrozen = graph(StageKind.SDD, fixture, freezeOwn = true)
        open(snapshot(StageKind.SDD, fixture, ownFrozen)).use { session ->
            val denied = session.write(listOf(
                StageWrite.Edit("specs/Feature.md",
                    digest(fixture.contents.getValue("specs/Feature.md")), "unapproved\n")
            ))
            assertEquals("APPROVED_ARTIFACT_IMMUTABLE", denied.deniedCode)
            assertEquals("agreed requirements\n", Files.readString(fixture.root.resolve("specs/Feature.md")))
        }
    }

    @Test fun crossRoleImmutableEditsSymlinksAndPathEscapesFailClosed() {
        val fixture = fixture("path-guards", withSymlink = true)
        open(snapshot(StageKind.DEVELOP, fixture)).use { session ->
            for (path in listOf(
                "specs/Feature.md", "tests/acceptance/Feature.md",
                "tests/unit/FeatureTest.kt", "src/test/Injected.kt",
                "src/main/FeatureTest.kt", "src/main/../src/test/Escape.kt",
                "/tmp/escape.kt", "src/main/.env"
            )) {
                assertFalse(session.write(listOf(StageWrite.Create(path, "attack\n"))).permitted, path)
            }
            assertEquals("UNSAFE_PATH_COMPONENT",
                session.write(listOf(StageWrite.Create("src/main/escape/Escape.kt", "attack\n")))
                    .deniedCode)
            assertFalse(Files.exists(temp.resolve("outside-path-guards/Escape.kt")))
        }
    }

    @Test fun staleHostAuthorityIsRevokedEvenIfTheOldSnapshotReturns() {
        val fixture = fixture("stale")
        val original = snapshot(StageKind.SDD, fixture)
        var current = original
        val source = StageToolAuthoritySource { current }
        val open = LinuxStageToolSessionOpener(temp.resolve("locks")).open(source)
        val session = (open as StageToolOpenResult.Granted).session
        session.use {
            current = original.copy(hostAcceptedArtifactIds = setOf(ArtifactId("revoked")))
            assertEquals("STALE_STAGE_AUTHORITY",
                it.write(listOf(StageWrite.Create("specs/Stale.md", "unsafe"))).deniedCode)
            current = original
            assertEquals("STALE_STAGE_AUTHORITY", it.runCheck("git_diff_check").deniedCode)
            assertFalse(it.verify().successful)
            assertFalse(Files.exists(fixture.root.resolve("specs/Stale.md")))
        }
    }

    @Test fun hostAcceptanceAndCorrelatedRequestAreMandatory() {
        val fixture = fixture("admission")
        val base = snapshot(StageKind.ATDD, fixture)
        val opener = LinuxStageToolSessionOpener(temp.resolve("locks"))
        fun denied(snapshot: StageToolHostSnapshot): String? =
            (opener.open(StageToolAuthoritySource { snapshot }) as StageToolOpenResult.Denied).code
        assertEquals("ARTIFACT_NOT_ACCEPTED",
            denied(base.copy(hostAcceptedArtifactIds = emptySet())))
        assertEquals("REQUEST_CORRELATION_MISMATCH",
            denied(base.copy(requestId = "forged-request")))
        assertEquals("INVALID_HOST_ACCEPTANCE",
            denied(base.copy(hostAcceptedArtifactIds =
                base.hostAcceptedArtifactIds + ArtifactId("unverified"))))
        val reviewer = snapshot(StageKind.REVIEW, fixture)
        assertEquals("ROLE_NOT_AUTHORIZED", denied(reviewer))
        val inadequate = snapshot(StageKind.DEVELOP, fixture).copy(
            requiredChecks = setOf("git_diff_check"))
        assertEquals("TEST_CHECK_REQUIRED", denied(inadequate))
    }

    @Test fun frozenDigestAndGitBaselineAreCheckedBeforeWrites() {
        val fixture = fixture("digests")
        val original = snapshot(StageKind.ATDD, fixture)
        val opener = LinuxStageToolSessionOpener(temp.resolve("locks"))
        val mismatched = original.copy(baselineHead = "a".repeat(40))
        assertEquals("WORKSPACE_BASELINE_MISMATCH",
            (opener.open(StageToolAuthoritySource { mismatched }) as StageToolOpenResult.Denied).code)
        Files.writeString(fixture.root.resolve("specs/Feature.md"), "different requirements\n")
        git(fixture.root, "add", ".")
        git(fixture.root, "commit", "-m", "change requirements")
        val changed = original.copy(baselineHead = git(fixture.root, "rev-parse", "HEAD"))
        assertEquals("APPROVED_ARTIFACT_CHANGED",
            (opener.open(StageToolAuthoritySource { changed }) as StageToolOpenResult.Denied).code)
    }

    @Test fun checksDoNotProduceSuccessAfterMutationOrFailedValidation() {
        val fixture = fixture("evidence")
        open(snapshot(StageKind.SDD, fixture)).use { session ->
            assertTrue(session.write(listOf(StageWrite.Create("specs/First.md", "first\n"))).permitted)
            val first = session.runCheck("git_diff_check")
            assertTrue(first.passed)
            assertFalse(session.runCheck("unlisted-check").passed)
            assertTrue(session.write(listOf(StageWrite.Create("specs/Second.md", "second\n"))).permitted)
            assertEquals("REQUIRED_CHECK_MISSING", session.verify().deniedCode)
            val second = session.runCheck("git_diff_check")
            assertTrue(second.passed)
            assertNotEquals(first.manifestHash, second.manifestHash)
            assertTrue(session.verify().successful)
            assertEquals("STAGE_TOOL_FINISHED",
                session.write(listOf(StageWrite.Create("specs/Third.md", "third\n"))).deniedCode)
        }
    }

    /**
     * CI must prove the newly wired port uses bubblewrap, not a host fallback.
     * Local restricted Linux may skip this probe, CI may not.
     */
    @Tag("e2e")
    @Test fun stageToolPortUsesRealIsolatedCheckWithoutSkippingInCi() {
        val sandbox = Path.of("/usr/bin/bwrap")
        val python = Path.of("/usr/bin/python3")
        val ci = System.getenv("CI").equals("true", true) ||
            System.getenv("GITHUB_ACTIONS").equals("true", true)
        val available = Files.isExecutable(sandbox) && Files.isExecutable(python)
        if (ci) assertTrue(available, "CI must install bwrap and Python")
        else Assumptions.assumeTrue(available, "Local sandbox unavailable")
        val probe = ProcessBuilder(sandbox.toString(), "--die-with-parent", "--new-session",
            "--unshare-all", "--tmpfs", "/", "--proc", "/proc", "--dev", "/dev",
            "--ro-bind", "/usr", "/usr", "--", "/usr/bin/true")
            .redirectErrorStream(true).start()
        val probeSuccess = probe.waitFor() == 0
        if (ci) assertTrue(probeSuccess, "CI namespaces must be usable")
        else Assumptions.assumeTrue(probeSuccess, "Local namespaces unavailable")

        val code = """
            from pathlib import Path
            import socket
            assert Path('/workspace/src/main/Feature.kt').is_file()
            try:
                Path('/workspace/src/main/Feature.kt').write_text('untrusted mutation')
                raise AssertionError('workspace was writable')
            except OSError:
                pass
            Path('/scratch/proof.txt').write_text('scratch writable')
            try:
                socket.create_connection(('1.1.1.1', 53), timeout=0.2)
                raise AssertionError('sandbox had network access')
            except OSError:
                pass
        """.trimIndent()
        val fixture = fixture("sandbox-e2e")
        val check = StageIsolatedCheck(python, listOf("-c", code), timeoutMs = 5_000)
        open(snapshot(StageKind.DEVELOP, fixture, independentCheck = check)).use { session ->
            assertTrue(session.write(listOf(
                StageWrite.Create("src/main/New.kt", "fun production() = true\n")
            )).permitted)
            val git = session.runCheck("git_diff_check")
            val isolated = session.runCheck("independent-test")
            assertTrue(git.passed, git.deniedCode)
            assertTrue(isolated.passed, isolated.deniedCode)
            val evidence = session.verify()
            assertTrue(evidence.successful, evidence.deniedCode)
            assertEquals(setOf("git_diff_check", "independent-test"),
                evidence.requiredChecks.map { it.checkId }.toSet())
            assertTrue(evidence.requiredChecks.all { it.policyHash != null && it.manifestHash != null })
        }
    }
}
