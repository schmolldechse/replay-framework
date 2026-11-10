package dev.voldechse.replayframework.prototype.packet;

/** Low-level codec bound to one verified Paper 26.3 PLAY connection. */
public interface PacketCodecSession {

    Object decode(RawPacketFrame frame);

    EncodedPacket encode(Object packet);
}
