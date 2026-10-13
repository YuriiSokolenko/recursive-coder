plugins {
    id("recursivecoder.jvm")
    application
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(project(":core:session"))
    implementation(project(":core:tasks"))
    implementation(project(":infra:model"))
    implementation(project(":infra:workspace"))
    implementation(project(":infra:task-store"))
    implementation(project(":infra:journal"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("io.insert-koin:koin-core:4.2.2")
    testImplementation("io.insert-koin:koin-test:4.2.2")
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
