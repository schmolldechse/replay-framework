package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.CaptureContext;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

/**
 * Replay policy for {@code minecraft:respawn}.
 *
 * <p>The packet changes the receiving player's world and local spawn state,
 * including dimension, game modes, death location, portal cooldown and the
 * attribute/entity-data retention mask. Forwarding it would respawn or
 * relocate the observer and alter their session state. It is therefore
 * recorded but dropped under the viewer-local playback policy.</p>
 */
final class Id082RespawnPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x52;
    static final String IDENTIFIER = "minecraft:respawn";

    Id082RespawnPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ClientboundRespawnPacket packet = ClientboundRespawnPacket.class.cast(decodedPacket);
        Key world = Key.key(packet.commonPlayerSpawnInfo().dimension().identifier().toString());
        state.world(connectionId, world.toString());
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

}
