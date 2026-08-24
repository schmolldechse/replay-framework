plugins {
    `java-library`
}

dependencies {
    implementation(project(":replay-api"))
    implementation(project(":replay-format"))
    implementation(project(":replay-storage:api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
