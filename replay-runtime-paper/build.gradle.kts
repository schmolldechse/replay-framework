import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

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

val runtimeClasspath = configurations.runtimeClasspath
val mergedRuntimeServices = layout.buildDirectory.dir("generated/runtime-services")
val mergeRuntimeServices = tasks.register("mergeRuntimeServices") {
    dependsOn(runtimeClasspath)
    inputs.files(runtimeClasspath)
    outputs.dir(mergedRuntimeServices)
    doLast {
        val providersByService = linkedMapOf<String, LinkedHashSet<String>>()
        runtimeClasspath.get().filter { it.isFile }.forEach { classpathEntry ->
            zipTree(classpathEntry)
                    .matching { include("META-INF/services/*") }
                    .files
                    .filter { it.isFile }
                    .forEach { serviceFile ->
                        val providers = providersByService.computeIfAbsent(serviceFile.name) {
                            linkedSetOf()
                        }
                        serviceFile.readLines()
                                .map(String::trim)
                                .filter { it.isNotEmpty() && !it.startsWith("#") }
                                .forEach(providers::add)
                    }
        }

        val outputRoot = mergedRuntimeServices.get().asFile
        outputRoot.deleteRecursively()
        providersByService.forEach { (serviceName, providers) ->
            val output = outputRoot.resolve("META-INF/services/$serviceName")
            output.parentFile.mkdirs()
            output.writeText(providers.joinToString(System.lineSeparator()) + System.lineSeparator())
        }
    }
}

tasks.named<Jar>("jar") {
    // Paper installs one plugin artifact, so project modules and runtime
    // libraries must be available from the same classloader boundary.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(runtimeClasspath, mergeRuntimeServices)
    from(runtimeClasspath.get().map { classpathEntry -> zipTree(classpathEntry) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/services/**")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    from(mergedRuntimeServices)
}

tasks.test {
    useJUnitPlatform()
}
