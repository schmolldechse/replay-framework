package dev.voldechse.replayframework.core.artifact;

import com.google.gson.Gson;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplayManifest.ArtifactFile;
import dev.voldechse.replayframework.format.ReplayManifest.ArtifactType;
import dev.voldechse.replayframework.format.ReplayManifestCodec;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ByteRange;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayArtifactFlowTest {

    private static final ReplayId REPLAY_ID = ReplayId.parse("11111111-1111-1111-1111-111111111111");
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void publisherUploadsManifestLastAndPublishesOnlyAfterIt(@TempDir Path temporaryDirectory)
            throws IOException {
        Path index = write(temporaryDirectory, "index", "index-bytes");
        Path segment = write(temporaryDirectory, "segment", "segment-bytes");
        Path checkpoint = write(temporaryDirectory, "checkpoint", "checkpoint-bytes");
        RecordingStorage storage = new RecordingStorage();
        ReplayManifestCodec codec = new ReplayManifestCodec(new Gson());
        ReplayArtifactPublisher publisher = new ReplayArtifactPublisher(
                storage,
                codec,
                new ArtifactIntegrityVerifier(),
                DIRECT_EXECUTOR,
                temporaryDirectory.resolve("publisher-work"));

        ReplayManifest manifest = publisher.publish(new ReplayArtifactPublisher.PublishRequest(
                        REPLAY_ID,
                        "paper-26.2",
                        0,
                        "sha256:registry",
                        ReplayManifest.CURRENT_FORMAT_REVISION,
                        12L,
                        index,
                        List.of(segment),
                        List.of(checkpoint)))
                .toCompletableFuture()
                .join();

        assertEquals(
                List.of(
                        "stage",
                        "put:segments/00000000.segment",
                        "put:checkpoints/00000000.checkpoint",
                        "put:index.bin",
                        "put:manifest.json",
                        "publish"),
                storage.events);
        assertTrue(storage.published);
        assertEquals(REPLAY_ID.toString(), manifest.replayId());
        assertEquals(
                List.of("checkpoints/00000000.checkpoint", "index.bin", "segments/00000000.segment"),
                manifest.files().stream().map(ArtifactFile::path).toList());
        assertArrayEquals(
                codec.encode(manifest),
                storage.publishedArtifacts.get("manifest.json"));
    }

    @Test
    void publisherDoesNotPublishAfterAnArtifactPutFails(@TempDir Path temporaryDirectory)
            throws IOException {
        Path index = write(temporaryDirectory, "index", "index-bytes");
        Path segment = write(temporaryDirectory, "segment", "segment-bytes");
        Path checkpoint = write(temporaryDirectory, "checkpoint", "checkpoint-bytes");
        RecordingStorage storage = new RecordingStorage();
        storage.failureKey = "segments/00000000.segment";
        ReplayArtifactPublisher publisher = new ReplayArtifactPublisher(
                storage,
                new ReplayManifestCodec(new Gson()),
                new ArtifactIntegrityVerifier(),
                DIRECT_EXECUTOR,
                temporaryDirectory.resolve("publisher-work"));

        assertThrows(
                CompletionException.class,
                () -> publisher.publish(new ReplayArtifactPublisher.PublishRequest(
                                REPLAY_ID,
                                "paper-26.2",
                                0,
                                "sha256:registry",
                                ReplayManifest.CURRENT_FORMAT_REVISION,
                                12L,
                                index,
                                List.of(segment),
                                List.of(checkpoint)))
                        .toCompletableFuture()
                        .join());

        assertFalse(storage.events.contains("publish"));
        assertFalse(storage.published);
    }

    @Test
    void readerRejectsHashMismatchWithoutReplacingExistingTarget(@TempDir Path temporaryDirectory)
            throws IOException {
        ReplayManifestCodec codec = new ReplayManifestCodec(new Gson());
        byte[] expectedBytes = "expected-index".getBytes();
        ReplayManifest manifest = manifestWithIndex(expectedBytes);
        RecordingStorage storage = new RecordingStorage();
        storage.publishedArtifacts.put("manifest.json", codec.encode(manifest));
        storage.publishedArtifacts.put("index.bin", "corrupted-index".getBytes());
        ReplayArtifactReader reader = new ReplayArtifactReader(
                storage,
                codec,
                new ArtifactIntegrityVerifier(),
                DIRECT_EXECUTOR,
                temporaryDirectory.resolve("reader-work"));
        ReplayArtifactReader.VerifiedReplay replay = reader.openVerified(REPLAY_ID)
                .toCompletableFuture()
                .join();
        Path target = write(temporaryDirectory, "target.bin", "keep-existing-target");

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> reader.fetchVerified(replay, manifest.files().getFirst(), target)
                        .toCompletableFuture()
                        .join());

        assertTrue(failure.getCause() instanceof CorruptReplayArtifactException);
        assertEquals("keep-existing-target", Files.readString(target));
    }

    @Test
    void readerReportsMissingManifestAsUnavailable(@TempDir Path temporaryDirectory) {
        RecordingStorage storage = new RecordingStorage();
        ReplayArtifactReader reader = new ReplayArtifactReader(
                storage,
                new ReplayManifestCodec(new Gson()),
                new ArtifactIntegrityVerifier(),
                DIRECT_EXECUTOR,
                temporaryDirectory.resolve("reader-work"));

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> reader.openVerified(REPLAY_ID).toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof ReplayArtifactReader.ReplayUnavailableException);
    }

    private static ReplayManifest manifestWithIndex(byte[] bytes) {
        return new ReplayManifest(
                REPLAY_ID.toString(),
                "paper-26.2",
                0,
                "sha256:registry",
                ReplayManifest.CURRENT_FORMAT_REVISION,
                0L,
                List.of(new ArtifactFile(ArtifactType.INDEX, "index.bin", bytes.length, sha256(bytes))));
    }

    private static Path write(Path directory, String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingStorage implements ReplayStorage {

        private final List<String> events = new ArrayList<>();
        private final Map<String, byte[]> stagedArtifacts = new HashMap<>();
        private final Map<String, byte[]> publishedArtifacts = new HashMap<>();
        private String failureKey;
        private boolean published;

        @Override
        public CompletionStage<StagingReplay> stage(ReplayId replayId) {
            events.add("stage");
            return CompletableFuture.completedFuture(new RecordingStaging(replayId));
        }

        @Override
        public CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source) {
            events.add("put:" + key.value());
            if (key.value().equals(failureKey)) {
                return CompletableFuture.failedFuture(new IOException("configured put failure"));
            }
            try {
                stagedArtifacts.put(key.value(), Files.readAllBytes(source));
                return CompletableFuture.completedFuture(null);
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public CompletionStage<Void> publish(StagingReplay staging) {
            events.add("publish");
            if (!stagedArtifacts.containsKey("manifest.json")) {
                return CompletableFuture.failedFuture(new IOException("manifest marker missing"));
            }
            publishedArtifacts.clear();
            publishedArtifacts.putAll(stagedArtifacts);
            published = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Path> fetch(
                ReplayId replayId,
                ArtifactKey key,
                Optional<ByteRange> range,
                Path target) {
            byte[] bytes = publishedArtifacts.get(key.value());
            if (bytes == null || range.isPresent()) {
                return CompletableFuture.failedFuture(new NoSuchFileException(key.value()));
            }
            try {
                Files.createDirectories(target.toAbsolutePath().normalize().getParent());
                Files.write(target, bytes);
                return CompletableFuture.completedFuture(target);
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key) {
            return CompletableFuture.completedFuture(publishedArtifacts.containsKey(key.value()));
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            publishedArtifacts.clear();
            stagedArtifacts.clear();
            return CompletableFuture.completedFuture(null);
        }
    }

    private record RecordingStaging(ReplayId replayId) implements StagingReplay {
    }
}
