package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Run the standalone CLI; Clikt handles syntax before any runtime is constructed. */
fun main(args: Array<String>) {
    exitProcess(executeCli(args.toList()))
}

/** Execute one first-party SessionRequest v1 with the configured model provider. */
internal fun executeSession(requestPath: Path, delayMs: Long): Int {
    val request = try {
        if (!requestPath.isAbsolute || Files.size(requestPath) > 262144) error("Invalid input")
        SessionRequest.parse(Files.readString(requestPath))
    } catch (_: Exception) {
        System.err.println("Invalid SessionRequest v1 JSON or input path")
        return 2
    }
    val provider = ModelConfig.from(request.model).provider
    if (provider != "fake" && delayMs != 0L) {
        System.err.println("--fake-delay-ms is supported only for the fake provider")
        return 2
    }
    val cancellation = Cancellation()
    val finished = CountDownLatch(1)
    val hook = Thread {
        cancellation.cancel()
        finished.await(3000, TimeUnit.MILLISECONDS)
    }
    Runtime.getRuntime().addShutdownHook(hook)
    val result = try {
        withRunSessionUseCase(ModelGateways.create(provider, delayMs)) { useCase ->
            useCase(request, cancellation)
        }
    } catch (_: Exception) {
        System.err.println("Session output initialization failed (no existing files were overwritten)")
        return 2
    } finally {
        finished.countDown()
        try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) {
            // The JVM is shutting down.
        }
    }
    val status = result.outcome.wire
    System.out.println(result.protocol.toString())
    System.err.println("Session " + request.sessionId + ": " + status +
        " | result=" + request.output("resultPath") +
        " | events=" + request.output("eventsPath"))
    return if (status == "succeeded") 0 else if (status == "rejected") 2 else 1
}

/** CLI-owned JSONL adapter selection shared by CLI integration fixtures. */
internal val cliEventSinkFactory: EventSinkFactory =
    EventSinkFactory { path -> JsonlEventSink.open(path) }

/** Compatibility adapter used by existing integration clients. */
internal fun createSessionController(
    request: SessionRequest,
    model: ModelGateway,
    cancellation: Cancellation = Cancellation()
): SessionController = SessionController(
    request, model, cancellation, cliWorkspaceOpener, cliEventSinkFactory
)
