package dev.voldechse.replayframework.adapter.paper.v26_2.packet;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection.ConnectionPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity.EntityPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.stream.Stream;

/** Version-local catalog of explicit Paper 26.2 PLAY packet definitions. */
public final class Paper26_2PacketCatalog {

    private static final PacketDefinitionCatalog<Paper26_2ReplayPacketRewriter> CATALOG =
            new PacketDefinitionCatalog<>(Stream.concat(
                            ConnectionPacketDefinitions.definitions().stream(),
                            EntityPacketDefinitions.definitions().stream())
                    .toList());

    private Paper26_2PacketCatalog() {}

    public static PacketDefinitionCatalog<Paper26_2ReplayPacketRewriter> catalog() {
        return CATALOG;
    }

    public static boolean explicitlyClassifies(String typeName) {
        return CATALOG.find(typeName).isPresent();
    }
}
