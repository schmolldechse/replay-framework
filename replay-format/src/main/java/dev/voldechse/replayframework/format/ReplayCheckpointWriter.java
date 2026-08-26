package dev.voldechse.replayframework.format;

import com.github.luben.zstd.ZstdOutputStream;

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

/** Writes one immutable, compressed checkpoint artifact. */
public final class ReplayCheckpointWriter {

    private static final byte[] MAGIC = "RFC1".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 3;

    private enum State {
        /** The writer has not published its checkpoint yet. */
        OPEN,
        /** The complete checkpoint is visible at the final target. */
        FINISHED,
        /** Writing failed and no valid target was published. */
        ABORTED
    }

    private final Path target;
    private final String adapterId;
    private State state = State.OPEN;

    /**
     * Creates a writer whose target must not already exist.
     *
     * @param target final checkpoint path
     * @param adapterId adapter identifier stored in the checkpoint header
     * @throws IOException if the target parent cannot be created
     */
    public ReplayCheckpointWriter(Path target, String adapterId) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isEmpty()) {
            throw new IllegalArgumentException("adapterId must not be empty");
        }
        if (SegmentHeader.adapterIdBytes(adapterId).length > 255) {
            throw new IllegalArgumentException("adapterId must fit in 255 UTF-8 bytes");
        }
        this.target = target.toAbsolutePath().normalize();
        if (Files.exists(this.target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(this.target.toString());
        }
        Path parent = this.target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("target must have a parent directory");
        }
        Files.createDirectories(parent);
        this.adapterId = adapterId;
    }

    /**
     * Writes and publishes the checkpoint exactly once.
     *
     * @param checkpoint checkpoint to encode
     * @throws IOException when encoding or publication fails
     */
    public void write(ReplayCheckpoint checkpoint) throws IOException {
        ensureOpen();
        Objects.requireNonNull(checkpoint, "checkpoint");
        Path framePath = null;
        Path compressedPath = null;
        try {
            framePath = Files.createTempFile(
                    target.getParent(), ".replay-checkpoint-", ".frames.part");
            long frameCount = 0L;
            long uncompressedLength = 0L;
            try (OutputStream frameOutput = new BufferedOutputStream(Files.newOutputStream(
                    framePath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                for (RawPacketFrame frame : checkpoint.initializationFrames()) {
                    long encodedLength = ReplayFrameCodec.encodedRecordLength(frame);
                    uncompressedLength = addLength(uncompressedLength, encodedLength);
                    ReplayFrameCodec.writeRecord(frameOutput, frame);
                    frameCount = Math.addExact(frameCount, 1L);
                }
            }

            compressedPath = Files.createTempFile(
                    target.getParent(), ".replay-checkpoint-", ".checkpoint.part");
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                    compressedPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                writeHeader(output, adapterId, checkpoint, frameCount, uncompressedLength);
                try (ZstdOutputStream compressed = new ZstdOutputStream(output)) {
                    Files.copy(framePath, compressed);
                }
            }
            forceFile(compressedPath);
            Files.deleteIfExists(framePath);
            framePath = null;
            moveWithoutReplacement(compressedPath, target);
            compressedPath = null;
            state = State.FINISHED;
        } catch (IOException | RuntimeException exception) {
            state = State.ABORTED;
            cleanupAfterFailure(exception, framePath, compressedPath);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static void writeHeader(
            OutputStream output,
            String adapterId,
            ReplayCheckpoint checkpoint,
            long frameCount,
            long uncompressedLength) throws IOException {
        output.write(MAGIC);
        SegmentWire.writeUnsignedVarInt(output, FORMAT_VERSION);
        byte[] adapterBytes = SegmentHeader.adapterIdBytes(adapterId);
        SegmentWire.writeUnsignedVarInt(output, adapterBytes.length);
        output.write(adapterBytes);
        SegmentWire.writeUnsignedVarInt(output, checkpoint.ordinal());
        SegmentWire.writeUnsignedVarLong(output, checkpoint.elapsedNanos());
        SegmentWire.writeUnsignedVarLong(output, frameCount);
        SegmentWire.writeUnsignedVarInt(
                output, SegmentHeader.CompressionId.ZSTD.wireCode());
        SegmentWire.writeUnsignedVarLong(output, uncompressedLength);
    }

    private void ensureOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("checkpoint writer is " + state);
        }
    }

    private void cleanupAfterFailure(Exception primary, Path framePath, Path compressedPath) {
        deleteAfterFailure(primary, framePath);
        deleteAfterFailure(primary, compressedPath);
    }

    private static void deleteAfterFailure(Exception primary, Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static long addLength(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("checkpoint length overflow", exception);
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
