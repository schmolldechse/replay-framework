package dev.voldechse.replayframework.storage.sftp;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import dev.voldechse.replayframework.storage.ReplayStorage;

import java.util.Objects;
import java.util.concurrent.Executor;

/** Internal Guice composition root for the SSHJ replay storage backend. */
public final class SftpStorageModule extends AbstractModule {

    private final SftpStorageConfiguration configuration;
    private final Executor ioExecutor;

    public SftpStorageModule(
            SftpStorageConfiguration configuration,
            Executor ioExecutor) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    @Provides
    @Singleton
    SftpStorageConfiguration provideConfiguration() {
        return configuration;
    }

    @Provides
    @Singleton
    Executor provideIoExecutor() {
        return ioExecutor;
    }

    @Provides
    @Singleton
    SftpConnectionPool provideConnectionPool(
            SftpStorageConfiguration configuration,
            Executor ioExecutor) {
        return new SftpConnectionPool(configuration, ioExecutor);
    }

    @Provides
    @Singleton
    SftpReplayStorage provideStorage(
            SftpStorageConfiguration configuration,
            SftpConnectionPool connectionPool,
            Executor ioExecutor) {
        return new SftpReplayStorage(configuration, connectionPool, ioExecutor);
    }

    @Provides
    @Singleton
    ReplayStorage provideReplayStorage(SftpReplayStorage storage) {
        return storage;
    }
}
