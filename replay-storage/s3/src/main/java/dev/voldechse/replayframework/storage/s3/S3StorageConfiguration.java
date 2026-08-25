package dev.voldechse.replayframework.storage.s3;

import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable configuration for one S3-compatible replay storage backend. */
public final class S3StorageConfiguration {

    private final String bucket;
    private final String prefix;
    private final Region region;
    private final Optional<URI> endpointOverride;
    private final boolean pathStyleAccessEnabled;
    private final CredentialProviderSelection credentialProviderSelection;
    private final Optional<String> profileName;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    private S3StorageConfiguration(Builder builder) {
        this.bucket = requireBucket(builder.bucket);
        this.prefix = normalizePrefix(builder.prefix);
        this.region = Objects.requireNonNull(builder.region, "region");
        this.endpointOverride = validateEndpoint(builder.endpointOverride);
        this.pathStyleAccessEnabled = builder.pathStyleAccessEnabled;
        this.credentialProviderSelection = Objects.requireNonNull(
                builder.credentialProviderSelection, "credentialProviderSelection");
        this.profileName = Optional.ofNullable(builder.profileName);
        if (maxAttemptsInvalid(builder.maxAttempts)) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = builder.maxAttempts;
        this.baseBackoff = requirePositiveDuration(builder.baseBackoff, "baseBackoff");
        this.maxBackoff = requirePositiveDuration(builder.maxBackoff, "maxBackoff");
        if (this.maxBackoff.compareTo(this.baseBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be less than baseBackoff");
        }
        validateCredentialSelection();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String bucket() {
        return bucket;
    }

    public String prefix() {
        return prefix;
    }

    public Region region() {
        return region;
    }

    public Optional<URI> endpointOverride() {
        return endpointOverride;
    }

    public boolean pathStyleAccessEnabled() {
        return pathStyleAccessEnabled;
    }

    public CredentialProviderSelection credentialProviderSelection() {
        return credentialProviderSelection;
    }

    public Optional<String> profileName() {
        return profileName;
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
        return "S3StorageConfiguration{"
                + "bucket=<configured>"
                + ", prefix=<configured>"
                + ", region=" + region.id()
                + ", endpointConfigured=" + endpointOverride.isPresent()
                + ", pathStyleAccessEnabled=" + pathStyleAccessEnabled
                + ", credentialProviderSelection=" + credentialProviderSelection
                + ", maxAttempts=" + maxAttempts
                + '}';
    }

    private void validateCredentialSelection() {
        if (credentialProviderSelection == CredentialProviderSelection.PROFILE) {
            if (profileName.isEmpty() || profileName.orElseThrow().isBlank()) {
                throw new IllegalArgumentException(
                        "profileName is required for PROFILE credentials");
            }
        } else if (profileName.isPresent()) {
            throw new IllegalArgumentException(
                    "profileName is only valid for PROFILE credentials");
        }
    }

    private static String requireBucket(String value) {
        Objects.requireNonNull(value, "bucket");
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("bucket must be non-blank and contain no whitespace");
        }
        return value;
    }

    private static String normalizePrefix(String value) {
        Objects.requireNonNull(value, "prefix");
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("prefix must not contain NUL");
        }
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.startsWith("/") || normalized.contains("://")) {
            throw new IllegalArgumentException("prefix must be a relative object prefix");
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("prefix contains an invalid path segment");
            }
        }
        return String.join("/", segments);
    }

    private static Optional<URI> validateEndpoint(URI endpoint) {
        if (endpoint == null) {
            return Optional.empty();
        }
        String scheme = endpoint.getScheme();
        if (endpoint.isOpaque()
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || endpoint.getHost() == null
                || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("endpointOverride must be a plain HTTP(S) URI");
        }
        return Optional.of(endpoint);
    }

    private static Duration requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static boolean maxAttemptsInvalid(int value) {
        return value <= 0;
    }

    public enum CredentialProviderSelection {
        DEFAULT_CHAIN,
        PROFILE
    }

    public static final class Builder {
        private String bucket;
        private String prefix = "";
        private Region region;
        private URI endpointOverride;
        private boolean pathStyleAccessEnabled;
        private CredentialProviderSelection credentialProviderSelection =
                CredentialProviderSelection.DEFAULT_CHAIN;
        private String profileName;
        private int maxAttempts = 3;
        private Duration baseBackoff = Duration.ofMillis(100);
        private Duration maxBackoff = Duration.ofSeconds(2);

        private Builder() {
        }

        public Builder bucket(String bucket) {
            this.bucket = bucket;
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        public Builder endpointOverride(URI endpointOverride) {
            this.endpointOverride = endpointOverride;
            return this;
        }

        public Builder pathStyleAccessEnabled(boolean pathStyleAccessEnabled) {
            this.pathStyleAccessEnabled = pathStyleAccessEnabled;
            return this;
        }

        public Builder credentialProviderSelection(
                CredentialProviderSelection credentialProviderSelection) {
            this.credentialProviderSelection = credentialProviderSelection;
            return this;
        }

        public Builder profileName(String profileName) {
            this.profileName = profileName;
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

        public S3StorageConfiguration build() {
            return new S3StorageConfiguration(this);
        }
    }
}
