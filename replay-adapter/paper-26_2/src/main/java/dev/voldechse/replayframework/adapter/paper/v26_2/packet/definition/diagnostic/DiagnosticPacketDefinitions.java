package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the diagnostic protocol domain. */
public final class DiagnosticPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id026DebugBlockValuePacketDefinition(),
                    new Id027DebugChunkValuePacketDefinition(),
                    new Id028DebugEntityValuePacketDefinition(),
                    new Id029DebugEventPacketDefinition(),
                    new Id030DebugSamplePacketDefinition());

    private DiagnosticPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
