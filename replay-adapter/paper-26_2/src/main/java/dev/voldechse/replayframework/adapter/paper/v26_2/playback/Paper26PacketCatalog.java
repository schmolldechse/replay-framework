package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinitionCatalog;
import java.util.List;

/**
 * Version-local packet catalog.
 *
 * <p>No Paper 26.2 packet implementation is currently registered. PLAY packets
 * remain capturable but are classified as unsupported until a definition is
 * added explicitly.
 */
public final class Paper26PacketCatalog {

    private static final PacketDefinitionCatalog<Paper26ReplayPacketRewriter> CATALOG =
            new PacketDefinitionCatalog<>(List.of());

    private Paper26PacketCatalog() {}

    public static PacketDefinitionCatalog<Paper26ReplayPacketRewriter> catalog() {
        return CATALOG;
    }

    public static boolean explicitlyClassifies(String typeName) {
        return CATALOG.find(typeName).isPresent();
    }
}
