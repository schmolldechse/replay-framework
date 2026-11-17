package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
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
 *
 * <p>The packet carries no entity ID; it always targets the receiving
 * player's own entity. Unless {@link ClientboundRespawnPacket#KEEP_ENTITY_DATA}
 * is set, the client discards that entity and builds a new one, which is the
 * case for a respawn after death. Capture marks the connection so that the
 * following {@code minecraft:player_position} can replace the scene entity in
 * the neutral stream. A respawn that keeps entity data, such as a dimension
 * change, does not request that replacement.</p>
 */
final class Id082RespawnPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

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
        // The respawn ends a death teardown of this connection's view. Only a
        // respawn into the same world rebuilds the chunks and entities whose
        // unloads were withheld.
        boolean sameWorld = state.world(connectionId)
                .map(world.toString()::equals)
                .orElse(true);
        state.viewTeardown().playerRespawned(connectionId, serverTick, sameWorld);
        state.world(connectionId, world.toString());
        if (!packet.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA)) {
            state.markRespawnPending(connectionId);
        }
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

}
