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
    factory<TaskQueriesUseCase> { DefaultTaskQueriesUseCase(get()) }
    factory<CancelTaskUseCase> { DefaultCancelTaskUseCase(get(), get()) }
    factory<RetryTaskUseCase> { DefaultRetryTaskUseCase(get(), get()) }
    factory<PauseTaskQueueUseCase> { DefaultPauseTaskQueueUseCase(get(), get()) }
    factory<ResumeTaskQueueUseCase> { DefaultResumeTaskQueueUseCase(get(), get()) }
    factory<ReplaceTaskDependenciesUseCase> { DefaultReplaceTaskDependenciesUseCase(get(), get()) }
    factory<CancelAllTasksUseCase> { DefaultCancelAllTasksUseCase(get(), get()) }
    factory<ClaimNextTaskUseCase> { DefaultClaimNextTaskUseCase(get(), get()) }
    factory<CompleteTaskAttemptUseCase> { DefaultCompleteTaskAttemptUseCase(get(), get()) }
    factory<InterruptTaskAttemptUseCase> { DefaultInterruptTaskAttemptUseCase(get(), get()) }
    factory<RecoverInterruptedAttemptsUseCase> { DefaultRecoverInterruptedAttemptsUseCase(get(), get()) }
    factory<PrepareTaskAttemptUseCase> { DefaultPrepareTaskAttemptUseCase(get()) }
    factory<TaskRunner> {
        TaskRunner { request, cancellation ->
            val gateway = ModelGateways.create(ModelConfig.from(request.model).provider)
            withRunSessionUseCase(gateway) { it(request, cancellation).outcome.wire }
        }
    }
    factory {
        TaskScheduler(
            repository = get(), queries = get(), claim = get(), complete = get(),
            interrupt = get(), recover = get(), prepare = get(), runner = get()
        )
    }
    // Compatibility adapter for callers still using the original in-process API.
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

    fun service(): TaskService = dependencies().get() // Compatibility adapter only.
    fun enqueue(): EnqueueTaskUseCase = dependencies().get()
    fun queries(): TaskQueriesUseCase = dependencies().get()
    fun cancel(): CancelTaskUseCase = dependencies().get()
    fun retry(): RetryTaskUseCase = dependencies().get()
    fun pause(): PauseTaskQueueUseCase = dependencies().get()
    fun resume(): ResumeTaskQueueUseCase = dependencies().get()
    fun cancelAll(): CancelAllTasksUseCase = dependencies().get()
    fun replaceDependencies(): ReplaceTaskDependenciesUseCase = dependencies().get()
    fun scheduler(): TaskScheduler = dependencies().get()

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
