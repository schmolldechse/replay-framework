package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.2 serverbound definitions owned by the connection protocol domain. */
public final class ConnectionServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id011ChunkBatchReceivedPacketDefinition(),
                    new Id013ClientTickEndPacketDefinition(),
                    new Id014ClientInformationPacketDefinition(),
                    new Id016ConfigurationAcknowledgedPacketDefinition(),
                    new Id021CookieResponsePacketDefinition(),
                    new Id022CustomPayloadPacketDefinition(),
                    new Id028KeepAlivePacketDefinition(),
                    new Id038PingRequestPacketDefinition(),
                    new Id044PlayerLoadedPacketDefinition(),
                    new Id045PongPacketDefinition(),
                    new Id049ResourcePackPacketDefinition(),
                    new Id068CustomClickActionPacketDefinition());

    private ConnectionServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
