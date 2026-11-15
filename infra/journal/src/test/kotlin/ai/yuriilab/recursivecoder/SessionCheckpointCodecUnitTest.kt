package ai.yuriilab.recursivecoder

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

@Tag("unit")
class SessionCheckpointCodecUnitTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    private fun snapshot() = SessionCheckpoint(
        sequence = 1,
        previousSha256 = null,
        binding = CheckpointBinding(a, b, "f".repeat(40), "refs/heads/dev", c,
            "graph-root", 1, a),
        sessionId = "sess-one",
        traceId = "trace-one",
        phase = "running",
        taskId = "task-one",
        taskAttempt = 1,
        taskState = "running",
        children = listOf(
            CheckpointChild("child-b", "child-a", "ready"),
            CheckpointChild("child-a", "graph-root", "succeeded")
        ),
        acceptedArtifacts = listOf(CheckpointArtifact("artifact-one", "child-a", b)),
        hostAssertedUsage = listOf(
            HostAssertedCheckpointUsage("task-one", "tool", "unknown", null),
            HostAssertedCheckpointUsage("child-a", "model", "known", 42)
        ),
        pendingEffects = listOf(CheckpointEffect("effect-one", "req-one", "unknown")),
        journalSequence = 3,
        journalHeadSha256 = c,
        journalFileSha256 = b,
        lastTrustedJournalOffset = 2048
    )

    @Test fun canonicalRoundTripAndSortingAreDeterministic() {
        val original = snapshot()
        val canonical = SessionCheckpointCodec.encode(original)
        assertEquals(original.copy(
            children = original.children.sortedBy { it.id },
            hostAssertedUsage = original.hostAssertedUsage.sortedWith(compareBy({ it.scopeId }, { it.kind }))),
            SessionCheckpointCodec.decode(canonical))
        assertContentEquals(canonical, SessionCheckpointCodec.encode(original.copy(
            children = original.children.reversed(),
            hostAssertedUsage = original.hostAssertedUsage.reversed())))
        assertEquals(64, SessionCheckpointCodec.sha256(canonical).length)
        assertTrue(canonical.toString(StandardCharsets.UTF_8).contains("\"units\":null"))
    }

    @Test fun rejectsUnknownFieldsAndNonCanonicalPayloads() {
        val text = SessionCheckpointCodec.encode(snapshot()).toString(StandardCharsets.UTF_8)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.decode(text.replace(
                    "\"kind\":\"host-checkpoint\"",
                    "\"kind\":\"host-checkpoint\",\"rawPrompt\":\"do-not-store-me\"")
                    .toByteArray(StandardCharsets.UTF_8))
            }.code)
        assertEquals("CHECKPOINT_NONCANONICAL",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.decode((" " + text).toByteArray(StandardCharsets.UTF_8))
            }.code)
        assertEquals("CHECKPOINT_VERSION_UNSUPPORTED",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.decode(text.replace("\"version\":1", "\"version\":2")
                    .toByteArray(StandardCharsets.UTF_8))
            }.code)
    }

    @Test fun unknownUsageIsNotSilentlyConvertedIntoZero() {
        val s = snapshot()
        assertNull(SessionCheckpointCodec.decode(SessionCheckpointCodec.encode(s))
            .hostAssertedUsage.single { it.kind == "tool" }.units)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.encode(s.copy(hostAssertedUsage = listOf(
                    HostAssertedCheckpointUsage("task-one", "tool", "unknown", 0))))
            }.code)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.encode(s.copy(hostAssertedUsage = listOf(
                    HostAssertedCheckpointUsage("task-one", "model", "known", null))))
            }.code)
    }

    @Test fun invalidDagDigestsAndUnsafeStatesFailBeforeSerialization() {
        val s = snapshot()
        val invalid = listOf(
            s.copy(children = listOf(CheckpointChild("child-a", "child-b", "running"),
                CheckpointChild("child-b", "child-a", "running"))),
            s.copy(acceptedArtifacts = listOf(CheckpointArtifact("artifact", "missing", b))),
            s.copy(binding = s.binding.copy(gitHead = "main")),
            s.copy(binding = s.binding.copy(graphGeneration = -1)),
            s.copy(taskAttempt = -1),
            s.copy(pendingEffects = listOf(CheckpointEffect("effect", "request", "accepted"))),
            s.copy(previousSha256 = b),
            s.copy(sequence = 2)
        )
        invalid.forEach { candidate ->
            assertEquals("CHECKPOINT_PAYLOAD_INVALID",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointCodec.encode(candidate)
                }.code)
        }
    }

    @Test fun phaseVocabularyIsDerivedFromActualSessionLifecycle() {
        assertEquals(setOf("created", "accepted", "running", "cancelled",
            "succeeded", "rejected", "failed"), SessionPhaseVocabulary.wires)
        assertEquals(setOf("succeeded", "rejected", "failed"),
            SessionPhaseVocabulary.terminalWires)
        for (wire in SessionPhaseVocabulary.wires) {
            val candidate = snapshot().copy(
                phase = wire,
                taskState = if (wire in SessionPhaseVocabulary.terminalWires) "failed" else "running")
            if (wire == "succeeded") {
                assertEquals("CHECKPOINT_PAYLOAD_INVALID",
                    assertFailsWith<CheckpointUnconfirmed> {
                        SessionCheckpointCodec.encode(candidate)
                    }.code)
            } else {
                assertEquals(wire, SessionCheckpointCodec.decode(
                    SessionCheckpointCodec.encode(candidate)).phase)
            }
        }
        for (wire in listOf("checkpointing", "paused", "timed_out")) {
            assertEquals("CHECKPOINT_PAYLOAD_INVALID",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointCodec.encode(snapshot().copy(phase = wire))
                }.code)
        }
    }

    @Test fun terminalPhaseCannotMasqueradeAsRunningTaskOrSuccessfulUncertainWork() {
        val s = snapshot()
        val invalid = listOf(
            s.copy(phase = "failed", taskState = "running"),
            s.copy(phase = "succeeded", taskState = "running"),
            s.copy(phase = "succeeded", taskState = "succeeded"),
            s.copy(phase = "succeeded", taskState = "succeeded",
                pendingEffects = emptyList())
        )
        invalid.forEach {
            assertEquals("CHECKPOINT_PAYLOAD_INVALID",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointCodec.encode(it)
                }.code)
        }
        val settled = s.copy(phase = "succeeded", taskState = "succeeded",
            pendingEffects = emptyList(),
            hostAssertedUsage = listOf(HostAssertedCheckpointUsage("task-one", "tool", "known", 1)))
        assertEquals("succeeded", SessionCheckpointCodec.decode(
            SessionCheckpointCodec.encode(settled)).phase)
    }

    @Test fun taskAndGraphIdsMayNotCollide() {
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.encode(snapshot().copy(taskId = "graph-root"))
            }.code)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.encode(snapshot().copy(children = listOf(
                    CheckpointChild("task-one", "graph-root", "ready"))))
            }.code)
    }

    @Test fun graphAndArtifactListsAreBoundedAndTypesAreStrict() {
        val s = snapshot()
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.encode(s.copy(acceptedArtifacts =
                    listOf(CheckpointArtifact("artifact-one", "child-a", "not-a-digest"))))
            }.code)
        val text = SessionCheckpointCodec.encode(s).toString(StandardCharsets.UTF_8)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.decode(text.replace("\"taskAttempt\":1", "\"taskAttempt\":\"1\"")
                    .toByteArray(StandardCharsets.UTF_8))
            }.code)
    }
}
