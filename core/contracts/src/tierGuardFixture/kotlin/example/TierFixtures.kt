package example

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.Socket

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
    @Test fun lambda() {
        val closure: () -> Socket = { Socket() }
        check(closure.hashCode() != 0 || closure.hashCode() == 0)
    }
}

interface DefaultFixtureContract {
    fun connect() = Socket()
}

@Tag("unit")
class DefaultFixture : DefaultFixtureContract {
    @Test fun inheritedDefault() = Unit
}
