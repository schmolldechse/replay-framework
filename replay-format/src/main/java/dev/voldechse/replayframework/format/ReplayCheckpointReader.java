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

/** Strict reader for revision-three checkpoint artifacts. */
public final class ReplayCheckpointReader {

    private static final byte[] MAGIC = "RFC1".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 3;

    /** Creates a stateless checkpoint reader. */
    public ReplayCheckpointReader() {
    }

    /**
     * Reads and validates one complete checkpoint.
     *
     * @param source checkpoint path
     * @return decoded checkpoint
     * @throws IOException when the source cannot be opened
     * @throws CorruptReplayArtifactException when the source is invalid
     */
    public ReplayCheckpoint read(Path source)
            throws IOException, CorruptReplayArtifactException {
        Objects.requireNonNull(source, "source");
        try (InputStream file = new BufferedInputStream(Files.newInputStream(source))) {
            try {
                readMagic(file);
                int formatVersion = ReplayFrameCodec.readUnsignedVarInt(
                        file, "checkpoint format version");
                if (formatVersion != FORMAT_VERSION) {
                    throw corrupt("unsupported checkpoint format version: " + formatVersion);
                }
                int adapterLength = ReplayFrameCodec.readUnsignedVarInt(
                        file, "adapter id length");
                if (adapterLength == 0 || adapterLength > 255) {
                    throw corrupt("adapter id length is outside the supported range");
                }
                decodeUtf8(ReplayFrameCodec.readBytes(file, adapterLength, "adapter id"));

                int ordinal = ReplayFrameCodec.readUnsignedVarInt(file, "checkpoint ordinal");
                long elapsedNanos = ReplayFrameCodec.readUnsignedVarLong(
                        file, "checkpoint elapsed time");
                long frameCount = ReplayFrameCodec.readUnsignedVarLong(
                        file, "checkpoint frame count");
                if (frameCount > Integer.MAX_VALUE) {
                    throw corrupt("checkpoint frame count cannot be represented");
                }
                int compressionCode = ReplayFrameCodec.readUnsignedVarInt(
                        file, "checkpoint compression id");
                try {
                    if (SegmentHeader.CompressionId.fromWireCode(compressionCode)
                            != SegmentHeader.CompressionId.ZSTD) {
                        throw corrupt("unsupported checkpoint compression id: " + compressionCode);
                    }
                } catch (IllegalArgumentException exception) {
                    throw corrupt("unknown checkpoint compression id: " + compressionCode,
                            exception);
                }
                long uncompressedLength = ReplayFrameCodec.readUnsignedVarLong(
                        file, "checkpoint uncompressed length");

                try (ZstdInputStream compressed = new ZstdInputStream(file).setContinuous(false)) {
                    ReplayFrameCodec.CountingInputStream body =
                            new ReplayFrameCodec.CountingInputStream(compressed);
                    List<RawPacketFrame> frames = new ArrayList<>();
                    RawPacketFrame previous = null;
                    for (long index = 0L; index < frameCount; index++) {
                        RawPacketFrame frame = ReplayFrameCodec.readRecord(body);
                        if (previous != null && isBefore(frame, previous)) {
                            throw corrupt(
                                    "checkpoint frames are not ordered by elapsed time, server tick and sequence");
                        }
                        frames.add(frame);
                        previous = frame;
                    }
                    if (body.read() != -1) {
                        throw corrupt("checkpoint body contains bytes beyond its declared frame count");
                    }
                    if (body.count() != uncompressedLength) {
                        throw corrupt("checkpoint body length does not match the header");
                    }
                    if (file.read() != -1) {
                        throw corrupt("checkpoint contains bytes after the Zstandard stream");
                    }
                    try {
                        return new ReplayCheckpoint(ordinal, elapsedNanos, frames);
                    } catch (IllegalArgumentException | NullPointerException exception) {
                        throw corrupt("decoded checkpoint values are invalid", exception);
                    }
                } catch (CorruptReplayArtifactException exception) {
                    throw exception;
                } catch (IOException exception) {
                    throw corrupt("could not decode the Zstandard checkpoint body", exception);
                }
            } catch (CorruptReplayArtifactException exception) {
                throw exception;
            } catch (IOException exception) {
                throw corrupt("could not decode the checkpoint artifact", exception);
            }
        }
    }

    private static void readMagic(InputStream input)
            throws IOException, CorruptReplayArtifactException {
        byte[] actual = ReplayFrameCodec.readBytes(input, MAGIC.length, "checkpoint magic");
        if (!Arrays.equals(MAGIC, actual)) {
            throw corrupt("checkpoint magic is not RFC1");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CorruptReplayArtifactException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw corrupt("adapter id is not valid UTF-8", exception);
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

    private static CorruptReplayArtifactException corrupt(String message) {
        return new CorruptReplayArtifactException(message);
    }

    private static CorruptReplayArtifactException corrupt(String message, Throwable cause) {
        return new CorruptReplayArtifactException(message, cause);
    }
}
