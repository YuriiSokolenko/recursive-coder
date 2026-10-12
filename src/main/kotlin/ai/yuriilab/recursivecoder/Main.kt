package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Execute one first-party SessionRequest v1, with an offline deterministic model. */
fun main(args: Array<String>) {
    if (args.size !in 2..4 || args[0] != "run" || args[1].startsWith("-") ||
        (args.size > 2 && (args.size != 4 || args[2] != "--fake-delay-ms"))) {
        System.err.println("Usage: recursive-coder run /absolute/request.json [--fake-delay-ms N]")
        exitProcess(2)
    }
    val delayMs = if (args.size == 4) args[3].toLongOrNull() else 0L
    if (delayMs == null || delayMs !in 0..60000) {
        System.err.println("Invalid fake delay")
        exitProcess(2)
    }
    val request = try {
        val source = Path.of(args[1])
        if (!source.isAbsolute || Files.size(source) > 262144) error("Invalid input")
        SessionRequest.parse(Files.readString(source))
    } catch (_: Exception) {
        System.err.println("Invalid SessionRequest v1 JSON or input path")
        exitProcess(2)
    }
    val controller = SessionController(request, FakeModel(delayMs))
    val hook = Thread {
        controller.cancellation.cancel()
        controller.awaitFinished(3000)
    }
    Runtime.getRuntime().addShutdownHook(hook)
    val result = try { controller.run() } catch (_: Exception) {
        System.err.println("Session output initialization failed (no existing files were overwritten)")
        exitProcess(2)
    }
    try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) { /* stopping */ }
    val status = result["status"]?.toString()?.trim('"') ?: "failed"
    System.out.println(result.toString())
    System.err.println("Session " + request.sessionId + ": " + status +
        " | result=" + request.output("resultPath") +
        " | events=" + request.output("eventsPath"))
    exitProcess(if (status == "succeeded") 0 else if (status == "rejected") 2 else 1)
}
