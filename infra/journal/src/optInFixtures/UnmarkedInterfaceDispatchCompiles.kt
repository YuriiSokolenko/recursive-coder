package ai.yuriilab.recursivecoder

// Out-of-source compiler proof: Kotlin 2.4 does not require opt-in at
// interface dispatch when only an override is marked. Never ship this pattern.
internal interface UnmarkedRunningPort {
    fun publishRunning()
}
internal class UnsafeRunningOverride : UnmarkedRunningPort {
    @TrustedRunningBoundaryApi
    override fun publishRunning() = Unit
}
internal fun uncheckedInterfaceDispatch(port: UnmarkedRunningPort) {
    port.publishRunning()
}
