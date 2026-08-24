package dev.voldechse.replayframework.storage.local;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ByteRange;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Local filesystem implementation of the replay storage contract.
 *
 * <p>All filesystem work runs on the supplied executor. Published replay directories are made
 * visible only by an atomic directory move from the staging namespace.</p>
 */
public final class LocalReplayStorage implements ReplayStorage {

    private static final ArtifactKey MANIFEST_KEY = ArtifactKey.of("manifest.json");
    private static final String UPLOAD_PART_PREFIX = ".replay-storage-upload-";
    private static final String FETCH_PART_PREFIX = ".replay-storage-fetch-";

    private final Path root;
    private final Path stagingRoot;
    private final Path publishedRoot;
    private final Executor ioExecutor;
    private final ConcurrentHashMap<ReplayId, ReplayHandle> activeHandles =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReplayId, ReentrantReadWriteLock> lifecycleLocks =
            new ConcurrentHashMap<>();

    /**
     * Creates a local backend.
     *
     * @param root trusted local storage root
     * @param ioExecutor executor for all filesystem operations
     */
    public LocalReplayStorage(Path root, Executor ioExecutor) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.stagingRoot = this.root.resolve(".staging");
        this.publishedRoot = this.root.resolve("replays");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    @Override
    public CompletionStage<StagingReplay> stage(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return submit(() -> stageSynchronously(replayId));
    }

    @Override
    public CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source) {
        Objects.requireNonNull(staging, "staging");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(source, "source");
        return submit(() -> {
            ReplayHandle handle = requireHandle(staging);
            ReentrantReadWriteLock lock = lockFor(handle.replayId);
            lock.readLock().lock();
            try {
                ensureOpen(handle);
                putSynchronously(handle, key, source);
                return null;
            } finally {
                lock.readLock().unlock();
            }
        });
    }

    @Override
    public CompletionStage<Void> publish(StagingReplay staging) {
        Objects.requireNonNull(staging, "staging");
        return submit(() -> {
            ReplayHandle handle = requireHandle(staging);
            ReentrantReadWriteLock lock = lockFor(handle.replayId);
            lock.writeLock().lock();
            try {
                publishSynchronously(handle);
                return null;
            } finally {
                lock.writeLock().unlock();
            }
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
        return submit(() -> fetchSynchronously(replayId, key, range, target));
    }

    @Override
    public CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key) {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(key, "key");
        return submit(() -> existsSynchronously(replayId, key));
    }

    @Override
    public CompletionStage<Void> delete(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return submit(() -> {
            ReentrantReadWriteLock lock = lockFor(replayId);
            lock.writeLock().lock();
            try {
                deleteSynchronously(replayId);
                return null;
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    private StagingReplay stageSynchronously(ReplayId replayId) throws IOException {
        ReentrantReadWriteLock lock = lockFor(replayId);
        lock.writeLock().lock();
        try {
            if (activeHandles.containsKey(replayId)) {
                throw new FileAlreadyExistsException("staging already active: " + replayId);
            }

            ensureDirectory(root);
            ensureDirectory(stagingRoot);
            ensureDirectory(publishedRoot);
            ensureStorageNamespaces();

            Path stagePath = stagingPath(replayId);
            Path publishedPath = publishedPath(replayId);
            if (Files.exists(publishedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException("replay already published: " + replayId);
            }
            if (Files.exists(stagePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException("stale staging exists: " + replayId);
            }

            Files.createDirectory(stagePath);
            ReplayHandle handle = new ReplayHandle(this, replayId, stagePath);
            activeHandles.put(replayId, handle);
            return handle;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void putSynchronously(ReplayHandle handle, ArtifactKey key, Path source)
            throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedSource)
                || !Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("source is not a regular file: " + normalizedSource);
        }

        ensureNoSymlinkComponents(root, handle.stagingPath);
        Path target = resolveArtifact(handle.stagingPath, key);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("artifact target has no parent: " + target);
        }
        ensureNoSymlinkComponents(handle.stagingPath, parent);
        Files.createDirectories(parent);
        ensureNoSymlinkComponents(handle.stagingPath, parent);

        Object keyLock = handle.keyLocks.computeIfAbsent(key.value(), ignored -> new Object());
        synchronized (keyLock) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }

            long expectedSize = Files.size(normalizedSource);
            Path temporary = Files.createTempFile(parent, UPLOAD_PART_PREFIX, ".part");
            try {
                Files.copy(normalizedSource, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (Files.size(temporary) != expectedSize) {
                    throw new IOException("source changed while uploading: " + normalizedSource);
                }
                forceFile(temporary);
                moveAtomically(temporary, target, false);
                temporary = null;
            } catch (IOException | RuntimeException exception) {
                cleanupAfterFailure(exception, temporary);
                throw exception;
            } finally {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    private void publishSynchronously(ReplayHandle handle) throws IOException {
        ensureOpen(handle);
        handle.state = StorageState.PUBLISHING;
        try {
            ensureNoSymlinkComponents(root, handle.stagingPath);
            ensureNoSymlinkComponents(root, publishedRoot);
            Path manifest = resolveArtifact(handle.stagingPath, MANIFEST_KEY);
            if (Files.isSymbolicLink(manifest)
                    || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException("manifest.json is missing from staging");
            }
            if (containsUploadPart(handle.stagingPath)) {
                throw new IOException("staging contains an unfinished storage upload");
            }

            ensureDirectory(publishedRoot);
            Path target = publishedPath(handle.replayId);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }

            moveAtomically(handle.stagingPath, target, false);
            handle.state = StorageState.PUBLISHED;
            activeHandles.remove(handle.replayId, handle);
        } catch (IOException | RuntimeException exception) {
            if (handle.state == StorageState.PUBLISHING) {
                handle.state = StorageState.OPEN;
            }
            throw exception;
        }
    }

    private Path fetchSynchronously(
            ReplayId replayId,
            ArtifactKey key,
            Optional<ByteRange> range,
            Path target) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.startsWith(root)) {
            throw new IOException("fetch target must be outside local storage root");
        }
        if (Files.isSymbolicLink(normalizedTarget)) {
            throw new IOException("fetch target must not be a symbolic link: " + normalizedTarget);
        }

        Path source = resolvePublishedArtifact(replayId, key);
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("fetch target has no parent: " + normalizedTarget);
        }
        Files.createDirectories(parent);
        ensureFetchTargetParent(parent);

        long sourceSize = Files.size(source);
        long start = 0L;
        long length = sourceSize;
        if (range.isPresent()) {
            ByteRange byteRange = range.get();
            if (byteRange.endExclusive() > sourceSize) {
                throw new EOFException("requested range exceeds artifact size");
            }
            start = byteRange.startInclusive();
            length = byteRange.length();
        }

        Path temporary = Files.createTempFile(parent, FETCH_PART_PREFIX, ".part");
        try {
            try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
                 FileChannel output = FileChannel.open(
                         temporary,
                         StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                copyExactly(input, output, start, length);
                output.force(true);
            }
            if (Files.size(temporary) != length) {
                throw new IOException("fetched byte count does not match requested range");
            }
            moveAtomically(temporary, normalizedTarget, true);
            temporary = null;
            return normalizedTarget;
        } catch (IOException | RuntimeException exception) {
            cleanupAfterFailure(exception, temporary);
            throw exception;
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private boolean existsSynchronously(ReplayId replayId, ArtifactKey key) throws IOException {
        try {
            ensureNoSymlinkComponents(root, publishedRoot);
        } catch (IOException exception) {
            return false;
        }
        Path replayPath = publishedPath(replayId);
        if (Files.isSymbolicLink(replayPath)
                || !Files.isDirectory(replayPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

        Path candidate = resolveArtifact(replayPath, key);
        Path current = replayPath;
        for (Path segment : replayPath.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return false;
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!current.equals(candidate)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS);
    }

    private void deleteSynchronously(ReplayId replayId) throws IOException {
        ReplayHandle handle = activeHandles.remove(replayId);
        if (handle != null) {
            handle.state = StorageState.DELETED;
        }

        IOException failure = null;
        try {
            deleteOwnedTree(stagingRoot, stagingPath(replayId));
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            deleteOwnedTree(publishedRoot, publishedPath(replayId));
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Path resolvePublishedArtifact(ReplayId replayId, ArtifactKey key) throws IOException {
        ensureNoSymlinkComponents(root, publishedRoot);
        Path replayPath = publishedPath(replayId);
        if (Files.isSymbolicLink(replayPath)
                || !Files.isDirectory(replayPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("published replay does not exist: " + replayId);
        }
        Path candidate = resolveArtifact(replayPath, key);
        ensureNoSymlinkComponents(replayPath, candidate);
        if (Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("published artifact is not a regular file: " + key.value());
        }
        return candidate;
    }

    private Path resolveArtifact(Path base, ArtifactKey key) throws IOException {
        Path normalizedBase = base.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedBase)) {
            throw new IOException("storage path must not be a symbolic link: " + normalizedBase);
        }
        Path candidate = normalizedBase.resolve(key.value()).normalize();
        if (!candidate.startsWith(normalizedBase)) {
            throw new IOException("artifact key escaped storage root: " + key.value());
        }
        return candidate;
    }

    private ReplayHandle requireHandle(StagingReplay staging) {
        if (!(staging instanceof ReplayHandle handle) || handle.owner != this) {
            throw new IllegalArgumentException("staging handle belongs to another storage backend");
        }
        return handle;
    }

    private static void ensureOpen(ReplayHandle handle) {
        if (handle.state != StorageState.OPEN) {
            throw new IllegalStateException("staging handle is " + handle.state);
        }
    }

    private ReentrantReadWriteLock lockFor(ReplayId replayId) {
        return lifecycleLocks.computeIfAbsent(replayId, ignored -> new ReentrantReadWriteLock());
    }

    private Path stagingPath(ReplayId replayId) {
        return stagingRoot.resolve(replayId.toString()).normalize();
    }

    private Path publishedPath(ReplayId replayId) {
        return publishedRoot.resolve(replayId.toString()).normalize();
    }

    private void ensureStorageNamespaces() throws IOException {
        ensureNoSymlinkComponents(root, stagingRoot);
        ensureNoSymlinkComponents(root, publishedRoot);
    }

    private static void ensureDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("storage path is not a directory: " + path);
            }
            return;
        }
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("could not create storage directory: " + path);
        }
    }

    private static void ensureNoSymlinkComponents(Path base, Path candidate) throws IOException {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedBase)) {
            throw new IOException("path escaped its storage base: " + normalizedCandidate);
        }
        if (Files.isSymbolicLink(normalizedBase)) {
            throw new IOException("storage base is a symbolic link: " + normalizedBase);
        }

        Path current = normalizedBase;
        for (Path segment : normalizedBase.relativize(normalizedCandidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("path contains a symbolic link: " + current);
            }
        }
    }

    private void ensureFetchTargetParent(Path parent) throws IOException {
        Path realRoot = root.toRealPath();
        Path realParent = parent.toRealPath();
        if (realParent.startsWith(realRoot)) {
            throw new IOException("fetch target must be outside local storage root");
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveAtomically(Path source, Path target, boolean replace)
            throws IOException {
        if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        if (replace && Files.isSymbolicLink(target)) {
            throw new IOException("target must not be a symbolic link: " + target);
        }
        if (replace) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static void copyExactly(
            FileChannel input,
            FileChannel output,
            long start,
            long length) throws IOException {
        input.position(start);
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long remaining = length;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = input.read(buffer);
            if (read < 0) {
                throw new EOFException("source ended before the requested range");
            }
            if (read == 0) {
                continue;
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                output.write(buffer);
            }
            remaining -= read;
        }
    }

    private static boolean containsUploadPart(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> {
                Path name = path.getFileName();
                if (name == null) {
                    return false;
                }
                String value = name.toString();
                return value.startsWith(UPLOAD_PART_PREFIX) && value.endsWith(".part");
            });
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteOwnedTree(Path namespaceRoot, Path replayPath) throws IOException {
        ensureNoSymlinkComponents(root, namespaceRoot);
        // A symlink at the UUID leaf is deleted as a link, never traversed; only its parent
        // namespace must be verified before the bounded visitor is allowed to run.
        ensureNoSymlinkComponents(namespaceRoot, replayPath.getParent());
        deleteTree(replayPath);
    }

    private static void cleanupAfterFailure(Exception primary, Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private <T> CompletionStage<T> submit(Callable<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.call();
            } catch (CompletionException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, ioExecutor);
    }

    private enum StorageState {
        /** Open staging accepts artifact puts; the replay is not published. */
        OPEN,
        /** Publish holds the per-replay write lock while moving the staging directory. */
        PUBLISHING,
        /** The directory move succeeded; this handle cannot mutate the replay. */
        PUBLISHED,
        /** Delete invalidated this handle and removed the replay-owned paths. */
        DELETED
    }

    private static final class ReplayHandle implements StagingReplay {
        private final LocalReplayStorage owner;
        private final ReplayId replayId;
        private final Path stagingPath;
        private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
        private final UUID token = UUID.randomUUID();
        private volatile StorageState state = StorageState.OPEN;

        private ReplayHandle(LocalReplayStorage owner, ReplayId replayId, Path stagingPath) {
            this.owner = owner;
            this.replayId = replayId;
            this.stagingPath = stagingPath;
        }

        @Override
        public ReplayId replayId() {
            return replayId;
        }

        @Override
        public String toString() {
            return "StagingReplay[replayId=" + replayId + ", token=" + token + "]";
        }
    }
}
