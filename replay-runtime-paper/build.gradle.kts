plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.paper)
}

dependencies {
    implementation(project(":replay-api"))
    implementation(project(":replay-core"))
    implementation(project(":replay-format"))
    implementation(project(":replay-adapter:api"))
    implementation(project(":replay-adapter:paper-26_2"))
    implementation(project(":replay-storage:api"))
    implementation(project(":replay-storage:local"))
    implementation(project(":replay-storage:s3"))
    implementation(project(":replay-storage:sftp"))
    implementation(project(":replay-database:postgresql"))
    implementation(libs.gson)
    implementation(libs.guice)
    implementation(libs.aws.s3)
    paperweight.paperDevBundle(libs.versions.paper.get())

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.runServer {
    minecraftVersion("26.2")
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("frameworkVersion" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
