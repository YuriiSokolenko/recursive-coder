package ai.yuriilab.recursivecoder

import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject

/**
 * Compatibility façade for older callers. The primary constructor accepts only
 * the Use Case contract; the legacy constructor preserves ports-based callers.
 */
class SessionController(
    private val request: SessionRequest,
    val cancellation: Cancellation,
    private val runSession: RunSessionUseCase
) {
    /**
     * Retains the original JVM constructor/default-argument signature. An
     * omitted cancellation token inherits the session Clock instead of
     * silently using the system clock. A supplied token must use the same
     * Clock, or construction fails before any model or workspace side effect.
     */
    constructor(
        request: SessionRequest,
        model: ModelGateway,
        cancellation: Cancellation? = null,
        workspaceOpener: WorkspaceOpener,
        eventSinkFactory: EventSinkFactory,
        clock: Clock = Clock.systemUTC()
    ) : this(request, cancellationForClock(cancellation, clock),
        DefaultRunSessionUseCase(model, workspaceOpener, eventSinkFactory, clock))

    private companion object {
        fun cancellationForClock(cancellation: Cancellation?, clock: Clock): Cancellation {
            val resolved = cancellation ?: Cancellation(clock)
            require(resolved.usesClock(clock)) {
                "Cancellation clock must match SessionController clock"
            }
            return resolved
        }
    }

    private val finished = CountDownLatch(1)

    fun awaitFinished(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun run(): JsonObject = try {
        runSession(request, cancellation).protocol
    } finally {
        finished.countDown()
    }
}
