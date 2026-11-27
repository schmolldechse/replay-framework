package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the connection protocol domain. */
public final class ConnectionPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id000BundleDelimiterPacketDefinition(),
                    new Id011ChunkBatchFinishedPacketDefinition(),
                    new Id012ChunkBatchStartPacketDefinition(),
                    new Id021CookieRequestPacketDefinition(),
                    new Id024CustomPayloadPacketDefinition(),
                    new Id032DisconnectPacketDefinition(),
                    new Id045KeepAlivePacketDefinition(),
                    new Id050LoginPacketDefinition(),
                    new Id062PingPacketDefinition(),
                    new Id063PongResponsePacketDefinition(),
                    new Id081ResourcePackPopPacketDefinition(),
                    new Id082ResourcePackPushPacketDefinition(),
                    new Id120StartConfigurationPacketDefinition(),
                    new Id122StoreCookiePacketDefinition(),
                    new Id130TickingStatePacketDefinition(),
                    new Id131TickingStepPacketDefinition(),
                    new Id132TransferPacketDefinition(),
                    new Id139CustomReportDetailsPacketDefinition());

    private ConnectionPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
