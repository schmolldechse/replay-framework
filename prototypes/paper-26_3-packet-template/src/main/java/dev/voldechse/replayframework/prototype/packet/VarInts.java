package dev.voldechse.replayframework.prototype.packet;

import io.netty.buffer.ByteBuf;

/** Bounded protocol VarInt helpers shared by codec and channel writer. */
final class VarInts {

    private VarInts() {}

    static int read(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            if (!buffer.isReadable()) {
                throw new IllegalArgumentException("buffer contains no complete VarInt");
            }
            int current = buffer.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt exceeds five bytes");
    }

    static void write(ByteBuf buffer, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt value must not be negative");
        }
        while ((value & ~0x7F) != 0) {
            buffer.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }
}
