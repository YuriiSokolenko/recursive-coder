package ai.yuriilab.recursivecoder

import com.sun.security.auth.module.UnixSystem
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Standalone host client. No runtime or agent registration. The operator must
 * establish the independent deployment described in EFFECT_INTENTS.md.
 * The only production transport is a dedicated, pinned SSH forced command.
 */
class SshEffectAntiRollbackAuthority internal constructor(
    private val register: EffectAuthorityRegister,
    transportKey: ByteArray,
    internal val command: List<String>,
    private val timeout: Duration
) : HostEffectAntiRollbackAuthority {
    private val key = transportKey.copyOf()
    private val random = SecureRandom()

    init {
        require(key.size == 32 && !timeout.isNegative && !timeout.isZero)
    }

    companion object {
        /**
         * No discovery, default credentials, implicit host trust or local
         * fallback. The deployment approval is an operator assertion, not
         * attestation of a remote administrator or storage device.
         */
        fun connect(
            register: EffectAuthorityRegister,
            transportKeyFile: Path,
            sshIdentity: Path,
            knownHosts: Path,
            destination: String,
            port: Int,
            timeout: Duration,
            independentDeploymentApproved: Boolean
        ): SshEffectAntiRollbackAuthority {
            require(independentDeploymentApproved)
            require(destination.matches(Regex("[a-zA-Z0-9_][a-zA-Z0-9_.-]*@[a-zA-Z0-9][a-zA-Z0-9.-]*")))
            require(port in 1..65535)
            require(sshIdentity.isAbsolute && knownHosts.isAbsolute && transportKeyFile.isAbsolute)
            return SshEffectAntiRollbackAuthority(
                register, AuthorityWire.key(transportKeyFile), listOf(
                    "/usr/bin/ssh", "-F", "/dev/null", "-T",
                    "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                    "-o", "UserKnownHostsFile=" + knownHosts,
                    "-o", "GlobalKnownHostsFile=/dev/null",
                    "-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=none",
                    "-o", "ConnectionAttempts=1", "-o", "ClearAllForwardings=yes",
                    "-o", "PreferredAuthentications=publickey",
                    "-o", "ControlMaster=no", "-i", sshIdentity.toString(),
                    "-p", port.toString(), "--", destination
                ), timeout
            )
        }
    }

    private fun exchange(verb: AuthorityVerb, expected: String, proposed: String): List<String>? {
        val nonce = ByteArray(32).also(random::nextBytes)
        val request = AuthorityWire.pack(key, AuthorityWire.REQUEST, listOf(
            AuthorityWire.hex(nonce), verb.wireValue, AuthorityWire.identity(register), expected, proposed
        ).joinToString("\n"))
        var process: Process? = null
        try {
            if (Thread.currentThread().isInterrupted) return null
            val child = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process = child
            val task = FutureTask {
                child.outputStream.use { it.write(request) }
                val response = child.inputStream.use { it.readNBytes(AuthorityWire.LIMIT + 1) }
                if (child.waitFor() != 0 || response.size > AuthorityWire.LIMIT) null else response
            }
            Thread(task, "effect-authority-transport").apply { isDaemon = true }.start()
            val response = task.get(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: return null
            val fields = AuthorityWire.unpack(key, AuthorityWire.RESPONSE, response)?.split('\n')
                ?: return emptyList()
            if (fields.size != 3 || fields[0] != AuthorityWire.hash(request)) return emptyList()
            return fields.drop(1)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        } finally {
            process?.destroyForcibly()
        }
    }

    private fun publication(verb: AuthorityVerb, expected: String, root: EffectAuthorityRoot):
        EffectAuthorityPublication {
        if (root.evidence.register != register) return EffectAuthorityPublication.Unauthenticated
        val proposed = AuthorityWire.encode(root) ?: return EffectAuthorityPublication.Uncertain
        val reply = exchange(verb, expected, proposed) ?: return EffectAuthorityPublication.Uncertain
        if (reply.size != 2) return EffectAuthorityPublication.Unauthenticated
        return when (AuthorityStatus.decode(reply[0])) {
            AuthorityStatus.ACK -> if (reply[1] == EffectAuthorityPolicy.digest(root))
                EffectAuthorityPublication.DurableAcknowledgement(reply[1])
            else EffectAuthorityPublication.Uncertain
            AuthorityStatus.REFUSED -> EffectAuthorityRefusal.entries.firstOrNull { it.name == reply[1] }
                ?.let { EffectAuthorityPublication.Refused(it) } ?: EffectAuthorityPublication.Uncertain
            AuthorityStatus.UNAUTHENTICATED -> EffectAuthorityPublication.Unauthenticated
            AuthorityStatus.UNAVAILABLE -> EffectAuthorityPublication.Unavailable
            else -> EffectAuthorityPublication.Uncertain
        }
    }

    override fun bootstrap(initial: EffectAuthorityRoot) = publication(AuthorityVerb.BOOTSTRAP, AuthorityWire.ABSENT, initial)

    override fun compareAndSet(expected: EffectAuthorityRoot, proposed: EffectAuthorityRoot):
        EffectAuthorityPublication {
        if (expected.evidence.register != register) return EffectAuthorityPublication.Unauthenticated
        return publication(AuthorityVerb.CAS, AuthorityWire.encode(expected)
            ?: return EffectAuthorityPublication.Uncertain, proposed)
    }

    override fun read(register: EffectAuthorityRegister): EffectAuthorityRead {
        if (register != this.register) return EffectAuthorityRead.Unauthenticated
        val reply = exchange(AuthorityVerb.READ, AuthorityWire.ABSENT, AuthorityWire.ABSENT) ?: return EffectAuthorityRead.Unavailable
        if (reply.size != 2) return EffectAuthorityRead.Unauthenticated
        return when (AuthorityStatus.decode(reply[0])) {
            AuthorityStatus.MISSING -> EffectAuthorityRead.MissingOrReset
            AuthorityStatus.UNAUTHENTICATED -> EffectAuthorityRead.Unauthenticated
            AuthorityStatus.UNAVAILABLE -> EffectAuthorityRead.Unavailable
            AuthorityStatus.HISTORY -> {
                val history = reply[1].split(',').map { AuthorityWire.decode(it) }
                if (history.any { it == null }) return EffectAuthorityRead.Unauthenticated
                val roots = history.filterNotNull()
                if (!AuthorityWire.validHistory(roots, register)) EffectAuthorityRead.Unauthenticated
                else EffectAuthorityRead.Authenticated(roots.last())
            }
            else -> EffectAuthorityRead.Unauthenticated
        }
    }
}

internal enum class AuthorityVerb(val wireValue: String) {
    READ("read"), BOOTSTRAP("bootstrap"), CAS("cas");

    companion object {
        fun decode(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

internal enum class AuthorityStatus(val wireValue: String) {
    ACK("ack"), REFUSED("refused"), MISSING("missing"),
    HISTORY("history"), UNAUTHENTICATED("unauthenticated"), UNAVAILABLE("unavailable");

    companion object {
        fun decode(value: String) = entries.firstOrNull { it.wireValue == value }
    }
}

internal enum class AuthorityFaultPoint(val wireValue: String) {
    BEFORE_APPEND("before-append"), BEFORE_PRIMARY_FORCE("before-primary-force"),
    AFTER_PRIMARY("after-primary"), BEFORE_MIRROR_FORCE("before-mirror-force"),
    AFTER_BOTH("after-both")
}

internal object AuthorityWire {
    const val ABSENT = "-"
    const val LIMIT = 16 * 1024 * 1024
    const val REQUEST = "recursive-coder/authority-request/v1"
    const val RESPONSE = "recursive-coder/authority-response/v1"
    const val PROVISION = "recursive-coder/authority-provision/v1"
    const val RECORD = "recursive-coder/authority-history/v1"
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
    fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun key(path: Path): ByteArray {
        fun attributes(): PosixFileAttributes {
            val attrs = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            val uid = (Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong()
            require(attrs.isRegularFile && uid == UnixSystem().uid && attrs.fileKey() != null)
            val allowed = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            require(PosixFilePermission.OWNER_READ in attrs.permissions() &&
                allowed.containsAll(attrs.permissions()))
            return attrs
        }
        // The operator must protect parent directories from rename/replacement.
        // Never follow the final path, even if swapped after the metadata check.
        val before = attributes()
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == 32L)
            val bytes = ByteBuffer.allocate(33)
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { /* bounded read */ }
            require(bytes.position() == 32 && channel.size() == 32L)
            val after = attributes()
            require(before.fileKey() == after.fileKey() && before.permissions() == after.permissions())
            bytes.array().copyOf(32)
        }
    }
    // lsattr -d prints a fixed attribute field, then whitespace and pathname.
    fun appendOnlyFlags(line: String?): Boolean {
        val match = line?.let { Regex("^([A-Za-z-]{16,32})[ \\t]+.+$").matchEntire(it) }
            ?: return false
        val flags = match.groupValues[1]
        return flags[5] == 'a' && flags.count { it == 'a' } == 1
    }

    fun identity(r: EffectAuthorityRegister): String = encoder.encodeToString(
        listOf(r.hostId, r.sessionId, r.registerId, r.epoch).joinToString("\n").toByteArray()
    )
    private fun mac(key: ByteArray, domain: String, bytes: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(domain.toByteArray(Charsets.US_ASCII))
            update(0.toByte())
        }.doFinal(bytes)

    fun pack(key: ByteArray, domain: String, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        return (encoder.encodeToString(bytes) + "." + hex(mac(key, domain, bytes)))
            .toByteArray(Charsets.US_ASCII)
    }

    fun unpack(key: ByteArray, domain: String, packet: ByteArray): String? = try {
        require(packet.size <= LIMIT)
        val fields = packet.toString(Charsets.US_ASCII).split('.')
        require(fields.size == 2)
        val bytes = decoder.decode(fields[0])
        require(encoder.encodeToString(bytes) == fields[0])
        val tag = HexFormat.of().parseHex(fields[1])
        require(MessageDigest.isEqual(tag, mac(key, domain, bytes)))
        require(bytes.all { it.toInt() in 0..127 })
        bytes.toString(Charsets.US_ASCII)
    } catch (_: IllegalArgumentException) { null }

    fun encode(root: EffectAuthorityRoot): String? =
        EffectAuthorityPolicy.canonicalBytes(root)?.let(encoder::encodeToString)

    fun decode(value: String): EffectAuthorityRoot? = try {
        val bytes = decoder.decode(value)
        val f = bytes.toString(Charsets.US_ASCII).split('\n')
        require(f.size == 18 && f.last().isEmpty())
        val r = EffectAuthorityRegister(f[1], f[2], f[3], f[4])
        val generation = f[7].toLong()
        val operation = if (f[13] == AuthorityWire.ABSENT) null else EffectIdentity(
            r.sessionId, f[13], generation, EffectKind.valueOf(f[14]), f[15]
        )
        val root = EffectAuthorityRoot(f[5].toLong(), f[6].takeUnless { it == AuthorityWire.ABSENT },
            EffectAuthorityEvidence(r, generation, f[8], f[9], f[10], f[11].toLong(),
                f[12], operation, EffectAuthorityState.entries.single { it.wireValue == f[16] }))
        require(encode(root) == value)
        root
    } catch (_: Exception) { null }

    fun validHistory(roots: List<EffectAuthorityRoot>, register: EffectAuthorityRegister): Boolean {
        if (roots.isEmpty() || roots.first().evidence.register != register ||
            !EffectAuthorityPolicy.validBootstrap(roots.first())) return false
        if (roots.size == 1) return true
        return EffectAuthorityPolicy.transitionRefusal(
            roots[roots.lastIndex - 1], roots.last(), roots.dropLast(1)
        ) == null
    }
}

/**
 * Each invocation runs on the independent host. No server endpoint can
 * provision, truncate, repair, delete or rotate a register. Lock and log
 * paths are fixed by the authority administrator, never by a request.
 */
internal class EffectAuthorityService(
    private val register: EffectAuthorityRegister,
    private val primary: Path,
    private val mirror: Path,
    private val lock: Path,
    private val transportKey: ByteArray,
    private val historyKey: ByteArray,
    private val requireAppendOnly: Boolean = true,
    private val fault: (AuthorityFaultPoint) -> Unit = {}
) {
    init {
        require(transportKey.size == 32 && historyKey.size == 32)
        require(!MessageDigest.isEqual(transportKey, historyKey))
    }

    private val mutex = lock.resolveSibling(lock.fileName.toString() + ".mutex")

    private fun header() = AuthorityWire.pack(
        historyKey, AuthorityWire.PROVISION, AuthorityWire.identity(register)
    ).toString(Charsets.US_ASCII)

    // Offline operator-only provisioning. CREATE_NEW on the lock is a
    // persistent one-shot marker; failed provisioning is not resumable.
    // The independent administrative inventory must also deny reissuance.
    fun provision() {
        require(listOf(primary, mirror, lock, mutex).map { it.toAbsolutePath().normalize() }.distinct().size == 4)
        create(lock, (header() + "\n").toByteArray(Charsets.US_ASCII))
        create(primary, (header() + "\n").toByteArray(Charsets.US_ASCII))
        create(mirror, (header() + "\n").toByteArray(Charsets.US_ASCII))
        create(mutex, byteArrayOf())
    }

    private fun create(path: Path, bytes: ByteArray) {
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use {
            write(it, bytes)
            it.force(true)
        }
        FileChannel.open(path.toAbsolutePath().parent, READ).use { it.force(true) }
    }

    private fun write(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun appendOnly(path: Path): Boolean {
        val process = ProcessBuilder("/usr/bin/lsattr", "-d", "--", path.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val flags = process.inputStream.use { it.bufferedReader().readLine() }
            return process.waitFor() == 0 && AuthorityWire.appendOnlyFlags(flags)
        } finally { process.destroyForcibly() }
    }

    private sealed interface Snapshot {
        data class Verified(val roots: List<EffectAuthorityRoot>) : Snapshot
        data class Denied(val status: AuthorityStatus) : Snapshot
    }

    private fun snapshot(): Snapshot {
        val unavailable = Snapshot.Denied(AuthorityStatus.UNAVAILABLE)
        val unauthenticated = Snapshot.Denied(AuthorityStatus.UNAUTHENTICATED)
        val files = listOf(primary, mirror, lock)
        if (files.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) } ||
            Files.isSameFile(primary, mirror) || Files.isSameFile(primary, lock) ||
            Files.isSameFile(mirror, lock)) return unavailable
        if (requireAppendOnly && files.any { !appendOnly(it) }) return unavailable
        fun read(path: Path): ByteArray? = Files.newInputStream(path, NOFOLLOW_LINKS).use {
            it.readNBytes(AuthorityWire.LIMIT + 1).takeIf { bytes -> bytes.size <= AuthorityWire.LIMIT }
        }
        val marker = read(lock) ?: return unavailable
        if (!marker.contentEquals((header() + "\n").toByteArray(Charsets.US_ASCII)))
            return unauthenticated
        val a = read(primary) ?: return unavailable
        val b = read(mirror) ?: return unavailable
        if (!a.contentEquals(b)) return unavailable
        val lines = a.toString(Charsets.US_ASCII).split('\n')
        if (lines.size < 2 || lines.first() != header() || lines.last().isNotEmpty()) return unauthenticated
        val roots = lines.drop(1).dropLast(1).map {
            val value = AuthorityWire.unpack(historyKey, AuthorityWire.RECORD,
                it.toByteArray(Charsets.US_ASCII)) ?: return unauthenticated
            AuthorityWire.decode(value) ?: return unauthenticated
        }
        return if (roots.isEmpty() || AuthorityWire.validHistory(roots, register))
            Snapshot.Verified(roots) else unauthenticated
    }

    private fun append(root: EffectAuthorityRoot) {
        val encoded = checkNotNull(AuthorityWire.encode(root))
        val line = AuthorityWire.pack(historyKey, AuthorityWire.RECORD, encoded) + byteArrayOf(10)
        // Bound the next snapshot before mutating either log.
        require(Files.size(primary) <= AuthorityWire.LIMIT - line.size.toLong())
        fault(AuthorityFaultPoint.BEFORE_APPEND)
        for ((index, path) in listOf(primary, mirror).withIndex()) {
            FileChannel.open(path, WRITE, APPEND, NOFOLLOW_LINKS).use {
                write(it, line)
                fault(if (index == 0) AuthorityFaultPoint.BEFORE_PRIMARY_FORCE else AuthorityFaultPoint.BEFORE_MIRROR_FORCE)
                it.force(true)
            }
            fault(if (index == 0) AuthorityFaultPoint.AFTER_PRIMARY else AuthorityFaultPoint.AFTER_BOTH)
        }
    }

    fun handle(request: ByteArray): ByteArray {
        val fields = AuthorityWire.unpack(transportKey, AuthorityWire.REQUEST, request)?.split('\n')
        fun reply(status: AuthorityStatus, value: String = "") = AuthorityWire.pack(
            transportKey, AuthorityWire.RESPONSE,
            listOf(AuthorityWire.hash(request), status.wireValue, value).joinToString("\n")
        )
        fun refuse(reason: EffectAuthorityRefusal) = reply(AuthorityStatus.REFUSED, reason.name)
        if (fields == null || fields.size != 5 ||
            !fields[0].matches(Regex("[0-9a-f]{64}")) ||
            fields[2] != AuthorityWire.identity(register)) return reply(AuthorityStatus.UNAUTHENTICATED)
        val verb = AuthorityVerb.decode(fields[1]) ?: return reply(AuthorityStatus.UNAUTHENTICATED)
        if (Files.notExists(lock, NOFOLLOW_LINKS)) return reply(
            if (verb == AuthorityVerb.READ) AuthorityStatus.MISSING else AuthorityStatus.UNAVAILABLE
        )
        if (!Files.isRegularFile(lock, NOFOLLOW_LINKS)) return reply(AuthorityStatus.UNAVAILABLE)
        // Closing any descriptor for a POSIX-locked inode in the same process
        // can release the lock. The dedicated mutex inode is never read or
        // opened a second time. Its directory is administrator-owned.
        if (!Files.isRegularFile(mutex, NOFOLLOW_LINKS) ||
            listOf(primary, mirror, lock).any {
                Files.isRegularFile(it, NOFOLLOW_LINKS) && Files.isSameFile(it, mutex)
            })
            return reply(AuthorityStatus.UNAVAILABLE)
        FileChannel.open(mutex, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                val roots = when (val snapshot = snapshot()) {
                    is Snapshot.Verified -> snapshot.roots
                    is Snapshot.Denied -> return reply(snapshot.status)
                }
                // A prior process may have died after writing both copies but
                // before forcing the second. A fresh authenticated snapshot is
                // forced under the same lock; this is NOT the prior invocation's ack.
                for (path in listOf(primary, mirror)) {
                    FileChannel.open(path, WRITE, APPEND, NOFOLLOW_LINKS).use { it.force(true) }
                }
                if (verb == AuthorityVerb.READ) {
                    if (fields[3] != AuthorityWire.ABSENT || fields[4] != AuthorityWire.ABSENT) return reply(AuthorityStatus.UNAUTHENTICATED)
                    if (roots.isEmpty()) return reply(AuthorityStatus.MISSING)
                    return reply(AuthorityStatus.HISTORY, roots.joinToString(",") { checkNotNull(AuthorityWire.encode(it)) })
                }
                val proposed = AuthorityWire.decode(fields[4]) ?: return refuse(EffectAuthorityRefusal.INVALID_RECORD)
                if (proposed.evidence.register != register) return reply(AuthorityStatus.UNAUTHENTICATED)
                if (verb == AuthorityVerb.BOOTSTRAP) {
                    if (fields[3] != AuthorityWire.ABSENT) return refuse(EffectAuthorityRefusal.INVALID_RECORD)
                    if (roots.isNotEmpty()) return refuse(EffectAuthorityRefusal.ALREADY_INITIALIZED)
                    if (!EffectAuthorityPolicy.validBootstrap(proposed))
                        return refuse(EffectAuthorityRefusal.INVALID_RECORD)
                } else {
                    val expected = AuthorityWire.decode(fields[3]) ?: return refuse(EffectAuthorityRefusal.INVALID_RECORD)
                    if (expected.evidence.register != register) return reply(AuthorityStatus.UNAUTHENTICATED)
                    if (roots.isEmpty()) return reply(AuthorityStatus.UNAVAILABLE)
                    if (roots.last() != expected) return refuse(EffectAuthorityRefusal.STALE_PREDECESSOR)
                    EffectAuthorityPolicy.transitionRefusal(expected, proposed, roots)?.let { return refuse(it) }
                }
                // Any escaped failure here produces no response, never refusal.
                append(proposed)
                return reply(AuthorityStatus.ACK, checkNotNull(EffectAuthorityPolicy.digest(proposed)))
            }
        }
    }
}

/** Fixed-command entry point; configure the command and paths out of band. */
object EffectAuthorityHostCommand {
    @JvmStatic
    fun main(args: Array<String>) {
        run(args)
    }

    // The test subprocess enters the same parser, key loader and exception
    // boundary. No command-line switch exposes the fixture seam in production.
    internal fun run(
        args: Array<String>,
        requireAppendOnly: Boolean = true,
        fault: (AuthorityFaultPoint) -> Unit = {}
    ) {
        try {
            require(args.size == 10)
            val service = EffectAuthorityService(
                EffectAuthorityRegister(args[6], args[7], args[8], args[9]),
                Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                AuthorityWire.key(Path.of(args[4])), AuthorityWire.key(Path.of(args[5])),
                requireAppendOnly, fault
            )
            when (args[0]) {
                "provision-never-initialized" -> service.provision()
                "serve-independent" -> {
                    val request = System.`in`.readNBytes(AuthorityWire.LIMIT + 1)
                    require(request.size <= AuthorityWire.LIMIT)
                    System.out.write(service.handle(request))
                    System.out.flush()
                    if (System.out.checkError()) throw IOException("response unavailable")
                }
                else -> error("unsupported authority command")
            }
        } catch (_: Exception) {
            // No paths, credentials or exception payload in the protocol.
            System.exit(1)
        }
    }
}
