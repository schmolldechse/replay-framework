package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the connection protocol domain. */
public final class ConnectionPacketDefinitions {

    private static final List<PacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id000BundleDelimiterPacketDefinition(),
                    new Id011ChunkBatchFinishedPacketDefinition(),
                    new Id012ChunkBatchStartPacketDefinition(),
                    new Id021CookieRequestPacketDefinition());

    private ConnectionPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
