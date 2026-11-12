package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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
            val attacks = mapOf(
                "specs/Feature.md" to "ROLE_NOT_AUTHORIZED",
                "tests/acceptance/Feature.md" to "ROLE_NOT_AUTHORIZED",
                "tests/unit/FeatureTest.kt" to "ROLE_NOT_AUTHORIZED",
                "src/test/Injected.kt" to "ROLE_NOT_AUTHORIZED",
                "src/main/FeatureTest.kt" to "PROTECTED_PATH",
                "src/main/../src/test/Escape.kt" to "INVALID_TOOL_PATH",
                "/tmp/escape.kt" to "INVALID_TOOL_PATH",
                "src/main/.env" to "PROTECTED_PATH"
            )
            for ((path, expected) in attacks) {
                assertEquals(expected,
                    session.write(listOf(StageWrite.Create(path, "attack\n"))).deniedCode, path)
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
            assertEquals("STALE_STAGE_AUTHORITY", it.verify().deniedCode)
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
            assertEquals("CHECK_NOT_ALLOWLISTED",
                session.runCheck("unlisted-check").deniedCode)
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
        val probeArgs = mutableListOf(sandbox.toString(), "--die-with-parent",
            "--new-session", "--unshare-all", "--tmpfs", "/",
            "--proc", "/proc", "--dev", "/dev")
        for (directory in listOf("/usr", "/bin", "/lib", "/lib64")) {
            if (Files.exists(Path.of(directory))) {
                probeArgs.addAll(listOf("--ro-bind", directory, directory))
            }
        }
        probeArgs.addAll(listOf("--", "/usr/bin/true"))
        val probe = ProcessBuilder(probeArgs)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val completed = probe.waitFor(10, TimeUnit.SECONDS)
        if (!completed) {
            probe.destroyForcibly()
            probe.waitFor(5, TimeUnit.SECONDS)
        }
        val probeSuccess = completed && probe.exitValue() == 0
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

    @Test fun runningStageIsRecheckedAgainstCurrentAcceptedInputs() {
        val fixture = fixture("running")
        val base = snapshot(StageKind.ATDD, fixture)
        fun running(graph: StageGraph): StageGraph = graph.copy(stages = graph.stages.map {
            if (it.kind == StageKind.ATDD) it.copy(state = StageState.RUNNING) else it
        })
        val valid = base.copy(graph = running(base.graph))
        open(valid).use { session ->
            assertTrue(session.write(listOf(
                StageWrite.Create("tests/acceptance/Next.md", "accepted\n")
            )).permitted)
        }
        val opener = LinuxStageToolSessionOpener(temp.resolve("locks"))
        fun denied(state: StageToolHostSnapshot): String =
            (opener.open(StageToolAuthoritySource { state }) as StageToolOpenResult.Denied).code
        // Use a separate clean fixture for denial cases because the first
        // successful write intentionally dirties its working tree.
        val clean = fixture("running-denied")
        val pristine = snapshot(StageKind.ATDD, clean).let { it.copy(graph = running(it.graph)) }
        val missingClean = pristine.copy(graph = pristine.graph.copy(stages =
            pristine.graph.stages.map {
                if (it.kind == StageKind.ATDD) it.copy(inputArtifacts = emptySet()) else it
            }))
        assertEquals("MISSING_REQUIRED_ARTIFACT", denied(missingClean))
        assertEquals("ARTIFACT_NOT_ACCEPTED",
            denied(pristine.copy(hostAcceptedArtifactIds = emptySet())))
    }

    private fun reviewedRevision(
        graph: StageGraph,
        digestOverride: String? = null,
        reviewer: String = "agent-reviewer"
    ): StageRevisionAuthorization {
        val artifact = graph.artifacts.single { it.kind == StageArtifactKind.REQUIREMENTS }
        val path = artifact.declaredPath
        return StageRevisionAuthorization(
            artifact.id, digestOverride ?: artifact.sha256, artifact.revision,
            ReviewedChangeFacts(
                changeRequestId = "review-74",
                requestedBy = agentId(StageKind.SDD).value,
                reviewedBy = reviewer,
                actorRole = EngineeringRole.SDD,
                reviewedRole = EngineeringRole.SDD,
                paths = setOf(path),
                approvedArtifactPaths = setOf(path)
            )
        )
    }

    @Test fun independentReviewAllowsOnlyTheExactFrozenRevision() {
        val fixture = fixture("review-positive")
        val frozen = graph(StageKind.SDD, fixture, freezeOwn = true)
        val revision = reviewedRevision(frozen)
        open(snapshot(StageKind.SDD, fixture, frozen).copy(revisionAuthorization = revision))
            .use { session ->
                val previous = digest(fixture.contents.getValue("specs/Feature.md"))
                assertTrue(session.write(listOf(
                    StageWrite.Edit("specs/Feature.md", previous, "reviewed requirements\n")
                )).permitted)
                assertTrue(session.runCheck("git_diff_check").passed)
                val verified = session.verify()
                assertTrue(verified.successful, verified.deniedCode)
                assertEquals("reviewed requirements\n",
                    Files.readString(fixture.root.resolve("specs/Feature.md")))
            }
    }

    @Test fun invalidRevisionAttestationsNeverAcquireWriteAccess() {
        val fixture = fixture("review-denied")
        val frozen = graph(StageKind.SDD, fixture, freezeOwn = true)
        val base = snapshot(StageKind.SDD, fixture, frozen)
        val valid = reviewedRevision(fixture, frozen)
        val opener = LinuxStageToolSessionOpener(temp.resolve("locks"))
        fun denied(candidate: StageToolHostSnapshot): String =
            (opener.open(StageToolAuthoritySource { candidate }) as StageToolOpenResult.Denied).code
        assertEquals("UNKNOWN_ARTIFACT", denied(base.copy(
            revisionAuthorization = valid.copy(artifactId = ArtifactId("missing-artifact")))))
        val draft = frozen.copy(artifacts = frozen.artifacts.map {
            it.copy(approval = ArtifactApproval.DRAFT, freeze = ArtifactFreeze.EDITABLE)
        })
        assertEquals("INVALID_REVISION_AUTHORIZATION",
            denied(base.copy(graph = draft, hostAcceptedArtifactIds = emptySet(),
                revisionAuthorization = valid)))
        assertEquals("FROZEN_ARTIFACT", denied(base.copy(
            revisionAuthorization = valid.copy(expectedSha256 = "f".repeat(64)))))
        assertEquals("FROZEN_ARTIFACT", denied(base.copy(
            revisionAuthorization = valid.copy(expectedRevision = 2))))
        assertEquals("INVALID_INDEPENDENT_REVIEW", denied(base.copy(
            revisionAuthorization = valid.copy(review =
                valid.review.copy(reviewedBy = valid.review.requestedBy)))))
    }

    @Test fun badOrRevokedAuthorityAndMissingActorFailBeforeLeaseAcquisition() {
        val fixture = fixture("missing-authority")
        val base = snapshot(StageKind.SDD, fixture)
        val opener = LinuxStageToolSessionOpener(temp.resolve("locks"))
        fun denied(source: StageToolAuthoritySource) =
            (opener.open(source) as StageToolOpenResult.Denied).code
        assertEquals("STAGE_AUTHORITY_UNAVAILABLE", denied(StageToolAuthoritySource { null }))
        assertEquals("STAGE_AUTHORITY_UNAVAILABLE",
            denied(StageToolAuthoritySource { error("unavailable") }))
        assertEquals("INVALID_REQUEST_ID",
            denied(StageToolAuthoritySource { base.copy(requestId = "invalid id") }))
        assertEquals("UNKNOWN_STAGE",
            denied(StageToolAuthoritySource { base.copy(stageRunId = StageRunId("unassigned")) }))
        val notReady = base.graph.copy(stages = base.graph.stages.map {
            if (it.kind == StageKind.SDD) it.copy(state = StageState.CREATED) else it
        })
        assertEquals("STAGE_NOT_READY",
            denied(StageToolAuthoritySource { base.copy(graph = notReady) }))
        val withoutActor = base.graph.copy(stages = base.graph.stages.map {
            if (it.kind == StageKind.SDD) it.copy(agentId = null) else it
        })
        assertEquals("MISSING_STAGE_ACTOR",
            denied(StageToolAuthoritySource { base.copy(graph = withoutActor) }))
        assertFalse(Files.exists(temp.resolve("locks")))

        var current: StageToolHostSnapshot? = base
        var failRead = false
        val source = StageToolAuthoritySource {
            if (failRead) error("host crashed")
            current
        }
        val session = (opener.open(source) as StageToolOpenResult.Granted).session
        session.use {
            current = null
            assertEquals("STALE_STAGE_AUTHORITY", it.runCheck("git_diff_check").deniedCode)
            current = base
            assertEquals("STALE_STAGE_AUTHORITY",
                it.write(listOf(StageWrite.Create("specs/Late.md", "late"))).deniedCode)
        }
        val second = (opener.open(source) as StageToolOpenResult.Granted).session
        second.use {
            failRead = true
            assertEquals("STALE_STAGE_AUTHORITY", it.verify().deniedCode)
        }
    }

    @Test fun quotaClosedSessionAndRepeatedVerificationAreNonSuccess() {
        val fixture = fixture("quota")
        val base = snapshot(StageKind.SDD, fixture).copy(maxToolCalls = 1)
        val session = open(base)
        assertTrue(session.write(listOf(StageWrite.Create("specs/One.md", "one\n"))).permitted)
        assertEquals("TOOL_QUOTA_EXCEEDED",
            session.write(listOf(StageWrite.Create("specs/Two.md", "two\n"))).deniedCode)
        // An exhausted stage cannot turn its check into success.
        assertEquals("TOOL_QUOTA_EXCEEDED", session.runCheck("git_diff_check").deniedCode)
        val failed = session.verify()
        assertFalse(failed.successful)
        assertEquals("TOOL_QUOTA_EXCEEDED", failed.requiredChecks.single().deniedCode)
        session.close()
        assertEquals("WRITE_SESSION_INVALID", session.runCheck("git_diff_check").deniedCode)
        assertEquals("WRITE_SESSION_INVALID", session.verify().deniedCode)

        val clean = fixture("verify-twice")
        open(snapshot(StageKind.SDD, clean)).use { valid ->
            assertTrue(valid.write(listOf(StageWrite.Create("specs/New.md", "content\n"))).permitted)
            assertEquals("CHECK_NOT_RUN", valid.verify().requiredChecks.single().deniedCode)
            assertTrue(valid.runCheck("git_diff_check").passed)
            assertTrue(valid.verify().successful)
            val repeated = valid.verify()
            assertFalse(repeated.successful)
            assertEquals("STAGE_TOOL_FINISHED", repeated.deniedCode)
            assertEquals("STAGE_TOOL_FINISHED", repeated.requiredChecks.single().deniedCode)
        }
    }

    @Test fun mutableHostCollectionChangesCannotSilentlyRewriteAdmittedSnapshot() {
        val fixture = fixture("mutable-authority")
        val base = snapshot(StageKind.SDD, fixture)
        val acceptance = mutableSetOf<ArtifactId>()
        val current = base.copy(hostAcceptedArtifactIds = acceptance)
        val session = (LinuxStageToolSessionOpener(temp.resolve("locks"))
            .open(StageToolAuthoritySource { current }) as StageToolOpenResult.Granted).session
        session.use {
            acceptance.add(ArtifactId("unexpected"))
            assertEquals("STALE_STAGE_AUTHORITY", it.runCheck("git_diff_check").deniedCode)
        }
    }

}
