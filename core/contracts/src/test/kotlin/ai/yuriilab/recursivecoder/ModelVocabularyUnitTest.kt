package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class ModelVocabularyUnitTest {
    @Test fun configuredWireIdentifiersRemainStable() {
        assertEquals(listOf("fake", "openai-compatible", "fake-v1"),
            listOf(ModelProviderIds.FAKE, ModelProviderIds.OPENAI_COMPATIBLE, ModelProviderIds.FAKE_MODEL_ID))
        assertEquals(listOf("tool_calls", "tool_choice", "streaming", "structured_outputs", "usage_metadata", "error_metadata"),
            listOf(ModelFeatureIds.TOOL_CALLS, ModelFeatureIds.TOOL_CHOICE, ModelFeatureIds.STREAMING,
                ModelFeatureIds.STRUCTURED_OUTPUTS, ModelFeatureIds.USAGE_METADATA, ModelFeatureIds.ERROR_METADATA))
        assertEquals(listOf("session-events-v1", "fake-model-v1", "model-gateway-v1", "read-only-tools-v1"),
            listOf(SessionCapabilityIds.EVENTS_V1, SessionCapabilityIds.FAKE_MODEL_V1,
                SessionCapabilityIds.MODEL_GATEWAY_V1, SessionCapabilityIds.READ_ONLY_TOOLS_V1))
    }
    @Test
    fun modelConfigMappingPreservesKnownAndUnknownWireIdentifiers() {
        val cases = listOf(
            Triple("fake", "fake-v1", listOf("tool_calls")),
            Triple("openai-compatible", "remote-model", listOf(
                "tool_calls", "tool_choice", "streaming", "structured_outputs",
                "usage_metadata", "error_metadata"
            )),
            Triple("vendor-provider", "vendor-model", listOf("vendor_feature"))
        )
        for ((provider, modelId, features) in cases) {
            // Literal field names and values are independent of the vocabulary.
            val featureJson = features.joinToString(",") { "\"$it\"" }
            val wire = """
                {
                  "provider": "$provider", "modelId": "$modelId",
                  "baseUrl": "http://localhost/v1", "credentialRef": "env:TEST_KEY",
                  "contextTokens": 8192, "maxOutputTokens": 512,
                  "features": [$featureJson]
                }
            """.trimIndent()
            val json = Json.parseToJsonElement(wire).jsonObject
            val expected = ModelConfig(provider, modelId, "http://localhost/v1",
                "env:TEST_KEY", 8192, 512, features.toSet(), null, 3000, 30000)
            assertEquals(expected, ModelConfig.from(json))
            // Mapping is not admission: opaque values must survive JSON round-trip
            // so the policy and gateway can reject unsupported selections.
            assertEquals(expected,
                ModelConfig.from(Json.parseToJsonElement(json.toString()).jsonObject))
        }
    }
}
