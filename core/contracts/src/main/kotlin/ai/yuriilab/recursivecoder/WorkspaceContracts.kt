package ai.yuriilab.recursivecoder

import java.time.Instant

/** Model-visible arguments contain data, never a command, working directory or execution authority. */
sealed interface WorkspaceTool {
    val name: String
    data class Read(val path: String) : WorkspaceTool { override val name = WorkspaceToolNames.READ }
    data class ListFiles(val directory: String = "") : WorkspaceTool { override val name = WorkspaceToolNames.LIST }
    data class Find(val nameFragment: String) : WorkspaceTool { override val name = WorkspaceToolNames.FIND }
    data class Search(val literal: String) : WorkspaceTool { override val name = WorkspaceToolNames.SEARCH }
    data object GitStatus : WorkspaceTool { override val name = WorkspaceToolNames.GIT_STATUS }
    data class GitDiff(val path: String) : WorkspaceTool { override val name = WorkspaceToolNames.GIT_DIFF }
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
    fun taskRelativePath(path: java.nio.file.Path): String
    fun execute(tool: WorkspaceTool, claimedExecutionId: String? = null): ToolOutcome
}

fun interface WorkspaceOpener {
    fun open(
        request: SessionRequest,
        onAudit: (ToolAudit) -> Unit,
        onStart: (String, String, java.time.Instant) -> Unit
    ): WorkspaceSession
}

/** Stable read-tool policy and audit names. */
object WorkspaceToolNames {
    const val READ = "read"
    const val LIST = "list"
    const val FIND = "find"
    const val SEARCH = "search"
    const val GIT_STATUS = "git_status"
    const val GIT_DIFF = "git_diff"
}

/** Session request tool-policy mode identifiers, not an authorization grant. */
object ToolPolicyModes {
    const val READ_ONLY = "read-only"
    const val SCOPED_WRITE = "scoped-write"
}
