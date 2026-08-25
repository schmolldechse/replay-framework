package dev.voldechse.replayframework.storage.sftp;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable, internal configuration for one verified SFTP backend. */
public final class SftpStorageConfiguration {

    private final String host;
    private final int port;
    private final String username;
    private final String basePath;
    private final Path knownHostsFile;
    private final AuthenticationMethod authenticationMethod;
    private final Optional<Path> privateKeyFile;
    private final Optional<SecretSource> secretSource;
    private final Optional<SecretSource> privateKeyPassphrase;
    private final int poolSize;
    private final Duration acquireTimeout;
    private final Duration connectTimeout;
    private final Duration operationTimeout;
    private final Duration closeTimeout;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    private SftpStorageConfiguration(Builder builder) {
        host = requireNonBlank(builder.host, "host");
        if (builder.port < 1 || builder.port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        port = builder.port;
        username = requireNonBlank(builder.username, "username");
        basePath = normalizeBasePath(builder.basePath);
        knownHostsFile = requireRegularFile(builder.knownHostsFile, "knownHostsFile");
        authenticationMethod = Objects.requireNonNull(
                builder.authenticationMethod, "authenticationMethod");
        privateKeyFile = Optional.ofNullable(builder.privateKeyFile)
                .map(path -> requireRegularFile(path, "privateKeyFile"));
        secretSource = Optional.ofNullable(builder.secretSource);
        privateKeyPassphrase = Optional.ofNullable(builder.privateKeyPassphrase);
        poolSize = positive(builder.poolSize, "poolSize");
        acquireTimeout = positive(builder.acquireTimeout, "acquireTimeout");
        connectTimeout = positive(builder.connectTimeout, "connectTimeout");
        operationTimeout = positive(builder.operationTimeout, "operationTimeout");
        closeTimeout = positive(builder.closeTimeout, "closeTimeout");
        maxAttempts = positive(builder.maxAttempts, "maxAttempts");
        baseBackoff = positive(builder.baseBackoff, "baseBackoff");
        maxBackoff = positive(builder.maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be less than baseBackoff");
        }
        validateAuthentication();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String username() {
        return username;
    }

    public String basePath() {
        return basePath;
    }

    public Path knownHostsFile() {
        return knownHostsFile;
    }

    public AuthenticationMethod authenticationMethod() {
        return authenticationMethod;
    }

    public Optional<Path> privateKeyFile() {
        return privateKeyFile;
    }

    Optional<SecretSource> secretSource() {
        return secretSource;
    }

    Optional<SecretSource> privateKeyPassphrase() {
        return privateKeyPassphrase;
    }

    public int poolSize() {
        return poolSize;
    }

    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration operationTimeout() {
        return operationTimeout;
    }

    public Duration closeTimeout() {
        return closeTimeout;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration baseBackoff() {
        return baseBackoff;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    @Override
    public String toString() {
        return "SftpStorageConfiguration{"
                + "host=<configured>"
                + ", port=" + port
                + ", username=<configured>"
                + ", basePath=<configured>"
                + ", knownHostsFile=<configured>"
                + ", authenticationMethod=" + authenticationMethod
                + ", poolSize=" + poolSize
                + ", maxAttempts=" + maxAttempts
                + '}';
    }

    private void validateAuthentication() {
        switch (authenticationMethod) {
            case PASSWORD -> {
                if (secretSource.isEmpty() || privateKeyFile.isPresent()) {
                    throw new IllegalArgumentException(
                            "PASSWORD requires a secret and no private key");
                }
                if (privateKeyPassphrase.isPresent()) {
                    throw new IllegalArgumentException(
                            "private key passphrase is only valid for PRIVATE_KEY");
                }
            }
            case PRIVATE_KEY -> {
                if (privateKeyFile.isEmpty()) {
                    throw new IllegalArgumentException("PRIVATE_KEY requires privateKeyFile");
                }
                if (secretSource.isPresent()) {
                    throw new IllegalArgumentException(
                            "PRIVATE_KEY uses privateKeyPassphrase for key decryption");
                }
            }
            case SSH_AGENT -> {
                if (privateKeyFile.isPresent()
                        || secretSource.isPresent()
                        || privateKeyPassphrase.isPresent()) {
                    throw new IllegalArgumentException(
                            "SSH_AGENT does not accept password or private key values");
                }
            }
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalizeBasePath(String value) {
        Objects.requireNonNull(value, "basePath");
        String normalized = value.replace('\\', '/');
        if (normalized.isBlank() || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("basePath must not be blank or contain NUL");
        }
        boolean absolute = normalized.startsWith("/");
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.equals("/")) {
            throw new IllegalArgumentException("basePath must not be the remote root");
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                if (!(absolute && segment.isEmpty() && segments[0].isEmpty())) {
                    throw new IllegalArgumentException("basePath contains an invalid segment");
                }
            }
        }
        return normalized;
    }

    private static Path requireRegularFile(Path value, String name) {
        Objects.requireNonNull(value, name);
        Path normalized = value.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(normalized)) {
            throw new IllegalArgumentException(name + " must be a readable regular file");
        }
        return normalized;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public enum AuthenticationMethod {
        PASSWORD,
        PRIVATE_KEY,
        SSH_AGENT
    }

    @FunctionalInterface
    public interface SecretSource {
        char[] read();
    }

    public static final class Builder {
        private String host;
        private int port = 22;
        private String username;
        private String basePath;
        private Path knownHostsFile;
        private AuthenticationMethod authenticationMethod;
        private Path privateKeyFile;
        private SecretSource secretSource;
        private SecretSource privateKeyPassphrase;
        private int poolSize = 4;
        private Duration acquireTimeout = Duration.ofSeconds(5);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration operationTimeout = Duration.ofSeconds(30);
        private Duration closeTimeout = Duration.ofSeconds(10);
        private int maxAttempts = 3;
        private Duration baseBackoff = Duration.ofMillis(100);
        private Duration maxBackoff = Duration.ofSeconds(2);

        private Builder() {
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public Builder knownHostsFile(Path knownHostsFile) {
            this.knownHostsFile = knownHostsFile;
            return this;
        }

        public Builder password(SecretSource secretSource) {
            authenticationMethod = AuthenticationMethod.PASSWORD;
            this.secretSource = secretSource;
            privateKeyFile = null;
            privateKeyPassphrase = null;
            return this;
        }

        public Builder privateKey(Path privateKeyFile) {
            return privateKey(privateKeyFile, null);
        }

        public Builder privateKey(Path privateKeyFile, SecretSource passphrase) {
            authenticationMethod = AuthenticationMethod.PRIVATE_KEY;
            this.privateKeyFile = privateKeyFile;
            secretSource = null;
            privateKeyPassphrase = passphrase;
            return this;
        }

        public Builder sshAgent() {
            authenticationMethod = AuthenticationMethod.SSH_AGENT;
            privateKeyFile = null;
            secretSource = null;
            privateKeyPassphrase = null;
            return this;
        }

        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        public Builder acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder operationTimeout(Duration operationTimeout) {
            this.operationTimeout = operationTimeout;
            return this;
        }

        public Builder closeTimeout(Duration closeTimeout) {
            this.closeTimeout = closeTimeout;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder baseBackoff(Duration baseBackoff) {
            this.baseBackoff = baseBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public SftpStorageConfiguration build() {
            return new SftpStorageConfiguration(this);
        }
    }
}
