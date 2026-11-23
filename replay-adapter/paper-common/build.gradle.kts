plugins {
    `java-library`
}

dependencies {
    api(project(":replay-adapter:api"))
    compileOnly(libs.paper.api)
    // The server's own Netty; channel handlers are version-independent.
    compileOnly(libs.netty.transport)
    testImplementation(libs.paper.api)
    testImplementation(libs.netty.transport)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
