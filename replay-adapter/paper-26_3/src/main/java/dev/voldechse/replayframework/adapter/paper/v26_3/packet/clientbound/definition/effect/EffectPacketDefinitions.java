package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.effect;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the scene-effect protocol domain. */
public final class EffectPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id002AnimatePacketDefinition(),
                    new Id123SwingAnimationPacketDefinition(),
                    new Id005BlockDestructionPacketDefinition(),
                    new Id009BossEventPacketDefinition(),
                    new Id025DamageEventPacketDefinition(),
                    new Id034EntityEventPacketDefinition(),
                    new Id036ExplodePacketDefinition(),
                    new Id043HurtAnimationPacketDefinition(),
                    new Id048LevelParticlesPacketDefinition(),
                    new Id079RemoveMobEffectPacketDefinition(),
                    new Id118SoundEntityPacketDefinition(),
                    new Id119SoundPacketDefinition(),
                    new Id121StopSoundPacketDefinition(),
                    new Id127TakeItemEntityPacketDefinition(),
                    new Id135UpdateMobEffectPacketDefinition());

    private EffectPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
