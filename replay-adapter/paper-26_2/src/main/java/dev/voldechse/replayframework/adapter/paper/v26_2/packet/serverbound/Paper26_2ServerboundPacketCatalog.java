package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.chat.ChatServerboundPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection.ConnectionServerboundPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic.DiagnosticServerboundPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.entity.EntityServerboundPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory.InventoryServerboundPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.player.PlayerServerboundPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.ui.UiServerboundPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.world.WorldServerboundPacketDefinitions;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Stream;

/** Version-local catalog of explicit Paper 26.2 PLAY serverbound packet definitions. */
public final class Paper26_2ServerboundPacketCatalog {

    private static final ServerboundPacketDefinitionCatalog CATALOG =
            new ServerboundPacketDefinitionCatalog(Stream.of(
                            ChatServerboundPacketDefinitions.definitions(),
                            ConnectionServerboundPacketDefinitions.definitions(),
                            DiagnosticServerboundPacketDefinitions.definitions(),
                            EntityServerboundPacketDefinitions.definitions(),
                            InventoryServerboundPacketDefinitions.definitions(),
                            PlayerServerboundPacketDefinitions.definitions(),
                            UiServerboundPacketDefinitions.definitions(),
                            WorldServerboundPacketDefinitions.definitions())
                    .flatMap(Collection::stream)
                    .sorted(Comparator.comparingInt(ServerboundPacketDefinition::packetId))
                    .toList());

    private Paper26_2ServerboundPacketCatalog() {
    }

    public static ServerboundPacketDefinitionCatalog catalog() {
        return CATALOG;
    }
}
