package ai.yuriilab.recursivecoder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Host-local failures are not evidence that the caller was cancelled.
 * On actual cancellation the Job's exception is thrown (not necessarily [failure]).
 */
internal suspend fun ensureHostCallerActive(failure: Exception) {
    try {
        currentCoroutineContext().ensureActive()
    } catch (cancelled: CancellationException) {
        if (cancelled !== failure) cancelled.addSuppressed(failure)
        throw cancelled
    }
}

internal suspend fun <T> captureHostCall(call: suspend () -> T): Result<T> =
    try {
        Result.success(call())
    } catch (failure: Exception) {
        ensureHostCallerActive(failure)
        Result.failure(failure)
    }

/** Read all bounded inputs; a confirmed mismatch wins over unavailable reads. */
internal suspend fun verifyHostReads(
    checks: List<Pair<String, (ByteArray?) -> Boolean>>,
    read: suspend (String) -> ByteArray?
): HostReadVerification {
    var changed = false
    var unavailable = false
    for ((path, matches) in checks) {
        val result = captureHostCall { read(path) }
        if (result.isFailure) unavailable = true
        else if (!matches(result.getOrThrow())) changed = true
    }
    return when {
        changed -> HostReadVerification.CHANGED
        unavailable -> HostReadVerification.UNAVAILABLE
        else -> HostReadVerification.INTACT
    }
}

internal enum class HostReadVerification { INTACT, CHANGED, UNAVAILABLE }

/** Preserve the primary tool failure if cleanup also throws. */
internal suspend fun <T> withStageSessionCleanup(
    close: suspend () -> Unit,
    block: suspend () -> T
): T {
    var primary: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            close()
        } catch (cleanup: Throwable) {
            val original = primary
            if (original == null) throw cleanup
            if (original !== cleanup) original.addSuppressed(cleanup)
        }
    }
}

/** Returns actual Job cancellation, attaching an independent failure where possible. */
internal suspend fun callerCancellation(failure: Exception): CancellationException? =
    try {
        ensureHostCallerActive(failure)
        null
    } catch (cancelled: CancellationException) {
        cancelled
    }
