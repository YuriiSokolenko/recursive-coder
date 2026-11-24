package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*

/** The gateway has no authority to execute tools: it may only propose a decision. */
interface ModelGateway {
    fun negotiate(config: ModelConfig, cancellation: Cancellation, deadline: Instant): GatewayCapabilities
    fun next(
        config: ModelConfig,
        task: Path,
        observed: Pair<Int, String>?,
        cancellation: Cancellation,
        deadline: Instant,
        allowRetry: Boolean
    ): ModelTurn
}

data class GatewayCapabilities(val features: Set<String>, val contextTokens: Long, val maxOutputTokens: Long)
data class ModelUsage(val input: Long, val output: Long)
data class ModelTurn(
    val decision: ModelDecision,
    val usage: ModelUsage? = null,
    val attempts: Int = 1,
    val statusCode: Int? = null
)
sealed interface ModelDecision {
    data class Read(val path: Path) : ModelDecision
    data class Done(val bytes: Int, val sha256: String) : ModelDecision
    data object Refusal : ModelDecision
}

/**
 * Only references to credentials are accepted in the request. Neither credentials nor
 * response bodies become diagnostics, exceptions, or event metadata.
 */
data class ModelConfig(
    val provider: String,
    val modelId: String,
    val baseUrl: String,
    val credentialRef: String,
    val contextTokens: Long,
    val maxOutputTokens: Long,
    val features: Set<String>,
    val temperature: Double?,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long
) {
    companion object {
        fun from(model: JsonObject) = ModelConfig(
            provider = model.getValue(ModelConfigFields.PROVIDER).jsonPrimitive.content,
            modelId = model.getValue(ModelConfigFields.MODEL_ID).jsonPrimitive.content,
            baseUrl = model.getValue(ModelConfigFields.BASE_URL).jsonPrimitive.content,
            credentialRef = model.getValue(ModelConfigFields.CREDENTIAL_REF).jsonPrimitive.content,
            contextTokens = model.getValue(ModelConfigFields.CONTEXT_TOKENS).jsonPrimitive.long,
            maxOutputTokens = model.getValue(ModelConfigFields.MAX_OUTPUT_TOKENS).jsonPrimitive.long,
            features = model.getValue(ModelConfigFields.FEATURES).jsonArray.map { it.jsonPrimitive.content }.toSet(),
            temperature = model[ModelConfigFields.TEMPERATURE]?.jsonPrimitive?.double,
            connectTimeoutMs = model[ModelConfigFields.CONNECT_TIMEOUT_MS]?.jsonPrimitive?.long ?: 3000L,
            requestTimeoutMs = model[ModelConfigFields.REQUEST_TIMEOUT_MS]?.jsonPrimitive?.long ?: 30000L
        )
    }
}

/** Configured provider and fake-model identifiers, not HTTP API fields. */
object ModelProviderIds {
    const val FAKE = "fake"
    const val OPENAI_COMPATIBLE = "openai-compatible"
    const val FAKE_MODEL_ID = "fake-v1"
}

/** Model configuration and advertised capability identifiers. */
object ModelFeatureIds {
    const val TOOL_CALLS = "tool_calls"
    const val TOOL_CHOICE = "tool_choice"
    const val STREAMING = "streaming"
    const val STRUCTURED_OUTPUTS = "structured_outputs"
    const val USAGE_METADATA = "usage_metadata"
    const val ERROR_METADATA = "error_metadata"
}
