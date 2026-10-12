package ai.yuriilab.recursivecoder

import java.time.Instant

/** Model-visible arguments contain data, never a command, working directory or execution authority. */
sealed interface WorkspaceTool {
    val name: String
    data class Read(val path: String) : WorkspaceTool { override val name = "read" }
    data class ListFiles(val directory: String = "") : WorkspaceTool { override val name = "list" }
    data class Find(val nameFragment: String) : WorkspaceTool { override val name = "find" }
    data class Search(val literal: String) : WorkspaceTool { override val name = "search" }
    data object GitStatus : WorkspaceTool { override val name = "git_status" }
    data class GitDiff(val path: String) : WorkspaceTool { override val name = "git_diff" }
}

sealed interface ToolData {
    data class FileText(val path: String, val text: String, val bytes: Int, val sha256: String) : ToolData
    data class Paths(val paths: List<String>) : ToolData
    data class Matches(val matches: List<Match>, val skippedFiles: Int = 0,
                       val truncated: Boolean = false) : ToolData
    data class Match(val path: String, val line: Int, val excerpt: String)
    data class Status(val entries: List<StatusEntry>) : ToolData
    data class StatusEntry(val path: String, val index: Char, val worktree: Char)
    data class Diff(val path: String, val patch: String) : ToolData
}

data class ToolOutcome(val receiptId: String, val tool: String, val data: ToolData? = null,
                       val deniedCode: String? = null) {
    val permitted get() = deniedCode == null
}

/** Metadata-only audit: neither arguments, file contents nor search results are persisted. */
data class ToolAudit(val receiptId: String, val tool: String, val started: Instant,
                     val permitted: Boolean, val code: String?, val outputBytes: Int,
                     val sha256: String? = null)


/** Trusted workspace access is injected by the application, never resolved in the session core. */
interface WorkspaceSession : AutoCloseable {
    fun verifyTask(path: java.nio.file.Path, expectedSha: String)
    fun execute(tool: WorkspaceTool, claimedExecutionId: String? = null): ToolOutcome
}

fun interface WorkspaceOpener {
    fun open(
        request: SessionRequest,
        onAudit: (ToolAudit) -> Unit,
        onStart: (String, String, java.time.Instant) -> Unit
    ): WorkspaceSession
}
