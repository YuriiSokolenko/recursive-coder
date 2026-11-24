package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class RecursiveGraphPolicyDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("INVALID_ROOT", RecursiveGraphPolicyDiagnosticCodes.INVALID_ROOT)
        assertEquals("GLOBAL_CHILD_LIMIT", RecursiveGraphPolicyDiagnosticCodes.GLOBAL_CHILD_LIMIT)
        assertEquals("CHILD_AUTHORITY_ESCALATION", RecursiveGraphPolicyDiagnosticCodes.CHILD_AUTHORITY_ESCALATION)
        assertEquals("INVALID_CHILD_DEPENDENCY", RecursiveGraphPolicyDiagnosticCodes.INVALID_CHILD_DEPENDENCY)
        assertEquals("CYCLIC_RECURSIVE_GRAPH", RecursiveGraphPolicyDiagnosticCodes.CYCLIC_RECURSIVE_GRAPH)
        assertEquals("INVALID_CHILD_RESERVATION", RecursiveGraphPolicyDiagnosticCodes.INVALID_CHILD_RESERVATION)
        assertEquals("NO_PROGRESS_LOOP", RecursiveGraphPolicyDiagnosticCodes.NO_PROGRESS_LOOP)
    }
}
