package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.world.level.storage.LevelData;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_default_spawn_position}.
 *
 * <p>Paper 26.3 carries dimension-scoped spawn position and orientation in
 * {@link LevelData.RespawnData}; preserve all four parts as checkpointed scene
 * state.</p>
 */
final class Id099SetDefaultSpawnPositionPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSetDefaultSpawnPositionPacket,
        Id099SetDefaultSpawnPositionPacketDefinition.Value> {

    static final int PACKET_ID = 0x63;
    static final String IDENTIFIER = "minecraft:set_default_spawn_position";

    Id099SetDefaultSpawnPositionPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetDefaultSpawnPositionPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.DIMENSION_SPAWN);
    }

    @Override
    protected Value read(ClientboundSetDefaultSpawnPositionPacket packet) {
        return new Value(packet.respawnData());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetDefaultSpawnPositionPacket rebuild(Value value) {
        return new ClientboundSetDefaultSpawnPositionPacket(value.respawnData());
    }

    record Value(LevelData.RespawnData respawnData) {

        Value {
            Objects.requireNonNull(respawnData, "respawnData");
        }
    }

    /** One packet object for every player. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.identicalPayload();
    }
}
