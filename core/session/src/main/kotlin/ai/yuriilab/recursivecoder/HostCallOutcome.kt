package ai.yuriilab.recursivecoder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Host-local failures are not evidence that the caller was cancelled. */
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
