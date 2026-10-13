package ai.yuriilab.recursivecoder

import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject

/**
 * Compatibility façade for existing runtime callers.
 * New entry points resolve RunSessionUseCase from the application composition root.
 */
class SessionController(
    private val request: SessionRequest,
    model: ModelGateway,
    val cancellation: Cancellation = Cancellation(),
    workspaceOpener: WorkspaceOpener,
    eventSinkFactory: EventSinkFactory,
    clock: Clock = Clock.systemUTC(),
    private val runSession: RunSessionUseCase =
        DefaultRunSessionUseCase(model, workspaceOpener, eventSinkFactory, clock)
) {
    private val finished = CountDownLatch(1)

    fun awaitFinished(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun run(): JsonObject = try {
        runSession(request, cancellation)
    } finally {
        finished.countDown()
    }
}
