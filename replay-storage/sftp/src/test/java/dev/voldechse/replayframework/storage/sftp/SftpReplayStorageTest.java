package dev.voldechse.replayframework.storage.sftp;

import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.ReplayStorageContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SftpReplayStorageTest extends ReplayStorageContract {

    private static final List<SftpReplayStorage> BACKENDS = new CopyOnWriteArrayList<>();
    private SftpReplayStorage backend;

    @Override
    protected ReplayStorage createStorage(Path root, Executor executor) {
        String host = requiredProperty("replay.sftp.test.host");
        int port = Integer.parseInt(requiredProperty("replay.sftp.test.port"));
        String username = requiredProperty("replay.sftp.test.username");
        Path knownHosts = Path.of(requiredProperty("replay.sftp.test.known-hosts"));
        Path privateKey = Path.of(requiredProperty("replay.sftp.test.private-key"));
        String configuredBasePath = requiredProperty("replay.sftp.test.base-path");
        String basePath = configuredBasePath + "/contract-" + System.nanoTime();

        SftpStorageConfiguration configuration = SftpStorageConfiguration.builder()
                .host(host)
                .port(port)
                .username(username)
                .basePath(basePath)
                .knownHostsFile(knownHosts)
                .privateKey(privateKey)
                .poolSize(2)
                .acquireTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(10))
                .operationTimeout(Duration.ofSeconds(30))
                .closeTimeout(Duration.ofSeconds(10))
                .maxAttempts(1)
                .baseBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .build();

        backend = new SftpReplayStorage(configuration, executor, root);
        BACKENDS.add(backend);
        return backend;
    }

    @AfterAll
    static void closeBackends() throws IOException {
        IOException failure = null;
        for (SftpReplayStorage current : BACKENDS) {
            try {
                current.close().toCompletableFuture().join();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = new IOException("SFTP storage close failed", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        BACKENDS.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    void rejectsMissingKnownHostsBeforeOpeningAConnection() {
        Path missingKnownHosts = root.resolve("missing-known-hosts");

        assertThrows(IllegalArgumentException.class, () -> configuredBuilder(missingKnownHosts)
                .privateKey(Path.of(requiredProperty("replay.sftp.test.private-key")))
                .build());
    }

    @Test
    void rejectsAnUntrustedHostKeyWithoutATrustAllFallback() throws IOException {
        Path untrustedKnownHosts = root.resolve("untrusted-known-hosts");
        String configuredKnownHosts = Files.readString(
                Path.of(requiredProperty("replay.sftp.test.known-hosts")));
        String configuredPort = requiredProperty("replay.sftp.test.port");
        String unmatchedHost = "[127.0.0.1]:" + (Integer.parseInt(configuredPort) + 1);
        Files.writeString(
                untrustedKnownHosts,
                configuredKnownHosts.replace(
                        "[127.0.0.1]:" + configuredPort,
                        unmatchedHost));

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        SftpReplayStorage untrustedBackend = new SftpReplayStorage(
                configuredBuilder(untrustedKnownHosts)
                        .privateKey(Path.of(requiredProperty("replay.sftp.test.private-key")))
                        .build(),
                executor);
        try {
            assertThrows(CompletionException.class, () -> untrustedBackend
                    .stage(dev.voldechse.replayframework.api.id.ReplayId.parse(
                            "22222222-2222-2222-2222-222222222222"))
                    .toCompletableFuture()
                    .join());
        } finally {
            untrustedBackend.close().toCompletableFuture().join();
            executor.close();
        }
    }

    @Test
    void redactsSecretsFromConfigurationDiagnostics() {
        String password = "not-for-logs";
        SftpStorageConfiguration configuration = configuredBuilder(
                Path.of(requiredProperty("replay.sftp.test.known-hosts")))
                .password(password::toCharArray)
                .build();

        String diagnostic = configuration.toString();
        assertFalse(diagnostic.contains(password));
        assertTrue(diagnostic.contains("authenticationMethod=PASSWORD"));
    }

    private static SftpStorageConfiguration.Builder configuredBuilder(Path knownHosts) {
        return SftpStorageConfiguration.builder()
                .host(requiredProperty("replay.sftp.test.host"))
                .port(Integer.parseInt(requiredProperty("replay.sftp.test.port")))
                .username(requiredProperty("replay.sftp.test.username"))
                .basePath(requiredProperty("replay.sftp.test.base-path"))
                .knownHostsFile(knownHosts)
                .poolSize(2)
                .acquireTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(10))
                .operationTimeout(Duration.ofSeconds(30))
                .closeTimeout(Duration.ofSeconds(10))
                .maxAttempts(1)
                .baseBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required SFTP test property: " + name);
        }
        return value;
    }
}
