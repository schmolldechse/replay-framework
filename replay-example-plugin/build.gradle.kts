plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":replay-api"))
    testImplementation(project(":replay-api"))
    compileOnly(libs.paper.api)
    testCompileOnly(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
