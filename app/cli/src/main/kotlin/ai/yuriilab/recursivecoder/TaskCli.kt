package ai.yuriilab.recursivecoder

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.json.*

/**
 * CLI syntax and transport format only. Injected Use Cases own task decisions;
 * the session and scheduler retain their separate runtime authority.
 */
internal fun executeCli(
    args: List<String>,
    repositoryFactory: (Path) -> TaskRepository = ::TaskStore
): Int {
    val root = RecursiveCoderCommand(repositoryFactory)
    // Keep the command failure primary, even when shutdown also fails. In particular,
    // a successful ProgramResult(0) must not mask an owned repository close failure.
    val commandFailure = try {
        root.parse(args)
        null
    } catch (error: Throwable) {
        error
    }
    val cleanupFailure = try {
        root.close()
        null
    } catch (error: Throwable) {
        error
    }

    if (commandFailure == null) {
        if (cleanupFailure != null) {
            reportCleanupFailure(cleanupFailure)
            return 2
        }
        return 0
    }
    if (cleanupFailure != null && cleanupFailure !== commandFailure) {
        commandFailure.addSuppressed(cleanupFailure)
    }
    return when (commandFailure) {
        is CliktError -> {
            if (commandFailure !is ProgramResult && commandFailure !is Abort) {
                root.echoFormattedHelp(commandFailure)
            }
            commandFailure.suppressed.forEach(::reportCleanupFailure)
            val status = when {
                commandFailure is ProgramResult || commandFailure is Abort ->
                    commandFailure.statusCode
                commandFailure.statusCode == 0 -> 0
                else -> 2 // Preserve the legacy invalid-input status for usage errors.
            }
            if (status == 0 && cleanupFailure != null) 2 else status
        }
        is Exception -> {
            System.err.println(commandFailure.message ?: "Command failed")
            commandFailure.suppressed.forEach(::reportCleanupFailure)
            2
        }
        else -> throw commandFailure // JVM-fatal errors must not become ordinary CLI exit codes.
    }
}

private fun reportCleanupFailure(error: Throwable) {
    // Identity-based visitation handles cyclic suppressed graphs without recursion.
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending.addLast(error)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (!seen.add(current)) continue
        System.err.println("Cleanup failed: " + (current.message ?: "Unknown failure"))
        current.suppressed.reversed().forEach(pending::addLast)
    }
}

private fun <T> once(values: List<T>, flag: String, default: T): T {
    if (values.size > 1) throw UsageError("$flag may be specified only once")
    return values.singleOrNull() ?: default
}

private class RecursiveCoderCommand(
    repositoryFactory: (Path) -> TaskRepository
) : CliktCommand(name = "recursive-coder"), AutoCloseable {
    override val invokeWithoutSubcommand = true
    override fun help(context: Context) = "Run a coding session or manage local tasks."

    private val tasks = TasksCommand(repositoryFactory)

    init { subcommands(RunSessionCommand(), tasks) }

    override fun close() = tasks.close()

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

private class TasksCommand(
    private val repositoryFactory: (Path) -> TaskRepository
) : CliktCommand(name = "tasks") {
    override val invokeWithoutSubcommand = true
    override fun help(context: Context) =
        "Manage native tasks. Store defaults to ~/.recursive-coder/tasks. " +
            "The run scheduler stays in the foreground."

    private val storeOptions by option("--store", help = "Absolute directory for durable task state.")
        .convert { Path.of(it) }.multiple()

    private lateinit var storeDirectory: Path
    private var composition: TaskComposition? = null

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

    // Delay file store creation until after Clikt has validated the command.
    private fun dependencies(): TaskComposition =
        composition ?: TaskComposition(repositoryFactory(storeDirectory), TaskRepositoryOwnership.OWNED)
            .also { composition = it }

    fun enqueue(): EnqueueTaskUseCase = dependencies().enqueue()
    fun queries(): TaskQueriesUseCase = dependencies().queries()
    fun cancel(): CancelTaskUseCase = dependencies().cancel()
    fun retry(): RetryTaskUseCase = dependencies().retry()
    fun pause(): PauseTaskQueueUseCase = dependencies().pause()
    fun resume(): ResumeTaskQueueUseCase = dependencies().resume()
    fun cancelAll(): CancelAllTasksUseCase = dependencies().cancelAll()
    fun scheduler(): TaskScheduler = dependencies().scheduler()

    fun close() {
        val current = composition
        composition = null
        current?.close()
    }
}

private class EnqueueTask(private val tasks: TasksCommand) : CliktCommand(name = "enqueue") {
    override fun help(context: Context) = "Add a SessionRequest v1 to the durable task queue."

    private val requestPath by argument("REQUEST").convert { Path.of(it) }
    private val priorityOptions by option("--priority", help = "Task priority (default 50; validated by the enqueue Use Case).")
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
        echo(tasks.enqueue()(Files.readString(requestPath), priority, dependencies).toPublicJson())
    }
}

private class ListTasks(private val tasks: TasksCommand) : CliktCommand(name = "list") {
    override fun help(context: Context) = "List tasks without their request bodies."
    override fun run() = echo(JsonArray(tasks.queries().list().map(Task::toPublicJson)))
}

private abstract class TaskByIdCommand(
    name: String,
    protected val tasks: TasksCommand
) : CliktCommand(name = name) {
    protected val taskId by argument("ID")
}

private class ShowTask(tasks: TasksCommand) : TaskByIdCommand("show", tasks) {
    override fun help(context: Context) = "Show task state and transitions."
    override fun run() = echo(tasks.queries().show(taskId).toPublicJson())
}

private class CancelTask(tasks: TasksCommand) : TaskByIdCommand("cancel", tasks) {
    override fun help(context: Context) = "Cancel a queued or running task."
    override fun run() = echo(tasks.cancel()(taskId).toPublicJson())
}

private class RetryTask(tasks: TasksCommand) : TaskByIdCommand("retry", tasks) {
    override fun help(context: Context) = "Retry a failed or cancelled task."
    override fun run() = echo(tasks.retry()(taskId).toPublicJson())
}

private class PauseTasks(private val tasks: TasksCommand) : CliktCommand(name = "pause") {
    override fun help(context: Context) = "Pause new task dispatches."
    override fun run() {
        tasks.pause()()
        echo(buildJsonObject { put("paused", true) })
    }
}

private class ResumeTasks(private val tasks: TasksCommand) : CliktCommand(name = "resume") {
    override fun help(context: Context) = "Resume dispatching queued tasks."
    override fun run() {
        tasks.resume()()
        echo(buildJsonObject { put("paused", false) })
    }
}

private class CancelAllTasks(private val tasks: TasksCommand) : CliktCommand(name = "cancel-all") {
    override fun help(context: Context) = "Cancel all tasks and pause future launches."
    override fun run() {
        tasks.cancelAll()()
        echo(buildJsonObject { put("paused", true); put("cancelled", true) })
    }
}

private class RunTasks(private val tasks: TasksCommand) : CliktCommand(name = "run") {
    override fun help(context: Context) = "Run the foreground scheduler until it is idle or paused."

    private val concurrencyOptions by option("--concurrency", help = "Maximum parallel sessions (1..32).")
        .int().multiple()

    override fun run() {
        val concurrency = once(concurrencyOptions, "--concurrency", 1)
        val scheduler = tasks.scheduler()
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
