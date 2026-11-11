package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinitionCatalog;
import java.util.List;

/**
 * Version-local packet catalog.
 *
 * <p>No Paper 26.3 packet implementation is currently registered. PLAY packets
 * remain capturable but are classified as unsupported until a definition is
 * added explicitly.
 */
public final class Paper26_3PacketCatalog {

    private static final PacketDefinitionCatalog<Paper26_3ReplayPacketRewriter> CATALOG =
            new PacketDefinitionCatalog<>(List.of());

    private Paper26_3PacketCatalog() {}

    public static PacketDefinitionCatalog<Paper26_3ReplayPacketRewriter> catalog() {
        return CATALOG;
    }

    public static boolean explicitlyClassifies(String typeName) {
        return CATALOG.find(typeName).isPresent();
    }
}
