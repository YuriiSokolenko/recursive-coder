package ai.yuriilab.recursivecoder

/**
 * Trusted, session-owned read side of the stage host. An untrusted model or a
 * RecursiveStageExecutor must never implement this interface or construct its
 * accepted-ID set. In particular, a completed child is not automatically approved.
 *
 * Implementations must check live trusted stage approval and workspace receipts,
 * and return immutable snapshots. The scheduler re-reads all evidence at parent
 * completion; external concurrent writers must still be fenced by the host.
 */
interface RecursiveChildIntegrationHost {
    fun acceptedArtifactIds(): Set<ArtifactId>
    fun manifestsFor(child: RecursiveNode): List<StageArtifactManifest>?
    fun readBytes(path: String): ByteArray?
    fun reservedParentArtifacts(parent: RecursiveNode): List<StageArtifactManifest> = emptyList()
}
