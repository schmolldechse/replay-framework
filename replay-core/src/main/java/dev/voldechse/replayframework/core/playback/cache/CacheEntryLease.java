package dev.voldechse.replayframework.core.playback.cache;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotent handle protecting one cache file from eviction while it is used. */
public final class CacheEntryLease implements AutoCloseable {

    private final Path path;
    private final String sha256;
    private final long sizeBytes;
    private final boolean cached;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    CacheEntryLease(
            Path path,
            String sha256,
            long sizeBytes,
            boolean cached,
            Runnable release) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        if (sizeBytes < 0L) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        this.sizeBytes = sizeBytes;
        this.cached = cached;
        this.release = Objects.requireNonNull(release, "release");
    }

    /** Returns the complete local segment path. */
    public Path path() {
        return path;
    }

    /** Returns the lowercase SHA-256 key of the segment. */
    public String sha256() {
        return sha256;
    }

    /** Returns the exact stored byte size declared by the manifest. */
    public long sizeBytes() {
        return sizeBytes;
    }

    /** Returns whether the path is retained in the shared persistent cache. */
    public boolean cached() {
        return cached;
    }

    /** Releases the lease once; repeated calls are no-ops. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
