package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

@Tag("unit")
class LinuxStageGitBaselineReconcilerDiagnosticCodesUnitTest {
    @Test
    fun exactDiagnosticCodes() {
        assertEquals("GIT_BASELINE_TIMEOUT", LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_BASELINE_TIMEOUT)
        assertEquals("INVALID_STAGE_PATH", LinuxStageGitBaselineReconcilerDiagnosticCodes.INVALID_STAGE_PATH)
        assertEquals("UNSAFE_STAGE_FILE_MODE", LinuxStageGitBaselineReconcilerDiagnosticCodes.UNSAFE_STAGE_FILE_MODE)
        assertEquals("GIT_COMMAND_INTERRUPTED", LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_COMMAND_INTERRUPTED)
        assertEquals("GIT_BASELINE_INTERRUPTED", LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_BASELINE_INTERRUPTED)
        assertEquals("GIT_COMMAND_UNAVAILABLE", LinuxStageGitBaselineReconcilerDiagnosticCodes.GIT_COMMAND_UNAVAILABLE)
        assertEquals("UNEXPECTED_GIT_CHANGE", LinuxStageGitBaselineReconcilerDiagnosticCodes.UNEXPECTED_GIT_CHANGE)
        assertEquals("STAGE_BRANCH_CHANGED", LinuxStageGitBaselineReconcilerDiagnosticCodes.STAGE_BRANCH_CHANGED)
        assertEquals("STAGE_WORKSPACE_CHANGED", LinuxStageGitBaselineReconcilerDiagnosticCodes.STAGE_WORKSPACE_CHANGED)
        assertEquals("FROZEN_ARTIFACT_CHANGED", LinuxStageGitBaselineReconcilerDiagnosticCodes.FROZEN_ARTIFACT_CHANGED)
        assertEquals("INVALID_GIT_BASELINE", LinuxStageGitBaselineReconcilerDiagnosticCodes.INVALID_GIT_BASELINE)
        assertEquals("INVALID_STAGE_CHANGESET", LinuxStageGitBaselineReconcilerDiagnosticCodes.INVALID_STAGE_CHANGESET)
        assertEquals("UNSAFE_GIT_ENTRY", LinuxStageGitBaselineReconcilerDiagnosticCodes.UNSAFE_GIT_ENTRY)
        assertEquals("UNCERTAIN_GIT_HANDOFF", LinuxStageGitBaselineReconcilerDiagnosticCodes.UNCERTAIN_GIT_HANDOFF)
        assertEquals("FOREIGN_GIT_OR_INDEX_EDIT", LinuxStageGitBaselineReconcilerDiagnosticCodes.FOREIGN_GIT_OR_INDEX_EDIT)
        assertEquals("POST_RESTORE_IMAGE_UNCONFIRMED", LinuxStageGitBaselineReconcilerDiagnosticCodes.POST_RESTORE_IMAGE_UNCONFIRMED)
    }
}
