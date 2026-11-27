package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.chat.ChatPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.connection.ConnectionPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.diagnostic.DiagnosticPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.effect.EffectPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.entity.EntityPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.player.PlayerPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.scoreboard.ScoreboardPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui.UiPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world.WorldPacketDefinitions;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Stream;

/** Version-local catalog of explicit Paper 26.2 PLAY packet definitions. */
public final class Paper26_2ClientboundPacketCatalog {

    private static final ClientboundPacketDefinitionCatalog<Paper26_2ReplayPacketRewriter> CATALOG =
            new ClientboundPacketDefinitionCatalog<>(Stream.of(
                            ChatPacketDefinitions.definitions(),
                            ConnectionPacketDefinitions.definitions(),
                            DiagnosticPacketDefinitions.definitions(),
                            EffectPacketDefinitions.definitions(),
                            EntityPacketDefinitions.definitions(),
                            PlayerPacketDefinitions.definitions(),
                            ScoreboardPacketDefinitions.definitions(),
                            UiPacketDefinitions.definitions(),
                            WorldPacketDefinitions.definitions())
                    .flatMap(Collection::stream)
                    .sorted(Comparator.comparingInt(ClientboundPacketDefinition::packetId))
                    .toList());

    private Paper26_2ClientboundPacketCatalog() {
    }

    public static ClientboundPacketDefinitionCatalog<Paper26_2ReplayPacketRewriter> catalog() {
        return CATALOG;
    }

    public static boolean explicitlyClassifies(String typeName) {
        return CATALOG.find(typeName).isPresent();
    }
}
