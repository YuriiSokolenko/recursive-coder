package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Pure effect envelope, projection and probe-boundary regressions. */
@Tag("unit")
class EffectEvidenceProjectionUnitTest {
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)
    private val expected = EffectIdentity("sess-one", "effect-one", 1L,
        EffectKind.TRUSTED_WRITE, digestA)

    private fun event(
        sequence: Int, correlation: String, status: String,
        metadata: JsonObject, kind: String = "checkpoint.saved"
    ): JsonObject = buildJsonObject {
        put("type", kind)
        put("status", status)
        put("sessionId", "sess-one")
        put("sequence", sequence)
        put("metadata", metadata)
    }

    private fun physical(vararg records: EffectRecord) =
        VerifiedEffectRecordJournal(
            EffectJournalHeader("sess-one", 1L, digestA, digestB),
            records.mapIndexed { i, record ->
                EffectJournalEntry(i.toLong() + 1L, record,
                    ('c' + i).toString().repeat(64))
            },
            records.size.toLong(), "c".repeat(64), "d".repeat(64),
            500L, 500L
        )

    private fun effectEvent(eventSequence: Int, physicalSequence: Long,
                            record: EffectRecord, mac: String): JsonObject {
        val state = when (record) {
            is EffectRecord.Intent -> "intent"
            is EffectRecord.Started -> "started"
            is EffectRecord.Committed -> "committed"
            is EffectRecord.Unconfirmed -> "unconfirmed"
        }
        val metadata = buildJsonObject {
            put("effectOperationId", record.identity.operationId)
            put("effectFenceGeneration", record.identity.fenceGeneration)
            put("effectKind", record.identity.kind.name)
            put("effectInputSha256", record.identity.inputSha256)
            put("effectRecordSequence", physicalSequence)
            put("effectRecordMacSha256", mac)
            put("effectState", state)
            if (record is EffectRecord.Committed)
                put("effectObservedStateSha256", record.observedStateSha256)
            if (record is EffectRecord.Unconfirmed)
                put("effectErrorCode", record.code)
        }
        return event(eventSequence, "req-effect", when (state) {
            "intent", "started" -> "started"
            "committed" -> "completed"
            else -> "failed"
        }, metadata, when (state) {
            "intent", "started" -> "tool.started"
            "committed" -> "tool.completed"
            else -> "session.error"
        })
    }

    @Test fun completeEffectProjectionMatchesEveryPhysicalRecordAndSessionEvent() {
        val records = arrayOf(
            EffectRecord.Intent(expected), EffectRecord.Started(expected),
            EffectRecord.Committed(expected, digestB))
        val physical = physical(*records)
        val history = listOf(
            event(1, "req-one", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "checkpoint.saved"),
            effectEvent(2, 1L, records[0], "c".repeat(64)),
            JsonObject(event(3, "req-two", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "checkpoint.saved").toMutableMap().also { it.remove("metadata") }),
            effectEvent(4, 2L, records[1], "d".repeat(64)),
            effectEvent(5, 3L, records[2], "e".repeat(64))
        )
        val result = assertNotNull(EffectEvidenceProjection.fromSessionLedger(history, physical))
        assertEquals(listOf(2L, 4L, 5L), result.map { it.sequence })
        assertEquals(records.toList(), result.map { it.record })
        assertEquals(listOf("c", "d", "e"), result.map { it.hostMacSha256.take(1) })
        // Missing a single ledger effect does not become an implicit no-op.
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history.filterIndexed { i, _ -> i != 3 }, physical))
        // A correct-looking but wrong MAC must not match the physical prefix.
        val wrongMac = history.toMutableList().also {
            it[4] = effectEvent(5, 3L, records[2], "f".repeat(64))
        }
        assertNull(EffectEvidenceProjection.fromSessionLedger(wrongMac, physical))
        // A second record with the same sequence is not authoritative.
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history + effectEvent(6, 3L, records[2], "e".repeat(64)), physical))
        // Invalid metadata must not be silently skipped as a non-effect event.
        val partial = history.toMutableList().also {
            it[2] = event(3, "req-two", "completed", buildJsonObject {
                put("effectOperationId", "effect-one")
            }, "checkpoint.saved")
        }
        assertNull(EffectEvidenceProjection.fromSessionLedger(partial, physical))
        // Unmarked tool events may be mutating and therefore fail closed.
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history + event(6, "req-three", "completed", buildJsonObject {
                put("graphId", "graph-root")
            }, "tool.completed"), physical))
    }

    @Test fun unconfirmedAndTornPhysicalHistoriesNeverProjectAsComplete() {
        val intent = EffectRecord.Intent(expected)
        val physical = physical(intent, EffectRecord.Unconfirmed(expected, "HOST_ERROR"))
        val history = listOf(
            effectEvent(1, 1L, intent, "c".repeat(64)),
            effectEvent(2, 2L, physical.entries.last().record, "d".repeat(64))
        )
        assertEquals(2, EffectEvidenceProjection.fromSessionLedger(history, physical)?.size)
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history, physical.copy(tornTailOffset = 500L,
                tailKind = EffectJournalTailKind.SHORT_FRAME)))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history, physical.copy(lastSequence = 1L)))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            history, physical.copy(entries = physical.entries.take(1))))
    }

    @Test fun wrongEventTypeStatusFenceAndRecordOrderAreDenied() {
        val intent = EffectRecord.Intent(expected)
        val started = EffectRecord.Started(expected)
        val committed = EffectRecord.Committed(expected, digestB)
        val physical = physical(intent, started, committed)
        val events = listOf(
            effectEvent(1, 1L, intent, "c".repeat(64)),
            effectEvent(2, 2L, started, "d".repeat(64)),
            effectEvent(3, 3L, committed, "e".repeat(64)))
        assertNotNull(EffectEvidenceProjection.fromSessionLedger(events, physical))
        fun replaced(index: Int, event: JsonObject): List<JsonObject> =
            events.toMutableList().also { it[index] = event }
        fun field(event: JsonObject, key: String, value: JsonElement): JsonObject =
            JsonObject(event.toMutableMap().also { it[key] = value })
        fun metadata(event: JsonObject, key: String, value: JsonElement): JsonObject {
            val m = event["metadata"] as JsonObject
            return field(event, "metadata",
                JsonObject(m.toMutableMap().also { it[key] = value }))
        }
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            replaced(0, field(events[0], "type", JsonPrimitive("checkpoint.saved"))),
            physical))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            replaced(2, field(events[2], "status", JsonPrimitive("failed"))),
            physical))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            replaced(1, metadata(events[1], "effectFenceGeneration", JsonPrimitive(2L))),
            physical))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            replaced(1, metadata(events[1], "effectiveUnrelatedKey", JsonPrimitive("x"))),
            physical))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            listOf(events[1], events[0], events[2]), physical))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            listOf(events[0], events[2]), physical))
        assertNull(EffectEvidenceProjection.fromSessionLedger(
            events.reversed(), physical))
    }

    @Test fun projectionReportsStableRefusalReasonsWithoutExposingPayload() {
        val intent = EffectRecord.Intent(expected)
        val started = EffectRecord.Started(expected)
        val physical = physical(intent, started)
        val good = listOf(
            effectEvent(1, 1L, intent, "c".repeat(64)),
            effectEvent(2, 2L, started, "d".repeat(64)))
        fun deny(events: List<JsonObject>,
                 evidence: VerifiedEffectRecordJournal = physical): String =
            assertIs<EffectEvidenceProjection.Result.Denied>(
                EffectEvidenceProjection.inspect(events, evidence)).code
        assertEquals("PROJECTION_EXTRA_PHYSICAL", deny(good.take(1)))
        assertEquals("PROJECTION_EXTRA_EVENT", deny(
            good + effectEvent(3, 3L, started, "d".repeat(64))))
        assertEquals("PROJECTION_UNMARKED_TOOL_EVENT", deny(listOf(
            event(1, "req", "started", buildJsonObject {
                put("graphId", "graph-root")
            }, "tool.started"))))
        assertEquals("PROJECTION_MAC_MISMATCH", deny(listOf(
            effectEvent(1, 1L, intent, "f".repeat(64)), good.last())))
        assertEquals("PROJECTION_ENVELOPE_INVALID", deny(listOf(
            event(1, "req", "completed", good.first()["metadata"] as JsonObject,
                "checkpoint.saved"), good.last())))
        assertEquals("PROJECTION_CONTEXT_MISMATCH", deny(listOf(
            effectEvent(1, 1L, intent.copy(identity =
                expected.copy(fenceGeneration = 2L)), "c".repeat(64)),
            good.last())))
        assertEquals("PROJECTION_PHYSICAL_INCOMPLETE", deny(good,
            physical.copy(tornTailOffset = 500L,
                tailKind = EffectJournalTailKind.SHORT_FRAME)))
        assertEquals("PROJECTION_RECORD_MISMATCH", deny(listOf(
            effectEvent(1, 1L, started, "c".repeat(64)), good.last())))
        assertEquals("PROJECTION_SEQUENCE_MISMATCH", deny(listOf(
            effectEvent(1, 2L, intent, "c".repeat(64)), good.last())))
    }

    @Test fun envelopePolicyReservesEffectPrefixAndExactEventPairs() {
        val intent = EffectRecord.Intent(expected)
        val committed = EffectRecord.Committed(expected, digestB)
        val unconfirmed = EffectRecord.Unconfirmed(expected, "HOST_IO_UNAVAILABLE")
        val good = listOf(
            Triple("tool.started", "started",
                effectEvent(1, 1L, intent, "c".repeat(64))["metadata"] as JsonObject),
            Triple("tool.completed", "completed",
                effectEvent(2, 2L, committed, "d".repeat(64))["metadata"] as JsonObject),
            Triple("session.error", "failed",
                effectEvent(3, 3L, unconfirmed, "e".repeat(64))["metadata"] as JsonObject))
        good.forEach { (type, status, meta) ->
            assertTrue(EffectEvidenceEnvelopePolicy.hasReservedKey(meta))
            assertTrue(EffectEvidenceEnvelopePolicy.validForEvent(type, status, meta))
            assertFalse(EffectEvidenceEnvelopePolicy.validForEvent("checkpoint.saved", status, meta))
            assertFalse(EffectEvidenceEnvelopePolicy.validForEvent(type, "denied", meta))
            assertFalse(EffectEvidenceEnvelopePolicy.validForEvent(type, "failed", meta)
                && status != "failed")
        }
        val full = good[0].third
        assertFalse(EffectEvidenceEnvelopePolicy.validForEvent(
            "tool.started", "started", JsonObject(full.toMutableMap().also {
                it.remove("effectRecordMacSha256")
            })))
        assertFalse(EffectEvidenceEnvelopePolicy.validForEvent(
            "tool.started", "started", JsonObject(full.toMutableMap().also {
                it["effectiveUnrelatedKey"] = JsonPrimitive("collision")
            })))
        assertFalse(EffectEvidenceEnvelopePolicy.validForEvent(
            "tool.started", "started", JsonObject(full.toMutableMap().also {
                it["graphId"] = JsonPrimitive("graph-root")
            })))
    }
}
