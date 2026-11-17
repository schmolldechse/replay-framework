package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the player protocol domain. */
public final class PlayerPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id003AwardStatsPacketDefinition(),
                    new Id064PlayerAbilitiesPacketDefinition(),
                    new Id066PlayerCombatEndPacketDefinition(),
                    new Id067PlayerCombatEnterPacketDefinition(),
                    new Id068PlayerCombatKillPacketDefinition(),
                    new Id069PlayerInfoRemovePacketDefinition(),
                    new Id070PlayerInfoUpdatePacketDefinition(),
                    new Id071PlayerLookAtPacketDefinition(),
                    new Id072PlayerPositionPacketDefinition(),
                    new Id073PlayerRotationPacketDefinition(),
                    new Id082RespawnPacketDefinition(),
                    new Id093SetCameraPacketDefinition(),
                    new Id103SetExperiencePacketDefinition(),
                    new Id104SetHealthPacketDefinition(),
                    new Id105SetHeldSlotPacketDefinition(),
                    new Id130UpdateAdvancementsPacketDefinition());

    private PlayerPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
