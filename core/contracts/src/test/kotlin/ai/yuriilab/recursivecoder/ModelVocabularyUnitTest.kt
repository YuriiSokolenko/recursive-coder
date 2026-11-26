package ai.yuriilab.recursivecoder

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
}
