package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-module compatibility oracle at the existing CLI composition boundary.
 * Neither journal internals nor the task codec supplies the expected literals.
 */
@Tag("unit")
class CheckpointTaskStateVocabularyUnitTest {
    private val digest = "a".repeat(64)
    private val gitHead = "f".repeat(40)

    // Independent durable v1 fixture, including field order and wire spellings.
    private fun canonical(
        taskState: String,
        phase: String = "running",
        childState: String = "ready"
    ): ByteArray = (
        """{"kind":"host-checkpoint","version":1,"sequence":1,"previousSha256":null,""" +
        """"binding":{"policySha256":"$digest","workspaceIdentitySha256":"$digest",""" +
        """"gitHead":"$gitHead","gitBranch":"refs/heads/dev","gitBaselineSha256":"$digest",""" +
        """"graphId":"graph-root","graphGeneration":1,"frozenInputsSha256":"$digest"},""" +
        """"sessionId":"sess-one","traceId":"trace-one","phase":"$phase",""" +
        """"taskId":"task-one","taskAttempt":1,"taskState":"$taskState",""" +
        """"children":[{"id":"child-one","parentId":"graph-root","state":"$childState"}],""" +
        """"acceptedArtifacts":[],"hostAssertedUsage":[],"pendingEffects":[],""" +
        """"journalSequence":1,"journalHeadSha256":"$digest","journalFileSha256":"$digest",""" +
        """"lastTrustedJournalOffset":2048}"""
    ).encodeToByteArray()

    private fun snapshot(
        taskState: String,
        phase: String = "running",
        childState: String = "ready"
    ) = SessionCheckpoint(
        sequence = 1,
        previousSha256 = null,
        binding = CheckpointBinding(
            digest, digest, gitHead, "refs/heads/dev", digest, "graph-root", 1, digest
        ),
        sessionId = "sess-one",
        traceId = "trace-one",
        phase = phase,
        taskId = "task-one",
        taskAttempt = 1,
        taskState = taskState,
        children = listOf(CheckpointChild("child-one", "graph-root", childState)),
        acceptedArtifacts = emptyList(),
        hostAssertedUsage = emptyList(),
        pendingEffects = emptyList(),
        journalSequence = 1,
        journalHeadSha256 = digest,
        journalFileSha256 = digest,
        lastTrustedJournalOffset = 2048
    )

    @Test
    fun allTaskStateWiresMatchIndependentCheckpointV1Bytes() {
        val expected = setOf(
            "created", "ready", "blocked", "running", "paused",
            "succeeded", "failed", "needs-attention", "cancelled"
        )
        assertEquals(expected.size, TaskState.entries.size)
        assertEquals(expected, TaskState.entries.map { it.wire }.toSet())

        // A nonterminal session phase isolates task vocabulary from terminal policy.
        for (state in TaskState.entries) {
            val wire = state.wire
            assertEquals(state, TaskState.fromWire(wire))
            val candidate = snapshot(wire)
            val bytes = canonical(wire)
            assertContentEquals(bytes, SessionCheckpointCodec.encode(candidate), wire)
            assertEquals(candidate, SessionCheckpointCodec.decode(bytes), wire)
        }
    }

    @Test
    fun unknownAndForeignTaskWiresAreRejectedByBothOwners() {
        for (wire in listOf(
            "", "READY", "running ", "needs_attention", "unknown", "pending",
            "accepted", "rejected", "timed_out"
        )) {
            assertFailsWith<IllegalStateException>(wire) { TaskState.fromWire(wire) }
            assertCheckpointRejected(wire)
        }
    }

    @Test
    fun taskSessionAndChildVocabulariesRemainSeparate() {
        // These belong to the other state dimensions, not TaskState.
        for ((phase, child) in listOf("accepted" to "ready", "running" to "timed_out")) {
            val candidate = snapshot("running", phase, child)
            val bytes = canonical("running", phase, child)
            assertContentEquals(bytes, SessionCheckpointCodec.encode(candidate))
            assertEquals(candidate, SessionCheckpointCodec.decode(bytes))
        }

        // Task-only values must not leak into the session or child allowlists.
        for (wire in listOf("blocked", "paused", "needs-attention")) {
            assertCheckpointRejected("running", phase = wire)
            assertCheckpointRejected("running", childState = wire)
        }
    }

    @Test
    fun terminalSessionPhaseStillRestrictsOtherwiseValidTaskStates() {
        for (wire in listOf("created", "ready", "blocked", "running", "paused")) {
            assertCheckpointRejected(wire, phase = "failed")
        }
        for (wire in listOf("succeeded", "failed", "needs-attention", "cancelled")) {
            val candidate = snapshot(wire, phase = "failed")
            val bytes = canonical(wire, phase = "failed")
            assertContentEquals(bytes, SessionCheckpointCodec.encode(candidate), wire)
            assertEquals(candidate, SessionCheckpointCodec.decode(bytes), wire)
        }
    }

    private fun assertCheckpointRejected(
        taskState: String,
        phase: String = "running",
        childState: String = "ready"
    ) {
        val label = "task=$taskState phase=$phase child=$childState"
        assertEquals("CHECKPOINT_PAYLOAD_INVALID", assertFailsWith<CheckpointUnconfirmed>(label) {
            SessionCheckpointCodec.encode(snapshot(taskState, phase, childState))
        }.code, label)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID", assertFailsWith<CheckpointUnconfirmed>(label) {
            SessionCheckpointCodec.decode(canonical(taskState, phase, childState))
        }.code, label)
    }
}
