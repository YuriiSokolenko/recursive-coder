package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Pure, I/O-free boundary regressions for ledger capacities.
 * Physical framing and durable fsync paths remain in the integration suite.
 */
@Tag("unit")
class DurableSessionEventLedgerCapacityUnitTest {
    @Test fun fencedCheckpointCapacityGuardsExactPrefixAndNextFrameBoundary() {
        val limit = DurableSessionEventLedger.MAX_LIVE_CHECKPOINT_LEDGER_BYTES
        val frame = 36L + 100
        DurableSessionEventLedger.requireReopenCheckpointCapacity(limit)
        DurableSessionEventLedger.requireReopenCheckpointCapacity(limit - frame, 100)
        assertEquals("LEDGER_REOPEN_CHECKPOINT_CAPACITY",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireReopenCheckpointCapacity(limit + 1)
            }.code)
        assertEquals("LEDGER_REOPEN_CHECKPOINT_CAPACITY",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireReopenCheckpointCapacity(
                    limit - frame + 1, 100)
            }.code)
        // CREATE_NEW's 32 MiB cap is intentionally unchanged.
        DurableSessionEventLedger.requireAppendCapacity(3, limit + 1, 100)
    }

    @Test fun cumulativeAppendCapacityUsesDistinctEventAndByteLimitCodes() {
        val max = DurableSessionEventLedger.MAX_EVENTS
        // The same capacity guard runs for CREATE_NEW and reopened writers.
        // Test its exact boundary without performing 100,000 fsync operations.
        DurableSessionEventLedger.requireAppendCapacity(max - 1, 7L, 100)
        assertEquals("LEDGER_EVENT_LIMIT_EXCEEDED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireAppendCapacity(max, 7L, 100)
            }.code)
        assertEquals("LEDGER_EVENT_LIMIT_EXCEEDED",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireAppendCapacity(max + 1, 7L, 100)
            }.code)

        val fileLimit = 32L * 1024 * 1024
        val frameBytes = 4L + 32 + 100
        DurableSessionEventLedger.requireAppendCapacity(
            1, fileLimit - frameBytes, 100)
        assertEquals("LEDGER_RECORD_SIZE_INVALID",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireAppendCapacity(
                    1, fileLimit - frameBytes + 1, 100)
            }.code)
        assertEquals("LEDGER_RECORD_SIZE_INVALID",
            assertFailsWith<SessionLedgerUnconfirmed> {
                DurableSessionEventLedger.requireAppendCapacity(1, 7L, 16 * 1024 + 1)
            }.code)
    }
}
