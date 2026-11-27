package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.3 serverbound definitions owned by the diagnostic protocol domain. */
public final class DiagnosticServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id002BlockEntityTagQueryPacketDefinition(),
                    new Id023DebugSubscriptionRequestPacketDefinition(),
                    new Id025EntityTagQueryPacketDefinition(),
                    new Id027JigsawGeneratePacketDefinition(),
                    new Id055SetCommandBlockPacketDefinition(),
                    new Id056SetCommandMinecartPacketDefinition(),
                    new Id059SetJigsawBlockPacketDefinition(),
                    new Id060SetStructureBlockPacketDefinition(),
                    new Id061SetTestBlockPacketDefinition(),
                    new Id065TestInstanceBlockActionPacketDefinition());

    private DiagnosticServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
