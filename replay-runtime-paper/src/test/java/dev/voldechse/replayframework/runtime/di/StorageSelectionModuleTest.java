package dev.voldechse.replayframework.runtime.di;

import com.google.gson.GsonBuilder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.voldechse.replayframework.runtime.config.ReplayConfigurationLoader;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.local.LocalReplayStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

final class StorageSelectionModuleTest {

    @Test
    void localSelectionBindsExactlyOneSharedBackendInstance() throws Exception {
        Path dataDirectory = Files.createTempDirectory("replay-storage-selection-");
        Injector injector = Guice.createInjector(new StorageSelectionModule(
                new ReplayConfigurationLoader(new GsonBuilder().create())
                        .load(dataDirectory)
                        .storage(),
                Runnable::run));
        ReplayStorage storage = injector.getInstance(ReplayStorage.class);

        assertInstanceOf(LocalReplayStorage.class, storage);
        assertSame(storage, injector.getInstance(LocalReplayStorage.class));
    }
}
