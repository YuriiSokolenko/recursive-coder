package ai.yuriilab.recursivecoder

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.*

/** Real Clikt command dispatch with an owned resource-bearing repository adapter. */
@Tag("integration")
@ResourceLock("SYSTEM_ERR")
class TaskCliCleanupTest {
    private class TrackingRepository(
        private val readFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val backing: TaskRepository = InMemoryTaskRepository()
    ) : TaskRepository by backing, AutoCloseable {
        var closeCount = 0
            private set

        override fun <T> read(operation: (TaskQueue) -> T): T {
            readFailure?.let { throw it }
            return backing.read(operation)
        }

        override fun close() {
            closeCount++
            closeFailure?.let { throw it }
        }
    }

    private fun withStderr(action: () -> Int): Pair<Int, String> {
        val original = System.err
        val output = ByteArrayOutputStream()
        return try {
            System.setErr(PrintStream(output, true, "UTF-8"))
            action() to output.toString("UTF-8")
        } finally {
            System.setErr(original)
        }
    }

    @Test fun successfulTaskCommandClosesItsOwnedRepositoryOnce() {
        val repository = TrackingRepository()
        var creations = 0
        val status = executeCli(listOf("tasks", "list")) {
            creations++
            repository
        }
        assertEquals(0, status)
        assertEquals(1, creations)
        assertEquals(1, repository.closeCount)
    }

    @Test fun usageErrorAfterRepositoryCreationStillClosesAndRetainsPrimaryFailure() {
        val usageFailure = UsageError("late task usage error")
        val closeFailure = IllegalStateException("usage cleanup failed")
        val repository = TrackingRepository(usageFailure, closeFailure)
        val (status, diagnostics) = withStderr {
            executeCli(listOf("tasks", "list")) { repository }
        }
        assertEquals(2, status)
        assertEquals(1, repository.closeCount)
        assertEquals(listOf(closeFailure), usageFailure.suppressed.toList())
        assertContains(diagnostics, "Cleanup failed: usage cleanup failed")
    }

    @Test fun runtimeFailureAfterRepositoryCreationStillClosesAndRetainsPrimaryFailure() {
        val runtimeFailure = IllegalStateException("read failed")
        val closeFailure = IllegalStateException("runtime cleanup failed")
        val repository = TrackingRepository(runtimeFailure, closeFailure)
        val (status, diagnostics) = withStderr {
            executeCli(listOf("tasks", "list")) { repository }
        }
        assertEquals(2, status)
        assertEquals(1, repository.closeCount)
        assertEquals(listOf(closeFailure), runtimeFailure.suppressed.toList())
        assertContains(diagnostics, "read failed")
        assertContains(diagnostics, "Cleanup failed: runtime cleanup failed")
    }

    @Test fun cleanupFailureAfterSuccessfulCommandIsReportedAndNonzero() {
        val repository = TrackingRepository(closeFailure = IllegalStateException("close failed"))
        val (status, diagnostics) = withStderr {
            executeCli(listOf("tasks", "list")) { repository }
        }
        assertEquals(2, status)
        assertEquals(1, repository.closeCount)
        assertContains(diagnostics, "Cleanup failed: close failed")
    }

    @Test fun successfulProgramResultCannotHideCleanupFailure() {
        val successfulExit = ProgramResult(0)
        val closeFailure = IllegalStateException("help cleanup failed")
        val repository = TrackingRepository(successfulExit, closeFailure)
        val (status, diagnostics) = withStderr {
            executeCli(listOf("tasks", "list")) { repository }
        }
        assertEquals(2, status)
        assertEquals(1, repository.closeCount)
        assertEquals(listOf(closeFailure), successfulExit.suppressed.toList())
        assertContains(diagnostics, "Cleanup failed: help cleanup failed")
    }

    @Test fun unsuccessfulProgramResultPreservesOriginalExitStatus() {
        val commandFailure = ProgramResult(7)
        val closeFailure = IllegalStateException("cleanup failed")
        val repository = TrackingRepository(commandFailure, closeFailure)
        val (status, diagnostics) = withStderr {
            executeCli(listOf("tasks", "list")) { repository }
        }
        assertEquals(7, status)
        assertEquals(1, repository.closeCount)
        assertEquals(listOf(closeFailure), commandFailure.suppressed.toList())
        assertContains(diagnostics, "Cleanup failed: cleanup failed")
    }

    @Test fun helpAndParserOnlyErrorsDoNotConstructARepository() {
        var creations = 0
        val factory: (Path) -> TaskRepository = {
            creations++
            TrackingRepository()
        }
        assertEquals(0, executeCli(listOf("tasks", "--help"), factory))
        assertEquals(2, executeCli(listOf("tasks", "--store", "relative", "list"), factory))
        assertEquals(2, executeCli(listOf("tasks", "--unknown"), factory))
        assertEquals(0, creations)
    }
}
