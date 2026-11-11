package dev.voldechse.replayframework.adapter.paper.v26_2.packet;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection.ConnectionPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26ReplayPacketRewriter;

/** Version-local catalog of explicit Paper 26.2 PLAY packet definitions. */
public final class Paper26PacketCatalog {

    private static final PacketDefinitionCatalog<Paper26ReplayPacketRewriter> CATALOG =
            new PacketDefinitionCatalog<>(ConnectionPacketDefinitions.definitions());

    private Paper26PacketCatalog() {}

    public static PacketDefinitionCatalog<Paper26ReplayPacketRewriter> catalog() {
        return CATALOG;
    }

    public static boolean explicitlyClassifies(String typeName) {
        return CATALOG.find(typeName).isPresent();
    }
}
