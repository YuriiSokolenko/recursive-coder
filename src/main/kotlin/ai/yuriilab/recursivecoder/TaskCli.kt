package ai.yuriilab.recursivecoder

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** A thin CLI adapter over the same TaskService used by future frontends. */
object TaskCli {
    private const val HELP = """
Usage: recursive-coder tasks [--store /absolute/directory] COMMAND
  enqueue /absolute/request.json [--priority 0..100] [--depends-on id,id]
  list
  show ID
  cancel ID
  retry ID
  pause
  resume
  cancel-all
  run [--concurrency 1..32]

run is a foreground scheduler. Use a second CLI process to pause or cancel.
The store defaults to ~/.recursive-coder/tasks.
"""

    fun execute(args: List<String>): Int {
        return try {
            val arguments = args.toMutableList()
            val directory = if (arguments.firstOrNull() == "--store") {
                require(arguments.size >= 3) { HELP }
                arguments.removeAt(0)
                Path.of(arguments.removeAt(0)).also {
                    require(it.isAbsolute) { "Store directory must be absolute" }
                }
            } else Path.of(System.getProperty("user.home"), ".recursive-coder", "tasks")
            require(arguments.isNotEmpty()) { HELP }
            val service = TaskService(TaskStore(directory))
            val command = arguments.removeAt(0)
            when (command) {
                "enqueue" -> {
                    require(arguments.isNotEmpty()) { HELP }
                    val requestPath = Path.of(arguments.removeAt(0))
                    require(requestPath.isAbsolute && Files.isRegularFile(requestPath) &&
                        Files.size(requestPath) <= 262144) { "Invalid request path" }
                    var priority = 50
                    var dependencies = emptyList<String>()
                    var prioritySet = false
                    var dependenciesSet = false
                    while (arguments.isNotEmpty()) {
                        require(arguments.size >= 2) { HELP }
                        val flag = arguments.removeAt(0)
                        val value = arguments.removeAt(0)
                        when (flag) {
                            "--priority" -> {
                                require(!prioritySet) { "Duplicate priority option" }
                                prioritySet = true
                                priority = value.toIntOrNull()
                                    ?: throw IllegalArgumentException("Priority must be an integer from 0 to 100")
                            }
                            "--depends-on" -> {
                                require(!dependenciesSet) { "Duplicate dependency option" }
                                dependenciesSet = true
                                dependencies = value.split(',').also {
                                    require(it.all(String::isNotBlank)) { "Empty dependency" }
                                }
                            }
                            else -> error(HELP)
                        }
                    }
                    println(service.enqueue(Files.readString(requestPath), priority, dependencies).toPublicJson())
                }
                "list" -> {
                    require(arguments.isEmpty()) { HELP }
                    println(JsonArray(service.list().map(Task::toPublicJson)))
                }
                "show" -> {
                    require(arguments.size == 1) { HELP }
                    println(service.show(arguments.single()).toPublicJson())
                }
                "cancel" -> {
                    require(arguments.size == 1) { HELP }
                    println(service.cancel(arguments.single()).toPublicJson())
                }
                "retry" -> {
                    require(arguments.size == 1) { HELP }
                    println(service.retry(arguments.single()).toPublicJson())
                }
                "pause" -> {
                    require(arguments.isEmpty()) { HELP }
                    service.pause()
                    println(buildJsonObject { put("paused", true) })
                }
                "resume" -> {
                    require(arguments.isEmpty()) { HELP }
                    service.resume()
                    println(buildJsonObject { put("paused", false) })
                }
                "cancel-all" -> {
                    require(arguments.isEmpty()) { HELP }
                    service.cancelAll()
                    println(buildJsonObject { put("paused", true); put("cancelled", true) })
                }
                "run" -> {
                    val concurrency = if (arguments.isEmpty()) 1 else {
                        require(arguments.size == 2 && arguments[0] == "--concurrency") { HELP }
                        arguments[1].toIntOrNull()
                            ?: throw IllegalArgumentException("Concurrency must be an integer from 1 to 32")
                    }
                    val scheduler = TaskScheduler(service)
                    val hook = Thread({ scheduler.requestStop() }, "task-scheduler-shutdown")
                    Runtime.getRuntime().addShutdownHook(hook)
                    val report = try {
                        scheduler.runUntilIdle(concurrency)
                    } finally {
                        try {
                            Runtime.getRuntime().removeShutdownHook(hook)
                        } catch (_: IllegalStateException) {
                            // The JVM is already executing shutdown hooks.
                        }
                    }
                    println(buildJsonObject {
                        put("launched", report.launched)
                        put("completed", report.completed)
                        put("paused", report.paused)
                    })
                }
                else -> error(HELP)
            }
            0
        } catch (e: Exception) {
            System.err.println(e.message ?: "Task command failed")
            2
        }
    }
}
