package dev.voldechse.replayframework.format;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Strict reader for revision-one replay indexes. */
public final class ReplayIndexReader {

    private static final byte[] MAGIC = "RFI1".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;

    /** Creates a stateless index reader. */
    public ReplayIndexReader() {
    }

    /**
     * Reads and validates one complete index.
     *
     * @param source index path
     * @return decoded immutable index
     * @throws IOException when the source cannot be opened
     * @throws CorruptReplayArtifactException when the source is invalid
     */
    public ReplayIndex read(Path source)
            throws IOException, CorruptReplayArtifactException {
        Objects.requireNonNull(source, "source");
        long fileSize = Files.size(source);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(source))) {
            ReplayFrameCodec.CountingInputStream input =
                    new ReplayFrameCodec.CountingInputStream(file);
            try {
                byte[] magic = ReplayFrameCodec.readBytes(input, MAGIC.length, "index magic");
                if (!Arrays.equals(MAGIC, magic)) {
                    throw corrupt("index magic is not RFI1");
                }
                int formatVersion = ReplayFrameCodec.readUnsignedVarInt(
                        input, "index format version");
                if (formatVersion != FORMAT_VERSION) {
                    throw corrupt("unsupported index format version: " + formatVersion);
                }
                int entryCount = ReplayFrameCodec.readUnsignedVarInt(input, "index entry count");
                long remainingBytes = fileSize - input.count();
                long minimumEntryBytes;
                try {
                    minimumEntryBytes = Math.multiplyExact((long) entryCount, 4L);
                } catch (ArithmeticException exception) {
                    throw corrupt("index entry count is too large", exception);
                }
                if (minimumEntryBytes > remainingBytes) {
                    throw corrupt("index entry count exceeds the remaining file length");
                }

                List<SeekPoint> points = new ArrayList<>(entryCount);
                for (int index = 0; index < entryCount; index++) {
                    long elapsedNanos = ReplayFrameCodec.readUnsignedVarLong(
                            input, "index elapsed time");
                    int checkpointOrdinal = ReplayFrameCodec.readUnsignedVarInt(
                            input, "index checkpoint ordinal");
                    int segmentOrdinal = ReplayFrameCodec.readUnsignedVarInt(
                            input, "index segment ordinal");
                    long frameOffset = ReplayFrameCodec.readUnsignedVarLong(
                            input, "index frame offset");
                    try {
                        points.add(new SeekPoint(
                                elapsedNanos,
                                checkpointOrdinal,
                                segmentOrdinal,
                                frameOffset));
                    } catch (IllegalArgumentException exception) {
                        throw corrupt("decoded index point is invalid", exception);
                    }
                }
                if (input.read() != -1) {
                    throw corrupt("index contains bytes after its declared entries");
                }
                try {
                    return ReplayIndex.of(points);
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw corrupt("decoded index ordering is invalid", exception);
                }
            } catch (CorruptReplayArtifactException exception) {
                throw exception;
            } catch (IOException exception) {
                throw corrupt("could not decode the index artifact", exception);
            }
        }
    }

    private static CorruptReplayArtifactException corrupt(String message) {
        return new CorruptReplayArtifactException(message);
    }

    private static CorruptReplayArtifactException corrupt(String message, Throwable cause) {
        return new CorruptReplayArtifactException(message, cause);
    }
}
