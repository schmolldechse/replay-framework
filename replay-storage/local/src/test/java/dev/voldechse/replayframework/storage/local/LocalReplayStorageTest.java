package dev.voldechse.replayframework.storage.local;

import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.ReplayStorageContract;

import java.nio.file.Path;
import java.util.concurrent.Executor;

/** Runs the common storage contract against the Local backend. */
class LocalReplayStorageTest extends ReplayStorageContract {

    @Override
    protected ReplayStorage createStorage(Path root, Executor executor) {
        return new LocalReplayStorage(root, executor);
    }
}
