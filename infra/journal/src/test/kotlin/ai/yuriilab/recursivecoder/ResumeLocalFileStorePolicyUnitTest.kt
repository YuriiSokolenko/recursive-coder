package ai.yuriilab.recursivecoder

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Pure type classifier: I/O and entrypoint ordering belong to integration tests. */
@Tag("unit")
class ResumeLocalFileStorePolicyUnitTest {
    @Test fun typesAreAllowlistedIndependentlyAndCaseInsensitively() {
        val supported = listOf("ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "zfs")
        supported.forEach { type ->
            ResumeLocalFileStorePolicy.requireSupportedTypes(type, "ext4")
            ResumeLocalFileStorePolicy.requireSupportedTypes("EXT4", type.uppercase())
        }

        val denied = listOf("nfs", "nfs4", "cifs", "smb3", "fuse", "fuse.sshfs",
            "fuseblk", "overlay", "tmpfs", "unknown", "")
        for (type in denied) {
            val checkpoint = assertFailsWith<SessionResumeUnconfirmed> {
                ResumeLocalFileStorePolicy.requireSupportedTypes(type, "ext4")
            }
            assertEquals("RESUME_FILESYSTEM_UNSUPPORTED", checkpoint.code)
            assertEquals("checkpoint=${if (type.isBlank()) "unknown" else type} ledger=ext4",
                checkpoint.detail)

            val ledger = assertFailsWith<SessionResumeUnconfirmed> {
                ResumeLocalFileStorePolicy.requireSupportedTypes("ext4", type)
            }
            assertEquals("RESUME_FILESYSTEM_UNSUPPORTED", ledger.code)
            assertEquals("checkpoint=ext4 ledger=${if (type.isBlank()) "unknown" else type}",
                ledger.detail)
        }
    }

    @Test fun unsupportedTypeDiagnosticsDoNotEchoArbitraryHostStrings() {
        val failure = assertFailsWith<SessionResumeUnconfirmed> {
            ResumeLocalFileStorePolicy.requireSupportedTypes("ext4\nsecret", "xfs")
        }
        assertEquals("RESUME_FILESYSTEM_UNSUPPORTED", failure.code)
        assertEquals("checkpoint=unknown ledger=xfs", failure.detail)
        assertFalse(failure.message!!.contains("secret"))
    }
}
