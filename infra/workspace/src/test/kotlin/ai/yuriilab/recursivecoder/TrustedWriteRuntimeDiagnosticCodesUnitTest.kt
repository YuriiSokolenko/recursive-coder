package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class TrustedWriteRuntimeDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("PATH_CASE_COLLISION", TrustedWriteRuntimeDiagnosticCodes.PATH_CASE_COLLISION)
        assertEquals("INDEX_MUTATION_DENIED", TrustedWriteRuntimeDiagnosticCodes.INDEX_MUTATION_DENIED)
        assertEquals("FILE_SIZE_LIMIT", TrustedWriteRuntimeDiagnosticCodes.FILE_SIZE_LIMIT)
        assertEquals("WRITE_IO_DENIED", TrustedWriteRuntimeDiagnosticCodes.WRITE_IO_DENIED)
        assertEquals("MUTATION_DIGEST_MISMATCH", TrustedWriteRuntimeDiagnosticCodes.MUTATION_DIGEST_MISMATCH)
        assertEquals("WRITE_EXECUTION_DENIED", TrustedWriteRuntimeDiagnosticCodes.WRITE_EXECUTION_DENIED)
        assertEquals("CHECK_OUTPUT_LIMIT", TrustedWriteRuntimeDiagnosticCodes.CHECK_OUTPUT_LIMIT)
        assertEquals("CHECK_OUTPUT_UNAVAILABLE", TrustedWriteRuntimeDiagnosticCodes.CHECK_OUTPUT_UNAVAILABLE)
    }
}
