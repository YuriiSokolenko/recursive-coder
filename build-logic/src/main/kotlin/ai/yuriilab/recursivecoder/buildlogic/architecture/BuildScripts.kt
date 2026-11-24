package ai.yuriilab.recursivecoder.buildlogic.architecture

// Keep this outside the precompiled script's nested Gradle callbacks to avoid
// the Kotlin JVM backend failure on local classes in that context.
/** Configuration-time snapshot of script presence and selected build-script text. */
internal class BuildScripts(val present: Boolean, val selectedText: String?)
