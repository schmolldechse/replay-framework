package dev.voldechse.replayframework.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Package-private wire codec shared by segment and checkpoint bodies.
 *
 * <p>The record layout is deliberately kept in one place so both artifact
 * types preserve the exact Task-7 frame representation.</p>
 */
final class ReplayFrameCodec {

    private ReplayFrameCodec() {
    }

    static long encodedRecordLength(RawPacketFrame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] payload = frame.payload();
        long recordLength = 0L;
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarLong(frame.elapsedNanos()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarLong(frame.serverTick()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(frame.sequence()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(frame.phase().wireCode()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(frame.packetId()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(payload.length));
        recordLength = addLength(recordLength, payload.length);
        if (recordLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("frame record is too large");
        }
        return addLength(
                SegmentWire.sizeOfUnsignedVarInt((int) recordLength), recordLength);
    }

    static void writeRecord(OutputStream output, RawPacketFrame frame) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(frame, "frame");
        byte[] payload = frame.payload();
        long recordLength = recordBodyLength(frame, payload);
        SegmentWire.writeUnsignedVarInt(output, (int) recordLength);
        SegmentWire.writeUnsignedVarLong(output, frame.elapsedNanos());
        SegmentWire.writeUnsignedVarLong(output, frame.serverTick());
        SegmentWire.writeUnsignedVarInt(output, frame.sequence());
        SegmentWire.writeUnsignedVarInt(output, frame.phase().wireCode());
        SegmentWire.writeUnsignedVarInt(output, frame.packetId());
        SegmentWire.writeUnsignedVarInt(output, payload.length);
        output.write(payload);
    }

    static RawPacketFrame readRecord(InputStream input)
            throws IOException, CorruptReplayArtifactException {
        Objects.requireNonNull(input, "input");
        int recordLength = readUnsignedVarInt(input, "frame record length");
        LimitedInputStream record = new LimitedInputStream(input, recordLength);
        long elapsedNanos = readUnsignedVarLong(record, "frame elapsed time");
        long serverTick = readUnsignedVarLong(record, "frame server tick");
        int sequence = readUnsignedVarInt(record, "frame sequence");
        int phaseCode = readUnsignedVarInt(record, "frame phase");
        PacketPhase phase;
        try {
            phase = PacketPhase.fromWireCode(phaseCode);
        } catch (IllegalArgumentException exception) {
            throw corrupt("unknown frame phase code: " + phaseCode, exception);
        }
        int packetId = readUnsignedVarInt(record, "frame packet id");
        int payloadLength = readUnsignedVarInt(record, "frame payload length");
        if (payloadLength > record.remaining()) {
            throw corrupt("payload length exceeds its frame record");
        }
        byte[] payload = readBytes(record, payloadLength, "frame payload");
        if (record.remaining() != 0L) {
            throw corrupt("frame record contains trailing bytes");
        }
        try {
            return new RawPacketFrame(
                    elapsedNanos, serverTick, sequence, phase, packetId, payload);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw corrupt("decoded frame values are invalid", exception);
        }
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

    private static long recordBodyLength(RawPacketFrame frame, byte[] payload) {
        long recordLength = 0L;
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarLong(frame.elapsedNanos()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarLong(frame.serverTick()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(frame.sequence()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(frame.phase().wireCode()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(frame.packetId()));
        recordLength = addLength(recordLength,
                SegmentWire.sizeOfUnsignedVarInt(payload.length));
        recordLength = addLength(recordLength, payload.length);
        if (recordLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("frame record is too large");
        }
        return recordLength;
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
