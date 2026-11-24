package ai.yuriilab.recursivecoder

import java.nio.file.Path

/**
 * Trusted, deny-only filesystem preflight for Session Protocol v1 resume
 * requests. It uses the SAME allowlist/lookup semantics as real fenced
 * admission, but deliberately does not call admit(), take a lease, or mint
 * a fence. This is not a general crash-resume implementation.
 *
 * Only structured code + sanitized volume detail cross into core. Never
 * expose the original exception, its message, cause, stack or suppressed
 * failures to a model or protocol result.
 */
@ResumeOperatorOnlyApi
class ResumeRuntimeFilesystemPreflight private constructor(
    private val fileStoreType: (Path) -> String
) : SessionResumePreflight {
    override fun deny(request: SessionRequest): SessionResumePreflightDenial =
        try {
            ResumeLocalFileStorePolicy.requireSupportedPaths(
                request.output("checkpointDir"), request.output("eventsPath"),
                fileStoreType)
            SessionResumePreflightDenial.NotEnabled
        } catch (failure: SessionResumeUnconfirmed) {
            if (failure.code != ResumeDenialCodes.RESUME_FILESYSTEM_UNSUPPORTED)
                SessionResumePreflightDenial.Unavailable
            else {
                val detail = failure.detail
                // The core type validates the exact limited wire grammar.
                try {
                    if (detail == null) SessionResumePreflightDenial.Unavailable
                    else SessionResumePreflightDenial.UnsupportedFilesystem(detail)
                } catch (_: IllegalArgumentException) {
                    SessionResumePreflightDenial.Unavailable
                }
            }
        } catch (_: Exception) {
            SessionResumePreflightDenial.Unavailable
        }

    companion object {
        fun production(): ResumeRuntimeFilesystemPreflight =
            ResumeRuntimeFilesystemPreflight(ResumeLocalFileStorePolicy.systemType)

        /** Per-invocation CI fault seam: never configure from model input. */
        @LedgerTestFaultInjection
        internal fun withFault(fileStoreType: (Path) -> String):
            ResumeRuntimeFilesystemPreflight =
            ResumeRuntimeFilesystemPreflight(fileStoreType)
    }
}
