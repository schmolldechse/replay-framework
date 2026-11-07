plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":replay-api"))
    testImplementation(project(":replay-api"))
    compileOnly(libs.paper.api)
    testCompileOnly(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    when (providers.gradleProperty("testMinecraftVersion").orElse("26.2").get()) {
        "26.2" -> testImplementation(libs.mockbukkit262)
        "26.3" -> {
            testImplementation(libs.mockbukkit263)
            testImplementation(libs.paper.api263)
        }
        else -> error("testMinecraftVersion must be 26.2 or 26.3")
    }
    testRuntimeOnly(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
