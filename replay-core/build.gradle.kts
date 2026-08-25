plugins {
    `java-library`
}

dependencies {
    implementation(project(":replay-api"))
    implementation(project(":replay-format"))
    implementation(project(":replay-adapter:api"))
    implementation(project(":replay-storage:api"))
    implementation(libs.gson)
    compileOnly(libs.guice)
    compileOnly(libs.paper.api)
    testCompileOnly(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.guice)
    testRuntimeOnly(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
