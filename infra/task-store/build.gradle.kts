plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:tasks"))
    implementation(project(":core:contracts"))
    testImplementation(testFixtures(project(":core:tasks")))
}

/** Forked lock probes require the complete runtime classpath, not Gradle's worker jar. */
tasks.withType<Test>().configureEach {
    doFirst {
        systemProperty("taskstore.test.runtime.classpath", classpath.asPath)
    }
}
