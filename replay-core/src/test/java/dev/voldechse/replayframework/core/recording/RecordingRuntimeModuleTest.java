package dev.voldechse.replayframework.core.recording;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingRuntimeModuleTest {
    @Test
    void createsThePerReplayWorkspaceBeforeARecordingStarts(@TempDir Path temporaryDirectory) {
        ReplayAdapter adapter = (ReplayAdapter) Proxy.newProxyInstance(
                ReplayAdapter.class.getClassLoader(),
                new Class<?>[]{ReplayAdapter.class},
                (proxy, method, arguments) -> null);
        RecordingRuntimeModule module = new RecordingRuntimeModule(
                adapter,
                ReplayStorageBackend.LOCAL,
                temporaryDirectory.resolve("recording-work"));

        Path workspace = module.provideRecordingTarget()
                .stagingDirectoryFactory()
                .apply(ReplayId.random());

        assertTrue(Files.isDirectory(workspace));
    }
}
