package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:player_info_remove}.
 *
 * <p>Every UUID is remapped into the replay session's identity space. This
 * allows profile cleanup to affect replay-owned entries without removing the
 * observer's profile or any other live player.</p>
 */
final class Id069PlayerInfoRemovePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundPlayerInfoRemovePacket,
        Id069PlayerInfoRemovePacketDefinition.Value> {

    static final int PACKET_ID = 0x45;
    static final String IDENTIFIER = "minecraft:player_info_remove";

    Id069PlayerInfoRemovePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundPlayerInfoRemovePacket.class);
    }

    @Override
    protected Value read(ClientboundPlayerInfoRemovePacket packet) {
        return new Value(packet.profileIds());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                value.profileIds().stream().map(context::mapUuid).toList()));
    }

    @Override
    protected ClientboundPlayerInfoRemovePacket rebuild(Value value) {
        return new ClientboundPlayerInfoRemovePacket(value.profileIds());
    }

    record Value(List<UUID> profileIds) {
        Value {
            profileIds = List.copyOf(Objects.requireNonNull(profileIds, "profileIds"));
        }
    }
}
