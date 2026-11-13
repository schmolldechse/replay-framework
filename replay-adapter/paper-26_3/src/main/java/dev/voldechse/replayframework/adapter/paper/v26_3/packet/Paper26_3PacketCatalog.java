package dev.voldechse.replayframework.adapter.paper.v26_3.packet;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.connection.ConnectionPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect.EffectPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.entity.EntityPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.player.PlayerPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.ui.UiPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.world.WorldPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Comparator;
import java.util.stream.Stream;

/** Version-local catalog of explicit Paper 26.3 PLAY packet definitions. */
public final class Paper26_3PacketCatalog {

    private static final PacketDefinitionCatalog<Paper26_3ReplayPacketRewriter> CATALOG =
            new PacketDefinitionCatalog<>(Stream.concat(
                            Stream.concat(
                                    Stream.concat(
                                            ConnectionPacketDefinitions.definitions().stream(),
                                            EntityPacketDefinitions.definitions().stream()),
                                    Stream.concat(
                                            EffectPacketDefinitions.definitions().stream(),
                                            PlayerPacketDefinitions.definitions().stream())),
                            Stream.concat(
                                    WorldPacketDefinitions.definitions().stream(),
                                    UiPacketDefinitions.definitions().stream()))
                    .sorted(Comparator.comparingInt(PacketDefinition::packetId))
                    .toList());

    private Paper26_3PacketCatalog() {}

    public static PacketDefinitionCatalog<Paper26_3ReplayPacketRewriter> catalog() {
        return CATALOG;
    }

    public static boolean explicitlyClassifies(String typeName) {
        return CATALOG.find(typeName).isPresent();
    }
}
