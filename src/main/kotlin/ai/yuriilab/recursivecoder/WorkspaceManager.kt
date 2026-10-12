package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

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
    data class Matches(val matches: List<Match>) : ToolData
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
                     val permitted: Boolean, val code: String?, val outputBytes: Int)

/**
 * A per-process read-only Git workspace boundary.
 *
 * Only files recorded as regular blobs in baseline HEAD are candidates for reads. SecureDirectoryStream
 * and NOFOLLOW_LINKS protect every path component and descriptor open; lacking this facility fails closed.
 * The exclusive lock lives outside the repository and is held through the entire session.
 */
class WorkspaceManager private constructor(
    val root: Path,
    val workspaceId: String,
    val baselineHead: String,
    val branch: String,
    val policyHash: String,
    private val allowed: Set<String>,
    private val denied: Set<String>,
    private val protectedGlobs: List<java.nio.file.PathMatcher>,
    private val tree: Map<String, String>,
    private val maxCalls: Int,
    private val git: RestrictedGit,
    private val rootKey: Any?,
    private val gitKey: Any?,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val sessionId: String,
    private val onAudit: (ToolAudit) -> Unit
) : AutoCloseable {
    private var calls = 0
    private var closed = false
    private var totalBytes = 0

    companion object {
        private val known = setOf("read", "list", "find", "search", "git_status", "git_diff")
        private const val maxReadBytes = 32 * 1024
        private const val maxToolOutputBytes = 48 * 1024
        private const val maxTotalOutputBytes = 256 * 1024

        /** lockDirectory and gitExecutable are trusted host configuration, never tool-call fields. */
        fun open(
            request: SessionRequest,
            onAudit: (ToolAudit) -> Unit = {},
            lockDirectory: Path = Path.of(System.getProperty("user.home"), ".recursive-coder", "locks"),
            gitExecutable: Path = Path.of("/usr/bin/git")
        ): WorkspaceManager {
            val mode = request.policy["mode"]!!.jsonPrimitive.content
            val allowed = request.policy["allowedTools"]!!.jsonArray.map { it.jsonPrimitive.content }
            val denied = request.policy["deniedTools"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (mode != "read-only" || allowed.any { it !in known } ||
                request.policy["writablePaths"]!!.jsonArray.isNotEmpty() ||
                (request.policy["immutableInputs"] as? JsonArray)?.isNotEmpty() == true)
                throw Refused("UNSUPPORTED_TOOL_POLICY", "policy", "Unsupported workspace tool policy")
            val root = try {
                if (Files.isSymbolicLink(request.rootPath)) throw IllegalArgumentException()
                request.rootPath.toRealPath()
            } catch (_: Exception) {
                throw Refused("INVALID_WORKSPACE", "policy", "Workspace root cannot be resolved")
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(root.resolve(".git")))
                throw Refused("UNSUPPORTED_WORKSPACE", "policy", "Only regular local Git working trees are supported")
            if (!gitExecutable.isAbsolute || !Files.isRegularFile(gitExecutable) || !Files.isExecutable(gitExecutable))
                throw Refused("GIT_UNAVAILABLE", "policy", "Configured Git binary is unavailable")
            // Do not depend on a model-provided PATH, HOME, Git config, hooks or environment.
            val git = RestrictedGit(root, gitExecutable)
            val checkedRoot = git.text(4096, "rev-parse", "--show-toplevel")
            val checkedGitDir = git.text(4096, "rev-parse", "--absolute-git-dir")
            if (Path.of(checkedRoot).toRealPath() != root ||
                Path.of(checkedGitDir).toRealPath() != root.resolve(".git").toRealPath())
                throw Refused("UNSUPPORTED_WORKSPACE", "policy", "Nested, bare or linked worktrees are not supported")
            // Git read-only commands can otherwise invoke repository-defined clean/process filters.
            // Treat any local command-capable Git configuration as unsupported for this milestone.
            val localConfig = git.text(256 * 1024, "config", "--local", "--list")
            if (localConfig.lineSequence().map { it.substringBefore('=').lowercase() }.any { key ->
                    key.startsWith("filter.") || key.startsWith("include.") ||
                        key.startsWith("includeif.") || key.startsWith("core.fsmonitor") ||
                        key.startsWith("core.hookspath") || key.startsWith("diff.external") ||
                        key.matches(Regex("diff\\..*\\.command")) ||
                        key.matches(Regex("diff\\..*\\.textconv"))
                })
                throw Refused("UNSAFE_GIT_CONFIGURATION", "policy",
                    "Repository Git configuration may execute external commands")
            val head = git.text(4096, "rev-parse", "HEAD")
            val currentBranch = git.text(4096, "branch", "--show-current")
            if (head != request.workspace["baselineSha"]!!.jsonPrimitive.content ||
                head != request.workspace["headSha"]!!.jsonPrimitive.content)
                throw Refused("WORKSPACE_BASELINE_MISMATCH", "policy", "Workspace HEAD changed")
            if (currentBranch.isBlank() ||
                currentBranch != request.workspace["branch"]?.jsonPrimitive?.content)
                throw Refused("WORKSPACE_BRANCH_MISMATCH", "policy", "Workspace branch is unavailable or changed")
            val rootKey = Files.readAttributes(root, BasicFileAttributes::class.java).fileKey()
            val gitKey = Files.readAttributes(root.resolve(".git"), BasicFileAttributes::class.java).fileKey()
            if (rootKey == null || gitKey == null)
                throw Refused("SANDBOX_UNAVAILABLE", "policy", "Filesystem identity is unavailable")
            // Test descriptor-based traversal support before admitting a session.
            Files.newDirectoryStream(root).use {
                if (it !is SecureDirectoryStream<*>)
                    throw Refused("SANDBOX_UNAVAILABLE", "policy", "Race-safe directory access is unavailable")
            }
            val patterns = try {
                request.policy["protectedPaths"]!!.jsonArray.map {
                    val pattern = it.jsonPrimitive.content
                    if (pattern.isBlank() || pattern.length > 256 || '\u0000' in pattern)
                        throw IllegalArgumentException()
                    root.fileSystem.getPathMatcher("glob:$pattern")
                }
            } catch (_: Exception) {
                throw Refused("INVALID_TOOL_POLICY", "policy", "Invalid protected path pattern")
            }
            val tree = git.text(4 * 1024 * 1024, "ls-tree", "-r", "-z", "--full-tree", "HEAD")
                .split('\u0000').asSequence().filter { it.isNotEmpty() }.associate { entry ->
                    val tab = entry.indexOf('\t')
                    val metadata = if (tab < 0) emptyList() else entry.substring(0, tab).split(' ')
                    if (metadata.size != 3 || metadata[0] !in setOf("100644", "100755", "120000", "160000"))
                        throw Refused("UNSUPPORTED_GIT_TREE", "policy", "Unexpected Git tree entry")
                    val path = entry.substring(tab + 1)
                    if (!validRelative(path)) throw Refused("UNSUPPORTED_GIT_TREE", "policy", "Unsafe Git tree path")
                    path to metadata[0]
                }
            if (tree.size > 50000) throw Refused("WORKSPACE_TOO_LARGE", "policy", "Git tree exceeds the supported limit")
            val policyWithoutClaim = JsonObject(request.policy.filterKeys { it != "policySha256" })
            val policyHash = sha256(policyWithoutClaim.toString().toByteArray(StandardCharsets.UTF_8))
            val locks = try {
                if (!lockDirectory.isAbsolute || lockDirectory.normalize().startsWith(root))
                    throw IllegalArgumentException()
                Files.createDirectories(lockDirectory)
                if (Files.isSymbolicLink(lockDirectory)) throw IllegalArgumentException()
                lockDirectory.toRealPath()
            } catch (_: Exception) {
                throw Refused("WORKSPACE_LOCK_UNAVAILABLE", "policy", "Trusted lock directory is unavailable")
            }
            if (locks.startsWith(root))
                throw Refused("WORKSPACE_LOCK_UNAVAILABLE", "policy", "Lock directory must be outside workspace")
            val lockName = sha256(root.toString().toByteArray(StandardCharsets.UTF_8)) + ".lock"
            val channel = try {
                FileChannel.open(locks.resolve(lockName),
                    setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
            } catch (_: Exception) {
                throw Refused("WORKSPACE_LOCK_UNAVAILABLE", "policy", "Cannot open workspace lease")
            }
            val lease = try { channel.tryLock() } catch (_: Exception) { null }
            if (lease == null) {
                channel.close()
                throw Refused("WORKSPACE_LOCKED", "policy", "Workspace already has an active session")
            }
            try {
                val manager = WorkspaceManager(root, request.workspace["workspaceId"]!!.jsonPrimitive.content,
                    head, currentBranch, policyHash, allowed.toSet(), denied.toSet(), patterns, tree,
                    request.budgets["maxToolCalls"]!!.jsonPrimitive.int, git, rootKey, gitKey,
                    channel, lease, request.sessionId, onAudit)
                manager.checkCurrent()
                return manager
            } catch (e: Exception) {
                lease.release(); channel.close(); throw e
            }
        }

        private fun validRelative(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith('/') && '\\' !in path &&
                path.length <= 4096 && path.split('/').all {
                    it.isNotEmpty() && it != "." && it != ".." &&
                        it.none { char -> char.code < 32 || char.code == 127 }
                }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun checkCurrent() {
        if (closed || !lock.isValid) deny("WORKSPACE_LOCK_LOST")
        if (Files.readAttributes(root, BasicFileAttributes::class.java).fileKey() != rootKey ||
            Files.readAttributes(root.resolve(".git"), BasicFileAttributes::class.java).fileKey() != gitKey)
            deny("WORKSPACE_REPLACED")
        if (git.text(4096, "rev-parse", "HEAD") != baselineHead ||
            git.text(4096, "branch", "--show-current") != branch)
            deny("WORKSPACE_BASELINE_MISMATCH")
    }

    private fun deny(code: String): Nothing =
        throw Refused(code, "policy", "Workspace operation denied: $code")

    private fun relative(path: String, allowRoot: Boolean = false): String {
        if (allowRoot && path.isEmpty()) return ""
        if (!validRelative(path)) deny("INVALID_TOOL_PATH")
        if (path.split('/').any { it.equals(".git", ignoreCase = true) }) deny("PROTECTED_PATH")
        return path
    }

    private fun confidential(path: String): Boolean {
        val components = path.lowercase().split('/')
        return components.any { component ->
            component in setOf(".git", ".ssh", ".aws", ".kube", ".gnupg", ".env",
                ".npmrc", ".pypirc", ".netrc", ".docker", "credentials", "secrets",
                "id_rsa", "id_ed25519", "gradle.properties", "local.properties") ||
                component.startsWith(".env.") || component.startsWith("credentials.") ||
                component.startsWith("secret.") || component.endsWith(".pem") ||
                component.endsWith(".key") || component.endsWith(".p12") ||
                component.endsWith(".pfx") || component.endsWith(".jks") ||
                component.endsWith(".keystore")
        }
    }

    private fun protected(path: String): Boolean {
        if (confidential(path)) return true
        val parts = path.split('/')
        return parts.indices.any { i ->
            val prefix = root.fileSystem.getPath(parts.take(i + 1).joinToString("/"))
            protectedGlobs.any { it.matches(prefix) }
        }
    }

    private fun permittedPath(path: String): Boolean =
        validRelative(path) && !protected(path) && tree[path] in setOf("100644", "100755")

    private fun authorized(path: String) {
        relative(path)
        if (protected(path)) deny("PROTECTED_PATH")
        if (tree.any { (entry, mode) -> mode == "160000" && path.startsWith("$entry/") })
            deny("SUBMODULE_BOUNDARY")
        if (tree[path] !in setOf("100644", "100755")) deny("UNTRACKED_OR_NONREGULAR_FILE")
    }

    private fun secureRead(path: String): ByteArray {
        authorized(path)
        Files.newDirectoryStream(root).use { initial ->
            @Suppress("UNCHECKED_CAST")
            var cursor = initial as? SecureDirectoryStream<Path> ?: deny("SANDBOX_UNAVAILABLE")
            if (cursor.getFileAttributeView(BasicFileAttributeView::class.java)
                    .readAttributes().fileKey() != rootKey)
                deny("WORKSPACE_REPLACED")
            val descendants = mutableListOf<SecureDirectoryStream<Path>>()
            try {
                val parts = path.split('/')
                for (part in parts.dropLast(1)) {
                    val next = try {
                        cursor.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) { deny("UNSAFE_PATH_COMPONENT") }
                    descendants.add(next)
                    cursor = next
                }
                val file = Path.of(parts.last())
                val attributes = try {
                    cursor.getFileAttributeView(file, BasicFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS).readAttributes()
                } catch (_: Exception) { deny("UNSAFE_FILE") }
                if (!attributes.isRegularFile || attributes.size() > maxReadBytes) deny("UNSAFE_FILE")
                val bytes = try {
                    cursor.newByteChannel(file,
                        setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteBuffer.allocate(8192)
                        while (true) {
                            val n = channel.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            buffer.flip()
                            output.write(buffer.array(), 0, n)
                            buffer.clear()
                            if (output.size() > maxReadBytes) deny("READ_LIMIT_EXCEEDED")
                        }
                        output.toByteArray()
                    }
                } catch (e: Refused) { throw e }
                catch (_: Exception) { deny("UNSAFE_FILE") }
                return bytes
            } finally {
                descendants.asReversed().forEach { it.close() }
            }
        }
    }

    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            .also { if ('\u0000' in it) deny("BINARY_FILE_DENIED") }
    } catch (e: Refused) { throw e }
    catch (_: Exception) { deny("BINARY_FILE_DENIED") }

    /** Internal admission check. This does not consume the model's tool-call allowance. */
    fun verifyTask(path: Path, expectedSha: String) {
        checkCurrent()
        if (!path.isAbsolute || !path.normalize().startsWith(root)) deny("INVALID_TASK_PATH")
        val rel = relative(root.relativize(path.normalize()).toString().replace('\\', '/'))
        if ("read" !in allowed || "read" in denied) deny("READ_NOT_AUTHORIZED")
        if (sha256(secureRead(rel)) != expectedSha) deny("TASK_DIGEST_MISMATCH")
    }

    /** Each attempted model tool call consumes a quota slot and generates an unforgeable host receipt ID. */
    @Synchronized
    fun execute(tool: WorkspaceTool, claimedExecutionId: String? = null): ToolOutcome {
        val started = Instant.now()
        val receipt = "tool-$sessionId-${++calls}"
        var error: String? = null
        var data: ToolData? = null
        var size = 0
        try {
            if (claimedExecutionId != null) deny("FORGED_TOOL_ID")
            if (calls > maxCalls) deny("TOOL_QUOTA_EXCEEDED")
            if (tool.name !in allowed || tool.name in denied) deny("TOOL_NOT_AUTHORIZED")
            checkCurrent()
            data = when (tool) {
                is WorkspaceTool.Read -> {
                    val path = relative(tool.path)
                    val bytes = secureRead(path)
                    ToolData.FileText(path, decode(bytes), bytes.size, sha256(bytes))
                }
                is WorkspaceTool.ListFiles -> {
                    val directory = relative(tool.directory, allowRoot = true)
                    if (directory.isNotEmpty() && protected(directory)) deny("PROTECTED_PATH")
                    val prefix = if (directory.isEmpty()) "" else "$directory/"
                    val paths = tree.keys.asSequence().filter {
                        it.startsWith(prefix) && permittedPath(it)
                    }.take(101).toList()
                    if (paths.size > 100) deny("RESULT_LIMIT_EXCEEDED")
                    ToolData.Paths(paths.sorted())
                }
                is WorkspaceTool.Find -> {
                    val fragment = tool.nameFragment
                    if (fragment.isBlank() || fragment.length > 128 ||
                        fragment.any { it.code < 32 }) deny("INVALID_FIND_QUERY")
                    val paths = tree.keys.asSequence().filter {
                        permittedPath(it) && it.substringAfterLast('/').contains(fragment, ignoreCase = true)
                    }.take(101).toList()
                    if (paths.size > 100) deny("RESULT_LIMIT_EXCEEDED")
                    ToolData.Paths(paths.sorted())
                }
                is WorkspaceTool.Search -> {
                    val query = tool.literal
                    if (query.isBlank() || query.length > 128 || query.any { it.code < 32 })
                        deny("INVALID_SEARCH_QUERY")
                    val candidates = tree.keys.filter(::permittedPath).sorted()
                    if (candidates.size > 256) deny("SEARCH_SCOPE_EXCEEDED")
                    val found = mutableListOf<ToolData.Match>()
                    var scanned = 0
                    for (path in candidates) {
                        val bytes = secureRead(path)
                        scanned += bytes.size
                        if (scanned > 1024 * 1024) deny("SEARCH_SCOPE_EXCEEDED")
                        val text = decode(bytes)
                        text.lineSequence().forEachIndexed { i, line ->
                            if (line.contains(query, ignoreCase = true)) {
                                if (found.size >= 100) deny("RESULT_LIMIT_EXCEEDED")
                                found += ToolData.Match(path, i + 1, line.take(160))
                            }
                        }
                    }
                    ToolData.Matches(found)
                }
                WorkspaceTool.GitStatus -> {
                    val raw = git.text(256 * 1024, "status", "--porcelain=v1", "-z", "--untracked-files=no")
                    val tokens = raw.split('\u0000')
                    val entries = mutableListOf<ToolData.StatusEntry>()
                    var i = 0
                    while (i < tokens.size) {
                        val record = tokens[i++]
                        if (record.isEmpty()) continue
                        if (record.length < 4) deny("GIT_OUTPUT_INVALID")
                        val path = record.substring(3)
                        val renamed = record.take(2).any { it == 'R' || it == 'C' }
                        if (renamed && i < tokens.size) i++ // Ignore the second path of a rename.
                        if (!permittedPath(path)) continue
                        entries += ToolData.StatusEntry(path, record[0], record[1])
                        if (entries.size > 100) deny("RESULT_LIMIT_EXCEEDED")
                    }
                    ToolData.Status(entries)
                }
                is WorkspaceTool.GitDiff -> {
                    val path = relative(tool.path)
                    authorized(path)
                    val patch = git.text(maxToolOutputBytes, "diff", "--no-ext-diff", "--no-textconv",
                        "--", ":(literal)$path")
                    ToolData.Diff(path, patch)
                }
            }
            // Never return a successful result if the repository drifted during this operation.
            checkCurrent()
            size = when (val value = data) {
                is ToolData.FileText -> value.bytes
                is ToolData.Paths -> value.paths.sumOf { it.toByteArray().size }
                is ToolData.Matches -> value.matches.sumOf { it.path.toByteArray().size + it.excerpt.toByteArray().size }
                is ToolData.Status -> value.entries.sumOf { it.path.toByteArray().size + 2 }
                is ToolData.Diff -> value.patch.toByteArray().size
                null -> 0
            }
            if (size > maxToolOutputBytes || totalBytes + size > maxTotalOutputBytes)
                deny("TOOL_OUTPUT_LIMIT_EXCEEDED")
            totalBytes += size
        } catch (e: Refused) {
            error = e.code
            data = null
            size = 0
        } catch (_: Exception) {
            error = "TOOL_EXECUTION_DENIED"
            data = null
            size = 0
        }
        onAudit(ToolAudit(receipt, tool.name, started, error == null, error, size))
        return ToolOutcome(receipt, tool.name, data, error)
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { lock.release() } finally { lockChannel.close() }
        }
    }
}

/** Fixed Git verbs with no shell, no inherited model environment and bounded process output/time. */
internal class RestrictedGit(private val root: Path, private val executable: Path) {
    fun text(maxBytes: Int, vararg arguments: String): String {
        val output = Files.createTempFile("recursive-coder-git-", ".out")
        try {
            val process = ProcessBuilder(
                listOf(executable.toString(), "--no-optional-locks", "-C", root.toString(),
                    "-c", "core.fsmonitor=false", "-c", "diff.external=") + arguments
            ).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output.toFile()).apply {
                    environment().clear()
                    environment()["LC_ALL"] = "C"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                    environment()["GIT_ATTR_NOSYSTEM"] = "1"
                    environment()["GIT_NO_REPLACE_OBJECTS"] = "1"
                    environment()["GIT_OPTIONAL_LOCKS"] = "0"
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }.start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw Refused("GIT_TIMEOUT", "policy", "Git inspection exceeded its deadline")
            }
            if (process.exitValue() != 0 || Files.size(output) > maxBytes)
                throw Refused("GIT_INSPECTION_DENIED", "policy", "Git inspection is unavailable or too large")
            return Files.readString(output, StandardCharsets.UTF_8).trimEnd('\n')
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
