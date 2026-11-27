package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the diagnostic protocol domain. */
public final class DiagnosticPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id026DebugBlockValuePacketDefinition(),
                    new Id027DebugChunkValuePacketDefinition(),
                    new Id028DebugEntityValuePacketDefinition(),
                    new Id029DebugEventPacketDefinition(),
                    new Id030DebugSamplePacketDefinition(),
                    new Id040GameTestHighlightPosPacketDefinition(),
                    new Id123TagQueryPacketDefinition(),
                    new Id126TestInstanceBlockStatusPacketDefinition());

    private DiagnosticPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
