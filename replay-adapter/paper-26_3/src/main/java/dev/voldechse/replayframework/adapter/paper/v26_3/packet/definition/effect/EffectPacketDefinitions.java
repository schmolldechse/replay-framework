package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the scene-effect protocol domain. */
public final class EffectPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id002AnimatePacketDefinition(),
                    new Id005BlockDestructionPacketDefinition(),
                    new Id009BossEventPacketDefinition(),
                    new Id025DamageEventPacketDefinition());

    private EffectPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
