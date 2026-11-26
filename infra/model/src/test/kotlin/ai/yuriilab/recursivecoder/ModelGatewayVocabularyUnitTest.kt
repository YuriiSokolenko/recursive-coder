package ai.yuriilab.recursivecoder

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@Tag("unit")
class ModelGatewayVocabularyUnitTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val cancellation = Cancellation(Clock.fixed(now, ZoneOffset.UTC))
    private val deadline = now.plusSeconds(60)

    // Independent literal fixture, deliberately not constructed from the IDs.
    private val config = ModelConfig(
        "fake", "fake-v1", "http://localhost/v1", "env:TEST_KEY",
        8192, 512, setOf("tool_calls"), null, 1000, 1000
    )

    @Test
    fun configuredProviderIdsSelectTheExistingGateways() {
        val fake = assertIs<FakeModel>(ModelGateways.create("fake"))
        assertEquals(GatewayCapabilities(setOf("tool_calls"), 8192, 512),
            fake.negotiate(config, cancellation, deadline))
        // Construction is offline; no HTTP discovery or inference is invoked.
        assertIs<OpenAICompatibleGateway>(ModelGateways.create("openai-compatible"))
    }

    @Test
    fun fakeNegotiationRejectsUnknownOrUnsupportedConfiguration() {
        val fake = ModelGateways.create("fake")
        for (invalid in listOf(
            config.copy(provider = "unknown-provider"),
            config.copy(modelId = "unknown-model"),
            config.copy(features = emptySet()),
            config.copy(features = setOf("tool_calls", "usage_metadata")),
            config.copy(features = setOf("tool_calls", "unknown-feature")),
            config.copy(features = setOf("TOOL_CALLS"))
        )) {
            val denial = assertFailsWith<Refused> {
                fake.negotiate(invalid, cancellation, deadline)
            }
            assertEquals("UNSUPPORTED_MODEL", denial.code)
            assertEquals("provider", denial.category)
        }
    }

    @Test
    fun unknownProviderIsNotSilentlyRoutedToAWorkingGateway() {
        for (provider in listOf("unknown-provider", "FAKE", "fake-v1")) {
            val gateway = ModelGateways.create(provider)
            val negotiation = assertFailsWith<Refused> {
                gateway.negotiate(config, cancellation, deadline)
            }
            assertEquals("UNSUPPORTED_MODEL", negotiation.code)
            assertEquals("provider", negotiation.category)
        }
    }

    @Test
    fun openAICompatibleNegotiationRejectsUnsupportedSelectionBeforeHttp() {
        val gateway = OpenAICompatibleGateway {
            error("Invalid model selection must not reach credential lookup or HTTP")
        }
        val remote = config.copy(
            provider = "openai-compatible", modelId = "remote-model",
            baseUrl = "https://example.invalid/v1",
            features = setOf("tool_calls", "usage_metadata")
        )
        for (invalid in listOf(
            remote.copy(provider = "fake"),
            remote.copy(features = setOf("usage_metadata")),
            remote.copy(features = setOf("tool_calls", "vendor_feature"))
        )) {
            val denial = assertFailsWith<Refused> {
                gateway.negotiate(invalid, cancellation, deadline)
            }
            assertEquals("UNSUPPORTED_MODEL", denial.code)
            assertEquals("provider", denial.category)
            assertEquals("Unsupported model feature selection", denial.explanation)
        }
    }
}
