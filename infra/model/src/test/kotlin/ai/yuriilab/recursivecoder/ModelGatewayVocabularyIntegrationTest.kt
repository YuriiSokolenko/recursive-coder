package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The tier guard restricts explicit Path references in unit fixtures.
// This boundary test constructs a path but performs no filesystem or HTTP I/O.
@Tag("integration")
class ModelGatewayVocabularyIntegrationTest {
    @Test
    fun unknownProviderRefusesNextEvenWithoutPriorNegotiation() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val cancellation = Cancellation(Clock.fixed(now, ZoneOffset.UTC))
        val deadline = now.plusSeconds(60)
        // A valid fake config makes accidental fallback to FakeModel observable.
        val config = ModelConfig(
            "fake", "fake-v1", "http://localhost/v1", "env:TEST_KEY",
            8192, 512, setOf("tool_calls"), null, 1000, 1000
        )
        for (provider in listOf("unknown-provider", "FAKE", "fake-v1")) {
            val gateway = ModelGateways.create(provider)
            val denial = assertFailsWith<Refused> {
                gateway.next(config, Path.of("/task.md"), null,
                    cancellation, deadline, false)
            }
            assertEquals("UNSUPPORTED_MODEL", denial.code)
            assertEquals("provider", denial.category)
        }
    }
}
