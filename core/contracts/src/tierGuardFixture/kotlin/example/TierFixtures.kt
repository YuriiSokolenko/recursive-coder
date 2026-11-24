package example

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.Socket
import java.net.URI

@Tag("unit")
class PureFixture {
    @Test fun pure() {
        ByteArrayInputStream(byteArrayOf(1)).use { check(it.read() == 1) }
        val closure: () -> Int = { 42 }
        check(closure() == 42)
    }
}

@Tag("unit")
class CompanionFixture {
    companion object {
        fun connect() = Socket()
    }
    @Test fun companion() = Unit
}

@Tag("unit")
class LambdaFixture {
    private fun consume(closure: () -> Socket) { check(closure is Function0<*>) }
    @Test fun lambda() {
        val closure: () -> Socket = { Socket() }
        consume(closure)
    }
}

interface DefaultFixtureContract {
    fun connect() = Socket()
}

@Tag("unit")
class DefaultFixture : DefaultFixtureContract {
    @Test fun inheritedDefault() = Unit
}

@Tag("unit")
class NetworkFixture {
    @Test fun readUrl() {
        URI("https://example.test").toURL().readText()
    }
}
