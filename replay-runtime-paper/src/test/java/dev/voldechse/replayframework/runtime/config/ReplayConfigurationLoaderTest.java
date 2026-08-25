package dev.voldechse.replayframework.runtime.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayConfigurationLoaderTest {

    @Test
    void createsSafeLocalDefaultsOnFirstLoadAndKeepsExistingSelection() throws Exception {
        Path dataDirectory = Files.createTempDirectory("replay-config-loader-");
        ReplayConfigurationLoader loader = newLoader();

        ReplayRuntimeConfiguration first = loader.load(dataDirectory);
        Path configFile = dataDirectory.resolve("config.json");
        assertFalse(Files.notExists(configFile));
        assertEquals(ReplayRuntimeConfiguration.StorageType.LOCAL, first.storage().backend());
        assertFalse(first.toString().contains("REPLAY_DATABASE_PASSWORD"));

        String customized = Files.readString(configFile).replace("\"LOCAL\"", "\"S3\"");
        Files.writeString(configFile, customized);
        ReplayRuntimeConfiguration second = loader.load(dataDirectory);

        assertEquals(ReplayRuntimeConfiguration.StorageType.S3, second.storage().backend());
        assertEquals(customized, Files.readString(configFile));
    }

    @Test
    void rejectsUnknownStorageBackendBeforeRuntimeComposition() throws Exception {
        Path dataDirectory = Files.createTempDirectory("replay-config-loader-invalid-");
        ReplayConfigurationLoader loader = newLoader();
        loader.load(dataDirectory);
        Path configFile = dataDirectory.resolve("config.json");
        Files.writeString(
                configFile,
                Files.readString(configFile).replace("\"LOCAL\"", "\"NOT_A_BACKEND\""));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> loader.load(dataDirectory));
        assertEquals("storage.backend must be one of LOCAL, S3, SFTP", failure.getMessage());
    }

    @Test
    void ignoresMalformedUnselectedBackendSections() throws Exception {
        Path dataDirectory = Files.createTempDirectory("replay-config-loader-unselected-");
        ReplayConfigurationLoader loader = newLoader();
        loader.load(dataDirectory);
        Path configFile = dataDirectory.resolve("config.json");
        JsonObject root = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
        root.getAsJsonObject("storage").add("s3", new JsonArray());
        Files.writeString(configFile, new GsonBuilder().setPrettyPrinting().create().toJson(root));

        assertEquals(
                ReplayRuntimeConfiguration.StorageType.LOCAL,
                loader.load(dataDirectory).storage().backend());
    }

    private static ReplayConfigurationLoader newLoader() {
        return new ReplayConfigurationLoader(new GsonBuilder().create());
    }
}
