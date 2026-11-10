plugins {
    `java-library`
}

dependencies {
    api(project(":replay-adapter:api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
