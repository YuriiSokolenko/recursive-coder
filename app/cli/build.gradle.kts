import java.security.MessageDigest

plugins {
    id("recursivecoder.jvm")
    application
}

dependencies {
    implementation(project(":agent:runtime"))
    implementation(project(":core:contracts"))
    implementation(project(":core:session"))
    implementation(project(":core:tasks"))
    implementation(project(":infra:model"))
    implementation(project(":infra:workspace"))
    implementation(project(":infra:task-store"))
    implementation(project(":infra:journal"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation(libs.koin.core)
    testImplementation(libs.koin.test)
    testImplementation(testFixtures(project(":core:contracts")))
    testImplementation(testFixtures(project(":core:tasks")))
    implementation(libs.coroutines.core)
}

application {
    mainClass.set("ai.yuriilab.recursivecoder.MainKt")
    applicationName = "recursive-coder"
}

// Preserve the historical root distribution path used by callers and Python CI.
tasks.named<Sync>("installDist") {
    into(rootProject.layout.buildDirectory.dir("install/recursive-coder"))
}

tasks.named<Test>("test") { dependsOn(tasks.named("installDist")) }


// The installed libraries must match the *resolved application runtimeClasspath*,
// not just any same-named artifact present in the machine's Gradle cache.
tasks.register("writeRuntimeClasspathManifest") {
    val runtime = configurations.named("runtimeClasspath")
    val inventory = rootProject.layout.buildDirectory.file("verification/runtime-classpath.sha256")
    inputs.files(runtime)
    outputs.file(inventory)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        val lines = runtime.get().files.map { file ->
            require(file.isFile && file.extension == "jar") {
                "Unreviewed non-JAR in resolved application runtimeClasspath: $file"
            }
            digest.reset()
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 255)
            }
            "${file.name}\t$hash"
        }.sorted()
        inventory.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }
}
