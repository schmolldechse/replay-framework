package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.effect;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the scene-effect protocol domain. */
public final class EffectPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id002AnimatePacketDefinition(),
                    new Id005BlockDestructionPacketDefinition(),
                    new Id009BossEventPacketDefinition(),
                    new Id025DamageEventPacketDefinition(),
                    new Id034EntityEventPacketDefinition(),
                    new Id036ExplodePacketDefinition(),
                    new Id042HurtAnimationPacketDefinition(),
                    new Id047LevelParticlesPacketDefinition(),
                    new Id078RemoveMobEffectPacketDefinition(),
                    new Id116SoundEntityPacketDefinition(),
                    new Id117SoundPacketDefinition(),
                    new Id119StopSoundPacketDefinition(),
                    new Id124TakeItemEntityPacketDefinition(),
                    new Id132UpdateMobEffectPacketDefinition());

    private EffectPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
