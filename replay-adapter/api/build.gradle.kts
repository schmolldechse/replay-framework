plugins {
    `java-library`
}

dependencies {
    api(project(":replay-api"))
    api(project(":replay-format"))
    compileOnly(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
