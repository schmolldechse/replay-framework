package dev.voldechse.replayframework.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Package-private wire codec shared by segment and checkpoint bodies.
 *
 * <p>A body is a sequence of frame records of one track. A record holds:</p>
 * <ol>
 *     <li>record length (VarInt),</li>
 *     <li>elapsed time since the previous frame of the body (VarLong),</li>
 *     <li>server tick change since the previous frame (zigzag VarLong),</li>
 *     <li>sequence within the server tick (VarInt),</li>
 *     <li>packet ID (VarInt),</li>
 *     <li>payload length (VarInt) and payload.</li>
 * </ol>
 *
 * <p>The first frame of a body is relative to zero. Phase and world are not
 * stored: every frame is a {@link PacketPhase#PLAY} packet, and its world is
 * the world of its track. A body is therefore only decoded from its start,
 * with an {@link Encoder} and a {@link Decoder} that carry the previous
 * frame.</p>
 */
final class ReplayFrameCodec {

    static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    private ReplayFrameCodec() {
    }

    /** Encodes the frames of one body in order. */
    static final class Encoder {
        private final TrackDescriptor track;
        private long previousElapsedNanos;
        private long previousServerTick;

        Encoder(TrackDescriptor track) {
            this.track = Objects.requireNonNull(track, "track");
        }

        /**
         * Returns the encoded length of the next record, including its length
         * prefix, without accepting the frame.
         */
        long encodedLength(RawPacketFrame frame) {
            validate(frame);
            int bodyLength = bodyLength(frame);
            return SegmentWire.sizeOfUnsignedVarInt(bodyLength) + (long) bodyLength;
        }

        /** Writes the next record and makes it the base of the following one. */
        void write(OutputStream output, RawPacketFrame frame) throws IOException {
            Objects.requireNonNull(output, "output");
            validate(frame);
            byte[] payload = frame.payload();
            SegmentWire.writeUnsignedVarInt(output, bodyLength(frame));
            SegmentWire.writeUnsignedVarLong(output, frame.elapsedNanos() - previousElapsedNanos);
            SegmentWire.writeUnsignedVarLong(
                    output, zigzag(frame.serverTick() - previousServerTick));
            SegmentWire.writeUnsignedVarInt(output, frame.sequence());
            SegmentWire.writeUnsignedVarInt(output, frame.packetId());
            SegmentWire.writeUnsignedVarInt(output, payload.length);
            output.write(payload);
            previousElapsedNanos = frame.elapsedNanos();
            previousServerTick = frame.serverTick();
        }

        private void validate(RawPacketFrame frame) {
            Objects.requireNonNull(frame, "frame");
            if (frame.phase() != PacketPhase.PLAY) {
                throw new IllegalArgumentException("a track only holds PLAY frames");
            }
            if (!frame.world().equals(track.frameWorld())) {
                throw new IllegalArgumentException(
                        "frame world " + frame.world().orElse("-")
                                + " does not belong to track " + track.id());
            }
            if (frame.elapsedNanos() < previousElapsedNanos) {
                throw new IllegalArgumentException("frames must be ordered by elapsed time");
            }
            if (frame.payload().length > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("frame payload exceeds maximum length");
            }
        }

        private int bodyLength(RawPacketFrame frame) {
            int payloadLength = frame.payload().length;
            long length = SegmentWire.sizeOfUnsignedVarLong(frame.elapsedNanos() - previousElapsedNanos)
                    + (long) SegmentWire.sizeOfUnsignedVarLong(
                            zigzag(frame.serverTick() - previousServerTick))
                    + SegmentWire.sizeOfUnsignedVarInt(frame.sequence())
                    + SegmentWire.sizeOfUnsignedVarInt(frame.packetId())
                    + SegmentWire.sizeOfUnsignedVarInt(payloadLength)
                    + payloadLength;
            if (length > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("frame record is too large");
            }
            return (int) length;
        }
    }

    /** Decodes the frames of one body in order. */
    static final class Decoder {
        private final TrackDescriptor track;
        private long previousElapsedNanos;
        private long previousServerTick;

        Decoder(TrackDescriptor track) {
            this.track = Objects.requireNonNull(track, "track");
        }

        RawPacketFrame read(InputStream input)
                throws IOException, CorruptReplayArtifactException {
            Objects.requireNonNull(input, "input");
            int recordLength = readUnsignedVarInt(input, "frame record length");
            if (recordLength <= 0) {
                throw corrupt("frame record length must be positive");
            }
            LimitedInputStream record = new LimitedInputStream(input, recordLength);
            long elapsedDelta = readUnsignedVarLong(record, "frame elapsed time");
            long tickDelta = unzigzag(readUnsignedVarLong(record, "frame server tick"));
            int sequence = readUnsignedVarInt(record, "frame sequence");
            int packetId = readUnsignedVarInt(record, "frame packet id");
            int payloadLength = readUnsignedVarInt(record, "frame payload length");
            if (payloadLength > MAX_PAYLOAD_LENGTH) {
                throw corrupt("frame payload exceeds maximum length");
            }
            if (payloadLength > record.remaining()) {
                throw corrupt("payload length exceeds its frame record");
            }
            byte[] payload = readBytes(record, payloadLength, "frame payload");
            if (record.remaining() != 0L) {
                throw corrupt("frame record contains trailing bytes");
            }
            long elapsedNanos;
            long serverTick;
            try {
                elapsedNanos = Math.addExact(previousElapsedNanos, elapsedDelta);
                serverTick = Math.addExact(previousServerTick, tickDelta);
            } catch (ArithmeticException exception) {
                throw corrupt("frame time or tick overflows", exception);
            }
            RawPacketFrame frame;
            try {
                frame = new RawPacketFrame(
                        elapsedNanos, serverTick, sequence, PacketPhase.PLAY,
                        packetId, payload, track.frameWorld());
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw corrupt("decoded frame values are invalid", exception);
            }
            previousElapsedNanos = elapsedNanos;
            previousServerTick = serverTick;
            return frame;
        }
    }

    private static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unzigzag(long value) {
        return (value >>> 1) ^ -(value & 1L);
    }

    static int readUnsignedVarInt(InputStream input, String field)
            throws IOException, CorruptReplayArtifactException {
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

    static long readUnsignedVarLong(InputStream input, String field)
            throws IOException, CorruptReplayArtifactException {
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

    static byte[] readBytes(InputStream input, int length, String field)
            throws IOException, CorruptReplayArtifactException {
        if (length < 0) {
            throw corrupt("negative length for " + field);
        }
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

    private static long addLength(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("frame record length overflow", exception);
        }
    }

    private static CorruptReplayArtifactException corrupt(String message) {
        return new CorruptReplayArtifactException(message);
    }

    private static CorruptReplayArtifactException corrupt(String message, Throwable cause) {
        return new CorruptReplayArtifactException(message, cause);
    }

    static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private long count;

        CountingInputStream(InputStream delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        long count() {
            return count;
        }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private LimitedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0L) {
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0L) {
                return -1;
            }
            int requested = (int) Math.min(remaining, length);
            int read = delegate.read(bytes, offset, requested);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        long remaining() {
            return remaining;
        }
    }
}
