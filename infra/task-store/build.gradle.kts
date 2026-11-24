import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

plugins { id("recursivecoder.jvm") }

dependencies {
    implementation(project(":core:tasks"))
    implementation(project(":core:contracts"))
    testImplementation(testFixtures(project(":core:tasks")))
}

/** Model the forked JVM classpath as a Gradle task input. */
abstract class TaskStoreFixtureClasspath : CommandLineArgumentProvider {
    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf("-Dtaskstore.test.runtime.classpath=${runtimeClasspath.asPath}")
}

tasks.withType<Test>().configureEach {
    val fixtureClasspath = objects.newInstance<TaskStoreFixtureClasspath>()
    fixtureClasspath.runtimeClasspath.from(classpath)
    jvmArgumentProviders.add(fixtureClasspath)
}
