package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the entity protocol domain. */
public final class EntityPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id001AddEntityPacketDefinition(),
                    new Id035EntityPositionSyncPacketDefinition(),
                    new Id054MoveEntityPosPacketDefinition(),
                    new Id055MoveEntityPosRotPacketDefinition(),
                    new Id056MoveMinecartAlongTrackPacketDefinition(),
                    new Id057MoveEntityRotPacketDefinition(),
                    new Id058MoveVehiclePacketDefinition(),
                    new Id078RemoveEntitiesPacketDefinition(),
                    new Id085RotateHeadPacketDefinition(),
                    new Id101SetEntityDataPacketDefinition(),
                    new Id102SetEntityLinkPacketDefinition(),
                    new Id103SetEntityMotionPacketDefinition(),
                    new Id104SetEquipmentPacketDefinition(),
                    new Id109SetPassengersPacketDefinition(),
                    new Id128TeleportEntityPacketDefinition(),
                    new Id134UpdateAttributesPacketDefinition(),
                    new Id138ProjectilePowerPacketDefinition());

    private EntityPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
