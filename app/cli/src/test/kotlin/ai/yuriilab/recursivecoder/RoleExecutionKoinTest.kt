package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Role adapter is only made available when a caller explicitly selects its module. */
@Tag("integration")
class RoleExecutionKoinTest {
    @Test fun optInModuleResolvesFreshRoleExecutorsWithInjectedGateway() {
        val bridge = KoogPromptBridge { _, _ -> error("No role was selected for execution") }
        val application = koinApplication { modules(roleExecutionModule(FakeModel(), bridge)) }
        try {
            val first: StageRoleExecutor = application.koin.get()
            val second: StageRoleExecutor = application.koin.get()
            assertTrue(first is KoogStageRoleExecutor)
            assertNotSame(first, second)
        } finally {
            application.close()
        }
    }
    @Test fun trustedStageToolsAreAbsentByDefaultAndRequireExplicitModule() {
        val bridge = KoogPromptBridge { _, _ -> error("Koog cannot open stage tools") }
        koinApplication { modules(roleExecutionModule(FakeModel(), bridge)) }.use { app ->
            assertNull(app.koin.getOrNull<StageToolSessionOpener>())
        }
        koinApplication {
            modules(roleExecutionModule(FakeModel(), bridge),
                stageToolModule(Path.of(System.getProperty("java.io.tmpdir"), "stage-74-koin-locks")))
        }.use { app ->
            assertNotNull(app.koin.get<StageToolSessionOpener>())
        }
    }

}
