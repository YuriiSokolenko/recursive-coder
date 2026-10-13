package ai.yuriilab.recursivecoder

/**
 * Protocol negotiation for the first standalone runtime milestone.
 *
 * The JSON Schema accepts forward-compatible 1.x envelopes, but a runtime
 * must explicitly opt into a version before it can process a session.
 * Until native capability negotiation is implemented, only 1.0 is supported.
 */
object SessionProtocol {
    const val VERSION: String = "1.0"

    fun supports(version: String): Boolean = version == VERSION
}

/** Shared terminal Session outcome; JSON strings belong only to boundary mappers. */
enum class SessionOutcome(val wire: String) {
    SUCCEEDED("succeeded"), REJECTED("rejected"), FAILED("failed"),
    CANCELLED("cancelled"), TIMED_OUT("timed_out");

    companion object {
        /** Never quote potentially sensitive untrusted status text in diagnostics. */
        fun fromWire(value: String): SessionOutcome =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unsupported session result status")
    }
}
