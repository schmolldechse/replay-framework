package dev.voldechse.replayframework.runtime.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static dev.voldechse.replayframework.runtime.config.ReplayRuntimeConfiguration.StorageType;

/** Loads and validates the one JSON configuration owned by the Paper runtime. */
public final class ReplayConfigurationLoader {
    private static final String CONFIG_FILE = "config.json";
    private static final String DEFAULT_PASSWORD_ENV = "REPLAY_DATABASE_PASSWORD";

    private final Gson gson;
    private final Function<String, Optional<String>> environment;

    /** Creates a loader resolving secrets from the process environment. */
    public ReplayConfigurationLoader(Gson gson) {
        this(gson, name -> Optional.ofNullable(System.getenv(name)));
    }

    /** Creates a loader with an injectable secret source for focused tests. */
    public ReplayConfigurationLoader(
            Gson gson,
            Function<String, Optional<String>> environment) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** Loads {@code config.json}, creating the safe Local default when absent. */
    public ReplayRuntimeConfiguration load(Path dataDirectory) {
        Path root = normalizeDataDirectory(dataDirectory);
        Path configFile = root.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(root);
            if (Files.notExists(configFile, LinkOption.NOFOLLOW_LINKS)) {
                writeDefault(configFile);
            }
            if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(configFile)) {
                throw new IllegalArgumentException("config.json must be a regular file");
            }
            JsonElement parsed = JsonParser.parseString(
                    Files.readString(configFile, StandardCharsets.UTF_8));
            JsonObject rootObject = object(parsed, "root");
            rejectUnknown(rootObject, Set.of("postgresql", "storage", "recording", "playback"), "root");
            return new ReplayRuntimeConfiguration(
                    parsePostgresql(requiredObject(rootObject, "postgresql", "root")),
                    parseStorage(requiredObject(rootObject, "storage", "root"), root),
                    parseRecording(requiredObject(rootObject, "recording", "root"), root),
                    parsePlayback(requiredObject(rootObject, "playback", "root"), root));
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw new IllegalArgumentException("could not load " + configFile, failure);
        }
    }

    private ReplayRuntimeConfiguration.PostgresSettings parsePostgresql(JsonObject object) {
        rejectUnknown(object, Set.of(
                "jdbcUrl", "username", "passwordEnv", "minimumIdle", "maximumPoolSize",
                "connectionTimeout", "validationTimeout", "idleTimeout", "maxLifetime",
                "databaseParallelism", "databaseQueueCapacity"), "postgresql");
        String passwordEnv = string(object, "passwordEnv", "postgresql");
        Optional<String> password = passwordEnv.isEmpty()
                ? Optional.empty()
                : environment.apply(passwordEnv).map(value -> {
                    if (value.isBlank()) {
                        throw new IllegalArgumentException(
                                "postgresql.passwordEnv resolved to a blank value");
                    }
                    return value;
                });
        return new ReplayRuntimeConfiguration.PostgresSettings(
                string(object, "jdbcUrl", "postgresql"),
                string(object, "username", "postgresql"),
                passwordEnv,
                password,
                integer(object, "minimumIdle", "postgresql"),
                integer(object, "maximumPoolSize", "postgresql"),
                duration(object, "connectionTimeout", "postgresql"),
                duration(object, "validationTimeout", "postgresql"),
                duration(object, "idleTimeout", "postgresql"),
                duration(object, "maxLifetime", "postgresql"),
                integer(object, "databaseParallelism", "postgresql"),
                integer(object, "databaseQueueCapacity", "postgresql"));
    }

    private ReplayRuntimeConfiguration.StorageSettings parseStorage(
            JsonObject object,
            Path root) {
        rejectUnknown(object, Set.of("backend", "local", "s3", "sftp"), "storage");
        String backendValue = string(object, "backend", "storage");
        StorageType backend;
        try {
            backend = StorageType.valueOf(backendValue);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "storage.backend must be one of LOCAL, S3, SFTP");
        }

        JsonObject local = backend == StorageType.LOCAL
                ? requiredObject(object, "local", "storage")
                : optionalObject(object, "local", "storage").orElseGet(JsonObject::new);
        Path localRoot = resolveRelativePath(
                stringOrDefault(local, "root", "replay-storage", "storage.local"),
                root,
                "storage.local.root");

        Optional<ReplayRuntimeConfiguration.S3Settings> s3Settings = backend == StorageType.S3
                ? Optional.of(parseS3(requiredObject(object, "s3", "storage"), "storage.s3"))
                : Optional.empty();
        Optional<ReplayRuntimeConfiguration.SftpSettings> sftpSettings = backend == StorageType.SFTP
                ? Optional.of(parseSftp(requiredObject(object, "sftp", "storage"), root, "storage.sftp"))
                : Optional.empty();

        if (backend == StorageType.LOCAL && Files.isSymbolicLink(localRoot)) {
            throw new IllegalArgumentException("storage.local.root must not be a symbolic link");
        }
        return new ReplayRuntimeConfiguration.StorageSettings(
                backend, localRoot, s3Settings, sftpSettings);
    }

    private ReplayRuntimeConfiguration.S3Settings parseS3(JsonObject object, String path) {
        rejectUnknown(object, Set.of(
                "bucket", "prefix", "region", "endpointOverride", "pathStyleAccessEnabled",
                "credentialProvider", "profileName", "maxAttempts", "baseBackoff", "maxBackoff"), path);
        String endpoint = stringOrDefault(object, "endpointOverride", "", path);
        Optional<URI> endpointOverride = endpoint.isEmpty()
                ? Optional.empty()
                : Optional.of(parseUri(endpoint, path + ".endpointOverride"));
        String profile = stringOrDefault(object, "profileName", "", path);
        return new ReplayRuntimeConfiguration.S3Settings(
                stringOrDefault(object, "bucket", "", path),
                stringOrDefault(object, "prefix", "", path),
                stringOrDefault(object, "region", "eu-central-1", path),
                endpointOverride,
                booleanOrDefault(object, "pathStyleAccessEnabled", false, path),
                stringOrDefault(object, "credentialProvider", "DEFAULT_CHAIN", path),
                profile.isEmpty() ? Optional.empty() : Optional.of(profile),
                integerOrDefault(object, "maxAttempts", 3, path),
                durationOrDefault(object, "baseBackoff", Duration.ofMillis(100), path),
                durationOrDefault(object, "maxBackoff", Duration.ofSeconds(2), path));
    }

    private ReplayRuntimeConfiguration.SftpSettings parseSftp(
            JsonObject object,
            Path root,
            String path) {
        rejectUnknown(object, Set.of(
                "host", "port", "username", "basePath", "knownHostsFile", "authentication",
                "privateKeyFile", "passwordEnv", "privateKeyPassphraseEnv", "poolSize",
                "acquireTimeout", "connectTimeout", "operationTimeout", "closeTimeout",
                "maxAttempts", "baseBackoff", "maxBackoff"), path);
        String knownHosts = stringOrDefault(object, "knownHostsFile", "", path);
        String privateKey = stringOrDefault(object, "privateKeyFile", "", path);
        String passwordEnv = stringOrDefault(object, "passwordEnv", "", path);
        String passphraseEnv = stringOrDefault(object, "privateKeyPassphraseEnv", "", path);
        validateEnvironmentReference(passwordEnv, path + ".passwordEnv");
        validateEnvironmentReference(passphraseEnv, path + ".privateKeyPassphraseEnv");
        return new ReplayRuntimeConfiguration.SftpSettings(
                stringOrDefault(object, "host", "", path),
                integerOrDefault(object, "port", 22, path),
                stringOrDefault(object, "username", "", path),
                stringOrDefault(object, "basePath", "", path),
                knownHosts.isEmpty()
                        ? Optional.empty()
                        : Optional.of(resolveRelativePath(knownHosts, root, path + ".knownHostsFile")),
                stringOrDefault(object, "authentication", "SSH_AGENT", path),
                privateKey.isEmpty()
                        ? Optional.empty()
                        : Optional.of(resolveRelativePath(privateKey, root, path + ".privateKeyFile")),
                passwordEnv.isEmpty() ? Optional.empty() : Optional.of(passwordEnv),
                passphraseEnv.isEmpty() ? Optional.empty() : Optional.of(passphraseEnv),
                integerOrDefault(object, "poolSize", 4, path),
                durationOrDefault(object, "acquireTimeout", Duration.ofSeconds(5), path),
                durationOrDefault(object, "connectTimeout", Duration.ofSeconds(10), path),
                durationOrDefault(object, "operationTimeout", Duration.ofSeconds(30), path),
                durationOrDefault(object, "closeTimeout", Duration.ofSeconds(10), path),
                integerOrDefault(object, "maxAttempts", 3, path),
                durationOrDefault(object, "baseBackoff", Duration.ofMillis(100), path),
                durationOrDefault(object, "maxBackoff", Duration.ofSeconds(2), path));
    }

    private ReplayRuntimeConfiguration.RecordingSettings parseRecording(
            JsonObject object,
            Path root) {
        rejectUnknown(object, Set.of(
                "workspaceRoot", "maxSegmentBytes", "maxSegmentDuration", "checkpointInterval",
                "maxQueueBytes"), "recording");
        return new ReplayRuntimeConfiguration.RecordingSettings(
                resolveRelativePath(string(object, "workspaceRoot", "recording"), root,
                        "recording.workspaceRoot"),
                longValue(object, "maxSegmentBytes", "recording"),
                duration(object, "maxSegmentDuration", "recording"),
                duration(object, "checkpointInterval", "recording"),
                longValue(object, "maxQueueBytes", "recording"));
    }

    private ReplayRuntimeConfiguration.PlaybackSettings parsePlayback(
            JsonObject object,
            Path root) {
        rejectUnknown(object, Set.of(
                "workDirectory", "cacheRoot", "cacheMaxBytes", "preloadAhead", "retainBehind",
                "minimumResumeBuffer", "memoryBudgetBytes", "diskBudgetBytes", "maxParallelFetches"),
                "playback");
        Path work = resolveRelativePath(string(object, "workDirectory", "playback"), root,
                "playback.workDirectory");
        Path cache = resolveRelativePath(string(object, "cacheRoot", "playback"), root,
                "playback.cacheRoot");
        if (Files.isSymbolicLink(work) || Files.isSymbolicLink(cache)) {
            throw new IllegalArgumentException("playback work and cache roots must not be symbolic links");
        }
        return new ReplayRuntimeConfiguration.PlaybackSettings(
                work,
                cache,
                longValue(object, "cacheMaxBytes", "playback"),
                duration(object, "preloadAhead", "playback"),
                duration(object, "retainBehind", "playback"),
                duration(object, "minimumResumeBuffer", "playback"),
                longValue(object, "memoryBudgetBytes", "playback"),
                longValue(object, "diskBudgetBytes", "playback"),
                integer(object, "maxParallelFetches", "playback"));
    }

    private void writeDefault(Path target) throws IOException {
        JsonObject defaults = defaultJson();
        String json = gson.newBuilder().setPrettyPrinting().create().toJson(defaults) + System.lineSeparator();
        Path temporary = Files.createTempFile(target.getParent(), "config-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            throw failure;
        }
    }

    private static JsonObject defaultJson() {
        JsonObject root = new JsonObject();
        JsonObject postgres = new JsonObject();
        postgres.addProperty("jdbcUrl", "jdbc:postgresql://127.0.0.1:5432/replay");
        postgres.addProperty("username", "replay");
        postgres.addProperty("passwordEnv", DEFAULT_PASSWORD_ENV);
        postgres.addProperty("minimumIdle", 1);
        postgres.addProperty("maximumPoolSize", 8);
        postgres.addProperty("connectionTimeout", "PT10S");
        postgres.addProperty("validationTimeout", "PT5S");
        postgres.addProperty("idleTimeout", "PT5M");
        postgres.addProperty("maxLifetime", "PT30M");
        postgres.addProperty("databaseParallelism", 4);
        postgres.addProperty("databaseQueueCapacity", 256);
        root.add("postgresql", postgres);

        JsonObject storage = new JsonObject();
        storage.addProperty("backend", "LOCAL");
        JsonObject local = new JsonObject();
        local.addProperty("root", "replay-storage");
        storage.add("local", local);
        JsonObject s3 = new JsonObject();
        s3.addProperty("bucket", "");
        s3.addProperty("prefix", "");
        s3.addProperty("region", "eu-central-1");
        s3.addProperty("endpointOverride", "");
        s3.addProperty("pathStyleAccessEnabled", false);
        s3.addProperty("credentialProvider", "DEFAULT_CHAIN");
        s3.addProperty("profileName", "");
        s3.addProperty("maxAttempts", 3);
        s3.addProperty("baseBackoff", "PT0.1S");
        s3.addProperty("maxBackoff", "PT2S");
        storage.add("s3", s3);
        JsonObject sftp = new JsonObject();
        sftp.addProperty("host", "");
        sftp.addProperty("port", 22);
        sftp.addProperty("username", "");
        sftp.addProperty("basePath", "");
        sftp.addProperty("knownHostsFile", "");
        sftp.addProperty("authentication", "SSH_AGENT");
        sftp.addProperty("privateKeyFile", "");
        sftp.addProperty("passwordEnv", "");
        sftp.addProperty("privateKeyPassphraseEnv", "");
        sftp.addProperty("poolSize", 4);
        sftp.addProperty("acquireTimeout", "PT5S");
        sftp.addProperty("connectTimeout", "PT10S");
        sftp.addProperty("operationTimeout", "PT30S");
        sftp.addProperty("closeTimeout", "PT10S");
        sftp.addProperty("maxAttempts", 3);
        sftp.addProperty("baseBackoff", "PT0.1S");
        sftp.addProperty("maxBackoff", "PT2S");
        storage.add("sftp", sftp);
        root.add("storage", storage);

        JsonObject recording = new JsonObject();
        recording.addProperty("workspaceRoot", "replay-work");
        recording.addProperty("maxSegmentBytes", 67108864L);
        recording.addProperty("maxSegmentDuration", "PT30S");
        recording.addProperty("checkpointInterval", "PT30S");
        recording.addProperty("maxQueueBytes", 33554432L);
        root.add("recording", recording);

        JsonObject playback = new JsonObject();
        playback.addProperty("workDirectory", "playback-work");
        playback.addProperty("cacheRoot", "playback-cache");
        playback.addProperty("cacheMaxBytes", 2147483648L);
        playback.addProperty("preloadAhead", "PT30S");
        playback.addProperty("retainBehind", "PT30S");
        playback.addProperty("minimumResumeBuffer", "PT3S");
        playback.addProperty("memoryBudgetBytes", 134217728L);
        playback.addProperty("diskBudgetBytes", 2147483648L);
        playback.addProperty("maxParallelFetches", 4);
        root.add("playback", playback);
        return root;
    }

    private static Path normalizeDataDirectory(Path dataDirectory) {
        Path root = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("dataDirectory must not be a symbolic link");
        }
        return root;
    }

    private static Path resolveRelativePath(String value, Path root, String path) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
        Path candidate = Path.of(value);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException(path + " must be relative to the plugin data directory");
        }
        Path normalized = root.resolve(candidate).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(path + " must remain below the plugin data directory");
        }
        return normalized;
    }

    private static void validateEnvironmentReference(String value, String path) {
        if (!value.isEmpty() && !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(path + " must be a valid environment name");
        }
    }

    private static void rejectUnknown(JsonObject object, Set<String> allowed, String path) {
        Set<String> unknown = new HashSet<>();
        for (String member : object.keySet()) {
            if (!allowed.contains(member)) {
                unknown.add(member);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(path + " contains unknown field(s): " + unknown);
        }
    }

    private static JsonObject object(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static JsonObject requiredObject(JsonObject parent, String name, String path) {
        JsonElement value = parent.get(name);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException(path + "." + name + " is required");
        }
        return object(value, path + "." + name);
    }

    private static Optional<JsonObject> optionalObject(JsonObject parent, String name, String path) {
        JsonElement value = parent.get(name);
        return value == null || value.isJsonNull()
                ? Optional.empty()
                : Optional.of(object(value, path + "." + name));
    }

    private static String string(JsonObject object, String name, String path) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + "." + name + " must be a string");
        }
        return value.getAsString();
    }

    private static String stringOrDefault(JsonObject object, String name, String defaultValue, String path) {
        return object.has(name) ? string(object, name, path) : defaultValue;
    }

    private static int integer(JsonObject object, String name, String path) {
        long value = longValue(object, name, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + "." + name + " is outside integer range");
        }
        return (int) value;
    }

    private static int integerOrDefault(JsonObject object, String name, int defaultValue, String path) {
        return object.has(name) ? integer(object, name, path) : defaultValue;
    }

    private static long longValue(JsonObject object, String name, String path) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + "." + name + " must be a number");
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(path + "." + name + " must be an integer", failure);
        }
    }

    private static Duration duration(JsonObject object, String name, String path) {
        return parseDuration(string(object, name, path), path + "." + name);
    }

    private static Duration durationOrDefault(
            JsonObject object,
            String name,
            Duration defaultValue,
            String path) {
        return object.has(name) ? duration(object, name, path) : defaultValue;
    }

    private static Duration parseDuration(String value, String path) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(path + " must be positive");
            }
            return duration;
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException
                    && failure.getMessage() != null
                    && failure.getMessage().startsWith(path)) {
                throw failure;
            }
            throw new IllegalArgumentException(path + " must be an ISO-8601 duration", failure);
        }
    }

    private static boolean booleanOrDefault(
            JsonObject object,
            String name,
            boolean defaultValue,
            String path) {
        if (!object.has(name)) {
            return defaultValue;
        }
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(path + "." + name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static URI parseUri(String value, String path) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(path + " must be a valid URI", failure);
        }
    }
}
