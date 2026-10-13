package ai.yuriilab.recursivecoder

import java.nio.file.Path
import java.time.Clock
import org.koin.dsl.koinApplication
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * CLI-owned dependency wiring. Neither core bounded context imports Koin.
 * A local KoinApplication is created per CLI invocation, never a global container.
 */
internal fun taskModule(storeDirectory: Path): Module = module {
    single<Clock> { Clock.systemUTC() }
    single<TaskRepository> { TaskStore(storeDirectory) }
    factory<EnqueueTaskUseCase> { DefaultEnqueueTaskUseCase(get(), get()) }
    factory { TaskService(get(), get(), get()) }
}

/**
 * Model provider is chosen per execution; a Use Case and WorkspaceOpener are
 * factories, and Cancellation / workspace / journal are per-invocation resources.
 */
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

internal fun resolveTaskService(storeDirectory: Path): TaskService =
    koinApplication { modules(taskModule(storeDirectory)) }.koin.get()

internal fun resolveEnqueueTaskUseCase(storeDirectory: Path): EnqueueTaskUseCase =
    koinApplication { modules(taskModule(storeDirectory)) }.koin.get()

internal fun resolveRunSessionUseCase(model: ModelGateway): RunSessionUseCase =
    koinApplication { modules(sessionModule(model)) }.koin.get()
