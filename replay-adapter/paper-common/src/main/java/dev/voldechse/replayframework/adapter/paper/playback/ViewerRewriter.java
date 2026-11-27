package dev.voldechse.replayframework.adapter.paper.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.OptionalInt;

/**
 * Turns recorded packets into packets for one viewer: each packet's
 * definition applies its playback policy, and entity IDs and UUIDs move into
 * the playback session's own identity space. Implemented once per version.
 */
public interface ViewerRewriter {

    /** Decides and rebuilds one recorded frame through its definition. */
    PacketRewriteResult rewrite(RawPacketFrame frame, PacketDescriptor descriptor, PacketCodec<Object> codec);

    /** Encodes a rebuilt packet as the descriptor's packet. */
    PacketCodec.EncodedPacket encode(Object packet, PacketDescriptor descriptor, PacketCodec<Object> codec);

    /** The recorded entity ID behind an entity ID the viewer's client knows. */
    OptionalInt recordedEntityId(int clientEntityId);

    /** Forgets what the view showed; identities stay stable. */
    void resetView();

    /** Forgets everything when the playback closes. */
    void clear();
}
