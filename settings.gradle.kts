import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    // paperweight-userdev registers setup repositories during project evaluation.
    // Keep settings repositories authoritative while allowing that plugin setup.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.codemc.io/repository/maven-releases/")
    }
}

rootProject.name = "replay-framework"

include(
    "replay-api",
    "replay-core",
    "replay-format",
    "replay-adapter:api",
    "replay-adapter:paper-26_2",
    "replay-storage:api",
    "replay-storage:local",
    "replay-storage:s3",
    "replay-storage:sftp",
    "replay-database:postgresql",
    "replay-runtime-paper",
    "replay-example-plugin",
    "replay-example-resource-pack"
)
