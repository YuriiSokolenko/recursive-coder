plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:contracts"))
    // Scheduler integration tests compose the real session and offline fake adapter.
    testImplementation(project(":core:session"))
    testImplementation(project(":infra:model"))
    testImplementation(project(":infra:workspace"))
}
