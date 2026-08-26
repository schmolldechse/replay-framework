plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
}

dependencies {
    implementation(project(":replay-adapter:api"))
    compileOnly(libs.packetevents.spigot)
    paperweight.paperDevBundle(libs.versions.paper.get())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
