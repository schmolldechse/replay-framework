package dev.voldechse.replayframework.core.artifact;

import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.ReplayManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Computes and verifies replay artifact metadata without buffering complete files in memory.
 *
 * <p>The verifier deliberately rejects symbolic links. A replay artifact must be a stable,
 * regular file in the local hand-off directory; following a link would make the bytes being
 * described dependent on a path outside that boundary.</p>
 */
public final class ArtifactIntegrityVerifier {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Describes the bytes currently available at a local artifact path.
     *
     * @param type manifest artifact type
     * @param artifactPath canonical manifest/storage path
     * @param source local regular file
     * @return size and lowercase SHA-256 metadata for the source
     * @throws IOException when the source is missing, unsafe or cannot be read
     */
    public ReplayManifest.ArtifactFile describe(
            ReplayManifest.ArtifactType type,
            String artifactPath,
            Path source) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(artifactPath, "artifactPath");
        Digest digest = digest(source);
        return new ReplayManifest.ArtifactFile(type, artifactPath, digest.sizeBytes(), digest.sha256());
    }

    /**
     * Verifies the exact byte size and SHA-256 digest declared by a manifest member.
     *
     * @param source local downloaded or staged artifact
     * @param expected manifest metadata
     * @throws IOException when the source is missing or unsafe
     * @throws CorruptReplayArtifactException when bytes differ from the manifest
     */
    public void verify(Path source, ReplayManifest.ArtifactFile expected)
            throws IOException, CorruptReplayArtifactException {
        Objects.requireNonNull(expected, "expected");
        Digest actual = digest(source);
        if (actual.sizeBytes() != expected.sizeBytes()) {
            throw new CorruptReplayArtifactException(
                    "artifact size mismatch for " + expected.path()
                            + ": expected " + expected.sizeBytes()
                            + " bytes but received " + actual.sizeBytes());
        }
        if (!actual.sha256().equals(expected.sha256())) {
            throw new CorruptReplayArtifactException(
                    "artifact SHA-256 mismatch for " + expected.path());
        }
    }

    private Digest digest(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        Path normalized = source.toAbsolutePath().normalize();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            throw exception;
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("artifact source must be a regular non-symlink file: " + normalized);
        }

        MessageDigest messageDigest = sha256Digest();
        long sizeBytes = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (Long.MAX_VALUE - sizeBytes < read) {
                    throw new IOException("artifact is too large to describe: " + normalized);
                }
                messageDigest.update(buffer, 0, read);
                sizeBytes += read;
            }
        }
        return new Digest(sizeBytes, HEX.formatHex(messageDigest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record Digest(long sizeBytes, String sha256) {
    }
}
