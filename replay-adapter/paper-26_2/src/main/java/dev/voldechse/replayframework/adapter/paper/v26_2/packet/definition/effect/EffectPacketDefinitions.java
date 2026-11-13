package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the scene-effect protocol domain. */
public final class EffectPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id002AnimatePacketDefinition(),
                    new Id005BlockDestructionPacketDefinition(),
                    new Id009BossEventPacketDefinition(),
                    new Id025DamageEventPacketDefinition());

    private EffectPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
