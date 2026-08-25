plugins {
    `java-library`
}

dependencies {
    implementation(project(":replay-storage:api"))
    implementation(libs.sshj)
    implementation(libs.guice)
    testImplementation(testFixtures(project(":replay-storage:api")))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()

    listOf(
        "replay.sftp.test.host",
        "replay.sftp.test.port",
        "replay.sftp.test.username",
        "replay.sftp.test.base-path",
        "replay.sftp.test.known-hosts",
        "replay.sftp.test.private-key",
        "replay.sftp.test.private-key-passphrase"
    ).forEach { propertyName ->
        System.getProperty(propertyName)?.let { propertyValue ->
            systemProperty(propertyName, propertyValue)
        }
    }
}
