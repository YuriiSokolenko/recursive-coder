plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:contracts"))
    // Integration tests verify a session using the trusted workspace adapter.
    testImplementation(project(":core:session"))
    testImplementation(project(":infra:model"))
}
