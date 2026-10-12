package ai.yuriilab.recursivecoder

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Host-owned write capability. Never deserialize this type from an agent's tool arguments.
 * In particular, reviewedChange must come from an independent authenticated control plane.
 */
enum class EngineeringRole { SDD, ATDD, TDD, DEVELOPER, REVIEWER }

data class ReviewedChange(
    val changeRequestId: String,
    val requestedBy: String,
    val reviewedBy: String,
    val role: EngineeringRole,
    val paths: Set<String>
)

data class TrustedCheck(
    val executable: Path,
    val arguments: List<String>,
    val timeoutMs: Long = 10_000,
    val maxOutputBytes: Int = 32 * 1024
)

data class TrustedWritePolicy(
    val sessionId: String,
    val root: Path,
    val baselineHead: String,
    val branch: String,
    val role: EngineeringRole,
    val approvedArtifacts: Map<String, String> = emptyMap(),
    val reviewedChange: ReviewedChange? = null,
    val checks: Map<String, TrustedCheck> = emptyMap(),
    val requiredChecks: Set<String> = setOf("git_diff_check"),
    val maxToolCalls: Int = 64
)

sealed interface WriteOperation {
    val path: String
    data class Create(override val path: String, val content: String) : WriteOperation
    data class Edit(override val path: String, val expectedSha256: String, val content: String) : WriteOperation
    data class Delete(override val path: String, val expectedSha256: String) : WriteOperation
}

data class WriteToolOutcome(val toolId: String, val deniedCode: String? = null) {
    val permitted: Boolean get() = deniedCode == null
}

data class TrustedCheckReceipt(
    val toolId: String,
    val check: String,
    val sessionId: String,
    val baselineHead: String,
    val policyHash: String,
    val manifestHash: String,
    val mac: String
)

data class CheckOutcome(
    val toolId: String,
    val deniedCode: String? = null,
    val receipt: TrustedCheckReceipt? = null
) {
    val passed: Boolean get() = deniedCode == null && receipt != null
}

data class FinalWriteVerification(val successful: Boolean, val deniedCode: String? = null)

/**
 * Synchronous, lease-bound trusted write/check boundary. Only clean, ordinary Git working
 * trees are admitted. Every operation uses descriptor-relative, no-follow file access.
 * A failed partial batch poisons the session: there is no implicit rollback or success.
 *
 * This is a host service, not a model tool exposed by the bootstrap read-only CLI.
 */
class TrustedWriteRuntime private constructor(
    private val policy: TrustedWritePolicy,
    private val root: Path,
    private val rootKey: Any,
    private val gitKey: Any,
    private val git: RestrictedGit,
    private val configHash: String,
    private val tracked: Map<String, String>,
    private val leaseChannel: FileChannel,
    private val lease: FileLock,
    val policyHash: String,
    private val audit: (String, String, String?) -> Unit
) : AutoCloseable {
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    private val accepted = linkedMapOf<String, String?>()
    private val issued = mutableMapOf<String, TrustedCheckReceipt>()
    private var sequence = 0
    private var poisoned = false
    private var closed = false

    companion object {
        private const val MAX_FILE_BYTES = 64 * 1024
        private val shaPattern = Regex("[a-f0-9]{64}")
        private val gitPattern = Regex("[a-f0-9]{40}")
        private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")

        private fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun fail(code: String): Nothing = throw Refused(code, "policy", "Trusted write denied: " + code)

        /** The host chooses executable paths, reviews, artifacts, and the external lease location. */
        fun open(
            policy: TrustedWritePolicy,
            lockDirectory: Path = Path.of(System.getProperty("user.home"), ".recursive-coder", "locks"),
            gitExecutable: Path = discoverGit(),
            audit: (String, String, String?) -> Unit = { _, _, _ -> }
        ): TrustedWriteRuntime {
            if (!idPattern.matches(policy.sessionId) || !gitPattern.matches(policy.baselineHead) ||
                policy.branch.isBlank() || policy.maxToolCalls !in 1..10_000 ||
                policy.requiredChecks.isEmpty() ||
                policy.requiredChecks.any { it != "git_diff_check" && it !in policy.checks } ||
                policy.checks.keys.any { !idPattern.matches(it) || it == "git_diff_check" })
                fail("INVALID_WRITE_POLICY")
            val review = policy.reviewedChange
            if (review != null && (!idPattern.matches(review.changeRequestId) ||
                review.requestedBy.isBlank() || review.reviewedBy.isBlank() ||
                review.requestedBy == review.reviewedBy || review.role != policy.role ||
                review.paths.isEmpty())) fail("INVALID_REVIEW_AUTHORIZATION")
            if (policy.approvedArtifacts.any { (path, hash) -> !validPath(path) || !shaPattern.matches(hash) } ||
                (review != null && review.paths.any { !validPath(it) || it !in policy.approvedArtifacts }))
                fail("INVALID_WRITE_POLICY")
            if (policy.checks.values.any {
                    !it.executable.isAbsolute || !Files.isRegularFile(it.executable) ||
                        !Files.isExecutable(it.executable) || it.timeoutMs !in 100..120_000 ||
                        it.maxOutputBytes !in 1..65536 || it.arguments.any { arg -> '\u0000' in arg }
                }) fail("INVALID_CHECK_POLICY")
            if (!policy.root.isAbsolute || Files.isSymbolicLink(policy.root)) fail("INVALID_WORKSPACE")
            val root = try { policy.root.toRealPath() } catch (_: Exception) { fail("INVALID_WORKSPACE") }
            if (!Files.isDirectory(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(root.resolve(".git"))) fail("UNSUPPORTED_WORKSPACE")
            if (!gitExecutable.isAbsolute || !Files.isExecutable(gitExecutable)) fail("GIT_UNAVAILABLE")
            val git = RestrictedGit(root, gitExecutable)
            if (Path.of(git.text(4096, "rev-parse", "--show-toplevel")).toRealPath() != root ||
                Path.of(git.text(4096, "rev-parse", "--absolute-git-dir")).toRealPath() !=
                    root.resolve(".git").toRealPath()) fail("UNSUPPORTED_WORKSPACE")
            val config = git.text(256 * 1024, "config", "--local", "--list")
            if (config.lineSequence().map { it.substringBefore('=').lowercase() }.any {
                    it.startsWith("filter.") || it.startsWith("include.") ||
                        it.startsWith("includeif.") || it.startsWith("core.fsmonitor") ||
                        it.startsWith("core.hookspath") || it.startsWith("diff.external") ||
                        it.matches(Regex("diff\\..*\\.(command|textconv)"))
                }) fail("UNSAFE_GIT_CONFIGURATION")
            if (git.text(4096, "rev-parse", "HEAD") != policy.baselineHead ||
                git.text(4096, "branch", "--show-current") != policy.branch)
                fail("WORKSPACE_BASELINE_MISMATCH")
            val tracked = git.text(4 * 1024 * 1024, "ls-tree", "-r", "-z", "--full-tree", "HEAD")
                .split('\u0000').filter(String::isNotEmpty).associate { entry ->
                    val tab = entry.indexOf('\t')
                    if (tab < 0) fail("UNSUPPORTED_GIT_TREE")
                    val header = entry.substring(0, tab).split(' ')
                    if (header.size != 3 || !validPath(entry.substring(tab + 1))) fail("UNSUPPORTED_GIT_TREE")
                    entry.substring(tab + 1) to header[0]
                }
            if (tracked.size > 50_000 || gitStatus(git).isNotEmpty()) fail("DIRTY_WORKSPACE")
            val rootKey = Files.readAttributes(root, BasicFileAttributes::class.java).fileKey()
                ?: fail("SANDBOX_UNAVAILABLE")
            val gitKey = Files.readAttributes(root.resolve(".git"), BasicFileAttributes::class.java).fileKey()
                ?: fail("SANDBOX_UNAVAILABLE")
            Files.newDirectoryStream(root).use {
                if (it !is SecureDirectoryStream<*>) fail("SANDBOX_UNAVAILABLE")
            }
            val locks = try {
                if (!lockDirectory.isAbsolute || Files.isSymbolicLink(lockDirectory)) fail("WORKSPACE_LOCK_UNAVAILABLE")
                Files.createDirectories(lockDirectory)
                lockDirectory.toRealPath()
            } catch (_: Exception) { fail("WORKSPACE_LOCK_UNAVAILABLE") }
            if (locks.startsWith(root) || root.startsWith(locks)) fail("WORKSPACE_LOCK_UNAVAILABLE")
            val lockFile = locks.resolve(sha(root.toString().toByteArray()) + ".lock")
            val channel = try {
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) { fail("WORKSPACE_LOCK_UNAVAILABLE") }
            val lock = try { channel.tryLock() } catch (_: Exception) { null }
            if (lock == null) { channel.close(); fail("WORKSPACE_LOCKED") }
            try {
                val policyHash = sha(listOf(policy.sessionId, policy.baselineHead, policy.branch,
                    policy.role.name, policy.approvedArtifacts.toSortedMap().toString(),
                    policy.reviewedChange.toString(), policy.requiredChecks.sorted().toString(),
                    policy.checks.toSortedMap().toString()).joinToString("\u0000").toByteArray())
                val runtime = TrustedWriteRuntime(policy, root, rootKey, gitKey, git,
                    sha(config.toByteArray()), tracked, channel, lock, policyHash, audit)
                for ((path, expected) in policy.approvedArtifacts) {
                    if (sha(runtime.readFile(path) ?: fail("APPROVED_ARTIFACT_MISSING")) != expected)
                        fail("APPROVED_ARTIFACT_CHANGED")
                }
                runtime.verifyState()
                return runtime
            } catch (e: Exception) {
                lock.release(); channel.close(); throw e
            }
        }

        private fun discoverGit(): Path {
            val configured = System.getProperty("recursiveCoder.gitExecutable")
            if (configured != null) return Path.of(configured)
            return System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
                .asSequence().filter(String::isNotBlank).map { Path.of(it).resolve("git") }
                .firstOrNull { it.isAbsolute && Files.isExecutable(it) } ?: fail("GIT_UNAVAILABLE")
        }

        private fun validPath(path: String): Boolean = path.isNotEmpty() && path.length <= 4096 &&
            !path.startsWith('/') && '\\' !in path && path.split('/').all {
                it.isNotEmpty() && it != "." && it != ".." &&
                    it.none { char -> char.code < 32 || char.code == 127 }
            }

        private fun gitStatus(git: RestrictedGit): Map<String, String> {
            val records = git.text(1024 * 1024, "status", "--porcelain=v1", "-z",
                "--untracked-files=all", "--ignore-submodules=none").split('\u0000')
            val found = linkedMapOf<String, String>()
            var index = 0
            while (index < records.size) {
                val record = records[index++]
                if (record.isEmpty()) continue
                if (record.length < 4 || record[2] != ' ') fail("GIT_STATUS_INVALID")
                val xy = record.substring(0, 2)
                val path = record.substring(3)
                if (!validPath(path)) fail("GIT_STATUS_INVALID")
                found[path] = xy
                if (xy.contains('R') || xy.contains('C')) {
                    if (index >= records.size) fail("GIT_STATUS_INVALID")
                    val oldPath = records[index++]
                    if (!validPath(oldPath)) fail("GIT_STATUS_INVALID")
                    found[oldPath] = xy
                }
            }
            return found
        }
    }

    private fun nextId(tool: String): String {
        val id = "tool-" + policy.sessionId + "-" + (++sequence)
        audit(id, tool, null)
        return id
    }

    private fun report(id: String, tool: String, denied: String?) = audit(id, tool, denied)

    private fun ensureLive() {
        if (closed || poisoned || !lease.isValid) fail("WRITE_SESSION_INVALID")
        if (Files.readAttributes(root, BasicFileAttributes::class.java).fileKey() != rootKey ||
            Files.readAttributes(root.resolve(".git"), BasicFileAttributes::class.java).fileKey() != gitKey)
            fail("WORKSPACE_REPLACED")
        if (git.text(4096, "rev-parse", "HEAD") != policy.baselineHead ||
            git.text(4096, "branch", "--show-current") != policy.branch)
            fail("WORKSPACE_BASELINE_MISMATCH")
        if (sha(git.text(256 * 1024, "config", "--local", "--list").toByteArray()) != configHash)
            fail("GIT_CONFIGURATION_CHANGED")
    }

    private fun authorized(path: String) {
        if (!validPath(path)) fail("INVALID_TOOL_PATH")
        val parts = path.lowercase().split('/')
        if (parts.any { it == ".git" || it == ".github" || it == "gradle" ||
                it == "build" || it == ".gradle" || it == ".env" ||
                it == "secrets" || it == "credentials" } ||
            parts.last().startsWith(".env") ||
            parts.last().endsWith(".key") || parts.last().endsWith(".pem") ||
            parts.last().endsWith(".p12") || parts.last().endsWith(".jks") ||
            parts.last() in setOf("gradle.properties", "local.properties", "build.gradle.kts",
                "settings.gradle.kts", ".gitattributes", ".gitignore", ".gitmodules"))
            fail("PROTECTED_PATH")
        val allowed = when (policy.role) {
            EngineeringRole.SDD -> path.startsWith("specs/")
            EngineeringRole.ATDD -> path.startsWith("tests/acceptance/")
            EngineeringRole.TDD -> path.startsWith("src/test/") || path.startsWith("tests/unit/")
            EngineeringRole.DEVELOPER -> path.startsWith("src/main/")
            EngineeringRole.REVIEWER -> false
        }
        if (!allowed) fail("ROLE_NOT_AUTHORIZED")
        if (policy.role == EngineeringRole.DEVELOPER &&
            (parts.any { it in setOf("test", "tests", "spec", "specs", "acceptance") } ||
                parts.last().matches(Regex(".*(Test|Spec)\\.[A-Za-z0-9]+"))))
            fail("PROTECTED_PATH")
        if (tracked.any { (p, mode) -> mode == "160000" && path.startsWith(p + "/") })
            fail("SUBMODULE_BOUNDARY")
        if (tracked[path] != null && tracked[path] !in setOf("100644", "100755"))
            fail("NONREGULAR_GIT_ENTRY")
        if (path in policy.approvedArtifacts && path !in (policy.reviewedChange?.paths ?: emptySet()))
            fail("APPROVED_ARTIFACT_IMMUTABLE")
    }

    /**
     * Opens every path component relative to a SecureDirectoryStream, without resolving
     * symlinks. Parent directories must already exist (no implicit recursive mkdir).
     */
    private fun <T> withParent(path: String, operation: (SecureDirectoryStream<Path>, Path) -> T): T {
        if (!validPath(path)) fail("INVALID_TOOL_PATH")
        Files.newDirectoryStream(root).use { stream ->
            @Suppress("UNCHECKED_CAST")
            var cursor = stream as? SecureDirectoryStream<Path> ?: fail("SANDBOX_UNAVAILABLE")
            if (cursor.getFileAttributeView(BasicFileAttributeView::class.java)
                    .readAttributes().fileKey() != rootKey) fail("WORKSPACE_REPLACED")
            val opened = mutableListOf<SecureDirectoryStream<Path>>()
            try {
                val parts = path.split('/')
                for (part in parts.dropLast(1)) {
                    val next = try {
                        cursor.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) { fail("UNSAFE_PATH_COMPONENT") }
                    opened.add(next)
                    cursor = next
                }
                return operation(cursor, Path.of(parts.last()))
            } finally {
                opened.asReversed().forEach { it.close() }
            }
        }
    }

    private fun readFile(path: String): ByteArray? = withParent(path) { dir, name ->
        val attrs = try {
            dir.getFileAttributeView(name, BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS).readAttributes()
        } catch (_: NoSuchFileException) { null }
        catch (_: Exception) { fail("UNSAFE_FILE") }
        if (attrs == null) null else {
            if (!attrs.isRegularFile || attrs.size() > MAX_FILE_BYTES) fail("UNSAFE_FILE")
            try {
                dir.newByteChannel(name, setOf<OpenOption>(StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)).use { channel ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteBuffer.allocate(8192)
                    while (true) {
                        val n = channel.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        buffer.flip()
                        output.write(buffer.array(), 0, n)
                        buffer.clear()
                        if (output.size() > MAX_FILE_BYTES) fail("FILE_SIZE_LIMIT")
                    }
                    output.toByteArray()
                }
            } catch (e: Refused) { throw e }
            catch (_: Exception) { fail("UNSAFE_FILE") }
        }
    }

    private fun writeFile(path: String, bytes: ByteArray, create: Boolean) {
        withParent(path) { dir, name ->
            val options = mutableSetOf<OpenOption>(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            if (create) options.add(StandardOpenOption.CREATE_NEW)
            else options.add(StandardOpenOption.TRUNCATE_EXISTING)
            try {
                dir.newByteChannel(name, options).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
            } catch (_: Exception) { fail("WRITE_IO_DENIED") }
        }
    }

    private fun verifyState(): String {
        ensureLive()
        val status = gitStatus(git)
        if (status.values.any { it[0] != ' ' && it != "??" }) fail("INDEX_MUTATION_DENIED")
        if (status.keys != accepted.keys) fail("UNAUTHORIZED_GIT_CHANGE")
        for ((path, hash) in accepted) {
            authorizedForVerification(path)
            val bytes = readFile(path)
            if ((bytes?.let(::sha)) != hash) fail("MUTATION_DIGEST_MISMATCH")
        }
        for ((path, expected) in policy.approvedArtifacts) {
            val current = readFile(path)?.let(::sha)
            if (path in (policy.reviewedChange?.paths ?: emptySet())) {
                if (path !in accepted || accepted[path] != current) fail("APPROVED_ARTIFACT_CHANGED")
            } else if (current != expected) fail("APPROVED_ARTIFACT_CHANGED")
        }
        return sha(accepted.toSortedMap().entries.joinToString("\u0000") {
            it.key + "=" + (it.value ?: "<deleted>")
        }.toByteArray())
    }

    private fun authorizedForVerification(path: String) {
        authorized(path)
        if (path in accepted && tracked[path] == null && accepted[path] == null)
            fail("INVALID_MUTATION_SCOPE")
    }

    /** Batch preflight is all-or-nothing. An I/O failure mid-batch permanently blocks success. */
    @Synchronized
    fun apply(operations: List<WriteOperation>, claimedToolId: String? = null): WriteToolOutcome {
        val id = nextId("write")
        return try {
            if (claimedToolId != null) fail("FORGED_TOOL_ID")
            if (sequence > policy.maxToolCalls) fail("TOOL_QUOTA_EXCEEDED")
            if (operations.isEmpty() || operations.size > 8 ||
                operations.map { it.path }.distinct().size != operations.size) fail("INVALID_WRITE_BATCH")
            verifyState()
            for (op in operations) {
                authorized(op.path)
                val before = readFile(op.path)
                when (op) {
                    is WriteOperation.Create -> {
                        if (before != null || op.path in tracked) fail("CREATE_CONFLICT")
                        if (op.content.toByteArray(StandardCharsets.UTF_8).size > MAX_FILE_BYTES)
                            fail("FILE_SIZE_LIMIT")
                    }
                    is WriteOperation.Edit -> {
                        if (before == null || op.path !in tracked ||
                            !shaPattern.matches(op.expectedSha256) ||
                            sha(before) != op.expectedSha256) fail("EDIT_PRECONDITION_FAILED")
                        if (op.content.toByteArray(StandardCharsets.UTF_8).size > MAX_FILE_BYTES)
                            fail("FILE_SIZE_LIMIT")
                    }
                    is WriteOperation.Delete -> {
                        if (before == null || op.path !in tracked ||
                            !shaPattern.matches(op.expectedSha256) ||
                            sha(before) != op.expectedSha256) fail("DELETE_PRECONDITION_FAILED")
                    }
                }
            }
            var startedMutation = false
            try {
                for (op in operations) {
                    startedMutation = true
                    when (op) {
                        is WriteOperation.Create -> writeFile(op.path, op.content.toByteArray(StandardCharsets.UTF_8), true)
                        is WriteOperation.Edit -> writeFile(op.path, op.content.toByteArray(StandardCharsets.UTF_8), false)
                        is WriteOperation.Delete -> withParent(op.path) { dir, name ->
                            try { dir.deleteFile(name) } catch (_: Exception) { fail("WRITE_IO_DENIED") }
                        }
                    }
                    accepted[op.path] = when (op) {
                        is WriteOperation.Delete -> null
                        is WriteOperation.Create -> sha(op.content.toByteArray(StandardCharsets.UTF_8))
                        is WriteOperation.Edit -> sha(op.content.toByteArray(StandardCharsets.UTF_8))
                    }
                }
                verifyState()
            } catch (e: Exception) {
                if (startedMutation) poisoned = true
                throw e
            }
            issued.clear()
            report(id, "write", null)
            WriteToolOutcome(id)
        } catch (e: Refused) {
            report(id, "write", e.code)
            WriteToolOutcome(id, e.code)
        } catch (_: Exception) {
            poisoned = true
            report(id, "write", "WRITE_EXECUTION_DENIED")
            WriteToolOutcome(id, "WRITE_EXECUTION_DENIED")
        }
    }

    /**
     * Built-in Git whitespace check or a host-allowlisted command in bubblewrap.
     * No shell, writable checkout, network, agent-provided argv, or fallback to host execution.
     */
    private fun runIsolated(check: TrustedCheck) {
        val sandbox = Path.of("/usr/bin/bwrap")
        if (!Files.isExecutable(sandbox)) fail("SANDBOX_UNAVAILABLE")
        val temp = Files.createTempDirectory("recursive-coder-check-")
        try {
            val argv = mutableListOf(sandbox.toString(), "--die-with-parent", "--new-session",
                "--unshare-all", "--tmpfs", "/", "--proc", "/proc", "--dev", "/dev")
            for (directory in listOf("/usr", "/bin", "/lib", "/lib64")) {
                if (Files.exists(Path.of(directory))) {
                    argv.add("--ro-bind"); argv.add(directory); argv.add(directory)
                }
            }
            argv.addAll(listOf("--ro-bind", root.toString(), "/workspace",
                "--bind", temp.toString(), "/scratch",
                "--setenv", "HOME", "/scratch",
                "--setenv", "PATH", "/usr/bin:/bin",
                "--chdir", "/workspace", "--"))
            argv.add(check.executable.toString())
            argv.addAll(check.arguments)
            val process = try {
                ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true)
                    .apply { environment().clear() }.start()
            } catch (_: Exception) { fail("SANDBOX_UNAVAILABLE") }
            val reader = Executors.newSingleThreadExecutor {
                Thread(it, "trusted-check-output").apply { isDaemon = true }
            }
            val future = reader.submit<Int> {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > check.maxOutputBytes) fail("CHECK_OUTPUT_LIMIT")
                    }
                    total
                }
            }
            try {
                val completed = process.waitFor(check.timeoutMs, TimeUnit.MILLISECONDS)
                if (!completed) fail("CHECK_TIMEOUT")
                try { future.get(1, TimeUnit.SECONDS) } catch (_: Exception) { fail("CHECK_OUTPUT_LIMIT") }
                if (process.exitValue() != 0) fail("CHECK_FAILED_OR_SANDBOX_UNAVAILABLE")
            } finally {
                if (process.isAlive) process.destroyForcibly()
                future.cancel(true)
                reader.shutdownNow()
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun signature(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun receiptPayload(r: TrustedCheckReceipt): String =
        listOf(r.toolId, r.check, r.sessionId, r.baselineHead, r.policyHash,
            r.manifestHash).joinToString("\u0000")

    @Synchronized
    fun runCheck(checkId: String, claimedToolId: String? = null): CheckOutcome {
        val id = nextId("run_check")
        return try {
            if (claimedToolId != null) fail("FORGED_TOOL_ID")
            if (sequence > policy.maxToolCalls) fail("TOOL_QUOTA_EXCEEDED")
            val manifest = verifyState()
            if (checkId == "git_diff_check") {
                // Fixed Git verb; no external diff tools, no shell, and an enforced deadline.
                git.text(1024 * 1024, "diff", "--check", "--no-ext-diff", "--no-textconv", "HEAD")
                for ((path, hash) in accepted) {
                    if (path !in tracked && hash != null) {
                        val data = readFile(path) ?: fail("MUTATION_DIGEST_MISMATCH")
                        if (data.toString(StandardCharsets.UTF_8).lineSequence()
                                .any { it.endsWith(' ') || it.endsWith('\t') })
                            fail("CHECK_FAILED")
                    }
                }
            } else {
                val check = policy.checks[checkId] ?: fail("CHECK_NOT_ALLOWLISTED")
                runIsolated(check)
            }
            if (verifyState() != manifest) fail("CHECK_WORKSPACE_CHANGED")
            val unsigned = TrustedCheckReceipt(id, checkId, policy.sessionId,
                policy.baselineHead, policyHash, manifest, "")
            val receipt = unsigned.copy(mac = signature(receiptPayload(unsigned)))
            issued[id] = receipt
            report(id, "run_check", null)
            CheckOutcome(id, receipt = receipt)
        } catch (e: Refused) {
            report(id, "run_check", e.code)
            CheckOutcome(id, e.code)
        } catch (_: Exception) {
            report(id, "run_check", "CHECK_UNAVAILABLE")
            CheckOutcome(id, "CHECK_UNAVAILABLE")
        }
    }

    /** Only locally issued, current, authenticated receipts can satisfy final verification. */
    @Synchronized
    fun finish(receipts: List<TrustedCheckReceipt>): FinalWriteVerification = try {
        val manifest = verifyState()
        if (accepted.isEmpty()) fail("NO_ACCEPTED_MUTATIONS")
        if (receipts.map { it.check }.toSet() != policy.requiredChecks ||
            receipts.size != policy.requiredChecks.size) fail("REQUIRED_CHECK_MISSING")
        for (receipt in receipts) {
            if (issued[receipt.toolId] != receipt ||
                receipt.sessionId != policy.sessionId ||
                receipt.baselineHead != policy.baselineHead ||
                receipt.policyHash != policyHash ||
                receipt.manifestHash != manifest ||
                signature(receiptPayload(receipt)) != receipt.mac)
                fail("CHECK_RECEIPT_INVALID")
        }
        FinalWriteVerification(true)
    } catch (e: Refused) {
        FinalWriteVerification(false, e.code)
    } catch (_: Exception) {
        FinalWriteVerification(false, "FINAL_VERIFICATION_DENIED")
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            secret.fill(0)
            try { lease.release() } finally { leaseChannel.close() }
        }
    }
}
