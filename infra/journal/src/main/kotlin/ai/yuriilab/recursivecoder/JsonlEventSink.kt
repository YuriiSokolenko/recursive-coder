package ai.yuriilab.recursivecoder

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/**
 * Physical Session Protocol v1 JSONL writer. CREATE_NEW prevents accidental replay,
 * overwrite or append to a previous attempt's log.
 *
 * Each event is flushed, preserving the existing live-read semantics. The checksum
 * is computed from the actual file bytes after close. Flush is not an fsync and
 * a process crash may leave a truncated last line; recovery belongs to issue #10.
 */
class JsonlEventSink internal constructor(
    private val path: Path,
    private val writer: BufferedWriter
) : EventSink {
    companion object {
        fun open(path: Path): JsonlEventSink = JsonlEventSink(
            path, Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        )
    }

    private var closeAttempted = false
    private var closeSucceeded = false

    override fun append(event: JsonObject) {
        check(!closeAttempted) { "Journal already closed" }
        writer.write(event.toString())
        writer.newLine()
    }

    override fun flush() {
        check(!closeAttempted) { "Journal already closed" }
        writer.flush()
    }

    override fun close() {
        if (closeAttempted) return
        closeAttempted = true
        writer.close()
        closeSucceeded = true
    }

    override fun sha256(): String {
        check(closeSucceeded) { "Journal was not closed successfully" }
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }
}
