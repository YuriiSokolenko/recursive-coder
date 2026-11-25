package ai.yuriilab.recursivecoder

/** Shared diagnostics for trusted write sessions and their workspace adapters. */
internal object WorkspaceWriteDenialCodes {
    const val WRITE_SESSION_INVALID = "WRITE_SESSION_INVALID"
    const val UNSAFE_GIT_INDEX = "UNSAFE_GIT_INDEX"
    const val CHECK_UNAVAILABLE = "CHECK_UNAVAILABLE"
}
