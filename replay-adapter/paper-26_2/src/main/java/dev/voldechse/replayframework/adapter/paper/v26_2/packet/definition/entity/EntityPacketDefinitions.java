package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the entity protocol domain. */
public final class EntityPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id001AddEntityPacketDefinition(),
                    new Id035EntityPositionSyncPacketDefinition(),
                    new Id053MoveEntityPosPacketDefinition(),
                    new Id054MoveEntityPosRotPacketDefinition(),
                    new Id055MoveMinecartAlongTrackPacketDefinition(),
                    new Id056MoveEntityRotPacketDefinition(),
                    new Id057MoveVehiclePacketDefinition(),
                    new Id077RemoveEntitiesPacketDefinition());

    private EntityPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
