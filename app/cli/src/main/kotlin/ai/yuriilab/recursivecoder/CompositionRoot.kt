package ai.yuriilab.recursivecoder

import java.time.Clock
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The CLI owns all Koin applications. Each task command uses one repository.
 * An owned closeable repository is released only after its Koin application;
 * borrowed repositories remain under their caller's lifetime management.
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

/** The CLI-created adapter is owned; adapters injected by other callers may be borrowed. */
internal enum class TaskRepositoryOwnership { OWNED, BORROWED }

/** Command lifetime: one shared repository for all task use cases and the scheduler. */
internal class TaskComposition(
    private val repository: TaskRepository,
    private val ownership: TaskRepositoryOwnership,
    private val afterKoinClose: () -> Unit = {}
) : AutoCloseable {
    private fun closeOwnedRepository() {
        if (ownership == TaskRepositoryOwnership.OWNED) {
            (repository as? AutoCloseable)?.close()
        }
    }

    private val application = try {
        koinApplication { modules(taskModule(repository)) }
    } catch (failure: Throwable) {
        // A failed DI setup must not leak a newly created resource-owning adapter.
        try {
            closeOwnedRepository()
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // A scheduler has already exited before command cleanup reaches this point.
        closeTaskCompositionResources(
            closeApplication = { application.close(); afterKoinClose() },
            closeRepository = ::closeOwnedRepository
        )
    }
}

/**
 * Shut down DI before releasing an owned repository. Always attempt both phases,
 * keeping the earliest failure and suppressing later distinct failures.
 */
internal fun closeTaskCompositionResources(
    closeApplication: () -> Unit,
    closeRepository: () -> Unit
) {
    var failure: Throwable? = null
    try {
        closeApplication()
    } catch (error: Throwable) {
        failure = error
    }
    try {
        closeRepository()
    } catch (error: Throwable) {
        val earlier = failure
        if (earlier == null) failure = error
        else if (earlier !== error) earlier.addSuppressed(error)
    }
    failure?.let { throw it }
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
