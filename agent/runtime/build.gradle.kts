plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:contracts"))
    implementation("ai.koog:koog-agents:1.3.0")
    testImplementation("ai.koog:agents-test:1.3.0")
}
