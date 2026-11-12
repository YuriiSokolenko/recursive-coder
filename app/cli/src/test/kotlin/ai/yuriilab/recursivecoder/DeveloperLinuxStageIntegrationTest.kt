package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * Real Linux Git/secure-descriptor/bubblewrap/HMAC receipt path and the
 * Developer use case in the same test. No network or live model.
 */
@EnabledOnOs(OS.LINUX)
@Tag("e2e")
class DeveloperLinuxStageIntegrationTest {
    @TempDir lateinit var temp: Path

    private fun sha(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }

    private fun git(root: Path, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(root.toFile()).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), output)
        return output.trim()
    }

    @Test fun realSandboxAndReceiptsAcceptOnlyHostApprovedSourceChanges() = runBlocking {
        runScenario(Path.of("/usr/bin/true"), expectCheckFailure = false)
    }

    @Test fun realSandboxFailedCheckLeavesUnapprovedDirtyWorkspace() = runBlocking {
        runScenario(Path.of("/usr/bin/false"), expectCheckFailure = true)
    }

    private suspend fun runScenario(checkExecutable: Path, expectCheckFailure: Boolean) {
        // Match the existing #74 sandbox E2E convention: local restricted hosts
        // may skip, but CI MUST fail if bubblewrap or user namespaces are unusable.
        val sandbox = Path.of("/usr/bin/bwrap")
        val ci = System.getenv("CI").equals("true", true) ||
            System.getenv("GITHUB_ACTIONS").equals("true", true)
        val installed = Files.isExecutable(sandbox)
        if (ci) assertTrue(installed, "CI must install bubblewrap")
        else Assumptions.assumeTrue(installed, "Local bubblewrap unavailable")
        val probeArgs = mutableListOf(sandbox.toString(), "--die-with-parent",
            "--new-session", "--unshare-all", "--tmpfs", "/",
            "--proc", "/proc", "--dev", "/dev")
        for (directory in listOf("/usr", "/bin", "/lib", "/lib64")) {
            if (Files.exists(Path.of(directory))) {
                probeArgs.addAll(listOf("--ro-bind", directory, directory))
            }
        }
        probeArgs.addAll(listOf("--", "/usr/bin/true"))
        val probe = try {
            ProcessBuilder(probeArgs)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (error: java.io.IOException) {
            if (ci) throw AssertionError("CI bubblewrap namespace probe unavailable", error)
            Assumptions.assumeTrue(false, "Local namespace probe unavailable")
            throw error // Unreachable after aborted assumption.
        }
        val probeCompleted = probe.waitFor(10, TimeUnit.SECONDS)
        if (!probeCompleted) {
            probe.destroyForcibly()
            probe.waitFor(5, TimeUnit.SECONDS)
        }
        val usable = probeCompleted && probe.exitValue() == 0
        if (ci) assertTrue(usable, "CI user namespaces must be usable")
        else Assumptions.assumeTrue(usable, "Local user namespaces unavailable")
        val root = temp.resolve("checkout")
        val content = mapOf(
            "specs/Feature.md" to "Requirement: feature is implemented.\n",
            "tests/acceptance/Feature.feature" to "Scenario: feature works.\n",
            "tests/unit/FeatureTest.kt" to "fun testFeature() = Unit\n",
            "src/main/Feature.kt" to "fun feature() = 1\n"
        )
        content.keys.forEach { Files.createDirectories(root.resolve(it).parent) }
        git(root, "init", "-b", "dev")
        git(root, "config", "user.email", "fixture@example.invalid")
        git(root, "config", "user.name", "Developer Integration Test")
        content.forEach { (path, text) -> Files.writeString(root.resolve(path), text) }
        git(root, "add", ".")
        git(root, "commit", "-m", "frozen baseline")
        val baseline = git(root, "rev-parse", "HEAD")

        val graphId = GraphId("developer-linux-graph")
        val developerId = StageRunId("developer-linux-run")
        val predecessors = listOf(StageKind.SDD, StageKind.ATDD, StageKind.TDD)
        val paths = listOf("specs/Feature.md", "tests/acceptance/Feature.feature",
            "tests/unit/FeatureTest.kt")
        val acceptedNodes = predecessors.mapIndexed { index, role ->
            StageNode(StageCorrelation("developer-linux-session", graphId,
                StageRunId("producer-$index"), "producer-request-$index", "corr-$index"),
                role, StageState.ACCEPTED, AgentId("producer-agent-$index"),
                outputArtifacts = setOf(ArtifactId("artifact-$index")))
        }
        val acceptedArtifacts = predecessors.mapIndexed { index, role ->
            StageArtifactManifest(ArtifactId("artifact-$index"),
                requireNotNull(role.output), paths[index],
                sha(Files.readAllBytes(root.resolve(paths[index]))),
                role.role, StageRunId("producer-$index"), 1,
                ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN)
        }
        val developer = StageNode(StageCorrelation("developer-linux-session", graphId,
            developerId, "developer-request", "developer-corr"),
            StageKind.DEVELOP, StageState.CREATED, AgentId("developer-agent"),
            dependencies = acceptedNodes.map { it.correlation.stageRunId }.toSet(),
            inputArtifacts = acceptedArtifacts.map { it.id }.toSet())
        val initialGraph = StageGraph(
            GraphCorrelation("developer-linux-session", graphId, "workflow-corr"),
            StageGraphState.RUNNING, acceptedNodes + developer, acceptedArtifacts,
            StageBudget(10_000, 60_000, 0, 0)
        )
        val accepted = acceptedArtifacts.map { it.id }.toSet()
        val outputPath = "src/main/Feature.kt"
        val newPath = "src/main/Additional.kt"
        val manifestPath = "src/main/ImplementationManifest.txt"
        val newSource = "fun feature() = 2\n"
        val additional = "fun additional() = true\n"

        val host = object : PreparationStageHost {
            var graph = initialGraph
            var acceptedIds = accepted
            var generation: String? = null
            var approval: StageArtifactManifest? = null
            var approvalDecision: PreparationApproval? = null

            override fun readBytes(path: String): ByteArray? = try {
                Files.readAllBytes(root.resolve(path))
            } catch (_: NoSuchFileException) { null }

            @Synchronized override fun beginStage(
                expectedGraph: StageGraph, stageRunId: StageRunId,
                expectedAcceptedIds: Set<ArtifactId>
            ): PreparationStageLease? {
                if (generation != null || graph != expectedGraph ||
                    acceptedIds != expectedAcceptedIds) return null
                val ready = graph.copy(stages = graph.stages.map {
                    if (it.correlation.stageRunId == stageRunId) it.copy(state = StageState.READY)
                    else it
                })
                if (StageGraphPolicy.admitStage(ready, stageRunId, acceptedIds) !=
                    StageGateDecision.Permit) return null
                graph = ready.copy(stages = ready.stages.map {
                    if (it.correlation.stageRunId == stageRunId) it.copy(state = StageState.RUNNING)
                    else it
                })
                generation = "linux-generation-1"
                val snapshot = StageToolHostSnapshot(graph, developerId, "developer-request",
                    acceptedIds, root, baseline, "dev",
                    checks = mapOf("offline-test" to StageIsolatedCheck(checkExecutable)),
                    requiredChecks = setOf("git_diff_check", "offline-test"))
                return PreparationStageLease(requireNotNull(generation), developerId,
                    graph, acceptedIds, StageToolAuthoritySource {
                        synchronized(this) {
                            if (generation == "linux-generation-1") snapshot else null
                        }
                    })
            }

            override fun beginHumanApproval(
                expectedGraph: StageGraph, stageRunId: StageRunId,
                expectedAcceptedIds: Set<ArtifactId>
            ): PreparationStageLease? = null

            @Synchronized override fun current(lease: PreparationStageLease): Boolean =
                generation == lease.generation && graph == lease.graph &&
                    acceptedIds == lease.acceptedIds

            @Synchronized override fun finishStage(
                lease: PreparationStageLease, updatedGraph: StageGraph,
                updatedAcceptedIds: Set<ArtifactId>, pending: PreparationPendingApproval?
            ): Boolean {
                if (!current(lease) || !PreparationStageFinishPolicy.permits(
                        lease, graph, acceptedIds, updatedGraph, updatedAcceptedIds,
                        pending, approvalDecision, approval)) return false
                graph = updatedGraph
                acceptedIds = updatedAcceptedIds
                generation = null
                approval = null
                approvalDecision = null
                return true
            }

            @Synchronized override fun abortStage(
                lease: PreparationStageLease, terminal: StageState
            ): PreparationStageSnapshot? {
                if (!current(lease) || terminal !in setOf(
                        StageState.CANCELLED, StageState.FAILED)) return null
                graph = graph.copy(stages = graph.stages.map {
                    if (it.correlation.stageRunId == developerId)
                        it.copy(state = terminal) else it
                })
                generation = null
                return PreparationStageSnapshot(graph, acceptedIds)
            }

            @Synchronized override fun approve(
                candidate: StageArtifactManifest, evidence: StageValidationEvidence,
                usage: StageRoleUsage
            ): PreparationApproval {
                assertTrue(evidence.successful)
                assertEquals(setOf("git_diff_check", "offline-test"),
                    evidence.requiredChecks.map { it.checkId }.toSet())
                assertTrue(evidence.requiredChecks.all { it.passed })
                approval = candidate
                approvalDecision = PreparationApproval.ACCEPT
                return PreparationApproval.ACCEPT
            }
        }

        var roleCalls = 0
        val roles = object : StageRoleExecutor {
            override suspend fun execute(
                request: StageRoleRequest, events: StageRoleEventSink
            ): StageRoleResult {
            roleCalls++
            assertEquals(EngineeringRole.DEVELOPER, request.expectedRole)
            assertTrue(request.requestedTools.isEmpty())
            assertTrue(request.instruction.contains(content.getValue("specs/Feature.md").trim()))
            return StageRoleResult(developer.correlation, EngineeringRole.DEVELOPER,
                developer.agentId,
                StageRoleOutcome.Proposed(
                    """{"files":[{"path":"src/main/Feature.kt","content":"fun feature() = 2\n"},{"path":"src/main/Additional.kt","content":"fun additional() = true\n"}]}"""
                ), StageRoleUsage(1, 250, 70))
            }
        }
        val model = ModelConfig("fake", "fake-v1", "http://localhost/unused",
            "env:FAKE", 4096, 512, setOf("tool_calls"), null, 1000, 1000)
        val events = mutableListOf<PreparationStageEvent>()
        val result = RunDeveloperStageUseCase(
            roles, LinuxStageToolSessionOpener(temp.resolve("locks")), host
        ).execute(DeveloperStageCommand(
            initialGraph, accepted, developerId, ArtifactId("implementation-linux"),
            manifestPath, listOf(
                DeveloperFileTarget(outputPath,
                    sha(content.getValue(outputPath).toByteArray())),
                DeveloperFileTarget(newPath)
            ), "Implement with approved tests", model,
            Instant.now().plusSeconds(50), Cancellation()
        ), StageRoleEventSink { }, PreparationStageEventSink { events.add(it) })

        if (expectCheckFailure) {
            val failed = assertIs<DeveloperStageOutcome.Failed>(result.outcome)
            // The runtime shares this code with sandbox startup failures. In CI
            // the independently required /usr/bin/true success case proves the
            // same sandbox is functional before interpreting this denial.
            assertEquals("CHECK_FAILED_OR_SANDBOX_UNAVAILABLE", failed.code)
            assertTrue(failed.workspaceMayBeDirty)
            assertEquals(StageState.FAILED, host.graph.stages.last().state)
            assertEquals(accepted, host.acceptedIds)
            assertNull(host.approval)
            assertEquals(newSource, Files.readString(root.resolve(outputPath)))
            assertEquals(additional, Files.readString(root.resolve(newPath)))
            assertTrue(Files.exists(root.resolve(manifestPath)))
            paths.forEach { assertEquals(content.getValue(it), Files.readString(root.resolve(it))) }
            assertEquals(1, roleCalls)
            assertEquals(listOf(StageState.RUNNING, StageState.FAILED), events.map { it.state })
            return
        }

        val completed = assertIs<DeveloperStageOutcome.Accepted>(result.outcome)
        assertEquals(1, roleCalls)
        assertEquals(StageState.ACCEPTED, host.graph.stages.last().state)
        assertEquals(sha(Files.readAllBytes(root.resolve(manifestPath))),
            completed.manifest.sha256)
        assertEquals(newSource, Files.readString(root.resolve(outputPath)))
        assertEquals(additional, Files.readString(root.resolve(newPath)))
        paths.forEach { assertEquals(content.getValue(it), Files.readString(root.resolve(it))) }
        assertEquals(setOf("git_diff_check", "offline-test"),
            completed.validation.requiredChecks.map { it.checkId }.toSet())
        assertTrue(completed.validation.requiredChecks.all { it.passed })
        assertEquals(2, completed.changedFiles.size)
        assertEquals(listOf(newPath, outputPath), completed.changedFiles.map { it.path })
        assertEquals(ArtifactApproval.ACCEPTED, completed.manifest.approval)
        assertEquals(ArtifactFreeze.FROZEN, completed.manifest.freeze)
        assertTrue(completed.manifest.id in host.acceptedIds)
        assertEquals(listOf(StageState.RUNNING, StageState.ACCEPTED), events.map { it.state })
        assertEquals(StageGateDecision.Permit, StageGraphPolicy.validate(host.graph))
        // A model cannot satisfy the checks by itself; the trusted Linux port
        // has actually issued and verified matching HMAC-backed receipts.
    }
}
