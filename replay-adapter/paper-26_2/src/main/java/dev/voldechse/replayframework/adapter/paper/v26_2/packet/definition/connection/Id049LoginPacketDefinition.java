package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;

/**
 * Captured connection/world initialization contract for {@code minecraft:login}.
 *
 * <p>The PLAY login packet assigns the local player entity and installs the
 * available dimensions and common spawn information. Sending a recorded login
 * into an established playback connection would replace the observer's active
 * player/world context, so preserve the raw frame and block it before decoding.</p>
 */
final class Id049LoginPacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x31;
    static final String IDENTIFIER = "minecraft:login";

    Id049LoginPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ClientboundLoginPacket packet = ClientboundLoginPacket.class.cast(decodedPacket);
        Key world = Key.key(packet.commonPlayerSpawnInfo().dimension().identifier().toString());
        state.world(connectionId, world.toString());
        state.connectionEntityId(connectionId, packet.playerId());
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

}
