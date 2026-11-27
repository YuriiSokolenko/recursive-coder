package ai.yuriilab.recursivecoder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.WRITE
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.HexFormat

/**
 * Standalone qualification protocol. No runtime registration, no SSH
 * administration, and no authority to execute or acknowledge an effect.
 * The witness operator must independently collect the physical images while
 * publishers are excluded. A signed record is not proof of that exclusion.
 */
internal enum class AuthorityOperationsPhase {
    ACTIVE, FROZEN, PREPARED, CUTOVER, VERIFIED, QUARANTINED, RETIRED
}

internal data class AuthorityCredentialIds(
    val sshClient: String, val sshHost: String, val transport: String, val history: String
) {
    fun values() = listOf(sshClient, sshHost, transport, history)
}

internal data class AuthoritySegmentPin(val credentialId: String, val length: Int, val sha256: String) {
    fun token() = "$credentialId:$length:$sha256"
}

internal data class AuthorityOperationsRecord(
    val generation: Long,
    val predecessor: String,
    val phase: AuthorityOperationsPhase,
    val credentials: AuthorityCredentialIds,
    val markerSha256: String,
    val segments: List<AuthoritySegmentPin>,
    val rootVersion: Long,
    val rootSha256: String,
    val historyKeyProof: String,
    val evidenceSha256: String
) {
    fun canonical(register: EffectAuthorityRegister): String = listOf(
        "recursive-coder/authority-operations-witness/v1", AuthorityWire.identity(register),
        generation.toString(), predecessor, phase.name, credentials.values().joinToString(","),
        markerSha256, segments.joinToString(",") { it.token() }, rootVersion.toString(),
        rootSha256, historyKeyProof, evidenceSha256
    ).joinToString("\n")

    fun digest(register: EffectAuthorityRegister) =
        AuthorityWire.hash(canonical(register).toByteArray(Charsets.US_ASCII))
}

internal data class AuthoritySegmentImage(val primary: ByteArray, val mirror: ByteArray)
internal data class AuthorityHistoryImage(val marker: ByteArray, val segments: List<AuthoritySegmentImage>)

internal object AuthorityOperationsHistory {
    private const val SEGMENT = "recursive-coder/authority-history-segment/v1"
    private const val KEY_PROOF = "recursive-coder/authority-history-key-proof/v1"
    private const val MAX_SEGMENTS = 128
    private val sha256 = Regex("[0-9a-f]{64}")
    internal fun isDigest(value: String) = sha256.matches(value)

    private fun headerValue(register: EffectAuthorityRegister, predecessor: AuthoritySegmentPin,
                            rootSha256: String, credentialId: String): String =
        listOf(AuthorityWire.identity(register), predecessor.token(), rootSha256, credentialId).joinToString("\n")

    // New segment creation must be offline, exclusive, forced and independently
    // inventoried. This only produces the exact authenticated header bytes.
    fun successorHeader(register: EffectAuthorityRegister, predecessor: AuthoritySegmentPin,
                        rootSha256: String, credentialId: String, key: ByteArray): ByteArray =
        AuthorityWire.pack(key, SEGMENT, headerValue(register, predecessor, rootSha256, credentialId)) +
            byteArrayOf(10)

    private fun lines(bytes: ByteArray): List<String> {
        require(bytes.size in 1..AuthorityWire.LIMIT && bytes.all { it.toInt() in 0..127 })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        require(lines.last().isEmpty() && lines.size >= 2)
        return lines.dropLast(1)
    }

    // A retired key is not a second write credential. Its exact old bytes
    // must already be pinned by the independently authenticated witness.
    private fun sealedPayload(line: String): String {
        val fields = line.split('.')
        require(fields.size == 2 && isDigest(fields[1]))
        val bytes = Base64.getDecoder().decode(fields[0])
        require(Base64.getEncoder().encodeToString(bytes) == fields[0] &&
            bytes.all { it.toInt() in 0..127 })
        return bytes.toString(Charsets.US_ASCII)
    }

    data class Measurement(val marker: String, val pins: List<AuthoritySegmentPin>,
                           val version: Long, val root: String, val keyProof: String)

    fun measure(register: EffectAuthorityRegister, previous: AuthorityOperationsRecord?,
                activeCredential: String, key: ByteArray, image: AuthorityHistoryImage,
                newSegment: Boolean): Measurement {
        val activeKey = key.copyOf()
        require(activeKey.size == 32 && isDigest(activeCredential))
        val keyProof = AuthorityWire.hash(AuthorityWire.pack(activeKey, KEY_PROOF, AuthorityWire.identity(register)))
        val priorPins = previous?.segments.orEmpty()
        val count = if (previous == null) 1 else priorPins.size + if (newSegment) 1 else 0
        require(count in 1..MAX_SEGMENTS && image.segments.size == count)
        val marker = image.marker.copyOf()
        val markerDigest = AuthorityWire.hash(marker)
        if (previous != null) require(previous.markerSha256 == markerDigest)
        val roots = mutableListOf<EffectAuthorityRoot>()
        val pins = mutableListOf<AuthoritySegmentPin>()
        for ((index, pair) in image.segments.withIndex()) {
            val bytes = pair.primary.copyOf()
            require(bytes.contentEquals(pair.mirror.copyOf()))
            val parts = lines(bytes)
            val active = index == count - 1
            val prior = priorPins.getOrNull(index)
            val credential = if (active) activeCredential else checkNotNull(prior).credentialId
            val pin = AuthoritySegmentPin(credential, bytes.size, AuthorityWire.hash(bytes))
            if (prior != null) {
                require(prior.credentialId == credential && bytes.size >= prior.length)
                require(AuthorityWire.hash(bytes.copyOf(prior.length)) == prior.sha256)
                if (!active) require(pin == prior)
            }
            val headerDomain = if (index == 0) AuthorityWire.PROVISION else SEGMENT
            val header = if (index == 0) AuthorityWire.identity(register) else headerValue(
                register, pins.last(), roots.lastOrNull()?.let { checkNotNull(EffectAuthorityPolicy.digest(it)) }
                    ?: AuthorityWire.ABSENT, credential)
            fun payload(line: String, domain: String): String {
                if (!active) return sealedPayload(line)
                val packet = line.toByteArray(Charsets.US_ASCII)
                val value = checkNotNull(AuthorityWire.unpack(activeKey, domain, packet))
                require(AuthorityWire.pack(activeKey, domain, value).contentEquals(packet))
                return value
            }
            require(payload(parts.first(), headerDomain) == header)
            if (index == 0) {
                require(marker.contentEquals((parts.first() + "\n").toByteArray(Charsets.US_ASCII)))
            }
            for (line in parts.drop(1)) {
                roots.add(checkNotNull(AuthorityWire.decode(payload(line, AuthorityWire.RECORD))))
            }
            // Each individual segment is bounded; cap the aggregate too.
            require(pins.sumOf { it.length.toLong() } + bytes.size <= AuthorityWire.LIMIT)
            pins.add(pin)
        }
        require(roots.isEmpty() || AuthorityWire.validHistory(roots, register))
        return Measurement(markerDigest, pins.toList(), roots.size.toLong(),
            roots.lastOrNull()?.let { checkNotNull(EffectAuthorityPolicy.digest(it)) } ?: AuthorityWire.ABSENT,
            keyProof)
    }
}

/**
 * Signed, exact-CAS, append-only witness log for an independently administered
 * qualification host. Existing files are supplied by the operator; no creation
 * or repair is offered. An independently retained enrollment inventory and
 * physical append-only enforcement are prerequisites, not attested here.
 *
 * The signer is operator-owned; the caller must not be an agent or authority
 * service principal. This is not a remote service or production admission gate.
 */
internal class AuthorityOperationsWitness(
    private val register: EffectAuthorityRegister,
    private val log: Path,
    private val mutex: Path,
    private val signer: PrivateKey,
    private val verifier: PublicKey,
    private val requireAppendOnly: Boolean = true
) {
    init {
        require(listOf(register.hostId, register.sessionId, register.registerId)
            .all(ToolAuthorizationPolicy::validHostIdentifier) &&
            AuthorityOperationsHistory.isDigest(register.epoch))
    }

    private fun storage() {
        require(Files.isRegularFile(log, NOFOLLOW_LINKS) && Files.isRegularFile(mutex, NOFOLLOW_LINKS))
        require(!Files.isSameFile(log, mutex))
        if (requireAppendOnly) {
            val process = ProcessBuilder("/usr/bin/lsattr", "-d", "--", log.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                val flags = process.inputStream.use { it.bufferedReader().readLine() }
                require(process.waitFor() == 0 && AuthorityWire.appendOnlyFlags(flags))
            } finally { process.destroyForcibly() }
        }
    }

    private fun parse(text: String): AuthorityOperationsRecord {
        val f = text.split('\n')
        require(f.size == 12 && f[1] == AuthorityWire.identity(register))
        val c = f[5].split(',')
        require(c.size == 4)
        val pins = f[7].split(',').map {
            val p = it.split(':')
            require(p.size == 3)
            AuthoritySegmentPin(p[0], p[1].toInt(), p[2])
        }
        val record = AuthorityOperationsRecord(f[2].toLong(), f[3], AuthorityOperationsPhase.valueOf(f[4]),
            AuthorityCredentialIds(c[0], c[1], c[2], c[3]), f[6], pins, f[8].toLong(), f[9], f[10], f[11])
        require(record.canonical(register) == text)
        require(record.credentials.values().all(AuthorityOperationsHistory::isDigest) &&
            record.credentials.values().distinct().size == 4 &&
            AuthorityOperationsHistory.isDigest(record.markerSha256) &&
            AuthorityOperationsHistory.isDigest(record.evidenceSha256) &&
            AuthorityOperationsHistory.isDigest(record.historyKeyProof) &&
            record.segments.all { it.length in 1..AuthorityWire.LIMIT &&
                AuthorityOperationsHistory.isDigest(it.credentialId) &&
                AuthorityOperationsHistory.isDigest(it.sha256) } &&
            record.rootVersion >= 0L &&
            (if (record.rootVersion == 0L) record.rootSha256 == AuthorityWire.ABSENT
             else AuthorityOperationsHistory.isDigest(record.rootSha256)))
        return record
    }

    private fun decode(line: String): AuthorityOperationsRecord {
        val f = line.split('.')
        require(f.size == 2)
        val bytes = Base64.getDecoder().decode(f[0])
        require(Base64.getEncoder().encodeToString(bytes) == f[0])
        val signature = HexFormat.of().parseHex(f[1])
        require(HexFormat.of().formatHex(signature) == f[1])
        require(Signature.getInstance("Ed25519").apply {
            initVerify(verifier)
            update(bytes)
        }.verify(signature))
        val record = parse(bytes.toString(Charsets.US_ASCII))
        require(bytes.contentEquals(record.canonical(register).toByteArray(Charsets.US_ASCII)))
        return record
    }

    private fun readRecords(): List<AuthorityOperationsRecord> {
        val bytes = Files.newInputStream(log, NOFOLLOW_LINKS).use {
            it.readNBytes(AuthorityWire.LIMIT + 1)
        }
        require(bytes.size <= AuthorityWire.LIMIT)
        if (bytes.isEmpty()) return emptyList()
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        require(lines.last().isEmpty())
        val records = mutableListOf<AuthorityOperationsRecord>()
        for (line in lines.dropLast(1)) {
            val record = decode(line)
            checkTransition(records, record)
            records.add(record)
        }
        return records
    }

    private fun checkTransition(records: List<AuthorityOperationsRecord>, next: AuthorityOperationsRecord) {
        val old = records.lastOrNull()
        require(next.generation == records.size.toLong() + 1L &&
            next.predecessor == (old?.digest(register) ?: AuthorityWire.ABSENT))
        if (old == null) {
            require(next.phase == AuthorityOperationsPhase.ACTIVE && next.rootVersion == 0L &&
                next.segments.size == 1 && next.segments.single().credentialId == next.credentials.history)
            return
        }
        require(old.phase != AuthorityOperationsPhase.RETIRED)
        require(next.markerSha256 == old.markerSha256)
        val sameHistory = next.segments == old.segments && next.rootVersion == old.rootVersion &&
            next.rootSha256 == old.rootSha256
        when (next.phase) {
            AuthorityOperationsPhase.ACTIVE -> {
                require(old.phase in setOf(AuthorityOperationsPhase.ACTIVE, AuthorityOperationsPhase.VERIFIED))
                require(next.rootVersion == old.rootVersion + 1L && next.rootSha256 != old.rootSha256)
                require(next.segments.size == old.segments.size &&
                    next.segments.dropLast(1) == old.segments.dropLast(1) &&
                    next.segments.last().credentialId == old.segments.last().credentialId &&
                    next.segments.last().length > old.segments.last().length)
            }
            AuthorityOperationsPhase.FROZEN -> require(
                old.phase in setOf(AuthorityOperationsPhase.ACTIVE, AuthorityOperationsPhase.VERIFIED) && sameHistory)
            AuthorityOperationsPhase.PREPARED -> {
                require(old.phase == AuthorityOperationsPhase.FROZEN && sameHistory &&
                    next.credentials != old.credentials)
                val used = records.flatMap { it.credentials.values() }.toSet()
                for ((before, after) in old.credentials.values().zip(next.credentials.values())) {
                    require(before == after || after !in used)
                }
            }
            AuthorityOperationsPhase.CUTOVER -> {
                require(old.phase == AuthorityOperationsPhase.PREPARED)
                val historyChanged = old.credentials.history != old.segments.last().credentialId
                if (historyChanged) {
                    require(next.rootVersion == old.rootVersion && next.rootSha256 == old.rootSha256 &&
                        next.segments.size == old.segments.size + 1 &&
                        next.segments.dropLast(1) == old.segments &&
                        next.segments.last().credentialId == old.credentials.history)
                } else require(sameHistory)
            }
            AuthorityOperationsPhase.VERIFIED -> require(old.phase == AuthorityOperationsPhase.CUTOVER && sameHistory)
            AuthorityOperationsPhase.QUARANTINED -> require(
                old.phase != AuthorityOperationsPhase.QUARANTINED && sameHistory)
            AuthorityOperationsPhase.RETIRED -> require(sameHistory)
        }
        if (next.phase != AuthorityOperationsPhase.PREPARED) require(next.credentials == old.credentials)
        val rotated = next.phase == AuthorityOperationsPhase.CUTOVER &&
            old.segments.last().credentialId != next.segments.last().credentialId
        if (rotated) require(records.none { it.historyKeyProof == next.historyKeyProof })
        else require(next.historyKeyProof == old.historyKeyProof)
    }

    private fun <T> locked(action: () -> T): T {
        storage()
        return FileChannel.open(mutex, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { action() }
        }
    }

    @Synchronized
    fun read(): AuthorityOperationsRecord? = locked { readRecords().lastOrNull() }

    /**
     * Exact expected witness digest, no automatic retry. A failed append or
     * missing response is not a durable acknowledgement. Terminal decisions
     * preserve the last authenticated commitment even if current storage is lost.
     */
    @Synchronized
    fun advance(expected: String?, phase: AuthorityOperationsPhase, credentials: AuthorityCredentialIds,
                evidenceSha256: String, image: AuthorityHistoryImage? = null,
                activeHistoryKey: ByteArray? = null): AuthorityOperationsRecord = locked {
        val records = readRecords()
        val old = records.lastOrNull()
        require(expected == old?.digest(register))
        val terminal = phase in setOf(AuthorityOperationsPhase.QUARANTINED, AuthorityOperationsPhase.RETIRED)
        val measurement = if (terminal) {
            require(image == null && activeHistoryKey == null)
            val prior = checkNotNull(old)
            AuthorityOperationsHistory.Measurement(prior.markerSha256, prior.segments, prior.rootVersion,
                prior.rootSha256, prior.historyKeyProof)
        } else {
            val newSegment = phase == AuthorityOperationsPhase.CUTOVER &&
                old?.segments?.last()?.credentialId != credentials.history
            val active = if (phase == AuthorityOperationsPhase.PREPARED)
                checkNotNull(old).segments.last().credentialId else credentials.history
            AuthorityOperationsHistory.measure(register, old, active, checkNotNull(activeHistoryKey),
                checkNotNull(image), newSegment)
        }
        val next = AuthorityOperationsRecord(records.size.toLong() + 1L, expected ?: AuthorityWire.ABSENT,
            phase, credentials, measurement.marker, measurement.pins, measurement.version, measurement.root,
            measurement.keyProof, evidenceSha256)
        val canonical = next.canonical(register)
        parse(canonical)
        checkTransition(records, next)
        val bytes = canonical.toByteArray(Charsets.US_ASCII)
        val signature = Signature.getInstance("Ed25519").apply { initSign(signer); update(bytes) }.sign()
        val line = Base64.getEncoder().encodeToString(bytes) + "." + HexFormat.of().formatHex(signature)
        // A mismatched signer must fail before mutating the log.
        require(decode(line) == next)
        val frame = (line + "\n").toByteArray(Charsets.US_ASCII)
        require(Files.size(log) <= AuthorityWire.LIMIT - frame.size.toLong())
        FileChannel.open(log, WRITE, APPEND, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(frame)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        next
    }
}
