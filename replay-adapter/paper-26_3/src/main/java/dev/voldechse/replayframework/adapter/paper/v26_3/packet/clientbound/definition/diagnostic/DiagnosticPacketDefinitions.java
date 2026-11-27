package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the diagnostic protocol domain. */
public final class DiagnosticPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id026DebugBlockValuePacketDefinition(),
                    new Id027DebugChunkValuePacketDefinition(),
                    new Id028DebugEntityValuePacketDefinition(),
                    new Id029DebugEventPacketDefinition(),
                    new Id030DebugSamplePacketDefinition(),
                    new Id041GameTestHighlightPosPacketDefinition(),
                    new Id126TagQueryPacketDefinition(),
                    new Id129TestInstanceBlockStatusPacketDefinition());

    private DiagnosticPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
