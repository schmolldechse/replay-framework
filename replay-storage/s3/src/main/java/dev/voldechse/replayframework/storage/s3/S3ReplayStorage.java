package dev.voldechse.replayframework.storage.s3;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ByteRange;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.EOFException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Asynchronous S3-compatible implementation of the internal replay storage port. */
public final class S3ReplayStorage implements ReplayStorage {

    private static final Logger LOGGER = Logger.getLogger(S3ReplayStorage.class.getName());
    private static final ArtifactKey MANIFEST_KEY = ArtifactKey.of("manifest.json");
    private static final String STAGE_LOCK = ".stage-lock";
    private static final String FETCH_PART_PREFIX = ".replay-storage-fetch-";
    private static final int DELETE_BATCH_SIZE = 1_000;

    private final S3AsyncClient client;
    private final S3StorageConfiguration configuration;
    private final Executor ioExecutor;
    private final Path forbiddenTargetRoot;
    private final ConcurrentHashMap<ReplayId, ReplayHandle> activeHandles =
            new ConcurrentHashMap<>();
    private final Object operationMonitor = new Object();
    private int activeOperations;
    private boolean closing;

    /** Creates an S3 backend without a local target-root restriction. */
    public S3ReplayStorage(
            S3AsyncClient client,
            S3StorageConfiguration configuration,
            Executor ioExecutor) {
        this(client, configuration, ioExecutor, null);
    }

    /** Creates an S3 backend with an optional local root used by backend contract tests. */
    public S3ReplayStorage(
            S3AsyncClient client,
            S3StorageConfiguration configuration,
            Executor ioExecutor,
            Path forbiddenTargetRoot) {
        this.client = Objects.requireNonNull(client, "client");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.forbiddenTargetRoot = forbiddenTargetRoot == null
                ? null
                : forbiddenTargetRoot.toAbsolutePath().normalize();
    }

    @Override
    public CompletionStage<StagingReplay> stage(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return track(() -> stageInternal(replayId));
    }

    @Override
    public CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source) {
        Objects.requireNonNull(staging, "staging");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(source, "source");
        return track(() -> {
            ReplayHandle handle = requireHandle(staging);
            return handle.enqueuePut(key, source);
        });
    }

    @Override
    public CompletionStage<Void> publish(StagingReplay staging) {
        Objects.requireNonNull(staging, "staging");
        return track(() -> {
            ReplayHandle handle = requireHandle(staging);
            return handle.enqueuePublish();
        });
    }

    @Override
    public CompletionStage<Path> fetch(
            ReplayId replayId,
            ArtifactKey key,
            Optional<ByteRange> range,
            Path target) {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(target, "target");
        return track(() -> fetchInternal(replayId, key, range, target));
    }

    @Override
    public CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key) {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(key, "key");
        return track(() -> headExists(publishedManifestKey(replayId))
                .thenCompose(manifestExists -> manifestExists
                        ? headExists(publishedArtifactKey(replayId, key))
                        : CompletableFuture.completedFuture(false)));
    }

    @Override
    public CompletionStage<Void> delete(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return track(() -> deleteInternal(replayId));
    }

    /** Stops accepting new operations and closes the client after active operations drain. */
    public void close() {
        boolean closeNow;
        synchronized (operationMonitor) {
            if (closing) {
                return;
            }
            closing = true;
            closeNow = activeOperations == 0;
        }
        if (closeNow) {
            client.close();
        }
    }

    private CompletionStage<StagingReplay> stageInternal(ReplayId replayId) {
        CompletableFuture<Void> stageCompletion = new CompletableFuture<>();
        ReplayHandle handle = new ReplayHandle(this, replayId, stageCompletion);
        if (activeHandles.putIfAbsent(replayId, handle) != null) {
            return failedStage(new FileAlreadyExistsException(
                    "staging already active: " + replayId));
        }

        String stagingPrefix = stagingPrefix(replayId);
        CompletionStage<StagingReplay> stage = headExists(publishedManifestKey(replayId))
                .thenCompose(published -> {
                    if (published) {
                        return failedStage(new FileAlreadyExistsException(
                                "replay already published: " + replayId));
                    }
                    return listHasObjects(publishedPrefix(replayId));
                })
                .thenCompose(publishedObjectsExist -> {
                    if (publishedObjectsExist) {
                        return failedStage(new FileAlreadyExistsException(
                                "published replay already has objects: " + replayId));
                    }
                    return listHasObjects(stagingPrefix);
                })
                .thenCompose(stagingExists -> {
                    if (stagingExists) {
                        return failedStage(new FileAlreadyExistsException(
                                "stale staging exists: " + replayId));
                    }
                    PutObjectRequest request = PutObjectRequest.builder()
                            .bucket(configuration.bucket())
                            .key(stagingPrefix + STAGE_LOCK)
                            .ifNoneMatch("*")
                            .contentLength(0L)
                            .build();
                    return client.putObject(request, AsyncRequestBody.fromBytes(new byte[0]))
                            .thenApply(ignored -> (StagingReplay) handle);
                });

        return stage.whenComplete((ignored, failure) -> {
            stageCompletion.complete(null);
            if (failure != null) {
                activeHandles.remove(replayId, handle);
            }
        });
    }

    private CompletionStage<Void> putInternal(
            ReplayHandle handle,
            ArtifactKey key,
            Path source) {
        return CompletableFuture.supplyAsync(() -> validateSource(source), ioExecutor)
                .thenCompose(sourceInfo -> {
                    PutObjectRequest request = PutObjectRequest.builder()
                            .bucket(configuration.bucket())
                            .key(stagingArtifactKey(handle.replayId, key))
                            .ifNoneMatch("*")
                            .contentLength(sourceInfo.size)
                            .build();
                    return client.putObject(
                                    request,
                                    AsyncRequestBody.fromFile(sourceInfo.path))
                            .thenApply(ignored -> (Void) null);
                });
    }

    private CompletionStage<Void> publishInternal(ReplayHandle handle) {
        String stagingPrefix = stagingPrefix(handle.replayId);
        return listAll(stagingPrefix).thenCompose(stagedObjects -> {
            List<String> keys = stagedObjects.stream()
                    .map(S3Object::key)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            String manifest = stagingArtifactKey(handle.replayId, MANIFEST_KEY);
            if (!keys.contains(manifest)) {
                return failedStage(new NoSuchFileException(
                        "manifest.json is missing from staging"));
            }
            return listHasObjects(publishedPrefix(handle.replayId))
                    .thenCompose(publishedExists -> {
                        if (publishedExists) {
                            return failedStage(new FileAlreadyExistsException(
                                    "published replay already has objects: "
                                            + handle.replayId));
                        }
                        String stageLock = stagingPrefix + STAGE_LOCK;
                        List<String> nonManifest = keys.stream()
                                .filter(key -> !key.equals(manifest))
                                .filter(key -> !key.equals(stageLock))
                                .toList();
                        return copySequential(handle.replayId, nonManifest)
                                .thenCompose(ignored -> copyObject(
                                        manifest,
                                        publishedArtifactKey(handle.replayId, MANIFEST_KEY)))
                                .thenCompose(ignored -> deleteKeys(keys)
                                        .handle((value, failure) -> {
                                            if (failure != null) {
                                                LOGGER.log(Level.WARNING,
                                                        "S3 staging cleanup failed for replay "
                                                                + handle.replayId
                                                                + " (cause type: "
                                                                + failure.getClass().getName()
                                                                + ")");
                                            }
                                            return null;
                                        }));
                    });
        });
    }

    private CompletionStage<Path> fetchInternal(
            ReplayId replayId,
            ArtifactKey key,
            Optional<ByteRange> range,
            Path target) {
        return headExists(publishedManifestKey(replayId)).thenCompose(manifestExists -> {
            if (!manifestExists) {
                return failedStage(new NoSuchFileException(
                        "published replay does not exist: " + replayId));
            }
            return CompletableFuture.supplyAsync(() -> prepareTarget(target), ioExecutor)
                    .thenCompose(fetchTarget -> {
                        GetObjectRequest.Builder request = GetObjectRequest.builder()
                                .bucket(configuration.bucket())
                                .key(publishedArtifactKey(replayId, key));
                        range.ifPresent(byteRange -> request.range(
                                "bytes=" + byteRange.startInclusive() + "-"
                                        + (byteRange.endExclusive() - 1)));
                        CompletionStage<software.amazon.awssdk.services.s3.model.GetObjectResponse>
                                response;
                        try {
                            response = client.getObject(
                                    request.build(),
                                    AsyncResponseTransformer.toFile(fetchTarget.temporary));
                        } catch (RuntimeException exception) {
                            deleteQuietly(fetchTarget.temporary);
                            return failedStage(exception);
                        }
                        return response.thenCompose(result ->
                                CompletableFuture.supplyAsync(
                                        () -> finishFetch(fetchTarget, result, range),
                                        ioExecutor))
                                .whenComplete((result, failure) -> {
                                    if (failure != null) {
                                        deleteQuietly(fetchTarget.temporary);
                                    }
                                });
                    });
        });
    }

    private CompletionStage<Void> deleteInternal(ReplayId replayId) {
        ReplayHandle handle = activeHandles.remove(replayId);
        CompletionStage<Void> prerequisite = CompletableFuture.completedFuture(null);
        if (handle != null) {
            synchronized (handle) {
                handle.state = StorageState.DELETED;
                handle.publishingRequested = true;
                prerequisite = handle.lifecycleTail;
            }
        }
        return prerequisite.handle((ignored, failure) -> null)
                .thenCompose(ignored -> listAll(stagingPrefix(replayId)))
                .thenCompose(stagingObjects -> listAll(publishedPrefix(replayId))
                        .thenCompose(publishedObjects -> {
                            List<String> keys = new ArrayList<>(stagingObjects.size()
                                    + publishedObjects.size());
                            stagingObjects.stream().map(S3Object::key).filter(Objects::nonNull)
                                    .forEach(keys::add);
                            publishedObjects.stream().map(S3Object::key).filter(Objects::nonNull)
                                    .forEach(keys::add);
                            return deleteKeys(keys);
                        }));
    }

    private CompletionStage<Void> copySequential(ReplayId replayId, List<String> keys) {
        CompletionStage<Void> result = CompletableFuture.completedFuture(null);
        for (String source : keys) {
            String relative = source.substring(stagingPrefix(replayId).length());
            result = result.thenCompose(ignored -> copyObject(
                    source,
                    publishedPrefix(replayId) + relative));
        }
        return result;
    }

    private CompletionStage<Void> copyObject(String source, String destination) {
        CopyObjectRequest request = CopyObjectRequest.builder()
                .bucket(configuration.bucket())
                .key(destination)
                .copySource(encodeCopySource(configuration.bucket(), source))
                .ifNoneMatch("*")
                .build();
        return client.copyObject(request).thenApply(ignored -> null);
    }

    private CompletionStage<List<S3Object>> listAll(String prefix) {
        List<S3Object> objects = new ArrayList<>();
        return listPage(prefix, null, objects).thenApply(ignored -> List.copyOf(objects));
    }

    private CompletionStage<Void> listPage(
            String prefix,
            String continuationToken,
            List<S3Object> target) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(configuration.bucket())
                .prefix(prefix);
        if (continuationToken != null) {
            request.continuationToken(continuationToken);
        }
        return client.listObjectsV2(request.build()).thenCompose(response -> {
            target.addAll(response.contents());
            String next = response.nextContinuationToken();
            if (Boolean.TRUE.equals(response.isTruncated()) && next != null) {
                return listPage(prefix, next, target);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletionStage<Boolean> listHasObjects(String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(configuration.bucket())
                .prefix(prefix)
                .maxKeys(1)
                .build();
        return client.listObjectsV2(request)
                .thenApply(response -> !response.contents().isEmpty());
    }

    private CompletionStage<Void> deleteKeys(List<String> keys) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        int end = Math.min(DELETE_BATCH_SIZE, keys.size());
        List<ObjectIdentifier> identifiers = keys.subList(0, end).stream()
                .distinct()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
        DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                .bucket(configuration.bucket())
                .delete(builder -> builder.objects(identifiers).quiet(true))
                .build();
        return client.deleteObjects(request).thenCompose(response -> {
            if (!response.errors().isEmpty()) {
                return failedStage(new IOException(
                        "S3 delete returned " + response.errors().size() + " object errors"));
            }
            return deleteKeys(keys.subList(end, keys.size()));
        });
    }

    private CompletionStage<Boolean> headExists(String key) {
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(configuration.bucket())
                .key(key)
                .build();
        return client.headObject(request)
                .thenApply(ignored -> true)
                .exceptionallyCompose(failure -> {
                    Throwable cause = unwrap(failure);
                    if (isNotFound(cause)) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return failedStage(cause);
                });
    }

    private SourceInfo validateSource(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new CompletionException(new NoSuchFileException(
                    "source is not a regular file: " + normalized));
        }
        try {
            return new SourceInfo(normalized, Files.size(normalized));
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private FetchTarget prepareTarget(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (forbiddenTargetRoot != null && normalized.startsWith(forbiddenTargetRoot)) {
            throw new CompletionException(new IOException(
                    "fetch target must be outside the backend storage root"));
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new CompletionException(new IOException("fetch target must not be symbolic"));
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new CompletionException(new IOException("fetch target has no parent"));
        }
        try {
            Files.createDirectories(parent);
            if (forbiddenTargetRoot != null
                    && parent.toRealPath().startsWith(forbiddenTargetRoot.toRealPath())) {
                throw new IOException("fetch target must be outside the backend storage root");
            }
            Path temporary = Files.createTempFile(parent, FETCH_PART_PREFIX, ".part");
            return new FetchTarget(normalized, temporary);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private Path finishFetch(
            FetchTarget fetchTarget,
            software.amazon.awssdk.services.s3.model.GetObjectResponse response,
            Optional<ByteRange> range) {
        try {
            long expected = range.map(ByteRange::length)
                    .orElse(response.contentLength());
            long actual = Files.size(fetchTarget.temporary);
            if (actual != expected) {
                throw new EOFException("fetched byte count does not match requested range");
            }
            forceFile(fetchTarget.temporary);
            if (Files.isSymbolicLink(fetchTarget.target)) {
                throw new IOException("fetch target must not be symbolic");
            }
            Files.move(fetchTarget.temporary, fetchTarget.target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return fetchTarget.target;
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private String stagingPrefix(ReplayId replayId) {
        return prefixWithSlash() + ".staging/" + replayId + "/";
    }

    private String publishedPrefix(ReplayId replayId) {
        return prefixWithSlash() + "replays/" + replayId + "/";
    }

    private String stagingArtifactKey(ReplayId replayId, ArtifactKey key) {
        return stagingPrefix(replayId) + key.value();
    }

    private String publishedArtifactKey(ReplayId replayId, ArtifactKey key) {
        return publishedPrefix(replayId) + key.value();
    }

    private String publishedManifestKey(ReplayId replayId) {
        return publishedArtifactKey(replayId, MANIFEST_KEY);
    }

    private String prefixWithSlash() {
        return configuration.prefix().isEmpty() ? "" : configuration.prefix() + "/";
    }

    private ReplayHandle requireHandle(StagingReplay staging) {
        if (!(staging instanceof ReplayHandle handle) || handle.owner != this) {
            throw new IllegalArgumentException(
                    "staging handle belongs to another storage backend");
        }
        return handle;
    }

    private <T> CompletionStage<T> track(Supplier<CompletionStage<T>> operation) {
        synchronized (operationMonitor) {
            if (closing) {
                return failedStage(new IllegalStateException("S3 storage is closed"));
            }
            activeOperations++;
        }
        CompletionStage<T> result;
        try {
            result = Objects.requireNonNull(operation.get(), "operation result");
        } catch (Throwable failure) {
            endOperation();
            return failedStage(failure);
        }
        return result.whenComplete((ignored, failure) -> endOperation());
    }

    private void endOperation() {
        boolean closeNow = false;
        synchronized (operationMonitor) {
            activeOperations--;
            if (closing && activeOperations == 0) {
                closeNow = true;
            }
        }
        if (closeNow) {
            client.close();
        }
    }

    private static String encodeCopySource(String bucket, String key) {
        String encodedKey = List.of(key.split("/", -1)).stream()
                .map(S3ReplayStorage::urlEncode)
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
        return urlEncode(bucket) + "/" + encodedKey;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A failed cleanup cannot make an unverified target visible.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isNotFound(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof S3Exception exception && exception.statusCode() == 404) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    private enum StorageState {
        /** The handle accepts new artifact uploads. */
        OPEN,
        /** The manifest publication is in progress. */
        PUBLISHING,
        /** The manifest was published and the handle is immutable. */
        PUBLISHED,
        /** Delete invalidated the handle and owns cleanup of both namespaces. */
        DELETED
    }

    private record SourceInfo(Path path, long size) {
    }

    private record FetchTarget(Path target, Path temporary) {
    }

    private static final class ReplayHandle implements StagingReplay {
        private final S3ReplayStorage owner;
        private final ReplayId replayId;
        private CompletableFuture<Void> mutationTail = CompletableFuture.completedFuture(null);
        private CompletableFuture<Void> lifecycleTail;
        private StorageState state = StorageState.OPEN;
        private boolean publishingRequested;

        private ReplayHandle(
                S3ReplayStorage owner,
                ReplayId replayId,
                CompletableFuture<Void> stageCompletion) {
            this.owner = owner;
            this.replayId = replayId;
            this.lifecycleTail = stageCompletion;
        }

        @Override
        public ReplayId replayId() {
            return replayId;
        }

        private synchronized CompletionStage<Void> enqueuePut(ArtifactKey key, Path source) {
            ensureOpen();
            if (publishingRequested) {
                throw new IllegalStateException("staging publish has already started");
            }
            CompletionStage<Void> prior = mutationTail;
            CompletableFuture<Void> operation = prior.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> owner.putInternal(this, key, source))
                    .toCompletableFuture();
            mutationTail = operation.handle((ignored, failure) -> (Void) null)
                    .toCompletableFuture();
            lifecycleTail = mutationTail;
            return operation;
        }

        private synchronized CompletionStage<Void> enqueuePublish() {
            ensureOpen();
            if (publishingRequested) {
                throw new IllegalStateException("publish already started");
            }
            publishingRequested = true;
            CompletionStage<Void> prior = mutationTail;
            CompletionStage<Void> operation = prior.thenCompose(ignored -> {
                synchronized (this) {
                    ensureOpen();
                    state = StorageState.PUBLISHING;
                }
                return owner.publishInternal(this);
            });
            lifecycleTail = operation.toCompletableFuture();
            return operation.whenComplete((ignored, failure) -> {
                synchronized (this) {
                    if (failure == null) {
                        state = StorageState.PUBLISHED;
                        owner.activeHandles.remove(replayId, this);
                    } else if (state == StorageState.PUBLISHING) {
                        state = StorageState.OPEN;
                    }
                    publishingRequested = false;
                }
            });
        }

        private synchronized void ensureOpen() {
            if (state != StorageState.OPEN) {
                throw new IllegalStateException("staging handle is " + state);
            }
        }
    }
}
