plugins {
    `java-library`
}

dependencies {
    implementation(project(":replay-storage:api"))
    testImplementation(testFixtures(project(":replay-storage:api")))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
