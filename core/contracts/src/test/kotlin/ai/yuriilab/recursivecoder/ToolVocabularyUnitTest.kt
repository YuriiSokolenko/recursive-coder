package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class ToolVocabularyUnitTest {
    @Test fun readToolNamesAndPolicyVocabularyRemainStable() {
        val tools = listOf(WorkspaceTool.Read("x"), WorkspaceTool.ListFiles(), WorkspaceTool.Find("x"),
            WorkspaceTool.Search("x"), WorkspaceTool.GitStatus, WorkspaceTool.GitDiff("x"))
        val expected = listOf("read", "list", "find", "search", "git_status", "git_diff")
        assertEquals(expected, tools.map { it.name })
        assertEquals(expected.toSet(), ToolAuthorizationPolicy.knownReadTools)
        assertEquals("read-only", ToolPolicyModes.READ_ONLY)
        assertEquals("scoped-write", ToolPolicyModes.SCOPED_WRITE)
        assertEquals("git_diff_check", StageCheckIds.GIT_DIFF_CHECK)
    }
}
