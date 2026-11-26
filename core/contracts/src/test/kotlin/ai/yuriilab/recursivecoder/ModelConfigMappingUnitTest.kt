package ai.yuriilab.recursivecoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class ModelConfigMappingUnitTest {
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
            // Preserve opaque values for gateway validation; session admission
            // independently extracts its facts from the raw request.
            assertEquals(expected, ModelConfig.from(json))
        }
    }
}
