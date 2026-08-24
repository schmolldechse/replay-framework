package dev.voldechse.replayframework.format;

import com.github.luben.zstd.ZstdOutputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Single-owner writer for one immutable replay segment.
 *
 * <p>Frames are first written to an uncompressed temporary stream because the
 * header counters are only known after the final frame has been accepted.</p>
 */
public final class ReplaySegmentWriter implements AutoCloseable {

    private enum State {
        /** Frames may be appended and the segment has not been published. */
        OPEN,
        /** The complete segment is visible at the final target path. */
        FINISHED,
        /** Writing stopped after an error or explicit cancellation. */
        ABORTED
    }

    private final Path target;
    private final Path framePath;
    private final String adapterId;
    private OutputStream frameOutput;
    private State state = State.OPEN;
    private boolean hasLastFrame;
    private long lastElapsedNanos;
    private long lastServerTick;
    private int lastSequence;
    private long frameCount;
    private long uncompressedLength;
    private long startElapsedNanos;
    private long endElapsedNanos;

    /**
     * Creates a writer for a not-yet-existing target path.
     *
     * @param target final segment path
     * @param adapterId adapter that produced the frames
     * @throws IOException when the temporary stream cannot be opened
     * @throws IllegalArgumentException for an invalid adapter id or target
     */
    public ReplaySegmentWriter(Path target, String adapterId) throws IOException {
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
        if (Files.exists(this.target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(this.target.toString());
        }

        this.adapterId = adapterId;
        this.framePath = Files.createTempFile(
                parent, ".replay-segment-", ".frames.part");
        try {
            this.frameOutput = new BufferedOutputStream(Files.newOutputStream(
                    framePath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING));
        } catch (IOException exception) {
            Files.deleteIfExists(framePath);
            throw exception;
        }
    }

    /**
     * Appends one frame in capture order.
     *
     * @param frame frame to append
     * @throws IOException when the temporary stream cannot accept the frame
     * @throws IllegalArgumentException when ordering or size invariants fail
     * @throws IllegalStateException when the writer is not open
     */
    public void append(RawPacketFrame frame) throws IOException {
        ensureOpen();
        Objects.requireNonNull(frame, "frame");
        validateOrder(frame);

        long encodedLength = ReplayFrameCodec.encodedRecordLength(frame);
        long nextUncompressedLength = addLength(uncompressedLength, encodedLength);
        if (frameCount == Long.MAX_VALUE) {
            throw new IllegalArgumentException("frameCount overflow");
        }

        try {
            ReplayFrameCodec.writeRecord(frameOutput, frame);
        } catch (IOException exception) {
            abortAfterFailure(exception);
            throw exception;
        }

        frameCount++;
        uncompressedLength = nextUncompressedLength;
        if (!hasLastFrame) {
            startElapsedNanos = frame.elapsedNanos();
            hasLastFrame = true;
        }
        endElapsedNanos = frame.elapsedNanos();
        lastElapsedNanos = frame.elapsedNanos();
        lastServerTick = frame.serverTick();
        lastSequence = frame.sequence();
    }

    /**
     * Returns the number of accepted frames.
     *
     * @return accepted frame count
     */
    public long frameCount() {
        return frameCount;
    }

    /**
     * Returns the encoded uncompressed frame-body length.
     *
     * @return uncompressed body length
     */
    public long uncompressedLength() {
        return uncompressedLength;
    }

    /**
     * Finishes and atomically moves the complete segment to its target.
     *
     * @return written segment header
     * @throws IOException when compression, I/O or final move fails
     */
    public SegmentHeader finish() throws IOException {
        ensureOpen();

        Path compressedPath = null;
        try {
            closeFrameOutput();
            SegmentHeader header = new SegmentHeader(
                    SegmentHeader.CURRENT_FORMAT_VERSION,
                    adapterId,
                    hasLastFrame ? startElapsedNanos : 0L,
                    hasLastFrame ? endElapsedNanos : 0L,
                    frameCount,
                    SegmentHeader.CompressionId.ZSTD,
                    uncompressedLength);

            compressedPath = Files.createTempFile(
                    target.getParent(), ".replay-segment-", ".segment.part");
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                    compressedPath,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                SegmentWire.writeHeader(output, header);
                try (ZstdOutputStream compressed = new ZstdOutputStream(output)) {
                    Files.copy(framePath, compressed);
                }
            }
            forceFile(compressedPath);
            // Remove the source temp file before publication so cleanup failure
            // cannot leave a final segment alongside an incomplete cleanup.
            Files.deleteIfExists(framePath);
            moveWithoutReplacement(compressedPath, target);
            compressedPath = null;

            state = State.FINISHED;
            return header;
        } catch (IOException | RuntimeException exception) {
            abortAfterFailure(exception);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        } finally {
            if (compressedPath != null) {
                Files.deleteIfExists(compressedPath);
            }
        }
    }

    /**
     * Aborts the writer and removes temporary files.
     *
     * @throws IOException when a temporary resource cannot be closed or removed
     */
    public void abort() throws IOException {
        if (state == State.FINISHED) {
            throw new IllegalStateException("finished writer cannot be aborted");
        }
        if (state == State.ABORTED) {
            return;
        }
        state = State.ABORTED;
        IOException failure = null;
        try {
            closeFrameOutput();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            Files.deleteIfExists(framePath);
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Closes an open writer through the abort path. Finished and aborted
     * writers are already closed and therefore remain idempotent.
     *
     * @throws IOException when abort cleanup fails
     */
    @Override
    public void close() throws IOException {
        if (state == State.OPEN) {
            abort();
        }
    }

    private void validateOrder(RawPacketFrame frame) {
        if (!hasLastFrame) {
            return;
        }
        boolean beforeLast = frame.elapsedNanos() < lastElapsedNanos
                || (frame.elapsedNanos() == lastElapsedNanos
                && frame.serverTick() < lastServerTick)
                || (frame.elapsedNanos() == lastElapsedNanos
                && frame.serverTick() == lastServerTick
                && frame.sequence() < lastSequence);
        if (beforeLast) {
            throw new IllegalArgumentException(
                    "frames must be ordered by elapsed time, server tick and sequence");
        }
    }

    private void ensureOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("segment writer is " + state);
        }
    }

    private static long addLength(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("segment length overflow", exception);
        }
    }

    private void closeFrameOutput() throws IOException {
        if (frameOutput != null) {
            frameOutput.close();
            frameOutput = null;
        }
    }

    private void abortAfterFailure(Exception failure) {
        state = State.ABORTED;
        try {
            closeFrameOutput();
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            Files.deleteIfExists(framePath);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
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

/** Package-private wire primitives shared by the writer and reader. */
final class SegmentWire {

    static final byte[] MAGIC = "RFS1".getBytes(StandardCharsets.US_ASCII);

    private SegmentWire() {
    }

    static int sizeOfUnsignedVarInt(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("unsigned VarInt value must not be negative");
        }
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    static int sizeOfUnsignedVarLong(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("unsigned VarLong value must not be negative");
        }
        int size = 1;
        while ((value & ~0x7FL) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    static void writeUnsignedVarInt(OutputStream output, int value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("unsigned VarInt value must not be negative");
        }
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    static void writeUnsignedVarLong(OutputStream output, long value) throws IOException {
        if (value < 0) {
            throw new IllegalArgumentException("unsigned VarLong value must not be negative");
        }
        while ((value & ~0x7FL) != 0) {
            output.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        output.write((int) value);
    }

    static void writeHeader(OutputStream output, SegmentHeader header) throws IOException {
        output.write(MAGIC);
        writeUnsignedVarInt(output, header.formatVersion());
        byte[] adapterBytes = SegmentHeader.adapterIdBytes(header.adapterId());
        writeUnsignedVarInt(output, adapterBytes.length);
        output.write(adapterBytes);
        writeUnsignedVarLong(output, header.startElapsedNanos());
        writeUnsignedVarLong(output, header.endElapsedNanos());
        writeUnsignedVarLong(output, header.frameCount());
        writeUnsignedVarInt(output, header.compressionId().wireCode());
        writeUnsignedVarLong(output, header.uncompressedLength());
    }
}
