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
     * Explicit cancellation compatibility constructor. With a custom clock,
     * callers must construct the supplied Cancellation with that same clock.
     */
    constructor(
        request: SessionRequest,
        model: ModelGateway,
        cancellation: Cancellation,
        workspaceOpener: WorkspaceOpener,
        eventSinkFactory: EventSinkFactory,
        clock: Clock = Clock.systemUTC()
    ) : this(request, cancellation,
        DefaultRunSessionUseCase(model, workspaceOpener, eventSinkFactory, clock))

    /** Omitted cancellation inherits the controller clock, including model checks. */
    constructor(
        request: SessionRequest,
        model: ModelGateway,
        workspaceOpener: WorkspaceOpener,
        eventSinkFactory: EventSinkFactory,
        clock: Clock = Clock.systemUTC()
    ) : this(request, model, Cancellation(clock), workspaceOpener, eventSinkFactory, clock)

    private val finished = CountDownLatch(1)

    fun awaitFinished(timeoutMs: Long): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun run(): JsonObject = try {
        runSession(request, cancellation).protocol
    } finally {
        finished.countDown()
    }
}
