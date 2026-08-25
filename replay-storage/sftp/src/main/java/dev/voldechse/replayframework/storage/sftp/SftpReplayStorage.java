package dev.voldechse.replayframework.storage.sftp;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ByteRange;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.RenameFlags;
import net.schmizz.sshj.sftp.SFTPClient;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SSHJ-backed implementation of the internal asynchronous replay storage port. */
public final class SftpReplayStorage implements ReplayStorage {

    private static final Logger LOGGER = Logger.getLogger(SftpReplayStorage.class.getName());
    private static final ArtifactKey MANIFEST_KEY = ArtifactKey.of("manifest.json");
    private static final String STAGING_DIRECTORY = ".staging";
    private static final String PUBLISHED_DIRECTORY = "replays";
    private static final String STAGE_LOCK_SUFFIX = ".stage-lock";
    private static final String FETCH_PART_PREFIX = ".replay-storage-fetch-";
    private static final String PART_SUFFIX = ".part";
    private static final int IO_BUFFER_SIZE = 32 * 1024;

    private final SftpStorageConfiguration configuration;
    private final SftpConnectionPool connectionPool;
    private final Path forbiddenTargetRoot;
    private final ConcurrentHashMap<ReplayId, ReplayHandle> activeHandles =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ReplayId, ReentrantReadWriteLock> lifecycleLocks =
            new ConcurrentHashMap<>();

    /** Creates a backend with a pool owned by this storage instance. */
    public SftpReplayStorage(
            SftpStorageConfiguration configuration,
            Executor ioExecutor) {
        this(configuration, ioExecutor, null);
    }

    /** Creates a backend with an optional local target root used by contract tests. */
    public SftpReplayStorage(
            SftpStorageConfiguration configuration,
            Executor ioExecutor,
            Path forbiddenTargetRoot) {
        this(
                configuration,
                new SftpConnectionPool(configuration, ioExecutor),
                ioExecutor,
                forbiddenTargetRoot);
    }

    SftpReplayStorage(
            SftpStorageConfiguration configuration,
            SftpConnectionPool connectionPool,
            Executor ioExecutor) {
        this(configuration, connectionPool, ioExecutor, null);
    }

    private SftpReplayStorage(
            SftpStorageConfiguration configuration,
            SftpConnectionPool connectionPool,
            Executor ioExecutor,
            Path forbiddenTargetRoot) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool");
        Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.forbiddenTargetRoot = forbiddenTargetRoot == null
                ? null
                : forbiddenTargetRoot.toAbsolutePath().normalize();
    }

    @Override
    public CompletionStage<StagingReplay> stage(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return connectionPool.execute(lease -> {
            ReentrantReadWriteLock lock = lockFor(replayId);
            lock.writeLock().lock();
            try {
                if (activeHandles.containsKey(replayId)) {
                    throw new FileAlreadyExistsException("staging already active: " + replayId);
                }
                SFTPClient sftp = lease.sftp();
                ensureStorageNamespaces(sftp);
                String stagingPath = stagingPath(replayId);
                String publishedPath = publishedPath(replayId);
                if (exists(sftp, publishedPath) || exists(sftp, stagingPath)
                        || exists(sftp, stageLockPath(replayId))) {
                    throw new FileAlreadyExistsException("staging collision: " + replayId);
                }

                createExclusiveFile(sftp, stageLockPath(replayId));
                try {
                    sftp.mkdir(stagingPath);
                    ReplayHandle handle = new ReplayHandle(this, replayId, stagingPath);
                    activeHandles.put(replayId, handle);
                    return (StagingReplay) handle;
                } catch (IOException | RuntimeException failure) {
                    deleteIfExists(sftp, stageLockPath(replayId));
                    throw failure;
                }
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    @Override
    public CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source) {
        Objects.requireNonNull(staging, "staging");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(source, "source");
        ReplayHandle handle = requireHandle(staging);
        ReentrantReadWriteLock lock = lockFor(handle.replayId);
        return connectionPool.execute(lease -> {
            lock.readLock().lock();
            try {
                ensureOpen(handle);
                if (handle.manifestStaged) {
                    throw new IllegalStateException("manifest has already been staged");
                }
                putSynchronously(lease.sftp(), handle, key, source);
                if (MANIFEST_KEY.equals(key)) {
                    handle.manifestStaged = true;
                }
                return null;
            } finally {
                lock.readLock().unlock();
            }
        });
    }

    @Override
    public CompletionStage<Void> publish(StagingReplay staging) {
        Objects.requireNonNull(staging, "staging");
        ReplayHandle handle = requireHandle(staging);
        ReentrantReadWriteLock lock = lockFor(handle.replayId);
        return connectionPool.execute(lease -> {
            lock.writeLock().lock();
            try {
                ensureOpen(handle);
                handle.state = StorageState.PUBLISHING;
                try {
                    publishSynchronously(lease.sftp(), handle);
                    handle.state = StorageState.PUBLISHED;
                    activeHandles.remove(handle.replayId, handle);
                    try {
                        deleteIfExists(lease.sftp(), stageLockPath(handle.replayId));
                    } catch (IOException cleanupFailure) {
                        LOGGER.log(
                                Level.WARNING,
                                "SFTP staging lock cleanup failed for replay "
                                        + handle.replayId
                                        + " (cause type: "
                                        + cleanupFailure.getClass().getName()
                                        + ")");
                    }
                    return null;
                } catch (IOException | RuntimeException failure) {
                    if (handle.state == StorageState.PUBLISHING) {
                        handle.state = StorageState.OPEN;
                    }
                    throw failure;
                }
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
        return connectionPool.execute(lease ->
                fetchSynchronously(lease.sftp(), replayId, key, range, target));
    }

    @Override
    public CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key) {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(key, "key");
        return connectionPool.execute(lease -> existsSynchronously(lease.sftp(), replayId, key));
    }

    @Override
    public CompletionStage<Void> delete(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        ReentrantReadWriteLock lock = lockFor(replayId);
        return connectionPool.execute(lease -> {
            lock.writeLock().lock();
            try {
                ReplayHandle handle = activeHandles.remove(replayId);
                if (handle != null) {
                    handle.state = StorageState.DELETED;
                }
                deleteTree(lease.sftp(), stageLockPath(replayId));
                deleteTree(lease.sftp(), stagingPath(replayId));
                deleteTree(lease.sftp(), publishedPath(replayId));
                return null;
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    /** Stops accepting new operations and closes all verified SFTP resources. */
    public CompletionStage<Void> close() {
        return connectionPool.close();
    }

    private void putSynchronously(
            SFTPClient sftp,
            ReplayHandle handle,
            ArtifactKey key,
            Path source) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedSource)
                || !Files.isRegularFile(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("source is not a regular file: " + normalizedSource);
        }

        String target = artifactPath(handle.stagingPath, key);
        String temporary = target + PART_SUFFIX;
        String parent = parentPath(target);
        ensureDirectoryTree(sftp, parent);
        ensureNoSymlinkComponents(sftp, handle.stagingPath);
        if (exists(sftp, target) || exists(sftp, temporary)) {
            throw new FileAlreadyExistsException(target);
        }

        long expectedSize = Files.size(normalizedSource);
        try {
            try (RemoteFile remote = sftp.open(
                    temporary,
                    EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
                    FileAttributes.EMPTY);
                 FileChannel input = FileChannel.open(normalizedSource, StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.allocate(IO_BUFFER_SIZE);
                long offset = 0L;
                while (input.read(buffer) >= 0) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        int length = buffer.remaining();
                        byte[] bytes = new byte[length];
                        buffer.get(bytes);
                        remote.write(offset, bytes, 0, bytes.length);
                        offset += bytes.length;
                    }
                    buffer.clear();
                }
            }

            FileAttributes uploaded = sftp.lstat(temporary);
            if (uploaded == null || uploaded.getType() != FileMode.Type.REGULAR
                    || uploaded.getSize() != expectedSize) {
                throw new IOException("uploaded byte count does not match source");
            }
            sftp.rename(temporary, target, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.NATIVE));
        } catch (IOException | RuntimeException failure) {
            deleteIfExists(sftp, temporary);
            throw failure;
        }
    }

    private void publishSynchronously(SFTPClient sftp, ReplayHandle handle) throws IOException {
        if (!handle.manifestStaged) {
            throw new NoSuchFileException("manifest.json is missing from staging");
        }
        requireRegularFile(sftp, artifactPath(handle.stagingPath, MANIFEST_KEY));
        if (containsPart(sftp, handle.stagingPath)) {
            throw new IOException("staging contains an unfinished upload");
        }

        String published = publishedPath(handle.replayId);
        if (exists(sftp, published)) {
            throw new FileAlreadyExistsException(published);
        }
        sftp.rename(
                handle.stagingPath,
                published,
                EnumSet.of(RenameFlags.ATOMIC, RenameFlags.NATIVE));
    }

    private Path fetchSynchronously(
            SFTPClient sftp,
            ReplayId replayId,
            ArtifactKey key,
            Optional<ByteRange> range,
            Path target) throws IOException {
        requireRegularFile(sftp, artifactPath(publishedPath(replayId), MANIFEST_KEY));
        String source = artifactPath(publishedPath(replayId), key);
        FileAttributes sourceAttributes = requireRegularFile(sftp, source);
        long sourceSize = sourceAttributes.getSize();
        long start = 0L;
        long length = sourceSize;
        if (range.isPresent()) {
            ByteRange byteRange = range.orElseThrow();
            if (byteRange.endExclusive() > sourceSize) {
                throw new EOFException("requested range exceeds artifact size");
            }
            start = byteRange.startInclusive();
            length = byteRange.length();
        }

        Path normalizedTarget = prepareTarget(target);
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("fetch target has no parent");
        }
        Path temporary = Files.createTempFile(parent, FETCH_PART_PREFIX, PART_SUFFIX);
        try {
            try (RemoteFile remote = sftp.open(source, Set.of(OpenMode.READ));
                 FileChannel output = FileChannel.open(
                         temporary,
                         StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {
                copyExactly(remote, output, start, length);
                output.force(true);
            }
            if (Files.size(temporary) != length) {
                throw new IOException("fetched byte count does not match requested range");
            }
            if (Files.isSymbolicLink(normalizedTarget)) {
                throw new IOException("fetch target must not be a symbolic link");
            }
            Files.move(
                    temporary,
                    normalizedTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return normalizedTarget;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private boolean existsSynchronously(SFTPClient sftp, ReplayId replayId, ArtifactKey key)
            throws IOException {
        if (!isRegularFile(sftp, artifactPath(publishedPath(replayId), MANIFEST_KEY))) {
            return false;
        }
        return isRegularFile(sftp, artifactPath(publishedPath(replayId), key));
    }

    private void ensureStorageNamespaces(SFTPClient sftp) throws IOException {
        ensureDirectoryTree(sftp, configuration.basePath());
        ensureDirectoryTree(sftp, join(configuration.basePath(), STAGING_DIRECTORY));
        ensureDirectoryTree(sftp, join(configuration.basePath(), PUBLISHED_DIRECTORY));
    }

    private static void ensureDirectoryTree(SFTPClient sftp, String path) throws IOException {
        String normalized = path.replace('\\', '/');
        String current = normalized.startsWith("/") ? "/" : "";
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = current.equals("/")
                    ? current + segment
                    : current.isEmpty() ? segment : current + "/" + segment;
            FileAttributes attributes = sftp.statExistence(current);
            if (attributes == null) {
                sftp.mkdir(current);
            } else if (attributes.getType() == FileMode.Type.SYMLINK
                    || attributes.getType() != FileMode.Type.DIRECTORY) {
                throw new IOException("remote path is not a directory");
            }
        }
    }

    private static void ensureNoSymlinkComponents(SFTPClient sftp, String path)
            throws IOException {
        String normalized = path.replace('\\', '/');
        String current = normalized.startsWith("/") ? "/" : "";
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = current.equals("/")
                    ? current + segment
                    : current.isEmpty() ? segment : current + "/" + segment;
            FileAttributes attributes = sftp.statExistence(current);
            if (attributes != null && attributes.getType() == FileMode.Type.SYMLINK) {
                throw new IOException("remote path contains a symbolic link");
            }
        }
    }

    private static FileAttributes requireRegularFile(SFTPClient sftp, String path)
            throws IOException {
        ensureNoSymlinkComponents(sftp, parentPath(path));
        FileAttributes attributes = sftp.statExistence(path);
        if (attributes == null) {
            throw new NoSuchFileException(path);
        }
        if (attributes.getType() == FileMode.Type.SYMLINK
                || attributes.getType() != FileMode.Type.REGULAR) {
            throw new IOException("remote artifact is not a regular file");
        }
        return attributes;
    }

    private static boolean isRegularFile(SFTPClient sftp, String path) throws IOException {
        ensureNoSymlinkComponents(sftp, parentPath(path));
        FileAttributes attributes = sftp.statExistence(path);
        return attributes != null && attributes.getType() == FileMode.Type.REGULAR;
    }

    private static boolean exists(SFTPClient sftp, String path) throws IOException {
        return sftp.statExistence(path) != null;
    }

    private static void createExclusiveFile(SFTPClient sftp, String path) throws IOException {
        ensureDirectoryTree(sftp, parentPath(path));
        if (exists(sftp, path)) {
            throw new FileAlreadyExistsException(path);
        }
        try (RemoteFile ignored = sftp.open(
                path,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
                FileAttributes.EMPTY)) {
            // The exclusive open is the cross-process lock.
        }
    }

    private static boolean containsPart(SFTPClient sftp, String directory) throws IOException {
        for (RemoteResourceInfo entry : sftp.ls(directory)) {
            String name = entry.getName();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            if (entry.getAttributes().getType() == FileMode.Type.SYMLINK) {
                throw new IOException("staging contains a symbolic link");
            }
            String path = join(directory, name);
            if (entry.isDirectory()) {
                if (containsPart(sftp, path)) {
                    return true;
                }
            } else if (name.endsWith(PART_SUFFIX)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteTree(SFTPClient sftp, String path) throws IOException {
        FileAttributes attributes = sftp.statExistence(path);
        if (attributes == null) {
            return;
        }
        if (attributes.getType() == FileMode.Type.SYMLINK
                || attributes.getType() == FileMode.Type.REGULAR) {
            sftp.rm(path);
            return;
        }
        if (attributes.getType() != FileMode.Type.DIRECTORY) {
            throw new IOException("remote cleanup target is not a file or directory");
        }
        for (RemoteResourceInfo entry : sftp.ls(path)) {
            String name = entry.getName();
            if (!name.equals(".") && !name.equals("..")) {
                deleteTree(sftp, join(path, name));
            }
        }
        sftp.rmdir(path);
    }

    private static void deleteIfExists(SFTPClient sftp, String path) throws IOException {
        FileAttributes attributes = sftp.statExistence(path);
        if (attributes == null) {
            return;
        }
        if (attributes.getType() == FileMode.Type.SYMLINK
                || attributes.getType() == FileMode.Type.REGULAR) {
            sftp.rm(path);
        }
    }

    private static void copyExactly(
            RemoteFile remote,
            FileChannel output,
            long start,
            long length) throws IOException {
        byte[] bytes = new byte[IO_BUFFER_SIZE];
        long offset = start;
        long remaining = length;
        while (remaining > 0) {
            int requested = (int) Math.min(bytes.length, remaining);
            int read = remote.read(offset, bytes, 0, requested);
            if (read <= 0) {
                throw new EOFException("remote file ended before requested range");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
            while (buffer.hasRemaining()) {
                output.write(buffer);
            }
            offset += read;
            remaining -= read;
        }
    }

    private Path prepareTarget(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (forbiddenTargetRoot != null && normalized.startsWith(forbiddenTargetRoot)) {
            throw new IOException("fetch target must be outside backend storage root");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("fetch target must not be a symbolic link");
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("fetch target has no parent");
        }
        Files.createDirectories(parent);
        if (forbiddenTargetRoot != null
                && parent.toRealPath().startsWith(forbiddenTargetRoot.toRealPath())) {
            throw new IOException("fetch target must be outside backend storage root");
        }
        return normalized;
    }

    private ReplayHandle requireHandle(StagingReplay staging) {
        if (!(staging instanceof ReplayHandle handle) || handle.owner != this) {
            throw new IllegalArgumentException(
                    "staging handle belongs to another storage backend");
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

    private String stagingPath(ReplayId replayId) {
        return join(configuration.basePath(), STAGING_DIRECTORY, replayId.toString());
    }

    private String publishedPath(ReplayId replayId) {
        return join(configuration.basePath(), PUBLISHED_DIRECTORY, replayId.toString());
    }

    private String stageLockPath(ReplayId replayId) {
        return join(configuration.basePath(), STAGING_DIRECTORY, replayId + STAGE_LOCK_SUFFIX);
    }

    private static String artifactPath(String base, ArtifactKey key) {
        return join(base, key.value());
    }

    private static String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? separator == 0 ? "/" : "." : path.substring(0, separator);
    }

    private static String join(String first, String... rest) {
        String result = first.replace('\\', '/');
        for (String part : rest) {
            if (result.endsWith("/")) {
                result += part;
            } else {
                result += "/" + part;
            }
        }
        return result;
    }

    private enum StorageState {
        /** Open staging accepts artifact puts; the replay is not published. */
        OPEN,
        /** Publish is validating and moving the complete staging directory. */
        PUBLISHING,
        /** The directory move succeeded; this handle cannot mutate the replay. */
        PUBLISHED,
        /** Delete invalidated this handle and owns cleanup of replay paths. */
        DELETED
    }

    private static final class ReplayHandle implements StagingReplay {
        private final SftpReplayStorage owner;
        private final ReplayId replayId;
        private final String stagingPath;
        private final UUID token = UUID.randomUUID();
        private volatile StorageState state = StorageState.OPEN;
        private volatile boolean manifestStaged;

        private ReplayHandle(SftpReplayStorage owner, ReplayId replayId, String stagingPath) {
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
