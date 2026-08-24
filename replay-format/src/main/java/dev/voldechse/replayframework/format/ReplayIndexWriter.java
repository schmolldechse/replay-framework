package dev.voldechse.replayframework.format;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Writes the uncompressed revision-one replay index. */
public final class ReplayIndexWriter {

    private static final byte[] MAGIC = "RFI1".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;

    /** Creates a stateless index writer. */
    public ReplayIndexWriter() {
    }

    /**
     * Writes an index without replacing an existing target.
     *
     * @param target final index path
     * @param index immutable index to write
     * @throws IOException when the index cannot be written or published
     */
    public void write(Path target, ReplayIndex index) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(index, "index");
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(normalizedTarget.toString());
        }
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("target must have a parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".replay-index-", ".index.part");
        try {
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                output.write(MAGIC);
                SegmentWire.writeUnsignedVarInt(output, FORMAT_VERSION);
                SegmentWire.writeUnsignedVarInt(output, index.points().size());
                for (SeekPoint point : index.points()) {
                    SegmentWire.writeUnsignedVarLong(output, point.elapsedNanos());
                    SegmentWire.writeUnsignedVarInt(output, point.checkpointOrdinal());
                    SegmentWire.writeUnsignedVarInt(output, point.segmentOrdinal());
                    SegmentWire.writeUnsignedVarLong(output, point.frameOffset());
                }
            }
            forceFile(temporary);
            moveWithoutReplacement(temporary, normalizedTarget);
        } finally {
            Files.deleteIfExists(temporary);
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
}
