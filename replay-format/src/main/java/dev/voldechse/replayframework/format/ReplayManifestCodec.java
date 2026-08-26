package dev.voldechse.replayframework.format;

import com.google.gson.Gson;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic Gson codec for revision-three neutral replay manifests. */
public final class ReplayManifestCodec {

    private final TypeAdapter<String> stringAdapter;
    private final TypeAdapter<Integer> integerAdapter;
    private final TypeAdapter<Long> longAdapter;

    /**
     * Creates a codec backed by the runtime's Gson instance.
     *
     * @param gson Gson instance used for primitive JSON adapters
     */
    public ReplayManifestCodec(Gson gson) {
        Objects.requireNonNull(gson, "gson");
        stringAdapter = gson.getAdapter(String.class);
        integerAdapter = gson.getAdapter(Integer.class);
        longAdapter = gson.getAdapter(Long.class);
    }

    /**
     * Encodes a manifest as canonical UTF-8 JSON.
     *
     * @param manifest manifest to encode
     * @return compact canonical JSON bytes
     */
    public byte[] encode(ReplayManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStreamWriter output = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            JsonWriter writer = new JsonWriter(output);
            writer.setStrictness(Strictness.STRICT);
            writer.setSerializeNulls(false);
            writeManifest(writer, manifest);
            writer.flush();
        } catch (IOException exception) {
            throw new IllegalStateException("could not encode manifest", exception);
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes and validates canonical manifest JSON.
     *
     * @param jsonBytes UTF-8 JSON bytes
     * @return validated manifest
     * @throws CorruptReplayArtifactException when JSON or manifest values are invalid
     */
    public ReplayManifest decode(byte[] jsonBytes) throws CorruptReplayArtifactException {
        Objects.requireNonNull(jsonBytes, "jsonBytes");
        final String json;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            json = decoder.decode(ByteBuffer.wrap(jsonBytes)).toString();
        } catch (CharacterCodingException exception) {
            throw corrupt("manifest is not valid UTF-8", exception);
        }
        try (Reader input = new StringReader(json)) {
            JsonReader reader = new JsonReader(input);
            reader.setStrictness(Strictness.STRICT);
            ReplayManifest manifest = readManifest(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw corrupt("manifest contains trailing JSON values");
            }
            return manifest;
        } catch (CorruptReplayArtifactException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw corrupt("could not decode manifest JSON", exception);
        }
    }

    /**
     * Writes canonical manifest bytes to a new target path.
     *
     * @param target final manifest path
     * @param manifest manifest to write
     * @throws IOException when the file cannot be written or published
     */
    public void write(Path target, ReplayManifest manifest) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(manifest, "manifest");
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(normalizedTarget.toString());
        }
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("target must have a parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".replay-manifest-", ".json.part");
        try {
            Files.write(
                    temporary,
                    encode(manifest),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            forceFile(temporary);
            moveWithoutReplacement(temporary, normalizedTarget);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads and validates a manifest file.
     *
     * @param source manifest path
     * @return validated manifest
     * @throws IOException when the source cannot be read
     * @throws CorruptReplayArtifactException when the source is invalid
     */
    public ReplayManifest read(Path source)
            throws IOException, CorruptReplayArtifactException {
        Objects.requireNonNull(source, "source");
        return decode(Files.readAllBytes(source));
    }

    private void writeManifest(JsonWriter writer, ReplayManifest manifest) throws IOException {
        writer.beginObject();
        writer.name("replayId");
        stringAdapter.write(writer, manifest.replayId());
        writer.name("adapterId");
        stringAdapter.write(writer, manifest.adapterId());
        writer.name("protocolVersion");
        integerAdapter.write(writer, manifest.protocolVersion());
        writer.name("registryFingerprint");
        stringAdapter.write(writer, manifest.registryFingerprint());
        writer.name("formatRevision");
        integerAdapter.write(writer, manifest.formatRevision());
        writer.name("durationNanos");
        longAdapter.write(writer, manifest.durationNanos());
        writer.name("files");
        writer.beginArray();
        for (ReplayManifest.ArtifactFile file : manifest.files()) {
            writer.beginObject();
            writer.name("type");
            stringAdapter.write(writer, file.type().name());
            writer.name("path");
            stringAdapter.write(writer, file.path());
            writer.name("sizeBytes");
            longAdapter.write(writer, file.sizeBytes());
            writer.name("sha256");
            stringAdapter.write(writer, file.sha256());
            writer.endObject();
        }
        writer.endArray();
        writer.endObject();
    }

    private ReplayManifest readManifest(JsonReader reader)
            throws IOException, CorruptReplayArtifactException {
        reader.beginObject();
        String replayId = null;
        String adapterId = null;
        Integer protocolVersion = null;
        String registryFingerprint = null;
        Integer formatRevision = null;
        Long durationNanos = null;
        List<ReplayManifest.ArtifactFile> files = null;
        boolean replayIdSeen = false;
        boolean adapterIdSeen = false;
        boolean protocolVersionSeen = false;
        boolean registryFingerprintSeen = false;
        boolean formatRevisionSeen = false;
        boolean durationNanosSeen = false;
        boolean filesSeen = false;
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "replayId" -> {
                    ensureNotSeen(replayIdSeen, name);
                    replayIdSeen = true;
                    replayId = requiredString(reader, name);
                }
                case "adapterId" -> {
                    ensureNotSeen(adapterIdSeen, name);
                    adapterIdSeen = true;
                    adapterId = requiredString(reader, name);
                }
                case "protocolVersion" -> {
                    ensureNotSeen(protocolVersionSeen, name);
                    protocolVersionSeen = true;
                    protocolVersion = requiredInteger(reader, name);
                }
                case "registryFingerprint" -> {
                    ensureNotSeen(registryFingerprintSeen, name);
                    registryFingerprintSeen = true;
                    registryFingerprint = requiredString(reader, name);
                }
                case "formatRevision" -> {
                    ensureNotSeen(formatRevisionSeen, name);
                    formatRevisionSeen = true;
                    formatRevision = requiredInteger(reader, name);
                }
                case "durationNanos" -> {
                    ensureNotSeen(durationNanosSeen, name);
                    durationNanosSeen = true;
                    durationNanos = requiredLong(reader, name);
                }
                case "files" -> {
                    ensureNotSeen(filesSeen, name);
                    filesSeen = true;
                    files = readFiles(reader);
                }
                default -> throw corrupt("unknown manifest field: " + name);
            }
        }
        reader.endObject();
        if (formatRevisionSeen && formatRevision != ReplayManifest.CURRENT_FORMAT_REVISION) {
            throw corrupt("unsupported manifest format revision (detected " + formatRevision + ")");
        }
        if (!replayIdSeen || !adapterIdSeen || !protocolVersionSeen
                || !registryFingerprintSeen || !formatRevisionSeen
                || !durationNanosSeen || !filesSeen) {
            throw corrupt("manifest is missing a required field");
        }
        try {
            return new ReplayManifest(
                    replayId,
                    adapterId,
                    protocolVersion,
                    registryFingerprint,
                    formatRevision,
                    durationNanos,
                    files);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw corrupt("manifest values are invalid", exception);
        }
    }

    private List<ReplayManifest.ArtifactFile> readFiles(JsonReader reader)
            throws IOException, CorruptReplayArtifactException {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw corrupt("manifest files must be an array");
        }
        List<ReplayManifest.ArtifactFile> files = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginObject();
            String type = null;
            String path = null;
            Long sizeBytes = null;
            String sha256 = null;
            boolean typeSeen = false;
            boolean pathSeen = false;
            boolean sizeBytesSeen = false;
            boolean sha256Seen = false;
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "type" -> {
                        ensureNotSeen(typeSeen, name);
                        typeSeen = true;
                        type = requiredString(reader, name);
                    }
                    case "path" -> {
                        ensureNotSeen(pathSeen, name);
                        pathSeen = true;
                        path = requiredString(reader, name);
                    }
                    case "sizeBytes" -> {
                        ensureNotSeen(sizeBytesSeen, name);
                        sizeBytesSeen = true;
                        sizeBytes = requiredLong(reader, name);
                    }
                    case "sha256" -> {
                        ensureNotSeen(sha256Seen, name);
                        sha256Seen = true;
                        sha256 = requiredString(reader, name);
                    }
                    default -> throw corrupt("unknown manifest artifact field: " + name);
                }
            }
            reader.endObject();
            if (!typeSeen || !pathSeen || !sizeBytesSeen || !sha256Seen) {
                throw corrupt("manifest artifact is missing a required field");
            }
            try {
                files.add(new ReplayManifest.ArtifactFile(
                        ReplayManifest.ArtifactType.valueOf(type), path, sizeBytes, sha256));
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw corrupt("manifest artifact values are invalid", exception);
            }
        }
        reader.endArray();
        return files;
    }

    private String requiredString(JsonReader reader, String field)
            throws IOException, CorruptReplayArtifactException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            throw corrupt(field + " must not be null");
        }
        String value = stringAdapter.read(reader);
        if (value == null) {
            throw corrupt(field + " must not be null");
        }
        return value;
    }

    private int requiredInteger(JsonReader reader, String field)
            throws IOException, CorruptReplayArtifactException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            throw corrupt(field + " must not be null");
        }
        Integer value = integerAdapter.read(reader);
        if (value == null) {
            throw corrupt(field + " must not be null");
        }
        return value;
    }

    private long requiredLong(JsonReader reader, String field)
            throws IOException, CorruptReplayArtifactException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            throw corrupt(field + " must not be null");
        }
        Long value = longAdapter.read(reader);
        if (value == null) {
            throw corrupt(field + " must not be null");
        }
        return value;
    }

    private static void ensureNotSeen(boolean seen, String field)
            throws CorruptReplayArtifactException {
        if (seen) {
            throw corrupt("duplicate manifest field: " + field);
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveWithoutReplacement(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static CorruptReplayArtifactException corrupt(String message) {
        return new CorruptReplayArtifactException(message);
    }

    private static CorruptReplayArtifactException corrupt(String message, Throwable cause) {
        return new CorruptReplayArtifactException(message, cause);
    }
}
