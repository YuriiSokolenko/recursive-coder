plugins {
    id("recursivecoder.jvm")
    `java-test-fixtures`
}

dependencies { implementation(project(":core:contracts")) }
