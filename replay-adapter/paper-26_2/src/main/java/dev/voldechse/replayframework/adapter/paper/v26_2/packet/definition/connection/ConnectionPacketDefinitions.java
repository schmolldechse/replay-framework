package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the connection protocol domain. */
public final class ConnectionPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id000BundleDelimiterPacketDefinition(),
                    new Id011ChunkBatchFinishedPacketDefinition(),
                    new Id012ChunkBatchStartPacketDefinition(),
                    new Id021CookieRequestPacketDefinition(),
                    new Id024CustomPayloadPacketDefinition(),
                    new Id032DisconnectPacketDefinition(),
                    new Id044KeepAlivePacketDefinition(),
                    new Id049LoginPacketDefinition(),
                    new Id061PingPacketDefinition(),
                    new Id062PongResponsePacketDefinition(),
                    new Id080ResourcePackPopPacketDefinition(),
                    new Id081ResourcePackPushPacketDefinition(),
                    new Id118StartConfigurationPacketDefinition(),
                    new Id120StoreCookiePacketDefinition());

    private ConnectionPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
