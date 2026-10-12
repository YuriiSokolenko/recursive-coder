package ai.yuriilab.recursivecoder

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/**
 * CLI syntax and transport format only. TaskService and SessionController remain
 * the authority for priorities, dependencies, scheduling and security policy.
 */
internal fun executeCli(args: List<String>): Int {
    val root = RecursiveCoderCommand()
    return try {
        root.parse(args)
        0
    } catch (error: CliktError) {
        if (error !is ProgramResult && error !is Abort) root.echoFormattedHelp(error)
        when {
            error is ProgramResult || error is Abort -> error.statusCode
            error.statusCode == 0 -> 0
            else -> 2 // Map only CLI usage errors to the legacy invalid-input exit status.
        }
    } catch (error: Exception) {
        System.err.println(error.message ?: "Command failed")
        2
    }
}

private fun <T> once(values: List<T>, flag: String, default: T): T {
    if (values.size > 1) throw UsageError("$flag may be specified only once")
    return values.singleOrNull() ?: default
}

private class RecursiveCoderCommand : CliktCommand(name = "recursive-coder") {
    override val invokeWithoutSubcommand = true
    override fun help(context: Context) = "Run a coding session or manage local tasks."

    init { subcommands(RunSessionCommand(), TasksCommand()) }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            throw UsageError("A command is required")
        }
    }
}

private class RunSessionCommand : CliktCommand(name = "run") {
    override fun help(context: Context) =
        "Execute a SessionRequest v1 JSON file with an offline fake or configured model."

    private val requestPath by argument("REQUEST").convert { Path.of(it) }
    private val delayOptions by option("--fake-delay-ms", help = "Fake provider delay in milliseconds (0..60000).")
        .long().multiple()

    override fun run() {
        val delay = once(delayOptions, "--fake-delay-ms", 0L)
        if (delay !in 0..60000) throw UsageError("--fake-delay-ms must be from 0 to 60000")
        val status = executeSession(requestPath, delay)
        if (status != 0) throw ProgramResult(status)
    }
}

private class TasksCommand : CliktCommand(name = "tasks") {
    override val invokeWithoutSubcommand = true
    override fun help(context: Context) =
        "Manage native tasks. Store defaults to ~/.recursive-coder/tasks. " +
            "The run scheduler stays in the foreground."

    private val storeOptions by option("--store", help = "Absolute directory for durable task state.")
        .convert { Path.of(it) }.multiple()

    private lateinit var storeDirectory: Path

    init {
        subcommands(
            EnqueueTask(this), ListTasks(this), ShowTask(this), CancelTask(this),
            RetryTask(this), PauseTasks(this), ResumeTasks(this),
            CancelAllTasks(this), RunTasks(this)
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            throw UsageError("A task command is required")
        }
        storeDirectory = once(
            storeOptions, "--store",
            Path.of(System.getProperty("user.home"), ".recursive-coder", "tasks")
        )
        if (!storeDirectory.isAbsolute) throw UsageError("Store directory must be absolute")
    }

    // Construct the store only after Clikt has parsed every token and checked options.
    fun service(): TaskService = TaskService(TaskStore(storeDirectory))
}

private class EnqueueTask(private val tasks: TasksCommand) : CliktCommand(name = "enqueue") {
    override fun help(context: Context) = "Add a SessionRequest v1 to the durable task queue."

    private val requestPath by argument("REQUEST").convert { Path.of(it) }
    private val priorityOptions by option("--priority", help = "Task priority (default 50; validated by TaskService).")
        .int().multiple()
    private val dependencyOptions by option("--depends-on", help = "Comma-separated existing task IDs.")
        .convert { value ->
            value.split(',').also { ids ->
                require(ids.all(String::isNotBlank)) { "Empty dependency ID" }
            }
        }.multiple()

    override fun run() {
        val priority = once(priorityOptions, "--priority", 50)
        val dependencies = once(dependencyOptions, "--depends-on", emptyList())
        if (!requestPath.isAbsolute || !Files.isRegularFile(requestPath) ||
            Files.size(requestPath) > 262144
        ) throw UsageError("Invalid request path")
        echo(tasks.service().enqueue(Files.readString(requestPath), priority, dependencies).toPublicJson())
    }
}

private class ListTasks(private val tasks: TasksCommand) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List tasks without their request bodies."
    override fun run() = echo(JsonArray(tasks.service().list().map(Task::toPublicJson)))
}

private abstract class TaskByIdCommand(
    name: String,
    protected val tasks: TasksCommand
) : CliktCommand(name = name) {
    protected val taskId by argument("ID")
}

private class ShowTask(tasks: TasksCommand) : TaskByIdCommand("show", tasks) {
    override fun help(context: Context) = "Show task state and transitions."
    override fun run() = echo(tasks.service().show(taskId).toPublicJson())
}

private class CancelTask(tasks: TasksCommand) : TaskByIdCommand("cancel", tasks) {
    override fun help(context: Context) = "Cancel a queued or running task."
    override fun run() = echo(tasks.service().cancel(taskId).toPublicJson())
}

private class RetryTask(tasks: TasksCommand) : TaskByIdCommand("retry", tasks) {
    override fun help(context: Context) = "Retry a failed or cancelled task."
    override fun run() = echo(tasks.service().retry(taskId).toPublicJson())
}

private class PauseTasks(private val tasks: TasksCommand) : CliktCommand(name = "pause") {
    override fun help(context: Context) = "Pause new task dispatches."
    override fun run() {
        tasks.service().pause()
        echo(buildJsonObject { put("paused", true) })
    }
}

private class ResumeTasks(private val tasks: TasksCommand) : CliktCommand(name = "resume") {
    override fun help(context: Context) = "Resume dispatching queued tasks."
    override fun run() {
        tasks.service().resume()
        echo(buildJsonObject { put("paused", false) })
    }
}

private class CancelAllTasks(private val tasks: TasksCommand) : CliktCommand(name = "cancel-all") {
    override fun help(context: Context) = "Cancel all tasks and pause future launches."
    override fun run() {
        tasks.service().cancelAll()
        echo(buildJsonObject { put("paused", true); put("cancelled", true) })
    }
}

private class RunTasks(private val tasks: TasksCommand) : CliktCommand(name = "run") {
    override fun help(context: Context) = "Run the foreground scheduler until it is idle or paused."

    private val concurrencyOptions by option("--concurrency", help = "Maximum parallel sessions (1..32).")
        .int().multiple()

    override fun run() {
        val concurrency = once(concurrencyOptions, "--concurrency", 1)
        val scheduler = TaskScheduler(tasks.service())
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
        echo(buildJsonObject {
            put("launched", report.launched)
            put("completed", report.completed)
            put("paused", report.paused)
        })
    }
}

/** Compatibility entry point for existing in-process task clients and tests. */
object TaskCli {
    fun execute(args: List<String>): Int = executeCli(listOf("tasks") + args)
}
