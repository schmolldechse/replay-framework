package dev.voldechse.replayframework.runtime;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.voldechse.replayframework.runtime.config.ReplayConfigurationLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A disposable configuration with explicit test database credentials. */
public final class ConfiguredRuntimeFixture {
    private ConfiguredRuntimeFixture() {}

    public static Path create(String prefix) throws IOException {
        Path directory = Files.createTempDirectory(prefix);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ReplayConfigurationLoader(gson).load(directory));
        assertEquals("password must not be blank", failure.getMessage());
        Path file = directory.resolve("config.json");
        var configuration = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        configuration.getAsJsonObject("postgresql").addProperty("password", "test-password");
        Files.writeString(file, gson.toJson(configuration));
        return directory;
    }
}
