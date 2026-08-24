package dev.voldechse.replayframework.storage;

import dev.voldechse.replayframework.api.id.ReplayId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend-neutral contract for the asynchronous replay storage boundary.
 */
public abstract class ReplayStorageContract {

    private static final ReplayId REPLAY_ID =
            ReplayId.parse("11111111-1111-1111-1111-111111111111");

    @TempDir
    protected Path root;

    private ExecutorService ioExecutor;
    private ReplayStorage storage;
    private Path cacheRoot;

    /** Creates the backend under test using the supplied temporary root and executor. */
    protected abstract ReplayStorage createStorage(Path root, Executor executor);

    @BeforeEach
    void setUp() throws IOException {
        ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        storage = createStorage(root, ioExecutor);
        cacheRoot = Files.createTempDirectory(root.getParent(), "replay-storage-cache-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storage != null) {
            storage.delete(REPLAY_ID).toCompletableFuture().join();
        }
        if (ioExecutor != null) {
            ioExecutor.close();
        }
        if (cacheRoot != null) {
            deleteTree(cacheRoot);
        }
    }

    @Test
    void doesNotExposeStagedArtifactsBeforePublish() throws IOException {
        StagingReplay staging = stage();
        put(staging, "segments/00000000.segment", new byte[] {1, 2, 3});

        assertFalse(storage.exists(REPLAY_ID, ArtifactKey.of("segments/00000000.segment"))
                .toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> storage.fetch(
                REPLAY_ID,
                ArtifactKey.of("segments/00000000.segment"),
                Optional.empty(),
                cacheRoot.resolve("before-publish.segment"))
                .toCompletableFuture()
                .join());
    }

    @Test
    void publishesAndReadsCompleteArtifacts() throws IOException {
        StagingReplay staging = stage();
        byte[] segmentBytes = {0, 1, 2, 3, 4, 5, 6, 7};
        put(staging, "segments/00000000.segment", segmentBytes);
        put(staging, "index.bin", new byte[] {8, 9});
        put(staging, "manifest.json", new byte[] {'{', '}'});

        storage.publish(staging).toCompletableFuture().join();

        Path target = cacheRoot.resolve("full.segment");
        Path result = storage.fetch(
                REPLAY_ID,
                ArtifactKey.of("segments/00000000.segment"),
                Optional.empty(),
                target).toCompletableFuture().join();

        assertEquals(target.toAbsolutePath().normalize(), result);
        assertTrue(storage.exists(REPLAY_ID, ArtifactKey.of("manifest.json"))
                .toCompletableFuture().join());
        assertArrayEquals(segmentBytes, Files.readAllBytes(result));
    }

    @Test
    void requiresManifestBeforePublishAndKeepsStagingOpen() throws IOException {
        StagingReplay staging = stage();

        assertThrows(CompletionException.class,
                () -> storage.publish(staging).toCompletableFuture().join());
        assertFalse(storage.exists(REPLAY_ID, ArtifactKey.of("manifest.json"))
                .toCompletableFuture().join());

        put(staging, "manifest.json", new byte[] {'{', '}'});
        storage.publish(staging).toCompletableFuture().join();
        assertTrue(storage.exists(REPLAY_ID, ArtifactKey.of("manifest.json"))
                .toCompletableFuture().join());
    }

    @Test
    void readsExactByteRangeAndPreservesTargetOnRangeFailure() throws IOException {
        StagingReplay staging = stage();
        byte[] segmentBytes = {0, 1, 2, 3, 4, 5, 6, 7};
        put(staging, "segments/00000000.segment", segmentBytes);
        put(staging, "manifest.json", new byte[] {'{', '}'});
        storage.publish(staging).toCompletableFuture().join();

        Path rangeTarget = cacheRoot.resolve("range.segment");
        Path result = storage.fetch(
                REPLAY_ID,
                ArtifactKey.of("segments/00000000.segment"),
                Optional.of(new ByteRange(2, 6)),
                rangeTarget).toCompletableFuture().join();
        assertEquals(rangeTarget.toAbsolutePath().normalize(), result);
        assertArrayEquals(new byte[] {2, 3, 4, 5}, Files.readAllBytes(result));

        Path failureTarget = cacheRoot.resolve("failure.segment");
        Files.write(failureTarget, new byte[] {99});
        assertThrows(CompletionException.class, () -> storage.fetch(
                REPLAY_ID,
                ArtifactKey.of("segments/00000000.segment"),
                Optional.of(new ByteRange(7, 9)),
                failureTarget).toCompletableFuture().join());
        assertArrayEquals(new byte[] {99}, Files.readAllBytes(failureTarget));
    }

    @Test
    void refusesFetchTargetInsideStorageRoot() throws IOException {
        StagingReplay staging = stage();
        put(staging, "manifest.json", new byte[] {'{', '}'});
        storage.publish(staging).toCompletableFuture().join();

        Path forbiddenTarget = root.resolve("forbidden-cache.bin");
        assertThrows(CompletionException.class, () -> storage.fetch(
                REPLAY_ID,
                ArtifactKey.of("manifest.json"),
                Optional.empty(),
                forbiddenTarget).toCompletableFuture().join());
        assertFalse(Files.exists(forbiddenTarget));
    }

    @Test
    void rejectsDuplicatePutAndKeepsOriginalBytes() throws IOException {
        StagingReplay staging = stage();
        put(staging, "manifest.json", new byte[] {'o', 'l', 'd'});
        Path replacement = Files.write(root.resolve("replacement.manifest"),
                new byte[] {'n', 'e', 'w'});

        assertThrows(CompletionException.class, () -> storage.put(
                staging,
                ArtifactKey.of("manifest.json"),
                replacement).toCompletableFuture().join());

        storage.publish(staging).toCompletableFuture().join();
        Path target = cacheRoot.resolve("manifest.json");
        storage.fetch(REPLAY_ID, ArtifactKey.of("manifest.json"), Optional.empty(), target)
                .toCompletableFuture().join();
        assertArrayEquals(new byte[] {'o', 'l', 'd'}, Files.readAllBytes(target));
    }

    @Test
    void deletesPublishedAndStagedDataIdempotently() throws IOException {
        StagingReplay staging = stage();
        put(staging, "manifest.json", new byte[] {'{', '}'});
        storage.publish(staging).toCompletableFuture().join();

        storage.delete(REPLAY_ID).toCompletableFuture().join();
        assertFalse(storage.exists(REPLAY_ID, ArtifactKey.of("manifest.json"))
                .toCompletableFuture().join());
        storage.delete(REPLAY_ID).toCompletableFuture().join();
        assertTrue(Files.exists(root));
    }

    @Test
    void rejectsTraversalKeysBeforeStorageOperations() {
        assertThrows(IllegalArgumentException.class, () -> ArtifactKey.of("../outside"));
        assertThrows(IllegalArgumentException.class, () -> ArtifactKey.of("/outside"));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactKey.of("segments/../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactKey.of("segments//outside"));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactKey.of("segments\\..\\outside"));
        assertThrows(IllegalArgumentException.class, () -> new ByteRange(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ByteRange(1, 1));
    }

    private StagingReplay stage() {
        return storage.stage(REPLAY_ID).toCompletableFuture().join();
    }

    private void put(StagingReplay staging, String key, byte[] bytes) throws IOException {
        Path source = Files.createTempFile(root.getParent(), "replay-storage-source-", ".bin");
        Files.write(source, bytes);
        storage.put(staging, ArtifactKey.of(key), source).toCompletableFuture().join();
        Files.deleteIfExists(source);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException exception) {
                            throw new ContractCleanupException(exception);
                        }
                    });
        } catch (ContractCleanupException exception) {
            throw exception.ioException;
        }
    }

    private static final class ContractCleanupException extends RuntimeException {
        private final IOException ioException;

        private ContractCleanupException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}
