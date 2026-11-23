package ai.yuriilab.recursivecoder

import java.nio.file.Path

/**
 * Deterministic run paths for pure unit tests. No filesystem operations are performed.
 * Kept in testFixtures so unit test bytecode does not depend on java.nio.file APIs.
 */
object VirtualTaskRunPaths : TaskRunPaths by DirectoryTaskRunPaths(Path.of("virtual-task-runs"))
