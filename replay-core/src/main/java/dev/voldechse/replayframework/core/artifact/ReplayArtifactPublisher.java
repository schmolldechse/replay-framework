package dev.voldechse.replayframework.core.artifact;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplayManifest.ArtifactFile;
import dev.voldechse.replayframework.format.ReplayManifest.ArtifactType;
import dev.voldechse.replayframework.format.ReplayManifestCodec;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Builds and atomically publishes a replay artifact set.
 *
 * <p>The manifest is the commit marker. All replay files are uploaded in deterministic order,
 * then {@code manifest.json} is uploaded, and only then is the opaque storage staging handle
 * published. A failed chain never invokes {@link ReplayStorage#publish(StagingReplay)}.</p>
 */
public final class ReplayArtifactPublisher {

    private static final String MANIFEST_PATH = "manifest.json";

    private final ReplayStorage storage;
    private final ReplayManifestCodec manifestCodec;
    private final ArtifactIntegrityVerifier integrityVerifier;
    private final Executor executor;
    private final Path workDirectory;
    private final ReplayDiagnostics diagnostics;

    /**
     * Creates a publisher whose blocking file work is isolated from the server thread.
     *
     * @param storage asynchronous storage port
     * @param manifestCodec manifest codec
     * @param integrityVerifier artifact metadata verifier
     * @param executor executor for local file work and continuation scheduling
     * @param workDirectory private local directory for generated commit markers
     */
    public ReplayArtifactPublisher(
            ReplayStorage storage,
            ReplayManifestCodec manifestCodec,
            ArtifactIntegrityVerifier integrityVerifier,
            Executor executor,
            Path workDirectory) {
        this(
                storage,
                manifestCodec,
                integrityVerifier,
                executor,
                workDirectory,
                new ReplayDiagnostics());
    }

    public ReplayArtifactPublisher(
            ReplayStorage storage,
            ReplayManifestCodec manifestCodec,
            ArtifactIntegrityVerifier integrityVerifier,
            Executor executor,
            Path workDirectory,
            ReplayDiagnostics diagnostics) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.manifestCodec = Objects.requireNonNull(manifestCodec, "manifestCodec");
        this.integrityVerifier = Objects.requireNonNull(integrityVerifier, "integrityVerifier");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory")
                .toAbsolutePath()
                .normalize();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Stages, describes and uploads a complete replay, then publishes it atomically.
     *
     * @param request immutable replay publication input
     * @return manifest returned only after the storage publish operation completes
     */
    public CompletionStage<ReplayManifest> publish(PublishRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(
                        () -> createWorkDirectory(request.replayId()),
                        executor)
                .thenCompose(work -> storage.stage(request.replayId())
                        .thenCompose(staging -> validateStaging(staging, request.replayId())
                                .thenCompose(ignored -> uploadArtifacts(
                                        staging,
                                        work,
                                        artifactSpecs(request),
                                        0,
                                        List.of()))
                                .thenCompose(files -> writeManifest(work, request, files)
                                                .thenCompose(manifest -> measuredPut(
                                                        staging,
                                                        ArtifactKey.of(MANIFEST_PATH),
                                                        work.resolve(MANIFEST_PATH))
                                                .thenCompose(ignored -> storage.publish(staging))
                                                .thenApply(ignored -> {
                                                    deleteWorkDirectory(work);
                                                    return manifest;
                                                })))));
    }

    private CompletionStage<Void> validateStaging(StagingReplay staging, ReplayId replayId) {
        Objects.requireNonNull(staging, "storage returned null staging handle");
        if (!replayId.equals(staging.replayId())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("storage returned a staging handle for another replay"));
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<List<ArtifactFile>> uploadArtifacts(
            StagingReplay staging,
            Path work,
            List<ArtifactSpec> specs,
            int position,
            List<ArtifactFile> uploadedFiles) {
        if (position == specs.size()) {
            return CompletableFuture.completedFuture(List.copyOf(uploadedFiles));
        }

        ArtifactSpec spec = specs.get(position);
        return CompletableFuture.supplyAsync(
                        () -> {
                            Path snapshot = snapshot(spec, work);
                            return new PreparedArtifact(
                                    describe(new ArtifactSpec(spec.type(), spec.path(), snapshot)),
                                    snapshot);
                        },
                        executor)
                .thenCompose(prepared -> measuredPut(
                                staging,
                                ArtifactKey.of(prepared.file().path()),
                                prepared.source())
                        .thenCompose(ignored -> {
                            List<ArtifactFile> nextFiles = new ArrayList<>(uploadedFiles);
                            nextFiles.add(prepared.file());
                            return uploadArtifacts(
                                    staging,
                                    work,
                                    specs,
                                    position + 1,
                                    nextFiles);
                        }));
    }

    private CompletionStage<Void> measuredPut(
            StagingReplay staging,
            ArtifactKey key,
            Path source) {
        final long size;
        try {
            size = Files.size(source);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        ReplayDiagnostics.StorageOperation operation = diagnostics.beginStorageOperation(size);
        final CompletionStage<Void> put;
        try {
            put = Objects.requireNonNull(storage.put(staging, key, source), "storage.put result");
        } catch (Throwable failure) {
            operation.fail();
            return CompletableFuture.failedFuture(failure);
        }
        return put.whenComplete((ignored, failure) -> {
            if (failure == null) {
                operation.complete();
            } else {
                operation.fail();
            }
        });
    }

    private ArtifactFile describe(ArtifactSpec spec) {
        try {
            return integrityVerifier.describe(spec.type(), spec.path(), spec.source());
        } catch (IOException exception) {
            throw new ArtifactPublicationException(
                "could not describe replay artifact " + spec.path(), exception);
        }
    }

    private Path snapshot(ArtifactSpec spec, Path work) {
        Path source = spec.source();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new ArtifactPublicationException(
                    "replay artifact source must be a regular non-symlink file: " + source,
                    new IOException("unsafe or missing source"));
        }
        Path snapshot = work.resolve("sources").resolve(spec.path().replace('/', '_') + ".source");
        try {
            Files.createDirectories(snapshot.getParent());
            Files.copy(source, snapshot, LinkOption.NOFOLLOW_LINKS);
            return snapshot;
        } catch (IOException exception) {
            throw new ArtifactPublicationException(
                    "could not snapshot replay artifact " + spec.path(), exception);
        }
    }

    private CompletionStage<ReplayManifest> writeManifest(
            Path work,
            PublishRequest request,
            List<ArtifactFile> files) {
        return CompletableFuture.supplyAsync(() -> {
            ReplayManifest manifest = new ReplayManifest(
                    request.replayId().toString(),
                    request.adapterId(),
                    request.protocolVersion(),
                    request.registryFingerprint(),
                    request.formatRevision(),
                    request.durationNanos(),
                    files);
            try {
                manifestCodec.write(work.resolve(MANIFEST_PATH), manifest);
            } catch (IOException exception) {
                throw new ArtifactPublicationException("could not write replay manifest", exception);
            }
            return manifest;
        }, executor);
    }

    private Path createWorkDirectory(ReplayId replayId) {
        try {
            Files.createDirectories(workDirectory);
            return Files.createTempDirectory(
                    workDirectory,
                    ".publish-" + replayId + "-");
        } catch (IOException exception) {
            throw new ArtifactPublicationException("could not create publisher work directory", exception);
        }
    }

    private void deleteWorkDirectory(Path work) {
        try (var paths = Files.walk(work)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A successful storage commit must not be reported as failed because cleanup is best effort.
                }
            });
        } catch (IOException ignored) {
            // Failed cleanup leaves only the publisher-owned diagnostic directory behind.
        }
    }

    private static List<ArtifactSpec> artifactSpecs(PublishRequest request) {
        List<ArtifactSpec> specs = new ArrayList<>(
                request.segmentFiles().size() + request.checkpointFiles().size() + 1);
        for (int index = 0; index < request.segmentFiles().size(); index++) {
            specs.add(new ArtifactSpec(
                    ArtifactType.SEGMENT,
                    String.format(Locale.ROOT, "segments/%08d.segment", index),
                    request.segmentFiles().get(index)));
        }
        for (int index = 0; index < request.checkpointFiles().size(); index++) {
            specs.add(new ArtifactSpec(
                    ArtifactType.CHECKPOINT,
                    String.format(Locale.ROOT, "checkpoints/%08d.checkpoint", index),
                    request.checkpointFiles().get(index)));
        }
        specs.add(new ArtifactSpec(ArtifactType.INDEX, "index.bin", request.indexFile()));
        return List.copyOf(specs);
    }

    /**
     * Immutable input to one publication attempt.
     *
     * <p>At least an index and one checkpoint are required. A segment list may be empty for a
     * replay whose index and checkpoint contain all required playback data.</p>
     *
     * @param replayId replay identity
     * @param adapterId producing adapter identifier
     * @param protocolVersion Minecraft protocol version
     * @param registryFingerprint adapter registry fingerprint
     * @param formatRevision manifest format revision
     * @param durationNanos recorded replay duration
     * @param indexFile local index artifact
     * @param segmentFiles local segment artifacts in playback order
     * @param checkpointFiles local checkpoint artifacts in checkpoint order
     */
    public record PublishRequest(
            ReplayId replayId,
            String adapterId,
            int protocolVersion,
            String registryFingerprint,
            int formatRevision,
            long durationNanos,
            Path indexFile,
            List<Path> segmentFiles,
            List<Path> checkpointFiles) {

        /** Validates and snapshots publication input. */
        public PublishRequest {
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(adapterId, "adapterId");
            if (adapterId.isEmpty()) {
                throw new IllegalArgumentException("adapterId must not be empty");
            }
            if (protocolVersion < 0) {
                throw new IllegalArgumentException("protocolVersion must not be negative");
            }
            Objects.requireNonNull(registryFingerprint, "registryFingerprint");
            if (registryFingerprint.isEmpty()) {
                throw new IllegalArgumentException("registryFingerprint must not be empty");
            }
            if (formatRevision != ReplayManifest.CURRENT_FORMAT_REVISION) {
                throw new IllegalArgumentException("unsupported manifest format revision: " + formatRevision);
            }
            if (durationNanos < 0L) {
                throw new IllegalArgumentException("durationNanos must not be negative");
            }
            indexFile = normalizeSource(indexFile, "indexFile");
            segmentFiles = normalizeSources(segmentFiles, "segmentFiles");
            checkpointFiles = normalizeSources(checkpointFiles, "checkpointFiles");
            if (checkpointFiles.isEmpty()) {
                throw new IllegalArgumentException("at least one checkpoint file is required");
            }
        }

        private static Path normalizeSource(Path source, String field) {
            Objects.requireNonNull(source, field);
            return source.toAbsolutePath().normalize();
        }

        private static List<Path> normalizeSources(List<Path> sources, String field) {
            Objects.requireNonNull(sources, field);
            List<Path> normalized = new ArrayList<>(sources.size());
            for (Path source : sources) {
                normalized.add(normalizeSource(source, field + " contains null"));
            }
            return List.copyOf(normalized);
        }
    }

    private record ArtifactSpec(ArtifactType type, String path, Path source) {
    }

    private record PreparedArtifact(ArtifactFile file, Path source) {
    }

    /** Runtime wrapper preserving the failed publication's checked cause in the async chain. */
    private static final class ArtifactPublicationException extends RuntimeException {

        private ArtifactPublicationException(String message, IOException cause) {
            super(message, cause);
        }
    }
}
