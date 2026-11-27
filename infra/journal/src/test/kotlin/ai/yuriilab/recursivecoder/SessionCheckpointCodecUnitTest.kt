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

    // Independent literal field order and spellings, not the production codec.
    private fun goldenV1(): String =
        """{"kind":"host-checkpoint","version":1,"sequence":1,"previousSha256":null,""" +
        """"binding":{"policySha256":"$a","workspaceIdentitySha256":"$b",""" +
        """"gitHead":"${"f".repeat(40)}","gitBranch":"refs/heads/dev",""" +
        """"gitBaselineSha256":"$c","graphId":"graph-root","graphGeneration":1,""" +
        """"frozenInputsSha256":"$a"},"sessionId":"sess-one","traceId":"trace-one",""" +
        """"phase":"running","taskId":"task-one","taskAttempt":1,"taskState":"running",""" +
        """"children":[{"id":"child-a","parentId":"graph-root","state":"succeeded"},""" +
        """{"id":"child-b","parentId":"child-a","state":"ready"}],""" +
        """"acceptedArtifacts":[{"id":"artifact-one","ownerId":"child-a","sha256":"$b"}],""" +
        """"hostAssertedUsage":[{"scopeId":"child-a","kind":"model","certainty":"known","units":42},""" +
        """{"scopeId":"task-one","kind":"tool","certainty":"unknown","units":null}],""" +
        """"pendingEffects":[{"id":"effect-one","correlationId":"req-one","state":"unknown"}],""" +
        """"journalSequence":3,"journalHeadSha256":"$c","journalFileSha256":"$b",""" +
        """"lastTrustedJournalOffset":2048}"""

    @Test fun canonicalRoundTripAndSortingAreDeterministic() {
        val original = snapshot()
        val canonical = SessionCheckpointCodec.encode(original)
        assertContentEquals(goldenV1().toByteArray(StandardCharsets.UTF_8), canonical)
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
                SessionCheckpointCodec.decode(text.replace("\"version\":1", "\"version\":4")
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
        assertEquals("succeeded", SessionPhaseVocabulary.succeededWire)
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

    @Test fun checkpointVersionsAndEffectHeadFieldOrderRemainStable() {
        val s = snapshot().copy(phase = "accepted", taskAttempt = 0,
            pendingEffects = emptyList())
        val v1 = goldenV1().replace("\"phase\":\"running\"", "\"phase\":\"accepted\"")
            .replace("\"taskAttempt\":1", "\"taskAttempt\":0")
            .replace("""[{"id":"effect-one","correlationId":"req-one","state":"unknown"}]""", "[]")
        assertContentEquals(v1.toByteArray(StandardCharsets.UTF_8),
            SessionCheckpointCodec.encode(s))
        val head = CheckpointEffectJournalHead("sess-one", 1L, a, b, c, 0L, a)
        val headJson = """{"sessionId":"sess-one","fenceGeneration":1,""" +
            """"admittedCheckpointSha256":"$a","toolAuthoritySha256":"$b",""" +
            """"sidecarPathSha256":"$c","lastSequence":0,"headMacSha256":"$a"}"""
        val v2 = v1.replace("\"version\":1", "\"version\":2").dropLast(1) +
            ",\"effectJournalHead\":$headJson}"
        assertContentEquals(v2.toByteArray(StandardCharsets.UTF_8),
            SessionCheckpointCodec.encode(s.copy(effectJournalHead = head)))
        val record = CheckpointEffectReconciliation(a, 1L, b, head, "operator-one", c)
        val recordJson = """{"sourceCheckpointSha256":"$a","sourceFenceGeneration":1,""" +
            """"sourceFenceSha256":"$b","operatorId":"operator-one",""" +
            """"hostSealMacSha256":"$c","sourceHead":$headJson}"""
        val v3 = v1.replace("\"version\":1", "\"version\":3").dropLast(1) +
            ",\"effectJournalHead\":null,\"effectReconciliation\":$recordJson}"
        val encoded = SessionCheckpointCodec.encode(s.copy(effectReconciliation = record))
        assertContentEquals(v3.toByteArray(StandardCharsets.UTF_8), encoded)
        assertEquals(record, SessionCheckpointCodec.decode(encoded).effectReconciliation)
        assertEquals(head, SessionCheckpointCodec.decode(
            v2.toByteArray(StandardCharsets.UTF_8)).effectJournalHead)
        for (version in listOf(0, 4, 99)) {
            assertEquals("CHECKPOINT_VERSION_UNSUPPORTED",
                assertFailsWith<CheckpointUnconfirmed> {
                    SessionCheckpointCodec.decode(v1.replace("\"version\":1",
                        "\"version\":$version").toByteArray(StandardCharsets.UTF_8))
                }.code)
        }
    }

    @Test fun checkpointEffectHeadUsesPhysicalRecordCapacity() {
        val s = snapshot().copy(phase = "accepted", taskAttempt = 0,
            pendingEffects = emptyList())
        val head = CheckpointEffectJournalHead("sess-one", 1L, a, b, c, 100_000L, a)
        assertEquals(100_000L, SessionCheckpointCodec.decode(
            SessionCheckpointCodec.encode(s.copy(effectJournalHead = head)))
            .effectJournalHead?.lastSequence)
        assertEquals("CHECKPOINT_PAYLOAD_INVALID",
            assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.encode(s.copy(
                    effectJournalHead = head.copy(lastSequence = 100_001L)))
            }.code)
    }

    @Test fun checkpointClosedStateAndUsageSetsRemainCompatible() {
        val s = snapshot()
        for (state in listOf("created", "ready", "blocked", "running", "paused",
                "succeeded", "failed", "needs-attention", "cancelled")) {
            assertEquals(state, SessionCheckpointCodec.decode(
                SessionCheckpointCodec.encode(s.copy(taskState = state))).taskState)
        }
        for (state in RecursiveNodeState.entries.map { it.name.lowercase() }) {
            val candidate = s.copy(children = listOf(
                CheckpointChild("child-a", "graph-root", state)))
            assertEquals(state, SessionCheckpointCodec.decode(
                SessionCheckpointCodec.encode(candidate)).children.single().state)
        }
        for (kind in listOf("model", "tool")) {
            for ((certainty, units) in listOf("known" to 1L, "unknown" to null)) {
                val usage = HostAssertedCheckpointUsage("task-one", kind, certainty, units)
                assertEquals(listOf(usage), SessionCheckpointCodec.decode(
                    SessionCheckpointCodec.encode(s.copy(
                        hostAssertedUsage = listOf(usage)))).hostAssertedUsage)
            }
        }
        val invalid = listOf(s.copy(taskState = "future-state"),
            s.copy(children = listOf(CheckpointChild("child-a", "graph-root", "paused"))),
            s.copy(hostAssertedUsage = listOf(
                HostAssertedCheckpointUsage("task-one", "future-kind", "known", 1))),
            s.copy(hostAssertedUsage = listOf(
                HostAssertedCheckpointUsage("task-one", "model", "future-certainty", null))))
        for (candidate in invalid) {
            assertEquals("CHECKPOINT_PAYLOAD_INVALID", assertFailsWith<CheckpointUnconfirmed> {
                SessionCheckpointCodec.encode(candidate)
            }.code)
        }
    }
}
