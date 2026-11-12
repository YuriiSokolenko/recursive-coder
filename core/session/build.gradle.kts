plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:contracts"))
    implementation(libs.coroutines.core)
    testImplementation(testFixtures(project(":core:contracts")))
}
