plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    implementation(project(":replay-adapter:api"))
    implementation(project(":replay-adapter:paper-common"))
    paperweight.paperDevBundle(libs.versions.paper263.get())
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":replay-adapter:api")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
