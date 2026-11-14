package ai.yuriilab.recursivecoder

import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Canonical path-geometry tests; the tier verifier classifies java.nio.file.Path as integration. */
@Tag("integration")
class InstalledChildCheckoutScopesTest {
    private val root = Path.of("/isolated/child-sdd")
    private val locks = Path.of("/locks/child-sdd")
    private val parent = Path.of("/isolated/parent")
    private val parentLocks = Path.of("/locks/parent")
    private val plan = Path.of("/configs/plan.json")
    private val approval = Path.of("/configs/approval.json")
    private val events = Path.of("/journals/events.jsonl")
    private val cwd = Path.of("/builder")
    private fun safe(
        childRoot: Path = root, childLocks: Path = locks,
        config: List<Path> = listOf(plan, approval),
        event: Path? = events,
        earlierRoots: Set<Path> = emptySet(),
        earlierLocks: Set<Path> = emptySet(),
        workdir: Path = cwd
    ) = installedChildCheckoutScopesDisjoint(
        childRoot, childLocks, parent, parentLocks,
        config, event, earlierRoots, earlierLocks, workdir)

    @Test fun disjointCheckoutAndExternalInputsAreAllowed() {
        assertTrue(safe())
        assertTrue(safe(earlierRoots = setOf(Path.of("/isolated/child-develop")),
            earlierLocks = setOf(Path.of("/locks/child-develop"))))
    }

    @Test fun rejectOverlapWithParentRootOrParentLock() {
        assertFalse(safe(childRoot = parent))
        assertFalse(safe(childRoot = parent.resolve("nested")))
        assertFalse(safe(childRoot = parent.parent))
        assertFalse(safe(childLocks = parent))
        assertFalse(safe(childLocks = parentLocks))
        assertFalse(safe(childLocks = Path.of("/locks")))
    }

    @Test fun rejectChildRootAndItsOwnLockOrAnotherChild() {
        assertFalse(safe(childLocks = root.resolve("locked")))
        assertFalse(safe(childRoot = locks.resolve("child")))
        assertFalse(safe(earlierRoots = setOf(root)))
        assertFalse(safe(earlierRoots = setOf(root.resolve("nested"))))
        assertFalse(safe(earlierLocks = setOf(locks)))
        assertFalse(safe(earlierLocks = setOf(root.resolve("other-locks"))))
        assertFalse(safe(earlierRoots = setOf(locks.resolve("other-child"))))
    }

    @Test fun rejectConfigurationJournalAndCurrentWorkingDirectoryInsideChildScopes() {
        assertFalse(safe(config = listOf(root.resolve("plan.json"), approval)))
        assertFalse(safe(config = listOf(plan, locks.resolve("approval.json"))))
        assertFalse(safe(event = root.resolve("events.jsonl")))
        assertFalse(safe(event = locks.resolve("events.jsonl")))
        assertFalse(safe(workdir = root.resolve("working")))
        assertFalse(safe(workdir = locks))
        assertFalse(safe(workdir = locks.resolve("nested")))
        assertFalse(safe(childLocks = cwd.resolve("nested-locks")))
        assertTrue(safe(event = null))
    }
}
