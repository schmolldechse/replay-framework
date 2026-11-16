package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.DerivedScenePacket;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Replay policy for {@code minecraft:player_position}.
 *
 * <p>This is a server correction for the receiving player's own position,
 * velocity, and rotation. Its teleport ID also belongs to that connection's
 * confirmation flow. Replaying it would move the observer and mix the
 * recording's connection state into the observer's session, so the shared
 * viewer-local policy drops it before decoding.</p>
 *
 * <p>The first absolute correction after a {@code minecraft:respawn} that
 * discards the client's entity data is the spawn placement of the receiving
 * player's new client entity. Other tracking players learn about that
 * replacement through {@code remove_entities} and {@code add_entity}, but the
 * respawning connection itself never receives them. Capture therefore derives
 * exactly that pair for the connection's own entity from this packet's
 * position and rotation, so the neutral stream replaces the scene entity even
 * when no other player observed the respawn. Without the replacement a client
 * entity that played its death animation keeps that state permanently.</p>
 */
final class Id072PlayerPositionPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x48;
    static final String IDENTIFIER = "minecraft:player_position";

    Id072PlayerPositionPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

    @Override
    public List<DerivedScenePacket> derivedScenePackets(
            Object decodedPacket,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        super.derivedScenePackets(decodedPacket, connectionId, state, serverTick);
        ClientboundPlayerPositionPacket packet =
                ClientboundPlayerPositionPacket.class.cast(decodedPacket);
        // A relative correction carries no spawn placement; the respawn stays
        // pending until the absolute teleport that Paper sends right after it.
        if (connectionId == null || !packet.relatives().isEmpty()) {
            return List.of();
        }
        if (!state.consumeRespawnPending(connectionId)) {
            return List.of();
        }
        OptionalInt entityId = state.connectionEntityId(connectionId);
        Optional<String> world = state.world(connectionId);
        if (entityId.isEmpty() || world.isEmpty()) {
            return List.of();
        }

        Vec3 position = packet.change().position();
        float yaw = packet.change().yRot();
        float pitch = packet.change().xRot();
        PacketCaptureState.EntityPosition scenePosition = new PacketCaptureState.EntityPosition(
                world.get(), position.x, position.y, position.z);
        state.entityPosition(
                connectionId, entityId.getAsInt(), world.get(), position.x, position.y, position.z);
        state.markEntityReplaced(entityId.getAsInt(), serverTick);

        // The PacketEvents connection ID is the player's profile UUID, which
        // is also the UUID of the player entity.
        return List.of(
                new DerivedScenePacket(
                        new ClientboundRemoveEntitiesPacket(entityId.getAsInt()),
                        scenePosition),
                new DerivedScenePacket(
                        new ClientboundAddEntityPacket(
                                entityId.getAsInt(),
                                connectionId,
                                position.x,
                                position.y,
                                position.z,
                                pitch,
                                yaw,
                                EntityTypes.PLAYER,
                                0,
                                Vec3.ZERO,
                                yaw),
                        scenePosition));
    }
}
