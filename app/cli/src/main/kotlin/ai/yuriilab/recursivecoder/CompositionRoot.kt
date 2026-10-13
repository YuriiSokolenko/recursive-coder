package ai.yuriilab.recursivecoder

import java.time.Clock
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The CLI owns all Koin applications. Each command gets one task repository,
 * and session-scoped applications are closed after each invocation.
 * No Koin API is imported by core use cases or domain policies.
 */
internal fun taskModule(repository: TaskRepository): Module = module {
    single<Clock> { Clock.systemUTC() }
    single<TaskRepository> { repository }
    factory<EnqueueTaskUseCase> { DefaultEnqueueTaskUseCase(get(), get()) }
    factory { TaskService(get(), get(), get()) }
}

internal fun sessionModule(model: ModelGateway): Module = module {
    single<Clock> { Clock.systemUTC() }
    single<ModelGateway> { model }
    factory<WorkspaceOpener> { cliWorkspaceOpener }
    factory<EventSinkFactory> { cliEventSinkFactory }
    factory<RunSessionUseCase> { DefaultRunSessionUseCase(get(), get(), get(), get()) }
}

internal val cliWorkspaceOpener: WorkspaceOpener = WorkspaceOpener { request, onAudit, onStart ->
    WorkspaceManager.open(request, onAudit, onStart)
}

/** Command lifetime: one repository/lock owner for both service and enqueue. */
internal class TaskComposition(repository: TaskRepository) : AutoCloseable {
    private val application = koinApplication { modules(taskModule(repository)) }
    private var closed = false

    private fun dependencies() = application.koin.also {
        check(!closed) { "Task composition is closed" }
    }

    fun service(): TaskService = dependencies().get()
    fun enqueue(): EnqueueTaskUseCase = dependencies().get()

    override fun close() {
        if (!closed) {
            closed = true
            application.close()
        }
    }
}

/** Per-session scope: close the container on success, denial and exceptions. */
internal fun <T> withRunSessionUseCase(model: ModelGateway, action: (RunSessionUseCase) -> T): T {
    val application = koinApplication { modules(sessionModule(model)) }
    return try {
        action(application.koin.get())
    } finally {
        application.close()
    }
}
