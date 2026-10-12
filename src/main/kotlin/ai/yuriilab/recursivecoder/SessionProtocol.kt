package ai.yuriilab.recursivecoder

/**
 * Protocol negotiation for the first standalone runtime milestone.
 *
 * The JSON Schema accepts forward-compatible 1.x envelopes, but a runtime
 * must explicitly opt into a version before it can process a session.
 * Until #785 implements capability negotiation, only 1.0 is supported.
 */
object SessionProtocol {
    const val VERSION: String = "1.0"

    fun supports(version: String): Boolean = version == VERSION
}
