package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class SessionDiagnosticCategoriesUnitTest {
    @Test fun categoriesRetainV1Spelling() {
        assertEquals("policy", SessionDiagnosticCategories.POLICY)
        assertEquals("validation", SessionDiagnosticCategories.VALIDATION)
        assertEquals("provider", SessionDiagnosticCategories.PROVIDER)
        assertEquals("tool", SessionDiagnosticCategories.TOOL)
        assertEquals("infrastructure", SessionDiagnosticCategories.INFRASTRUCTURE)
        assertEquals("timeout", SessionDiagnosticCategories.TIMEOUT)
        assertEquals("cancellation", SessionDiagnosticCategories.CANCELLATION)
        assertEquals("checkpoint", SessionDiagnosticCategories.CHECKPOINT)
        assertEquals("protocol", SessionDiagnosticCategories.PROTOCOL)
    }
}
