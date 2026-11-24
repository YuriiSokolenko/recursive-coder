package ai.yuriilab.recursivecoder.buildlogic.architecture

/** Configuration-time snapshot of script presence and selected build-script text. */
// Keep this outside the precompiled script's nested Gradle callbacks: the Kotlin
// JVM backend cannot generate the captured map signature for a local class there.
internal data class BuildScripts(val present: Boolean, val selectedText: String?)
