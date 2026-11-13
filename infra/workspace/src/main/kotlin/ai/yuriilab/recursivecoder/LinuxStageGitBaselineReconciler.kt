package ai.yuriilab.recursivecoder

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Host-only Git handoff. The caller MUST separately authenticate the stage's
 * write/check receipts and host-approved artifact manifests before invoking it.
 * An accepted graph or StageValidationEvidence is not a physical capability.
 *
 * This component neither opens write tools nor exposes Git commands to agents.
 */
data class StageGitAcceptedFile(val path: String, val sha256: String)

data class StageGitReconciliationRequest(
    val expectedHead: String,
    val branch: String,
    val actorRole: EngineeringRole,
    val acceptedFiles: List<StageGitAcceptedFile>,
    val frozenArtifacts: Map<String, String> = emptyMap()
)

sealed interface StageGitReconciliationResult {
    data class Committed(val oldHead: String, val newHead: String) :
        StageGitReconciliationResult
    data class Denied(val code: String) : StageGitReconciliationResult
    /** The branch ref or index MAY have changed; inspect under a fresh host fence. */
    data class ReconciliationRequired(val code: String) : StageGitReconciliationResult
}

/**
 * Reconciles exactly one independently accepted stage change set into a new,
 * clean Git HEAD without running a shell, filters, Git hooks, or model commands.
 *
 * The lock file is deliberately identical to TrustedWriteRuntime's workspace
 * lease. A trusted writer and this reconciler cannot operate concurrently.
 * This does not fence arbitrary external processes: callers must own the
 * workspace and quarantine it if the post-commit readback is uncertain.
 *
 * Git object writes may leave unreachable objects on failure. Once update-ref
 * is attempted, any exception is an UNKNOWN commit, never a retryable Denied.
 */
class LinuxStageGitBaselineReconciler(
    private val root: Path,
    private val lockDirectory: Path =
        Path.of(System.getProperty("user.home"), ".recursive-coder", "locks"),
    private val gitExecutable: Path = findGit()
) {
    private class Reject(val code: String) : RuntimeException()
    private data class OpenedFile(val bytes: ByteArray, val gitMode: String)
    private val sha256 = Regex("[0-9a-f]{64}")
    private val gitObjectId = Regex("[0-9a-f]{40}")
    private val gitRef = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
    private val safeRemoteOrBranchKey = Regex("""(remote|branch)\.[A-Za-z0-9_.-]+\.(url|fetch|remote|merge)""")

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun check(allowed: Boolean, code: String) {
        if (!allowed) throw Reject(code)
    }

    private fun canonicalRoot(): Path {
        check(System.getProperty("os.name").lowercase().contains("linux"), "LINUX_REQUIRED")
        check(root.isAbsolute && !Files.isSymbolicLink(root), "INVALID_WORKSPACE")
        val real = root.toRealPath()
        check(real == root.normalize() && Files.isDirectory(real), "INVALID_WORKSPACE")
        check(Files.isDirectory(real.resolve(".git"), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(real.resolve(".git")), "UNSUPPORTED_WORKSPACE")
        return real
    }

    /**
     * Descriptor-relative no-follow reads like TrustedWriteRuntime. Path-based
     * isRegularFile/readAllBytes would be vulnerable to a symlink swap between
     * the check and read, even under the trusted-writer coordination lock.
     */
    private fun read(
        root: Path, path: String, requireCanonicalMode: Boolean = false
    ): OpenedFile {
        check(ToolAuthorizationPolicy.validDeclaredPath(path), "INVALID_STAGE_PATH")
        val key = Files.readAttributes(root, BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).fileKey()
        check(key != null, "SANDBOX_UNAVAILABLE")
        Files.newDirectoryStream(root).use { openedRoot ->
            val first = openedRoot as? SecureDirectoryStream<Path>
                ?: throw Reject("SANDBOX_UNAVAILABLE")
            check(first.getFileAttributeView(BasicFileAttributeView::class.java)
                .readAttributes().fileKey() == key, "WORKSPACE_REPLACED")
            var cursor = first
            val opened = mutableListOf<SecureDirectoryStream<Path>>()
            try {
                val parts = path.split('/')
                for (component in parts.dropLast(1)) {
                    check(cursor.any { it.fileName.toString() == component },
                        "UNSAFE_STAGE_PATH")
                    val next = try {
                        cursor.newDirectoryStream(Path.of(component), LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) {
                        throw Reject("UNSAFE_STAGE_PATH")
                    }
                    opened += next
                    cursor = next
                }
                val name = Path.of(parts.last())
                check(cursor.any { it.fileName.toString() == name.toString() },
                    "UNSAFE_STAGE_FILE")
                val attributes = try {
                    cursor.getFileAttributeView(name, BasicFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS).readAttributes()
                } catch (_: Exception) {
                    throw Reject("UNSAFE_STAGE_FILE")
                }
                check(attributes.isRegularFile && attributes.size() <= 4 * 1024 * 1024L,
                    "UNSAFE_STAGE_FILE")
                val perms = try {
                    cursor.getFileAttributeView(name, PosixFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS).readAttributes().permissions()
                } catch (_: Exception) { throw Reject("UNSAFE_STAGE_FILE_MODE") }
                // Git stores regular files as 100644 or 100755: umask-adjusted
                // read/write bits (0600, 0644, 0664) do not change Git mode.
                // TrustedWriteRuntime uses CREATE_NEW and respects process umask,
                // so requiring the exact 0644 POSIX set would reject valid writes.
                // Git's core.filemode=true checks the owner execute bit only.
                // Group/other execute without owner execute is an unusual
                // candidate mode, so reject it before update-ref rather than
                // permit ambiguous cross-platform permission semantics.
                val ownerExecutable = PosixFilePermission.OWNER_EXECUTE in perms
                val mode = if (ownerExecutable) "100755" else "100644"
                if (requireCanonicalMode) {
                    check(ownerExecutable || (
                        PosixFilePermission.GROUP_EXECUTE !in perms &&
                        PosixFilePermission.OTHERS_EXECUTE !in perms),
                        "UNSAFE_STAGE_FILE_MODE")
                    // Accepted writes must be owner-readable/writable and must
                    // not be world-writable. Immutable inputs only need a safe
                    // descriptor read, accepted digest and clean Git status.
                    check(PosixFilePermission.OWNER_READ in perms &&
                        PosixFilePermission.OWNER_WRITE in perms &&
                        PosixFilePermission.OTHERS_WRITE !in perms,
                        "UNSAFE_STAGE_FILE_MODE")
                }
                return try {
                    cursor.newByteChannel(name, setOf<OpenOption>(StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS)).use { channel ->
                        val result = ByteArrayOutputStream()
                        val buffer = ByteBuffer.allocate(8192)
                        while (true) {
                            val n = channel.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            buffer.flip()
                            result.write(buffer.array(), 0, n)
                            buffer.clear()
                            check(result.size() <= 4 * 1024 * 1024,
                                "STAGE_FILE_TOO_LARGE")
                        }
                        OpenedFile(result.toByteArray(), mode)
                    }
                } catch (e: Reject) {
                    throw e
                } catch (_: Exception) {
                    throw Reject("UNSAFE_STAGE_FILE")
                }
            } finally {
                opened.asReversed().forEach { it.close() }
            }
        }
    }

    /** Fixed-argument Git process; never uses a shell, hooks or external filters. */
    private fun git(root: Path, args: List<String>,
        input: ByteArray? = null, index: Path? = null): String {
        val process = ProcessBuilder(listOf(gitExecutable.toString(),
            "--no-optional-locks", "-C", root.toString(),
            "-c", "core.fsmonitor=false", "-c", "diff.external=",
            "-c", "core.hooksPath=/dev/null", "-c", "core.filemode=true") + args)
            .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment().clear()
                environment()["LC_ALL"] = "C"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                environment()["GIT_ATTR_NOSYSTEM"] = "1"
                environment()["GIT_NO_REPLACE_OBJECTS"] = "1"
                environment()["GIT_OPTIONAL_LOCKS"] = "0"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_AUTHOR_NAME"] = "Recursive Coder"
                environment()["GIT_AUTHOR_EMAIL"] = "recursive-coder@localhost"
                environment()["GIT_COMMITTER_NAME"] = "Recursive Coder"
                environment()["GIT_COMMITTER_EMAIL"] = "recursive-coder@localhost"
                if (index != null) environment()["GIT_INDEX_FILE"] = index.toString()
            }.start()
        val worker = Executors.newSingleThreadExecutor {
            Thread(it, "recursive-coder-git-handoff").apply { isDaemon = true }
        }
        val future = worker.submit<ByteArray> {
            process.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 1024 * 1024) throw Reject("GIT_OUTPUT_LIMIT")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        try {
            process.outputStream.use { pipe -> if (input != null) pipe.write(input) }
            val output = future.get(15, TimeUnit.SECONDS)
            check(process.waitFor(1, TimeUnit.SECONDS), "GIT_TIMEOUT")
            check(process.exitValue() == 0, "GIT_COMMAND_DENIED")
            return StandardCharsets.UTF_8.newDecoder().decode(
                java.nio.ByteBuffer.wrap(output)).toString().trimEnd('\n')
        } catch (e: Reject) {
            throw e
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is Reject) throw cause
            throw Reject("GIT_COMMAND_UNAVAILABLE")
        } catch (_: Exception) {
            throw Reject("GIT_COMMAND_UNAVAILABLE")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            future.cancel(true)
            worker.shutdownNow()
        }
    }

    private fun status(root: Path): Map<String, String> {
        val raw = git(root, listOf("status", "--porcelain=v1", "-z",
            "--untracked-files=all", "--ignored=matching", "--ignore-submodules=none"))
        // With -z, a rename has another NUL-separated path. Renames and
        // copies are denied because no allowed status has an index-side change.
        val paths = linkedMapOf<String, String>()
        for (record in raw.split('\u0000').filter(String::isNotEmpty)) {
            check(record.length >= 4 && record[2] == ' ', "GIT_STATUS_INVALID")
            val state = record.substring(0, 2)
            val path = record.substring(3)
            check(state == "??" || state == " M", "UNEXPECTED_GIT_CHANGE")
            check(ToolAuthorizationPolicy.validDeclaredPath(path) &&
                paths.put(path, state) == null, "GIT_STATUS_INVALID")
        }
        return paths
    }

    private fun verifyFacts(root: Path, request: StageGitReconciliationRequest,
        expectedStatuses: Map<String, String>): Map<String, OpenedFile> {
        check(git(root, listOf("rev-parse", "HEAD")) == request.expectedHead,
            "STAGE_BASELINE_CHANGED")
        check(git(root, listOf("symbolic-ref", "-q", "HEAD")) ==
            "refs/heads/" + request.branch, "STAGE_BRANCH_CHANGED")
        check(status(root) == expectedStatuses, "STAGE_WORKSPACE_CHANGED")
        for ((path, hash) in request.frozenArtifacts) {
            check(digest(read(root, path).bytes) == hash, "FROZEN_ARTIFACT_CHANGED")
        }
        return request.acceptedFiles.associate { change ->
            val bytes = read(root, change.path, requireCanonicalMode = true)
            check(digest(bytes.bytes) == change.sha256, "STAGE_ARTIFACT_CHANGED")
            change.path to bytes
        }
    }

    private fun preflight(root: Path, request: StageGitReconciliationRequest):
        Pair<Map<String, String>, Map<String, OpenedFile>> {
        check(gitObjectId.matches(request.expectedHead) &&
            gitRef.matches(request.branch) && !request.branch.contains("..") &&
            !request.branch.contains("//") && !request.branch.endsWith("/") &&
            !request.branch.endsWith(".lock") &&
            !request.branch.split('/').any { it.startsWith(".") },
            "INVALID_GIT_BASELINE")
        check(request.acceptedFiles.isNotEmpty() &&
            request.acceptedFiles.size <= 8, "INVALID_STAGE_CHANGESET")
        val names = request.acceptedFiles.map { it.path }
        check(names.toSet().size == names.size &&
            request.frozenArtifacts.keys.none { it in names }, "INVALID_STAGE_CHANGESET")
        check(request.acceptedFiles.all {
            sha256.matches(it.sha256) &&
                ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                    request.actorRole, WriteOperationKind.CREATE, it.path
                )) == ToolAuthorizationDecision.Permit
        } && request.frozenArtifacts.all { (path, hash) ->
            ToolAuthorizationPolicy.validDeclaredPath(path) && sha256.matches(hash)
        }, "INVALID_STAGE_CHANGESET")
        check(git(root, listOf("rev-parse", "--show-toplevel")) == root.toString() &&
            Path.of(git(root, listOf("rev-parse", "--absolute-git-dir"))).toRealPath() ==
            root.resolve(".git").toRealPath(), "UNSUPPORTED_WORKSPACE")
        // Explicit allowlist: repository-local configuration must not alter
        // filters, hooks, index, worktree, ref or attributes semantics.
        val config = git(root, listOf("config", "--local", "--list"))
        val safeConfig = config.lineSequence().filter { it.isNotBlank() }.all { entry ->
            val key = entry.substringBefore('=').lowercase()
            val value = entry.substringAfter('=', missingDelimiterValue = "")
            when (key) {
                "core.repositoryformatversion" -> value == "0"
                "core.bare" -> value == "false"
                "core.filemode", "core.symlinks", "core.logallrefupdates" -> value == "true"
                "core.ignorecase", "core.precomposeunicode" -> value == "false"
                "user.name", "user.email" -> value.isNotBlank()
                else -> safeRemoteOrBranchKey.matches(key)
            }
        }
        check(safeConfig && config.lines().any { it == "core.filemode=true" },
            "UNSAFE_GIT_CONFIGURATION")
        val flags = git(root, listOf("ls-files", "-v", "-z"))
        check(flags.split('\u0000').filter(String::isNotEmpty).all {
            it.startsWith("H ")
        }, "UNSAFE_GIT_INDEX")
        val staged = git(root, listOf("ls-files", "--stage", "-z"))
        check(staged.split('\u0000').filter(String::isNotEmpty).all {
            it.startsWith("100644 ") || it.startsWith("100755 ")
        }, "UNSAFE_GIT_INDEX")
        val changed = status(root)
        check(changed.keys == names.toSet(), "UNEXPECTED_GIT_CHANGE")
        val bytes = verifyFacts(root, request, changed)
        // Commit-tree records file modes. Mode-only changes are not part of
        // trusted writes and must be rejected BEFORE the ref CAS.
        for (item in request.acceptedFiles) {
            val status = changed.getValue(item.path)
            val trackedMode = if (status == "??") null else {
                val line = git(root, listOf("ls-tree", "HEAD", "--", item.path))
                line.substringBefore(' ').takeIf { it in setOf("100644", "100755") }
                    ?: throw Reject("UNSAFE_GIT_ENTRY")
            }
            val expectedMode = trackedMode ?: "100644"
            check(bytes.getValue(item.path).gitMode == expectedMode,
                "UNSAFE_STAGE_FILE_MODE")
            val decision = ToolAuthorizationPolicy.authorizeWrite(WriteAuthorizationFacts(
                request.actorRole,
                if (trackedMode == null) WriteOperationKind.CREATE else WriteOperationKind.EDIT,
                item.path, trackedGitMode = trackedMode,
                immutableArtifactPaths = request.frozenArtifacts.keys
            ))
            check(decision == ToolAuthorizationDecision.Permit, "STAGE_ROLE_DENIED")
        }
        return changed to bytes
    }

    /**
     * On success returns a clean new HEAD. The caller MUST persist the new
     * baseline in its fenced host snapshot before admitting another stage.
     */
    fun reconcile(request: StageGitReconciliationRequest): StageGitReconciliationResult {
        var updatedRef = false
        var index: Path? = null
        try {
            val real = canonicalRoot()
            check(lockDirectory.isAbsolute && !Files.isSymbolicLink(lockDirectory),
                "WORKSPACE_LOCK_UNAVAILABLE")
            Files.createDirectories(lockDirectory)
            val locks = lockDirectory.toRealPath()
            check(!locks.startsWith(real) && !real.startsWith(locks),
                "WORKSPACE_LOCK_UNAVAILABLE")
            val key = digest(real.toString().toByteArray())
            val lockPath = locks.resolve("$key.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val lease = try { channel.tryLock() } catch (_: Exception) { null }
                check(lease != null, "WORKSPACE_LOCKED")
                requireNotNull(lease).use {
                    val (before, contents) = preflight(real, request)
                    index = Files.createTempFile(locks, "stage-git-index-", ".tmp")
                    Files.delete(requireNotNull(index))
                    git(real, listOf("read-tree", request.expectedHead), index = index)
                    for (item in request.acceptedFiles.sortedBy { it.path }) {
                        val oid = git(real, listOf("hash-object", "-w", "--stdin"),
                            contents.getValue(item.path).bytes)
                        check(gitObjectId.matches(oid), "INVALID_GIT_OBJECT")
                        val mode = if (before.getValue(item.path) == "??") "100644" else {
                            val entry = git(real, listOf("ls-tree", "HEAD", "--", item.path))
                            val trackedMode = entry.substringBefore(' ')
                            check(trackedMode == "100644" || trackedMode == "100755",
                                "UNSAFE_GIT_ENTRY")
                            trackedMode
                        }
                        git(real, listOf("update-index", "--add", "--cacheinfo",
                            "$mode,$oid,${item.path}"), index = index)
                    }
                    val tree = git(real, listOf("write-tree"), index = index)
                    check(gitObjectId.matches(tree), "INVALID_GIT_TREE")
                    val commit = git(real, listOf("commit-tree", tree,
                        "-p", request.expectedHead, "-m",
                        "Recursive Coder accepted " + request.actorRole.name.lowercase() + " stage"))
                    check(gitObjectId.matches(commit), "INVALID_GIT_COMMIT")
                    verifyFacts(real, request, before)
                    // From here the ref may have moved, even if Git reports failure.
                    updatedRef = true
                    git(real, listOf("update-ref", "refs/heads/" + request.branch,
                        commit, request.expectedHead))
                    // This only updates the index; working-tree contents are already
                    // the exact accepted bytes and must never be reset by the host.
                    git(real, listOf("read-tree", "--reset", commit))
                    check(git(real, listOf("rev-parse", "HEAD")) == commit &&
                        status(real).isEmpty(), "UNCERTAIN_GIT_HANDOFF")
                    for ((path, hash) in request.frozenArtifacts)
                        check(digest(read(real, path).bytes) == hash, "UNCERTAIN_GIT_HANDOFF")
                    for (item in request.acceptedFiles)
                        check(digest(read(real, item.path,
                            requireCanonicalMode = true).bytes) == item.sha256,
                            "UNCERTAIN_GIT_HANDOFF")
                    return StageGitReconciliationResult.Committed(
                        request.expectedHead, commit)
                }
            }
        } catch (error: Reject) {
            return if (updatedRef) StageGitReconciliationResult.ReconciliationRequired(error.code)
            else StageGitReconciliationResult.Denied(error.code)
        } catch (_: Exception) {
            return if (updatedRef)
                StageGitReconciliationResult.ReconciliationRequired("GIT_HANDOFF_UNCONFIRMED")
            else StageGitReconciliationResult.Denied("GIT_HANDOFF_UNAVAILABLE")
        } finally {
            try { index?.let { Files.deleteIfExists(it) } } catch (_: Exception) {
                // An orphaned private temporary index is not an accepted result.
            }
        }
    }

    companion object {
        private fun findGit(): Path {
            val selected = System.getProperty("recursiveCoder.gitExecutable")
            if (selected != null) return Path.of(selected)
            return System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
                .asSequence().filter(String::isNotBlank).map { Path.of(it).resolve("git") }
                .firstOrNull { it.isAbsolute && Files.isExecutable(it) }
                ?: Path.of("/usr/bin/git")
        }
    }
}
