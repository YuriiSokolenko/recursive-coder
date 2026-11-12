package ai.yuriilab.recursivecoder

import java.nio.file.Path

/**
 * An explicitly host-opened stage tool surface. No type in this file is a model tool
 * declaration, an authorization token, or a Session Protocol v1 wire message.
 * Koog never receives an opener, a host snapshot, or check receipt material.
 */
sealed interface StageWrite {
    val path: String
    data class Create(override val path: String, val content: String) : StageWrite
    data class Edit(override val path: String, val expectedSha256: String, val content: String) : StageWrite
    data class Delete(override val path: String, val expectedSha256: String) : StageWrite
}

/** Only the host selects an executable and fixed arguments; the model cannot supply either. */
data class StageIsolatedCheck(
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val timeoutMs: Long = 10_000,
    val maxOutputBytes: Int = 32 * 1024
)

/**
 * The host must supply a fresh snapshot on every invocation. Approval IDs, baselines,
 * tool definitions and optional independent revision attestations are host facts.
 * A session is revoked if any of these facts change while it is open.
 */
data class StageToolHostSnapshot(
    val graph: StageGraph,
    val stageRunId: StageRunId,
    val requestId: String,
    val hostAcceptedArtifactIds: Set<ArtifactId>,
    val root: Path,
    val baselineHead: String,
    val branch: String,
    val checks: Map<String, StageIsolatedCheck> = emptyMap(),
    val requiredChecks: Set<String> = setOf("git_diff_check"),
    val revisionAuthorization: StageRevisionAuthorization? = null,
    val maxToolCalls: Int = 64
)

/** Never implement this with values taken from a model response or prompt. */
fun interface StageToolAuthoritySource {
    fun current(): StageToolHostSnapshot?
}

data class StageWriteEvidence(val toolId: String?, val deniedCode: String? = null) {
    val permitted: Boolean get() = toolId != null && deniedCode == null
}

/** Receipt metadata only. The HMAC and runtime-issued receipt remain within infrastructure. */
data class StageCheckEvidence(
    val checkId: String,
    val toolId: String?,
    val baselineHead: String? = null,
    val policyHash: String? = null,
    val manifestHash: String? = null,
    val deniedCode: String? = null
) {
    val passed: Boolean get() = toolId != null && policyHash != null &&
        manifestHash != null && deniedCode == null
}

/**
 * A verified in-process result, not a durable approval or transferable capability.
 * Downstream gates must revalidate stage state and host provenance at commit time.
 */
data class StageValidationEvidence(
    val correlation: StageCorrelation,
    val actor: AgentId,
    val requiredChecks: List<StageCheckEvidence>,
    val successful: Boolean,
    val deniedCode: String? = null
)

interface StageToolSession : AutoCloseable {
    fun write(operations: List<StageWrite>): StageWriteEvidence
    fun runCheck(checkId: String): StageCheckEvidence
    fun verify(): StageValidationEvidence
}

sealed interface StageToolOpenResult {
    data class Granted(val session: StageToolSession) : StageToolOpenResult
    data class Denied(val code: String) : StageToolOpenResult
}

/** Optional trusted port. Never bound by the read-only CLI composition. */
fun interface StageToolSessionOpener {
    fun open(authority: StageToolAuthoritySource): StageToolOpenResult
}
