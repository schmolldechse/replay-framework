package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientState;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.world.item.ItemStack;
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
    void exactlyTheRecipientsInventoryVitalsAndExperienceDescribeIt() {
        TreeSet<String> declared = new TreeSet<>();
        for (ClientboundPacketDefinition<?> definition : Paper26_2ClientboundPacketCatalog.catalog().definitions()) {
            if (definition.recipientState() != RecipientState.none()) {
                declared.add(definition.typeName().substring("play.clientbound.minecraft:".length()));
            }
        }

        assertEquals(new TreeSet<>(List.of(
                "container_set_content", "container_set_slot", "set_experience",
                "set_health", "set_held_slot", "set_player_inventory")), declared);
    }

    @Test
    void onlyTheRecipientsOwnInventoryWindowBelongsToItsState() {
        RecipientState content = state("container_set_content");
        RecipientState slot = state("container_set_slot");

        assertTrue(content.describesRecipient(
                new ClientboundContainerSetContentPacket(0, 1, List.of(), ItemStack.EMPTY)));
        assertFalse(content.describesRecipient(
                new ClientboundContainerSetContentPacket(3, 1, List.of(), ItemStack.EMPTY)));
        assertTrue(slot.describesRecipient(new ClientboundContainerSetSlotPacket(0, 1, 36, ItemStack.EMPTY)));
        assertFalse(slot.describesRecipient(new ClientboundContainerSetSlotPacket(3, 1, 0, ItemStack.EMPTY)));
        assertTrue(state("set_health").describesRecipient(new ClientboundSetHealthPacket(20.0F, 20, 5.0F)));
    }

    private static RecipientState state(String packet) {
        return Paper26_2ClientboundPacketCatalog.catalog()
                .require("play.clientbound.minecraft:" + packet)
                .recipientState();
    }
}
