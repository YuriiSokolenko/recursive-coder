package ai.yuriilab.recursivecoder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionProtocolTest {
    @Test
    fun supportsOnlyExplicitlyNegotiatedVersion() {
        assertTrue(SessionProtocol.supports("1.0"))
        assertFalse(SessionProtocol.supports("1.1"))
        assertFalse(SessionProtocol.supports("2.0"))
        assertFalse(SessionProtocol.supports("1.0.0"))
        assertFalse(SessionProtocol.supports(""))
    }
}
