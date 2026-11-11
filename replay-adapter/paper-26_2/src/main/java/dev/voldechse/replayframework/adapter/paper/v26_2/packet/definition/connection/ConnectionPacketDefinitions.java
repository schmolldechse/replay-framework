package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the connection protocol domain. */
public final class ConnectionPacketDefinitions {

    private static final List<PacketDefinition<Paper26ReplayPacketRewriter>> DEFINITIONS =
            List.of(new BundleDelimiterPacketDefinition());

    private ConnectionPacketDefinitions() {}

    public static List<PacketDefinition<Paper26ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
