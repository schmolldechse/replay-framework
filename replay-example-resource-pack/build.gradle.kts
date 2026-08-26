import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

val resourcePackDirectory = layout.projectDirectory.dir("src/main/resources")
val itemAssetNames = listOf(
    "play",
    "pause",
    "restart",
    "rewind",
    "forward",
    "speed_0_25",
    "speed_0_5",
    "speed_1",
    "speed_2",
    "speed_4",
    "leave"
)
val expectedFontCodePoints = (0xE100..0xE10E).toList()
val expectedStatusCodePoints = (0xE200..0xE213).toList()

fun readPng(path: java.io.File): BufferedImage {
    require(path.isFile) { "Missing PNG asset: ${path.path}" }
    FileInputStream(path).use { input ->
        DataInputStream(input).use { data ->
            val signature = ByteArray(8)
            data.readFully(signature)
            require(signature.contentEquals(byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            ))) { "Invalid PNG signature: ${path.path}" }
        }
    }
    return ImageIO.read(path) ?: error("Unreadable PNG asset: ${path.path}")
}

val verifyResourcePackAssets = tasks.register("verifyResourcePackAssets") {
    inputs.dir(resourcePackDirectory)
    doLast {
        val root = resourcePackDirectory.asFile
        val packMetadata = root.resolve("pack.mcmeta")
        require(packMetadata.isFile) { "Missing pack metadata: ${packMetadata.path}" }
        val metadataText = packMetadata.readText(StandardCharsets.UTF_8)
        require(Regex("\\\"pack_format\\\"\\s*:\\s*88").containsMatchIn(metadataText)) {
            "pack.mcmeta must declare pack_format 88"
        }
        require(metadataText.contains("Replay Framework Example Resource Pack")) {
            "pack.mcmeta must contain the stable Example Pack description"
        }

        itemAssetNames.forEach { assetName ->
            val model = root.resolve("assets/replay_example/models/item/$assetName.json")
            require(model.isFile) { "Missing item model: ${model.path}" }
            val itemDefinition = root.resolve("assets/replay_example/items/$assetName.json")
            require(itemDefinition.isFile) { "Missing item definition: ${itemDefinition.path}" }
            val itemDefinitionText = itemDefinition.readText(StandardCharsets.UTF_8)
            require(itemDefinitionText.contains("minecraft:model")) {
                "Item definition does not use minecraft:model: ${itemDefinition.path}"
            }
            require(itemDefinitionText.contains("replay_example:item/$assetName")) {
                "Item definition does not reference its model: ${itemDefinition.path}"
            }
            val modelText = model.readText(StandardCharsets.UTF_8)
            require(modelText.contains("minecraft:item/generated")) {
                "Item model does not use minecraft:item/generated: ${model.path}"
            }
            require(modelText.contains("replay_example:item/$assetName")) {
                "Item model does not reference its namespaced texture: ${model.path}"
            }

            val texture = readPng(root.resolve("assets/replay_example/textures/item/$assetName.png"))
            require(texture.width == 16 && texture.height == 16) {
                "Item texture must be 16x16: $assetName"
            }
            require(texture.colorModel.hasAlpha()) {
                "Item texture must contain an alpha channel: $assetName"
            }
        }

        val fontDefinition = root.resolve("assets/replay_example/font/replay.json")
        require(fontDefinition.isFile) { "Missing replay font definition: ${fontDefinition.path}" }
        val fontText = fontDefinition.readText(StandardCharsets.UTF_8)
        require(fontText.contains("replay_example:font/replay.png")) {
            "Replay font does not reference its bitmap"
        }
        expectedFontCodePoints.forEach { codePoint ->
            val escapedCodePoint = "\\u%04X".format(codePoint)
            require(fontText.contains(escapedCodePoint, ignoreCase = true)) {
                "Replay font is missing codepoint $escapedCodePoint"
            }
        }
        val fontTexture = readPng(root.resolve("assets/replay_example/textures/font/replay.png"))
        require(fontTexture.width == 240 && fontTexture.height == 16) {
            "Replay font bitmap must be 240x16"
        }
        require(fontTexture.colorModel.hasAlpha()) {
            "Replay font bitmap must contain an alpha channel"
        }

        val statusDefinition = root.resolve("assets/replay_example/font/status.json")
        require(statusDefinition.isFile) { "Missing status font definition: ${statusDefinition.path}" }
        val statusText = statusDefinition.readText(StandardCharsets.UTF_8)
        require(statusText.contains("replay_example:font/status.png")) {
            "Status font does not reference its bitmap"
        }
        require(statusText.contains("\"type\": \"space\"")) {
            "Status font must use the native space provider"
        }
        require(!statusText.contains("NegativeSpaceFont")) {
            "Status font must not depend on NegativeSpaceFont"
        }
        expectedStatusCodePoints.forEach { codePoint ->
            val escapedCodePoint = "\\u%04X".format(codePoint)
            require(statusText.contains(escapedCodePoint, ignoreCase = true)) {
                "Status font is missing codepoint $escapedCodePoint"
            }
        }
        listOf(0xE300, 0xE301, 0xE302, 0xE303, 0xE304, 0xE305, 0xE306).forEach { codePoint ->
            val escapedCodePoint = "\\u%04X".format(codePoint)
            require(statusText.contains(escapedCodePoint, ignoreCase = true)) {
                "Status font is missing native space codepoint $escapedCodePoint"
            }
        }
        val statusTexture = readPng(root.resolve("assets/replay_example/textures/font/status.png"))
        require(statusTexture.width == 240 && statusTexture.height == 16) {
            "Status font bitmap must be 240x16"
        }
        require(statusTexture.colorModel.hasAlpha()) {
            "Status font bitmap must contain an alpha channel"
        }

        val allowedFiles = buildSet {
            add("pack.mcmeta")
            add("assets/replay_example/font/replay.json")
            add("assets/replay_example/textures/font/replay.png")
            add("assets/replay_example/font/status.json")
            add("assets/replay_example/textures/font/status.png")
            itemAssetNames.forEach { assetName ->
                add("assets/replay_example/items/$assetName.json")
                add("assets/replay_example/models/item/$assetName.json")
                add("assets/replay_example/textures/item/$assetName.png")
            }
        }
        val actualFiles = Files.walk(root.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { root.toPath().relativize(it).toString().replace(java.io.File.separatorChar, '/') }
                .toList()
                .toSet()
        }
        require(actualFiles == allowedFiles) {
            "Resource-Pack file set differs. Expected $allowedFiles but found $actualFiles"
        }
    }
}

val resourcePackZip = tasks.register<Zip>("resourcePackZip") {
    dependsOn(verifyResourcePackAssets)
    from(resourcePackDirectory)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set("replay-example-resource-pack")
    archiveVersion.set(project.version.toString())
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

val resourcePackSha1 = tasks.register("resourcePackSha1") {
    dependsOn(resourcePackZip)
    val archive = resourcePackZip.flatMap { it.archiveFile }
    val hashFile = layout.buildDirectory.file("libs/replay-example-resource-pack-${project.version}.zip.sha1")
    inputs.file(archive)
    outputs.file(hashFile)
    doLast {
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(archive.get().asFile.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        hashFile.get().asFile.writeText("$hash\n", StandardCharsets.UTF_8)
    }
}

tasks.named("assemble") {
    dependsOn(resourcePackSha1)
}

tasks.named("check") {
    dependsOn(verifyResourcePackAssets)
}
