package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:post_effects}.
 *
 * <p>Paper 26.3 sends a player's own screen post effects only to that
 * player's connection ({@code ServerPlayer.sendPostEffects}). They describe
 * one player's view, not the scene; replaying them would change the
 * viewer's own screen. The {@link ClientboundPacketDefinition} base handles
 * this scope before payload decode. The same packet in the CONFIGURATION
 * phase is connection control, like every packet outside PLAY.</p>
 */
final class Id083PostEffectsPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x53;
    static final String IDENTIFIER = "minecraft:post_effects";

    Id083PostEffectsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
