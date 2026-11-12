package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Role adapter is only made available when a caller explicitly selects its module. */
@Tag("integration")
class RoleExecutionKoinTest {
    @Test fun optInModuleResolvesFreshRoleExecutorsWithInjectedGateway() {
        val bridge = KoogPromptBridge { _, _ -> error("No role was selected for execution") }
        koinApplication { modules(roleExecutionModule(FakeModel(), bridge)) }.use { application ->
            val first: StageRoleExecutor = application.koin.get()
            val second: StageRoleExecutor = application.koin.get()
            assertTrue(first is KoogStageRoleExecutor)
            assertNotSame(first, second)
        }
    }
}
