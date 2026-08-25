package dev.voldechse.replayframework.core.playback.cache;

import dev.voldechse.replayframework.core.artifact.ArtifactIntegrityVerifier;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.format.ReplayManifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shared, integrity-checked disk cache for complete immutable replay segments. */
public final class DiskSegmentCache implements SegmentCache, AutoCloseable {

    private final Path cacheRoot;
    private final Path segmentsRoot;
    private final Path inflightRoot;
    private final long maxBytes;
    private final ReplayArtifactReader artifactReader;
    private final Executor ioExecutor;
    private final ArtifactIntegrityVerifier integrityVerifier = new ArtifactIntegrityVerifier();
    private final ReplayDiagnostics diagnostics;
    private final Object lock = new Object();
    private final LinkedHashMap<String, CacheEntry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentHashMap<String, CompletableFuture<CacheEntry>> inFlight =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long currentBytes;

    /**
     * Creates a shared cache rooted at the supplied directory.
     *
     * @param cacheRoot cache root owned by this instance
     * @param maxBytes maximum persistent cache bytes
     * @param artifactReader verified replay artifact reader
     * @param ioExecutor executor for file and digest work
     */
    public DiskSegmentCache(
            Path cacheRoot,
            long maxBytes,
            ReplayArtifactReader artifactReader,
            Executor ioExecutor) {
        this(cacheRoot, maxBytes, artifactReader, ioExecutor, new ReplayDiagnostics());
    }

    public DiskSegmentCache(
            Path cacheRoot,
            long maxBytes,
            ReplayArtifactReader artifactReader,
            Executor ioExecutor,
            ReplayDiagnostics diagnostics) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot")
                .toAbsolutePath()
                .normalize();
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.artifactReader = Objects.requireNonNull(artifactReader, "artifactReader");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.segmentsRoot = this.cacheRoot.resolve("segments").normalize();
        this.inflightRoot = this.cacheRoot.resolve("inflight").normalize();
        if (!segmentsRoot.startsWith(this.cacheRoot)
                || !inflightRoot.startsWith(this.cacheRoot)) {
            throw new IllegalArgumentException("cache directories must remain below cacheRoot");
        }
        try {
            rejectSymbolicLink(this.cacheRoot);
            Files.createDirectories(this.segmentsRoot);
            Files.createDirectories(this.inflightRoot);
            rejectSymbolicLink(this.segmentsRoot);
            rejectSymbolicLink(this.inflightRoot);
        } catch (IOException exception) {
            throw new UncheckedIOException("could not create replay cache directories", exception);
        }
    }

    /** Ensures one verified segment is available and returns a lease for it. */
    @Override
    public CompletionStage<CacheEntryLease> ensureAvailable(
            ReplayArtifactReader.VerifiedReplay replay,
            SegmentRef segment) {
        try {
            SegmentCache.requireManifestMember(replay, segment);
            ensureOpen();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        return CompletableFuture.supplyAsync(
                        () -> findValidPersistentHit(replay, segment),
                        ioExecutor)
                .thenCompose(hit -> hit != null
                        ? CompletableFuture.completedFuture(hit)
                        : joinOrStartFetch(replay, segment));
    }

    /** Prevents new operations while allowing active leases to finish. */
    @Override
    public void close() {
        closed.set(true);
    }

    private CacheEntryLease findValidPersistentHit(
            ReplayArtifactReader.VerifiedReplay replay,
            SegmentRef segment) {
        String digest = segment.artifact().sha256();
        Path path = persistentPath(digest);
        if (!isRegularNonSymlink(path)) {
            removeUnleasedEntry(digest, path);
            return null;
        }

        try {
            integrityVerifier.verify(path, segment.artifact());
        } catch (IOException exception) {
            removeUnleasedEntry(digest, path);
            return null;
        }

        synchronized (lock) {
            if (closed.get()) {
                throw new IllegalStateException("replay segment cache is closed");
            }
            CacheEntry current = entries.get(digest);
            if (current != null && current.path.equals(path)) {
                current.leases++;
                diagnostics.cacheHit();
                return current.lease(() -> release(current));
            }
            if (segment.artifact().sizeBytes() > maxBytes) {
                deleteIfSafe(path, segmentsRoot);
                return null;
            }
            evictForLocked(segment.artifact().sizeBytes());
            if (!canFitLocked(segment.artifact().sizeBytes())) {
                deleteIfSafe(path, segmentsRoot);
                return null;
            }
            CacheEntry created = new CacheEntry(
                    digest,
                    path,
                    segment.artifact().sizeBytes(),
                    true);
            entries.put(digest, created);
            currentBytes = addExact(currentBytes, created.sizeBytes);
            created.leases++;
            diagnostics.cacheHit();
            return created.lease(() -> release(created));
        }
    }

    private CompletionStage<CacheEntryLease> joinOrStartFetch(
            ReplayArtifactReader.VerifiedReplay replay,
            SegmentRef segment) {
        String digest = segment.artifact().sha256();
        diagnostics.cacheMiss();
        CompletableFuture<CacheEntry> future = inFlight.computeIfAbsent(
                digest,
                ignored -> startFetch(replay, segment, digest));
        return future.thenApplyAsync(this::lease, ioExecutor);
    }

    private CompletableFuture<CacheEntry> startFetch(
            ReplayArtifactReader.VerifiedReplay replay,
            SegmentRef segment,
            String digest) {
        AtomicReference<Path> temporaryPath = new AtomicReference<>();
        CompletableFuture<CacheEntry> future = CompletableFuture
                .supplyAsync(() -> createTemporaryPath(digest), ioExecutor)
                .thenCompose(path -> {
                    temporaryPath.set(path);
                    return artifactReader.fetchVerified(
                                    replay,
                                    segment.artifact(),
                                    path)
                            .thenApplyAsync(
                                    fetched -> registerFetched(
                                            digest,
                                            segment.artifact(),
                                            path,
                                            fetched),
                                    ioExecutor);
                });
        future.whenCompleteAsync((entry, failure) -> {
            Path path = temporaryPath.get();
            if (failure != null || entry == null || (entry != null && entry.persistent)) {
                if (path != null) {
                    deleteIfSafe(path, inflightRoot);
                }
            }
            inFlight.remove(digest, future);
        }, ioExecutor);
        return future;
    }

    private CacheEntry registerFetched(
            String digest,
            ReplayManifest.ArtifactFile artifact,
            Path temporaryPath,
            Path fetchedPath) {
        Path normalizedFetched = fetchedPath.toAbsolutePath().normalize();
        Path normalizedTemporary = temporaryPath.toAbsolutePath().normalize();
        if (!normalizedFetched.equals(normalizedTemporary)) {
            deleteIfSafe(normalizedTemporary, inflightRoot);
            throw new CacheReadException("artifact reader returned an unexpected cache path");
        }
        try {
            integrityVerifier.verify(normalizedFetched, artifact);
        } catch (IOException exception) {
            deleteIfSafe(normalizedFetched, inflightRoot);
            throw new CacheReadException("fetched segment failed cache verification", exception);
        }

        synchronized (lock) {
            if (closed.get()) {
                deleteIfSafe(normalizedFetched, inflightRoot);
                throw new CacheReadException("replay segment cache is closed");
            }
            CacheEntry existing = entries.get(digest);
            if (existing != null && existing.path.equals(persistentPath(digest))) {
                deleteIfSafe(normalizedFetched, inflightRoot);
                return existing;
            }

            long sizeBytes = artifact.sizeBytes();
            if (sizeBytes > maxBytes) {
                return new CacheEntry(digest, normalizedFetched, sizeBytes, false);
            }

            evictForLocked(sizeBytes);
            if (!canFitLocked(sizeBytes)) {
                return new CacheEntry(digest, normalizedFetched, sizeBytes, false);
            }

            Path destination = persistentPath(digest);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(destination)) {
                deleteIfSafe(normalizedFetched, inflightRoot);
                throw new CacheReadException("refusing to replace symbolic cache entry");
            }
            moveAtomically(normalizedFetched, destination);
            CacheEntry created = new CacheEntry(digest, destination, sizeBytes, true);
            entries.put(digest, created);
            currentBytes = addExact(currentBytes, sizeBytes);
            return created;
        }
    }

    private void release(CacheEntry entry) {
        synchronized (lock) {
            if (entry.leases > 0) {
                entry.leases--;
            }
            if (!entry.persistent && entry.leases == 0) {
                deleteIfSafe(entry.path, inflightRoot);
            }
        }
    }

    private void removeUnleasedEntry(String digest, Path path) {
        synchronized (lock) {
            CacheEntry entry = entries.get(digest);
            if (entry != null && entry.leases == 0) {
                entries.remove(digest);
                currentBytes = subtractExact(currentBytes, entry.sizeBytes);
                deleteIfSafe(entry.path, segmentsRoot);
            } else if (entry == null) {
                deleteIfSafe(path, segmentsRoot);
            }
        }
    }

    private void evictForLocked(long requiredBytes) {
        while (!canFitLocked(requiredBytes)) {
            Iterator<java.util.Map.Entry<String, CacheEntry>> iterator = entries.entrySet().iterator();
            CacheEntry victim = null;
            String victimDigest = null;
            while (iterator.hasNext()) {
                java.util.Map.Entry<String, CacheEntry> candidate = iterator.next();
                if (candidate.getValue().leases == 0) {
                    victim = candidate.getValue();
                    victimDigest = candidate.getKey();
                    iterator.remove();
                    break;
                }
            }
            if (victim == null) {
                return;
            }
            currentBytes = subtractExact(currentBytes, victim.sizeBytes);
            deleteIfSafe(victim.path, segmentsRoot);
            if (victimDigest == null) {
                return;
            }
        }
    }

    private boolean canFitLocked(long requiredBytes) {
        return requiredBytes <= maxBytes - currentBytes;
    }

    private CacheEntryLease lease(CacheEntry entry) {
        synchronized (lock) {
            if (closed.get()) {
                throw new IllegalStateException("replay segment cache is closed");
            }
            entry.leases++;
            return entry.lease(() -> release(entry));
        }
    }

    private Path persistentPath(String digest) {
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("segment SHA-256 is not canonical");
        }
        Path path = segmentsRoot.resolve(digest + ".segment").normalize();
        if (!path.startsWith(segmentsRoot)) {
            throw new IllegalArgumentException("segment cache path escapes cache root");
        }
        return path;
    }

    private Path createTemporaryPath(String digest) {
        try {
            return Files.createTempFile(inflightRoot, digest + "-", ".download")
                    .toAbsolutePath()
                    .normalize();
        } catch (IOException exception) {
            throw new UncheckedIOException("could not create replay cache download", exception);
        }
    }

    private void moveAtomically(Path source, Path destination) {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            try {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallback) {
                throw new CacheReadException("could not publish replay cache entry", fallback);
            }
        } catch (IOException exception) {
            throw new CacheReadException("could not publish replay cache entry", exception);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("replay segment cache is closed");
        }
    }

    private static boolean isRegularNonSymlink(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(path);
    }

    private static void rejectSymbolicLink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("cache root must not be a symbolic link: " + path);
        }
    }

    private static void deleteIfSafe(Path path, Path parent) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedParent = parent.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(normalizedParent)) {
            return;
        }
        try {
            Files.deleteIfExists(normalized);
        } catch (IOException ignored) {
            // Cleanup must not replace the original read or decode result.
        }
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CacheReadException("replay cache byte counter overflow", exception);
        }
    }

    private static long subtractExact(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CacheReadException("replay cache byte counter underflow", exception);
        }
    }

    private static class CacheEntry {
        private final String sha256;
        private final Path path;
        private final long sizeBytes;
        private final boolean persistent;
        private int leases;

        private CacheEntry(String sha256, Path path, long sizeBytes, boolean persistent) {
            this.sha256 = sha256;
            this.path = path.toAbsolutePath().normalize();
            this.sizeBytes = sizeBytes;
            this.persistent = persistent;
        }

        private CacheEntryLease lease(Runnable release) {
            return new CacheEntryLease(
                    path,
                    sha256,
                    sizeBytes,
                    persistent,
                    release);
        }
    }

    private static final class CacheReadException extends RuntimeException {
        private CacheReadException(String message) {
            super(message);
        }

        private CacheReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
