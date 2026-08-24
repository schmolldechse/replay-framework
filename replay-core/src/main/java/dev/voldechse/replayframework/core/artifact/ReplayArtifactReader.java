package dev.voldechse.replayframework.core.artifact;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplayManifest.ArtifactFile;
import dev.voldechse.replayframework.format.ReplayManifestCodec;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ReplayStorage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Reads only committed replay artifacts and verifies every downloaded member before exposing it.
 *
 * <p>There is intentionally no verified range-read method. Playback may choose ranges later, but
 * this boundary only returns complete files whose manifest size and digest have been checked.</p>
 */
public final class ReplayArtifactReader {

    private static final String MANIFEST_PATH = "manifest.json";

    private final ReplayStorage storage;
    private final ReplayManifestCodec manifestCodec;
    private final ArtifactIntegrityVerifier integrityVerifier;
    private final Executor executor;
    private final Path workDirectory;

    /**
     * Creates a reader with a private local download workspace.
     *
     * @param storage asynchronous storage port
     * @param manifestCodec manifest codec
     * @param integrityVerifier artifact verifier
     * @param executor executor for local file work
     * @param workDirectory private local download workspace
     */
    public ReplayArtifactReader(
            ReplayStorage storage,
            ReplayManifestCodec manifestCodec,
            ArtifactIntegrityVerifier integrityVerifier,
            Executor executor,
            Path workDirectory) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.manifestCodec = Objects.requireNonNull(manifestCodec, "manifestCodec");
        this.integrityVerifier = Objects.requireNonNull(integrityVerifier, "integrityVerifier");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Fetches and validates the replay commit marker.
     *
     * @param replayId replay identity to open
     * @return verified manifest and its replay identity
     */
    public CompletionStage<VerifiedReplay> openVerified(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return CompletableFuture.supplyAsync(this::createManifestDownload, executor)
                .thenCompose(manifestTarget -> storage.exists(replayId, ArtifactKey.of(MANIFEST_PATH))
                        .thenCompose(exists -> {
                            if (!exists) {
                                deleteQuietly(manifestTarget);
                                return CompletableFuture.failedFuture(
                                        new ReplayUnavailableException(
                                                "replay manifest is not published: " + replayId));
                            }
                            return storage.fetch(
                                            replayId,
                                            ArtifactKey.of(MANIFEST_PATH),
                                            Optional.empty(),
                                            manifestTarget)
                                    .thenApplyAsync(fetched -> readVerifiedManifest(replayId, fetched), executor)
                                    .whenComplete((ignored, failure) -> deleteQuietly(manifestTarget));
                        }));
    }

    /**
     * Fetches one exact manifest member into a temporary file, verifies it, and atomically moves
     * it to the requested target. Existing targets are not touched when verification fails.
     *
     * @param replay verified replay handle
     * @param artifact exact artifact value from the verified manifest
     * @param target final local playback target
     * @return final target path after integrity verification and atomic replacement
     */
    public CompletionStage<Path> fetchVerified(
            VerifiedReplay replay,
            ArtifactFile artifact,
            Path target) {
        Objects.requireNonNull(replay, "replay");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(target, "target");
        ArtifactFile expected = exactManifestMember(replay.manifest(), artifact);
        Path normalizedTarget = target.toAbsolutePath().normalize();

        return CompletableFuture.supplyAsync(
                        () -> createArtifactDownload(normalizedTarget),
                        executor)
                .thenCompose(download -> storage.fetch(
                                replay.replayId(),
                                ArtifactKey.of(expected.path()),
                                Optional.empty(),
                                download)
                        .thenCompose(fetched -> CompletableFuture.runAsync(
                                () -> verifyDownloaded(download, fetched, expected),
                                executor))
                        .thenCompose(ignored -> CompletableFuture.runAsync(
                                () -> moveAtomically(download, normalizedTarget),
                                executor))
                        .thenApply(ignored -> normalizedTarget)
                        .whenComplete((ignored, failure) -> deleteQuietly(download)));
    }

    private VerifiedReplay readVerifiedManifest(ReplayId replayId, Path fetched) {
        Path normalized = fetched.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new CompletionException(new CorruptReplayArtifactException(
                    "fetched replay manifest is not a regular file"));
        }
        try {
            ReplayManifest manifest = manifestCodec.read(normalized);
            if (!replayId.toString().equals(manifest.replayId())) {
                throw new CompletionException(new CorruptReplayArtifactException(
                        "replay manifest identity does not match requested replay: " + replayId));
            }
            return new VerifiedReplay(replayId, manifest);
        } catch (IOException exception) {
            throw new CompletionException(new CorruptReplayArtifactException(
                    "replay manifest is unavailable or corrupt", exception));
        }
    }

    private static ArtifactFile exactManifestMember(
            ReplayManifest manifest,
            ArtifactFile requested) {
        for (ArtifactFile member : manifest.files()) {
            if (member.equals(requested)) {
                return member;
            }
        }
        throw new IllegalArgumentException("artifact is not an exact member of the verified manifest");
    }

    private void verifyDownloaded(Path expectedDownload, Path fetched, ArtifactFile expected) {
        Path normalizedFetched = fetched.toAbsolutePath().normalize();
        if (!expectedDownload.equals(normalizedFetched)) {
            throw new ArtifactReadException("storage returned an unexpected download path");
        }
        try {
            integrityVerifier.verify(expectedDownload, expected);
        } catch (CorruptReplayArtifactException exception) {
            throw new CompletionException(exception);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private void moveAtomically(Path source, Path target) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new ArtifactReadException("refusing to replace symbolic-link target: " + target);
        }
        try {
            Path parent = target.getParent();
            if (parent == null) {
                throw new IOException("target must have a parent directory");
            }
            Files.createDirectories(parent);
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new ArtifactReadException(
                    "filesystem does not support atomic replay artifact replacement", exception);
        } catch (IOException exception) {
            throw new ArtifactReadException("could not commit downloaded replay artifact", exception);
        }
    }

    private Path createManifestDownload() {
        try {
            Files.createDirectories(workDirectory);
            return Files.createTempFile(workDirectory, ".manifest-", ".json.part");
        } catch (IOException exception) {
            throw new ArtifactReadException("could not create manifest download", exception);
        }
    }

    private Path createArtifactDownload(Path target) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new ArtifactReadException("refusing to replace symbolic-link target: " + target);
        }
        try {
            Files.createDirectories(workDirectory);
            return Files.createTempFile(workDirectory, ".artifact-", ".download");
        } catch (IOException exception) {
            throw new ArtifactReadException("could not create artifact download", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A failed cleanup must never replace the original storage or integrity failure.
        }
    }

    /** Immutable handle proving that the requested manifest matched its replay identity. */
    public record VerifiedReplay(ReplayId replayId, ReplayManifest manifest) {

        /** Validates the identity binding retained by this handle. */
        public VerifiedReplay {
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(manifest, "manifest");
            if (!replayId.toString().equals(manifest.replayId())) {
                throw new IllegalArgumentException("manifest does not belong to replayId");
            }
        }
    }

    /** Signals that a replay cannot be opened as a committed, trusted artifact set. */
    public static final class ReplayUnavailableException extends IOException {

        /** Creates an unavailable-replay error. */
        public ReplayUnavailableException(String message) {
            super(message);
        }

        /** Creates an unavailable-replay error with its storage or decoding cause. */
        public ReplayUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ArtifactReadException extends RuntimeException {

        private ArtifactReadException(String message) {
            super(message);
        }

        private ArtifactReadException(String message, IOException cause) {
            super(message, cause);
        }

        private ArtifactReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
