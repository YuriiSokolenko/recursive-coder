package ai.yuriilab.recursivecoder

import java.nio.file.Path

/** Resolves per-attempt Session Protocol v1 output paths, independently of queue persistence. */
fun interface TaskRunPaths {
    fun outputRoot(task: Task): Path
}

/**
 * Filesystem layout policy supplied by the application composition root.
 * A store rooted at <store> supplies <store>/runs as [runsDirectory].
 */
class DirectoryTaskRunPaths(runsDirectory: Path) : TaskRunPaths {
    private val root = runsDirectory.toAbsolutePath().normalize()

    override fun outputRoot(task: Task): Path =
        root.resolve(task.id).resolve("attempt-" + task.attempt)
}
