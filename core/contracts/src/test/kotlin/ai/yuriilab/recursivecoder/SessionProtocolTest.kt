package ai.yuriilab.recursivecoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("unit")
class SessionProtocolTest {
    @Test
    fun supportsOnlyExplicitlyNegotiatedVersion() {
        assertTrue(SessionProtocol.supports("1.0"))
        assertFalse(SessionProtocol.supports("1.1"))
        assertFalse(SessionProtocol.supports("2.0"))
        assertFalse(SessionProtocol.supports("1.0.0"))
        assertFalse(SessionProtocol.supports(""))
    }

    @Test
    fun invocationVocabularyRetainsV1WireValues() {
        // Independent literals intentionally detect accidental wire changes.
        assertEquals("cli", SessionProtocol.INVOCATION_SOURCE_CLI)
        assertEquals("scheduler", SessionProtocol.INVOCATION_SOURCE_SCHEDULER)
        assertEquals(1L, SessionProtocol.FIRST_INVOCATION_ATTEMPT)
    }

    @Test
    fun terminalResultCodecRejectsUnknownStatusesSafely() {
        val expected = mapOf(
            "succeeded" to SessionOutcome.SUCCEEDED,
            "rejected" to SessionOutcome.REJECTED,
            "failed" to SessionOutcome.FAILED,
            "cancelled" to SessionOutcome.CANCELLED,
            "timed_out" to SessionOutcome.TIMED_OUT
        )
        expected.forEach { (wire, outcome) ->
            assertEquals(outcome, SessionOutcome.fromWire(wire))
            assertEquals(wire, outcome.wire)
        }
        for (invalid in listOf("", "SUCCESS", "success", "unknown", " succeeded",
            "timed-out", "secret\\ncontent")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                SessionOutcome.fromWire(invalid)
            }
            assertEquals("Unsupported session result status", failure.message)
        }
    }
}
