package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;

/**
 * Complete read/filter/rebuild contract for {@code minecraft:game_event}.
 *
 * <p>The event byte shares one packet with unrelated behaviors. Rain and
 * thunder changes describe the recorded world's presentation and are safe to
 * replay. The remaining known event types affect the recorded player's UI,
 * game mode, rules or login/respawn flow, so they stay local. Paper 26.2's
 * native decoder maps unknown event bytes to a null type; those are dropped
 * because their effect on this client version is not defined.</p>
 */
final class Id038GameEventPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundGameEventPacket,
        Id038GameEventPacketDefinition.Value> {

    static final int PACKET_ID = 0x26;
    static final String IDENTIFIER = "minecraft:game_event";

    Id038GameEventPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundGameEventPacket.class);
    }

    @Override
    protected Value read(ClientboundGameEventPacket packet) {
        return new Value(packet.getEvent(), packet.getParam());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        ClientboundGameEventPacket.Type event = value.event();
        if (event == null) {
            return ValueRewriteResult.drop("game_event contains an unknown event ID");
        }
        if (!isWeatherEvent(event)) {
            return ValueRewriteResult.drop(
                    "game_event changes the recorded player's or connection's state");
        }
        if (isWeatherLevelEvent(event)
                && (!Float.isFinite(value.parameter())
                        || value.parameter() < 0.0F
                        || value.parameter() > 1.0F)) {
            return ValueRewriteResult.fail(
                    "game_event weather level must be finite and within [0, 1]");
        }
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundGameEventPacket rebuild(Value value) {
        return new ClientboundGameEventPacket(value.event(), value.parameter());
    }

    private static boolean isWeatherEvent(ClientboundGameEventPacket.Type event) {
        return event == ClientboundGameEventPacket.START_RAINING
                || event == ClientboundGameEventPacket.STOP_RAINING
                || isWeatherLevelEvent(event);
    }

    private static boolean isWeatherLevelEvent(ClientboundGameEventPacket.Type event) {
        return event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE
                || event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE;
    }

    /** A null event is retained only so an unknown wire ID can be safely dropped. */
    record Value(ClientboundGameEventPacket.Type event, float parameter) {}
}
