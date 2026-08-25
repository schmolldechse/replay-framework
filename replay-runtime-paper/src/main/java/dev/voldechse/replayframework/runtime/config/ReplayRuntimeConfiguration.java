package dev.voldechse.replayframework.runtime.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable, validated configuration consumed by the Paper runtime modules. */
public final class ReplayRuntimeConfiguration {
    private final PostgresSettings postgresql;
    private final StorageSettings storage;
    private final RecordingSettings recording;
    private final PlaybackSettings playback;

    public ReplayRuntimeConfiguration(
            PostgresSettings postgresql,
            StorageSettings storage,
            RecordingSettings recording,
            PlaybackSettings playback) {
        this.postgresql = Objects.requireNonNull(postgresql, "postgresql");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.recording = Objects.requireNonNull(recording, "recording");
        this.playback = Objects.requireNonNull(playback, "playback");
    }

    public PostgresSettings postgresql() {
        return postgresql;
    }

    public StorageSettings storage() {
        return storage;
    }

    public RecordingSettings recording() {
        return recording;
    }

    public PlaybackSettings playback() {
        return playback;
    }

    @Override
    public String toString() {
        return "ReplayRuntimeConfiguration["
                + "postgresql=" + postgresql
                + ", storage=" + storage
                + ", recording=" + recording
                + ", playback=" + playback
                + ']';
    }

    public enum StorageType {
        LOCAL,
        S3,
        SFTP
    }

    /** PostgreSQL values with an optional process-local secret resolution. */
    public static final class PostgresSettings {
        private final String jdbcUrl;
        private final String username;
        private final String passwordEnv;
        private final Optional<String> password;
        private final int minimumIdle;
        private final int maximumPoolSize;
        private final Duration connectionTimeout;
        private final Duration validationTimeout;
        private final Duration idleTimeout;
        private final Duration maxLifetime;
        private final int databaseParallelism;
        private final int databaseQueueCapacity;

        public PostgresSettings(
                String jdbcUrl,
                String username,
                String passwordEnv,
                Optional<String> password,
                int minimumIdle,
                int maximumPoolSize,
                Duration connectionTimeout,
                Duration validationTimeout,
                Duration idleTimeout,
                Duration maxLifetime,
                int databaseParallelism,
                int databaseQueueCapacity) {
            this.jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
            if (!this.jdbcUrl.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("jdbcUrl must use the PostgreSQL JDBC scheme");
            }
            this.username = requireNonBlank(username, "username");
            this.passwordEnv = requireOptionalName(passwordEnv, "passwordEnv");
            this.password = Objects.requireNonNull(password, "password").map(value ->
                    requireNonBlank(value, "password"));
            if (minimumIdle < 0) {
                throw new IllegalArgumentException("minimumIdle must not be negative");
            }
            if (maximumPoolSize <= 0 || minimumIdle > maximumPoolSize) {
                throw new IllegalArgumentException(
                        "maximumPoolSize must be positive and minimumIdle must not exceed it");
            }
            this.minimumIdle = minimumIdle;
            this.maximumPoolSize = maximumPoolSize;
            this.connectionTimeout = requirePositive(connectionTimeout, "connectionTimeout");
            this.validationTimeout = requirePositive(validationTimeout, "validationTimeout");
            this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
            this.maxLifetime = requirePositive(maxLifetime, "maxLifetime");
            if (databaseParallelism <= 0) {
                throw new IllegalArgumentException("databaseParallelism must be positive");
            }
            if (databaseQueueCapacity <= 0) {
                throw new IllegalArgumentException("databaseQueueCapacity must be positive");
            }
            this.databaseParallelism = databaseParallelism;
            this.databaseQueueCapacity = databaseQueueCapacity;
        }

        public String jdbcUrl() {
            return jdbcUrl;
        }

        public String username() {
            return username;
        }

        public String passwordEnv() {
            return passwordEnv;
        }

        public Optional<String> password() {
            return password;
        }

        public int minimumIdle() {
            return minimumIdle;
        }

        public int maximumPoolSize() {
            return maximumPoolSize;
        }

        public Duration connectionTimeout() {
            return connectionTimeout;
        }

        public Duration validationTimeout() {
            return validationTimeout;
        }

        public Duration idleTimeout() {
            return idleTimeout;
        }

        public Duration maxLifetime() {
            return maxLifetime;
        }

        public int databaseParallelism() {
            return databaseParallelism;
        }

        public int databaseQueueCapacity() {
            return databaseQueueCapacity;
        }

        @Override
        public String toString() {
            return "PostgresSettings["
                    + "jdbcUrl=" + jdbcUrl
                    + ", username=<configured>"
                    + ", password=<redacted>"
                    + ", pool=" + maximumPoolSize
                    + ", databaseParallelism=" + databaseParallelism
                    + ']';
        }
    }

    /** Storage selection and the backend values needed by the selected module. */
    public static final class StorageSettings {
        private final StorageType backend;
        private final Path localRoot;
        private final Optional<S3Settings> s3;
        private final Optional<SftpSettings> sftp;

        public StorageSettings(
                StorageType backend,
                Path localRoot,
                Optional<S3Settings> s3,
                Optional<SftpSettings> sftp) {
            this.backend = Objects.requireNonNull(backend, "backend");
            this.localRoot = Objects.requireNonNull(localRoot, "localRoot")
                    .toAbsolutePath().normalize();
            this.s3 = Objects.requireNonNull(s3, "s3");
            this.sftp = Objects.requireNonNull(sftp, "sftp");
        }

        public StorageType backend() {
            return backend;
        }

        public Path localRoot() {
            return localRoot;
        }

        public Optional<S3Settings> s3() {
            return s3;
        }

        public Optional<SftpSettings> sftp() {
            return sftp;
        }

        @Override
        public String toString() {
            return "StorageSettings[backend=" + backend + ", localRoot=<configured>]";
        }
    }

    /** Raw S3 values; the S3 module performs its established backend validation. */
    public static final class S3Settings {
        private final String bucket;
        private final String prefix;
        private final String region;
        private final Optional<URI> endpointOverride;
        private final boolean pathStyleAccessEnabled;
        private final String credentialProvider;
        private final Optional<String> profileName;
        private final int maxAttempts;
        private final Duration baseBackoff;
        private final Duration maxBackoff;

        public S3Settings(
                String bucket,
                String prefix,
                String region,
                Optional<URI> endpointOverride,
                boolean pathStyleAccessEnabled,
                String credentialProvider,
                Optional<String> profileName,
                int maxAttempts,
                Duration baseBackoff,
                Duration maxBackoff) {
            this.bucket = Objects.requireNonNull(bucket, "bucket");
            this.prefix = Objects.requireNonNull(prefix, "prefix");
            this.region = requireNonBlank(region, "region");
            this.endpointOverride = Objects.requireNonNull(endpointOverride, "endpointOverride");
            this.pathStyleAccessEnabled = pathStyleAccessEnabled;
            this.credentialProvider = requireNonBlank(credentialProvider, "credentialProvider");
            this.profileName = Objects.requireNonNull(profileName, "profileName");
            this.maxAttempts = maxAttempts;
            this.baseBackoff = Objects.requireNonNull(baseBackoff, "baseBackoff");
            this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
        }

        public String bucket() { return bucket; }
        public String prefix() { return prefix; }
        public String region() { return region; }
        public Optional<URI> endpointOverride() { return endpointOverride; }
        public boolean pathStyleAccessEnabled() { return pathStyleAccessEnabled; }
        public String credentialProvider() { return credentialProvider; }
        public Optional<String> profileName() { return profileName; }
        public int maxAttempts() { return maxAttempts; }
        public Duration baseBackoff() { return baseBackoff; }
        public Duration maxBackoff() { return maxBackoff; }

        @Override
        public String toString() {
            return "S3Settings[bucket=<configured>, region=" + region
                    + ", credentialProvider=" + credentialProvider + ']';
        }
    }

    /** Raw SFTP values; authentication secrets are kept as environment references. */
    public static final class SftpSettings {
        private final String host;
        private final int port;
        private final String username;
        private final String basePath;
        private final Optional<Path> knownHostsFile;
        private final String authentication;
        private final Optional<Path> privateKeyFile;
        private final Optional<String> passwordEnv;
        private final Optional<String> privateKeyPassphraseEnv;
        private final int poolSize;
        private final Duration acquireTimeout;
        private final Duration connectTimeout;
        private final Duration operationTimeout;
        private final Duration closeTimeout;
        private final int maxAttempts;
        private final Duration baseBackoff;
        private final Duration maxBackoff;

        public SftpSettings(
                String host,
                int port,
                String username,
                String basePath,
                Optional<Path> knownHostsFile,
                String authentication,
                Optional<Path> privateKeyFile,
                Optional<String> passwordEnv,
                Optional<String> privateKeyPassphraseEnv,
                int poolSize,
                Duration acquireTimeout,
                Duration connectTimeout,
                Duration operationTimeout,
                Duration closeTimeout,
                int maxAttempts,
                Duration baseBackoff,
                Duration maxBackoff) {
            this.host = Objects.requireNonNull(host, "host");
            this.port = port;
            this.username = Objects.requireNonNull(username, "username");
            this.basePath = Objects.requireNonNull(basePath, "basePath");
            this.knownHostsFile = Objects.requireNonNull(knownHostsFile, "knownHostsFile");
            this.authentication = requireNonBlank(authentication, "authentication");
            this.privateKeyFile = Objects.requireNonNull(privateKeyFile, "privateKeyFile");
            this.passwordEnv = Objects.requireNonNull(passwordEnv, "passwordEnv");
            this.privateKeyPassphraseEnv = Objects.requireNonNull(
                    privateKeyPassphraseEnv, "privateKeyPassphraseEnv");
            this.poolSize = poolSize;
            this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout");
            this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
            this.maxAttempts = maxAttempts;
            this.baseBackoff = Objects.requireNonNull(baseBackoff, "baseBackoff");
            this.maxBackoff = Objects.requireNonNull(maxBackoff, "maxBackoff");
        }

        public String host() { return host; }
        public int port() { return port; }
        public String username() { return username; }
        public String basePath() { return basePath; }
        public Optional<Path> knownHostsFile() { return knownHostsFile; }
        public String authentication() { return authentication; }
        public Optional<Path> privateKeyFile() { return privateKeyFile; }
        public Optional<String> passwordEnv() { return passwordEnv; }
        public Optional<String> privateKeyPassphraseEnv() { return privateKeyPassphraseEnv; }
        public int poolSize() { return poolSize; }
        public Duration acquireTimeout() { return acquireTimeout; }
        public Duration connectTimeout() { return connectTimeout; }
        public Duration operationTimeout() { return operationTimeout; }
        public Duration closeTimeout() { return closeTimeout; }
        public int maxAttempts() { return maxAttempts; }
        public Duration baseBackoff() { return baseBackoff; }
        public Duration maxBackoff() { return maxBackoff; }

        @Override
        public String toString() {
            return "SftpSettings[host=<configured>, username=<configured>, authentication="
                    + authentication + ']';
        }
    }

    public static final class RecordingSettings {
        private final Path workspaceRoot;
        private final long maxSegmentBytes;
        private final Duration maxSegmentDuration;
        private final Duration checkpointInterval;
        private final long maxQueueBytes;

        public RecordingSettings(
                Path workspaceRoot,
                long maxSegmentBytes,
                Duration maxSegmentDuration,
                Duration checkpointInterval,
                long maxQueueBytes) {
            this.workspaceRoot = absolute(workspaceRoot, "workspaceRoot");
            this.maxSegmentBytes = positive(maxSegmentBytes, "maxSegmentBytes");
            this.maxSegmentDuration = requirePositive(maxSegmentDuration, "maxSegmentDuration");
            this.checkpointInterval = requirePositive(checkpointInterval, "checkpointInterval");
            this.maxQueueBytes = positive(maxQueueBytes, "maxQueueBytes");
        }

        public Path workspaceRoot() { return workspaceRoot; }
        public long maxSegmentBytes() { return maxSegmentBytes; }
        public Duration maxSegmentDuration() { return maxSegmentDuration; }
        public Duration checkpointInterval() { return checkpointInterval; }
        public long maxQueueBytes() { return maxQueueBytes; }

        @Override
        public String toString() {
            return "RecordingSettings[workspaceRoot=<configured>, maxSegmentBytes="
                    + maxSegmentBytes + ']';
        }
    }

    public static final class PlaybackSettings {
        private final Path workDirectory;
        private final Path cacheRoot;
        private final long cacheMaxBytes;
        private final Duration preloadAhead;
        private final Duration retainBehind;
        private final Duration minimumResumeBuffer;
        private final long memoryBudgetBytes;
        private final long diskBudgetBytes;
        private final int maxParallelFetches;

        public PlaybackSettings(
                Path workDirectory,
                Path cacheRoot,
                long cacheMaxBytes,
                Duration preloadAhead,
                Duration retainBehind,
                Duration minimumResumeBuffer,
                long memoryBudgetBytes,
                long diskBudgetBytes,
                int maxParallelFetches) {
            this.workDirectory = absolute(workDirectory, "workDirectory");
            this.cacheRoot = absolute(cacheRoot, "cacheRoot");
            this.cacheMaxBytes = positive(cacheMaxBytes, "cacheMaxBytes");
            this.preloadAhead = requirePositive(preloadAhead, "preloadAhead");
            this.retainBehind = requirePositive(retainBehind, "retainBehind");
            this.minimumResumeBuffer = requirePositive(
                    minimumResumeBuffer, "minimumResumeBuffer");
            this.memoryBudgetBytes = positive(memoryBudgetBytes, "memoryBudgetBytes");
            this.diskBudgetBytes = positive(diskBudgetBytes, "diskBudgetBytes");
            if (maxParallelFetches <= 0) {
                throw new IllegalArgumentException("maxParallelFetches must be positive");
            }
            this.maxParallelFetches = maxParallelFetches;
        }

        public Path workDirectory() { return workDirectory; }
        public Path cacheRoot() { return cacheRoot; }
        public long cacheMaxBytes() { return cacheMaxBytes; }
        public Duration preloadAhead() { return preloadAhead; }
        public Duration retainBehind() { return retainBehind; }
        public Duration minimumResumeBuffer() { return minimumResumeBuffer; }
        public long memoryBudgetBytes() { return memoryBudgetBytes; }
        public long diskBudgetBytes() { return diskBudgetBytes; }
        public int maxParallelFetches() { return maxParallelFetches; }

        @Override
        public String toString() {
            return "PlaybackSettings[workDirectory=<configured>, cacheRoot=<configured>, cacheMaxBytes="
                    + cacheMaxBytes + ']';
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireOptionalName(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isEmpty() && !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(name + " must be a valid environment name");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Path absolute(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
