package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.2 serverbound definitions owned by the player protocol domain. */
public final class PlayerServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id000AcceptTeleportationPacketDefinition(),
                    new Id004ChangeDifficultyPacketDefinition(),
                    new Id005ChangeGameModePacketDefinition(),
                    new Id012ClientCommandPacketDefinition(),
                    new Id029LockDifficultyPacketDefinition(),
                    new Id030MovePlayerPosPacketDefinition(),
                    new Id031MovePlayerPosRotPacketDefinition(),
                    new Id032MovePlayerRotPacketDefinition(),
                    new Id033MovePlayerStatusOnlyPacketDefinition(),
                    new Id034MoveVehiclePacketDefinition(),
                    new Id035PaddleBoatPacketDefinition(),
                    new Id040PlayerAbilitiesPacketDefinition(),
                    new Id042PlayerCommandPacketDefinition(),
                    new Id043PlayerInputPacketDefinition(),
                    new Id062SpectatorActionPacketDefinition(),
                    new Id064TeleportToEntityPacketDefinition());

    private PlayerServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
