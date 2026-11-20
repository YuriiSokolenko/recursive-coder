package ai.yuriilab.recursivecoder

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A deliberately narrow, operator-only transition request for a pinned journal
 * containing GENESIS ONLY. No Started/Committed/Unconfirmed effects qualify.
 * This does not authenticate a human; that is a trusted host obligation.
 */
@ResumeOperatorOnlyApi
data class GenesisEffectHeadReconciliation(
    val operatorId: String,
    val operatorApproved: Boolean,
    val expectedCheckpointSha256: String,
    val expectedFenceGeneration: Long,
    val expectedFenceSha256: String,
    val expectedHeadMacSha256: String
)

/** Durable canonical v3 transition with a host-key seal; not human approval proof. */
data class CheckpointEffectReconciliation(
    val sourceCheckpointSha256: String,
    val sourceFenceGeneration: Long,
    val sourceFenceSha256: String,
    val sourceHead: CheckpointEffectJournalHead,
    val operatorId: String,
    val hostSealMacSha256: String
)

/**
 * Host key HMAC domain is separate from the physical journal's HMAC subkey.
 * A checksum or host-rehashable checkpoint SHA alone is not a host seal.
 * Verification is REQUIRED at the explicit recovery admission boundary.
 */
internal object GenesisEffectReconciliationSeal {
    private val SHA = Regex("[0-9a-f]{64}")
    private const val PURPOSE = "recursive-coder/effect-genesis-reconciliation/v1"

    private fun canonical(record: CheckpointEffectReconciliation,
                          session: String, trace: String, policy: String): ByteArray =
        listOf("RCFX-OP-GEN1", record.sourceCheckpointSha256,
            record.sourceFenceGeneration.toString(), record.sourceFenceSha256,
            record.sourceHead.sessionId, record.sourceHead.fenceGeneration.toString(),
            record.sourceHead.admittedCheckpointSha256,
            record.sourceHead.toolAuthoritySha256,
            record.sourceHead.sidecarPathSha256,
            record.sourceHead.lastSequence.toString(), record.sourceHead.headMacSha256,
            session, trace, policy, record.operatorId)
            .joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)

    private fun hmac(key: ByteArray, payload: ByteArray): String {
        if (key.size < 32)
            throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_KEY_INVALID")
        val kdf = Mac.getInstance("HmacSHA256")
        kdf.init(SecretKeySpec(key, "HmacSHA256"))
        val subkey = kdf.doFinal(PURPOSE.toByteArray(StandardCharsets.US_ASCII))
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(subkey, "HmacSHA256"))
            return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
        } finally {
            subkey.fill(0)
        }
    }

    fun seal(key: ByteArray, record: CheckpointEffectReconciliation,
             snapshot: SessionCheckpoint): CheckpointEffectReconciliation =
        record.copy(hostSealMacSha256 = hmac(key, canonical(
            record, snapshot.sessionId, snapshot.traceId, snapshot.binding.policySha256)))

    fun verify(key: ByteArray, record: CheckpointEffectReconciliation,
               snapshot: SessionCheckpoint) {
        if (!SHA.matches(record.hostSealMacSha256) ||
            !MessageDigest.isEqual(
                record.hostSealMacSha256.toByteArray(StandardCharsets.US_ASCII),
                hmac(key, canonical(record, snapshot.sessionId, snapshot.traceId,
                    snapshot.binding.policySha256))
                    .toByteArray(StandardCharsets.US_ASCII)))
            throw SessionResumeUnconfirmed("RESUME_EFFECT_RECONCILIATION_MAC_MISMATCH")
    }
}
