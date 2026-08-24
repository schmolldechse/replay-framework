plugins {
    `java-library`
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.run.paper)
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.get())
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
