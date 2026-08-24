package dev.voldechse.replayframework.format;

import com.github.luben.zstd.ZstdInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Strict reader for revision-one replay segments.
 */
public final class ReplaySegmentReader {

    /**
     * Reads and fully validates one segment.
     *
     * @param source segment path
     * @return complete decoded segment
     * @throws IOException when the source cannot be opened or read
     * @throws CorruptReplaySegmentException when the source is not valid
     */
    public DecodedSegment read(Path source)
            throws IOException, CorruptReplaySegmentException {
        Objects.requireNonNull(source, "source");
        try (InputStream file = new BufferedInputStream(Files.newInputStream(source))) {
            SegmentHeader header = readHeader(file);
            try (ZstdInputStream compressed = new ZstdInputStream(file).setContinuous(false)) {
                ReplayFrameCodec.CountingInputStream body =
                        new ReplayFrameCodec.CountingInputStream(compressed);
                // Do not reserve memory from the untrusted header frame count.
                List<RawPacketFrame> frames = new ArrayList<>();
                RawPacketFrame previous = null;

                try {
                    for (long index = 0; index < header.frameCount(); index++) {
                        RawPacketFrame frame;
                        try {
                            frame = ReplayFrameCodec.readRecord(body);
                        } catch (CorruptReplayArtifactException exception) {
                            throw corrupt("could not decode the segment frame record", exception);
                        }
                        if (previous != null && isBefore(frame, previous)) {
                            throw corrupt("frames are not ordered by time, tick and sequence");
                        }
                        frames.add(frame);
                        previous = frame;
                    }

                    if (body.read() != -1) {
                        throw corrupt("frame body contains bytes beyond its declared frame count");
                    }
                    if (body.count() != header.uncompressedLength()) {
                        throw corrupt("uncompressed frame length does not match the header");
                    }
                    if (file.read() != -1) {
                        throw corrupt("segment contains bytes after the Zstandard stream");
                    }
                    validateHeaderAgainstFrames(header, frames);
                    return new DecodedSegment(header, frames);
                } catch (CorruptReplaySegmentException exception) {
                    throw exception;
                } catch (IOException exception) {
                    throw new CorruptReplaySegmentException(
                            "could not decode the segment frame body", exception);
                }
            } catch (CorruptReplaySegmentException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new CorruptReplaySegmentException(
                        "could not decode the Zstandard segment body", exception);
            }
        }
    }

    private static SegmentHeader readHeader(InputStream input)
            throws IOException, CorruptReplaySegmentException {
        byte[] magic = readBytes(input, SegmentWire.MAGIC.length, "segment magic");
        if (!Arrays.equals(SegmentWire.MAGIC, magic)) {
            throw corrupt("segment magic is not RFS1");
        }

        int formatVersion = readUnsignedVarInt(input, "format version");
        if (formatVersion != SegmentHeader.CURRENT_FORMAT_VERSION) {
            throw corrupt("unsupported segment format version: " + formatVersion);
        }

        int adapterLength = readUnsignedVarInt(input, "adapter id length");
        if (adapterLength == 0 || adapterLength > 255) {
            throw corrupt("adapter id length is outside the supported range");
        }
        byte[] adapterBytes = readBytes(input, adapterLength, "adapter id");
        String adapterId = decodeUtf8(adapterBytes);
        long startElapsedNanos = readUnsignedVarLong(input, "segment start time");
        long endElapsedNanos = readUnsignedVarLong(input, "segment end time");
        long frameCount = readUnsignedVarLong(input, "frame count");
        if (frameCount > Integer.MAX_VALUE) {
            throw corrupt("frame count cannot be represented by the decoded frame list");
        }
        int compressionCode = readUnsignedVarInt(input, "compression id");
        SegmentHeader.CompressionId compressionId;
        try {
            compressionId = SegmentHeader.CompressionId.fromWireCode(compressionCode);
        } catch (IllegalArgumentException exception) {
            throw corrupt("unknown segment compression id: " + compressionCode, exception);
        }
        long uncompressedLength = readUnsignedVarLong(input, "uncompressed length");

        try {
            return new SegmentHeader(
                    formatVersion,
                    adapterId,
                    startElapsedNanos,
                    endElapsedNanos,
                    frameCount,
                    compressionId,
                    uncompressedLength);
        } catch (IllegalArgumentException exception) {
            throw corrupt("segment header values are invalid", exception);
        }
    }

    private static void validateHeaderAgainstFrames(
            SegmentHeader header, List<RawPacketFrame> frames)
            throws CorruptReplaySegmentException {
        if (header.frameCount() != frames.size()) {
            throw corrupt("frame count does not match decoded frames");
        }
        if (frames.isEmpty()) {
            if (header.startElapsedNanos() != 0L || header.endElapsedNanos() != 0L) {
                throw corrupt("empty segment must have zero time bounds");
            }
            return;
        }
        RawPacketFrame first = frames.get(0);
        RawPacketFrame last = frames.get(frames.size() - 1);
        if (header.startElapsedNanos() != first.elapsedNanos()
                || header.endElapsedNanos() != last.elapsedNanos()) {
            throw corrupt("segment time bounds do not match decoded frames");
        }
    }

    private static boolean isBefore(RawPacketFrame current, RawPacketFrame previous) {
        return current.elapsedNanos() < previous.elapsedNanos()
                || (current.elapsedNanos() == previous.elapsedNanos()
                && current.serverTick() < previous.serverTick())
                || (current.elapsedNanos() == previous.elapsedNanos()
                && current.serverTick() == previous.serverTick()
                && current.sequence() < previous.sequence());
    }

    private static int readUnsignedVarInt(InputStream input, String field)
            throws IOException, CorruptReplaySegmentException {
        long value = 0L;
        for (int index = 0; index < 5; index++) {
            int next = input.read();
            if (next < 0) {
                throw corrupt("truncated " + field);
            }
            if (index == 9 && (next & 0x7E) != 0) {
                throw corrupt("overflow in " + field);
            }
            value |= (long) (next & 0x7F) << (index * 7);
            if ((next & 0x80) == 0) {
                if (value > Integer.MAX_VALUE) {
                    throw corrupt("overflow in " + field);
                }
                int result = (int) value;
                if (SegmentWire.sizeOfUnsignedVarInt(result) != index + 1) {
                    throw corrupt("non-canonical encoding of " + field);
                }
                return result;
            }
        }
        throw corrupt("overlong encoding of " + field);
    }

    private static long readUnsignedVarLong(InputStream input, String field)
            throws IOException, CorruptReplaySegmentException {
        long value = 0L;
        for (int index = 0; index < 10; index++) {
            int next = input.read();
            if (next < 0) {
                throw corrupt("truncated " + field);
            }
            value |= (long) (next & 0x7F) << (index * 7);
            if ((next & 0x80) == 0) {
                if (value < 0L) {
                    throw corrupt("overflow in " + field);
                }
                if (SegmentWire.sizeOfUnsignedVarLong(value) != index + 1) {
                    throw corrupt("non-canonical encoding of " + field);
                }
                return value;
            }
        }
        throw corrupt("overlong encoding of " + field);
    }

    private static byte[] readBytes(InputStream input, int length, String field)
            throws IOException, CorruptReplaySegmentException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(result, offset, length - offset);
            if (read < 0) {
                throw corrupt("truncated " + field);
            }
            if (read == 0) {
                int single = input.read();
                if (single < 0) {
                    throw corrupt("truncated " + field);
                }
                result[offset++] = (byte) single;
            } else {
                offset += read;
            }
        }
        return result;
    }

    private static String decodeUtf8(byte[] bytes) throws CorruptReplaySegmentException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw corrupt("adapter id is not valid UTF-8", exception);
        }
    }

    private static CorruptReplaySegmentException corrupt(String message) {
        return new CorruptReplaySegmentException(message);
    }

    private static CorruptReplaySegmentException corrupt(String message, Throwable cause) {
        return new CorruptReplaySegmentException(message, cause);
    }

    /** Decoded segment data is immutable after construction. */
    public record DecodedSegment(SegmentHeader header, List<RawPacketFrame> frames) {
        public DecodedSegment {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(frames, "frames");
            frames = List.copyOf(frames);
        }
    }

}
