package ai.yuriilab.recursivecoder

import java.security.MessageDigest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure comparison of independently host-authenticated receipts. The fixture
 * cannot issue HMAC receipts; LinuxFiveRoleWorkflowHostTest covers that trust
 * boundary with real trusted writes and authenticated checks.
 */
@Tag("unit")
class InstalledRecursiveEvidencePolicyTest {
    private val path = "src/main/FeatureManifest.xml"
    private val bytes = "trusted artifact bytes\n".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private val id = ArtifactId("parent-child-output")
    private val kind = StageKind.DEVELOP
    private val expected = FiveRoleArtifactTarget(id, path)

    private fun receipt(
        origin: StageKind = kind,
        artifactId: ArtifactId = id,
        declaredPath: String = path,
        hash: String = sha,
        writes: Map<String, String> = mapOf(path to sha)
    ): AuthenticatedAcceptedStageReceipt {
        val graphId = GraphId("receipt-graph")
        val runId = StageRunId("receipt-stage")
        return AuthenticatedAcceptedStageReceipt(
            GraphCorrelation("receipt-session", graphId, "receipt-correlation"),
            StageCorrelation("receipt-session", graphId, runId,
                "receipt-request", "receipt-stage-correlation"),
            origin,
            StageArtifactManifest(artifactId,
                requireNotNull(origin.output), declaredPath, hash, origin.role,
                runId, 1, ArtifactApproval.ACCEPTED, ArtifactFreeze.FROZEN),
            "write-authenticated", setOf("git_diff_check", "offline-check"),
            writes, StageRoleUsage(1, 100, 30))
    }

    private fun mismatch(
        parent: AuthenticatedAcceptedStageReceipt = receipt(),
        child: AuthenticatedAcceptedStageReceipt = receipt(),
        integrated: StageArtifactManifest? = child.manifest,
        parentBytes: ByteArray? = bytes,
        childBytes: ByteArray? = bytes,
        role: StageKind = kind
    ): String? = InstalledRecursiveEvidencePolicy.mismatch(
        role, expected, parent, child, integrated, parentBytes, childBytes)

    @Test fun identicalAuthenticatedMetadataAndBytesPassBothOrigins() {
        assertNull(mismatch())
        val sdd = receipt(origin = StageKind.SDD)
        val sddTarget = FiveRoleArtifactTarget(sdd.manifest.id, sdd.manifest.declaredPath)
        assertNull(InstalledRecursiveEvidencePolicy.mismatch(
            StageKind.SDD, sddTarget, sdd, sdd, null, bytes, bytes))
    }

    @Test fun parentAndChildIdOrOriginCannotBeRelabelled() {
        assertEquals("CHILD_ORIGIN_MISMATCH",
            mismatch(child = receipt(origin = StageKind.SDD)))
        assertEquals("CHILD_ARTIFACT_ID_MISMATCH",
            mismatch(parent = receipt(artifactId = ArtifactId("forged-parent"))))
        assertEquals("CHILD_ARTIFACT_ID_MISMATCH",
            mismatch(child = receipt(artifactId = ArtifactId("forged-child"))))
    }

    @Test fun pathAndDigestMustMatchOnBothHostReceipts() {
        assertEquals("CHILD_ARTIFACT_PATH_MISMATCH",
            mismatch(parent = receipt(declaredPath = "src/main/OtherManifest.xml")))
        assertEquals("CHILD_ARTIFACT_PATH_MISMATCH",
            mismatch(child = receipt(declaredPath = "src/main/OtherManifest.xml")))
        assertEquals("CHILD_ARTIFACT_DIGEST_MISMATCH",
            mismatch(child = receipt(hash = "a".repeat(64))))
    }

    @Test fun allWrittenPostimagesMustMatchNotJustTheManifest() {
        assertEquals("CHILD_WRITTEN_POSTIMAGES_MISMATCH",
            mismatch(child = receipt(writes = mapOf(path to sha,
                "src/main/Another.kt" to "b".repeat(64)))))
    }

    @Test fun integratedDeveloperManifestIsIndependentFromAHostReceipt() {
        assertEquals("CHILD_RESULT_NOT_INTEGRATED", mismatch(integrated = null))
        assertEquals("CHILD_INTEGRATED_MANIFEST_MISMATCH",
            mismatch(integrated = receipt(hash = "c".repeat(64)).manifest))
    }

    @Test fun physicalBytesCannotBeMissingDifferentOrDigestMismatched() {
        assertEquals("CHILD_ARTIFACT_BYTES_MISSING",
            mismatch(parentBytes = null))
        assertEquals("CHILD_ARTIFACT_BYTES_MISSING",
            mismatch(childBytes = null))
        assertEquals("CHILD_ARTIFACT_BYTES_MISMATCH",
            mismatch(childBytes = "modified".toByteArray()))
        val forgedDigest = "d".repeat(64)
        assertEquals("CHILD_ARTIFACT_CONTENT_DIGEST_MISMATCH",
            mismatch(parent = receipt(hash = forgedDigest),
                child = receipt(hash = forgedDigest),
                integrated = receipt(hash = forgedDigest).manifest))
    }
}
