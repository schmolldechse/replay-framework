plugins {
    `java-library`
}

dependencies {
    implementation(project(":replay-storage:api"))
    implementation(libs.aws.s3)
    implementation(libs.guice)
    testImplementation(testFixtures(project(":replay-storage:api")))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    listOf("endpoint", "bucket", "region", "access-key", "secret-key").forEach { key ->
        val propertyName = "replay.s3.test.$key"
        System.getProperty(propertyName)?.let { systemProperty(propertyName, it) }
    }
}
