package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.2 serverbound definitions owned by the diagnostic protocol domain. */
public final class DiagnosticServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id002BlockEntityTagQueryPacketDefinition(),
                    new Id023DebugSubscriptionRequestPacketDefinition(),
                    new Id025EntityTagQueryPacketDefinition(),
                    new Id027JigsawGeneratePacketDefinition(),
                    new Id054SetCommandBlockPacketDefinition(),
                    new Id055SetCommandMinecartPacketDefinition(),
                    new Id058SetJigsawBlockPacketDefinition(),
                    new Id059SetStructureBlockPacketDefinition(),
                    new Id060SetTestBlockPacketDefinition(),
                    new Id065TestInstanceBlockActionPacketDefinition());

    private DiagnosticServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
