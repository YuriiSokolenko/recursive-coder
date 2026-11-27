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
    private val checkFilesystem: (SessionRequest) -> Unit
) : SessionResumePreflight {
    override fun deny(request: SessionRequest): SessionResumePreflightDenial =
        try {
            checkFilesystem(request)
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
            outputProbe(ResumeLocalFileStorePolicy.systemType)

        private fun outputProbe(fileStoreType: (Path) -> String):
            ResumeRuntimeFilesystemPreflight = ResumeRuntimeFilesystemPreflight { request ->
            ResumeLocalFileStorePolicy.requireSupportedPaths(
                request.output(SessionOutputFields.CHECKPOINT_DIR),
                request.output(SessionOutputFields.EVENTS_PATH), fileStoreType)
        }

        /**
         * Opt-in diagnostic for a single independently authorized host request
         * and its existing durable source store, not its new rejection outputs.
         * The host must bind the request and paths before exposing this port.
         * Only the read-only pre-lock stage runs on the bounded probe worker.
         * Even a successful check MUST NOT mint a lease or authorize execution.
         */
        fun forTrustedStore(
            expectedRequest: SessionRequest, directory: Path,
            context: CheckpointHostContext, ledgerPath: Path
        ): ResumeRuntimeFilesystemPreflight = trustedStoreProbe(
            expectedRequest, directory, context, ledgerPath,
            ResumeLocalFileStorePolicy.systemType)

        private fun trustedStoreProbe(
            expectedRequest: SessionRequest, directory: Path,
            context: CheckpointHostContext, ledgerPath: Path,
            fileStoreType: (Path) -> String
        ): ResumeRuntimeFilesystemPreflight = ResumeRuntimeFilesystemPreflight { request ->
            // Compare only. Never select the source paths from request data.
            check(request.raw == expectedRequest.raw)
            SessionResumeAdmission.checkFilesystemBeforeLease(
                directory, context, ledgerPath, fileStoreType)
        }

        /** Per-invocation CI fault seam: never configure from model input. */
        @LedgerTestFaultInjection
        internal fun withFault(fileStoreType: (Path) -> String):
            ResumeRuntimeFilesystemPreflight = outputProbe(fileStoreType)

        @LedgerTestFaultInjection
        internal fun forTrustedStoreWithFault(
            expectedRequest: SessionRequest, directory: Path,
            context: CheckpointHostContext, ledgerPath: Path,
            fileStoreType: (Path) -> String
        ): ResumeRuntimeFilesystemPreflight = trustedStoreProbe(
            expectedRequest, directory, context, ledgerPath, fileStoreType)
    }
}
