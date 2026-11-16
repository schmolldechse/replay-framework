plugins {
    `java-library`
}

dependencies {
    api(project(":replay-adapter:api"))
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
