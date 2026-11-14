package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.world.level.storage.LevelData;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_default_spawn_position}.
 *
 * <p>Paper 26.2 carries dimension-scoped spawn position and orientation in
 * {@link LevelData.RespawnData}; preserve all four parts as checkpointed scene
 * state.</p>
 */
final class Id097SetDefaultSpawnPositionPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetDefaultSpawnPositionPacket,
        Id097SetDefaultSpawnPositionPacketDefinition.Value> {

    static final int PACKET_ID = 0x61;
    static final String IDENTIFIER = "minecraft:set_default_spawn_position";

    Id097SetDefaultSpawnPositionPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetDefaultSpawnPositionPacket.class);
    }

    @Override
    protected Value read(ClientboundSetDefaultSpawnPositionPacket packet) {
        return new Value(packet.respawnData());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
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
}
