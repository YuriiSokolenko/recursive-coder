plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:contracts"))
    // Session integration tests exercise concrete adapters without linking them into core production.
    testImplementation(project(":infra:model"))
    testImplementation(project(":infra:workspace"))
}
