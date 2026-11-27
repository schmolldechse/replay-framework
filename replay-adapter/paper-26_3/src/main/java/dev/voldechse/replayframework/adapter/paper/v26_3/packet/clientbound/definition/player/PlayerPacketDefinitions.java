package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the player protocol domain. */
public final class PlayerPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id003AwardStatsPacketDefinition(),
                    new Id083PostEffectsPacketDefinition(),
                    new Id065PlayerAbilitiesPacketDefinition(),
                    new Id067PlayerCombatEndPacketDefinition(),
                    new Id068PlayerCombatEnterPacketDefinition(),
                    new Id069PlayerCombatKillPacketDefinition(),
                    new Id070PlayerInfoRemovePacketDefinition(),
                    new Id071PlayerInfoUpdatePacketDefinition(),
                    new Id072PlayerLookAtPacketDefinition(),
                    new Id073PlayerPositionPacketDefinition(),
                    new Id074PlayerRotationPacketDefinition(),
                    new Id084RespawnPacketDefinition(),
                    new Id095SetCameraPacketDefinition(),
                    new Id105SetExperiencePacketDefinition(),
                    new Id106SetHealthPacketDefinition(),
                    new Id107SetHeldSlotPacketDefinition(),
                    new Id133UpdateAdvancementsPacketDefinition());

    private PlayerPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
