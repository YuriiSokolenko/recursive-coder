package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
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
    val controller = createSessionController(request, ModelGateways.create(provider, delayMs))
    val hook = Thread {
        controller.cancellation.cancel()
        controller.awaitFinished(3000)
    }
    Runtime.getRuntime().addShutdownHook(hook)
    val result = try { controller.run() } catch (_: Exception) {
        System.err.println("Session output initialization failed (no existing files were overwritten)")
        return 2
    } finally {
        try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) {
            // The JVM is shutting down.
        }
    }
    val status = result["status"]?.toString()?.trim('"') ?: "failed"
    System.out.println(result.toString())
    System.err.println("Session " + request.sessionId + ": " + status +
        " | result=" + request.output("resultPath") +
        " | events=" + request.output("eventsPath"))
    return if (status == "succeeded") 0 else if (status == "rejected") 2 else 1
}

/** Composition root: core modules do not reference concrete workspace or HTTP adapters. */
internal fun createSessionController(
    request: SessionRequest,
    model: ModelGateway,
    cancellation: Cancellation = Cancellation()
): SessionController = SessionController(request, model, cancellation,
    WorkspaceOpener { workspaceRequest, onAudit, onStart ->
        WorkspaceManager.open(workspaceRequest, onAudit, onStart)
    }, EventSinkFactory { path -> JsonlEventSink.open(path) })
