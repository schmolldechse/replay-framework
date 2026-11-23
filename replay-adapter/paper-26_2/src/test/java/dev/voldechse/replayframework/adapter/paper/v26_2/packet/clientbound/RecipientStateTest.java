package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientState;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Pins which 26.2 packets describe the state of their recipient. */
class RecipientStateTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void exactlyTheRecipientsVitalsAndExperienceDescribeIt() {
        TreeSet<String> declared = new TreeSet<>();
        for (ClientboundPacketDefinition<?> definition : Paper26_2ClientboundPacketCatalog.catalog().definitions()) {
            if (definition.recipientState() != RecipientState.none()) {
                declared.add(definition.typeName().substring("play.clientbound.minecraft:".length()));
            }
        }

        // The inventory and the selected slot are read every tick instead,
        // because the server does not send every change of them.
        assertEquals(new TreeSet<>(List.of("set_experience", "set_health")), declared);
    }

    @Test
    void vitalsDescribeTheirRecipient() {
        assertTrue(state("set_health").describesRecipient(new ClientboundSetHealthPacket(20.0F, 20, 5.0F)));
    }

    private static RecipientState state(String packet) {
        return Paper26_2ClientboundPacketCatalog.catalog()
                .require("play.clientbound.minecraft:" + packet)
                .recipientState();
    }
}
