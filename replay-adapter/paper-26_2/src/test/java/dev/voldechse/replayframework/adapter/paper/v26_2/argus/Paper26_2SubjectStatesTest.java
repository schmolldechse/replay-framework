package dev.voldechse.replayframework.adapter.paper.v26_2.argus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_2SubjectStatesTest {

    private static final int MENU_SLOTS = 46;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void aPlayerReadingChangesOnlyTheSlotsThatDiffer() {
        List<ItemStack> before = empty();
        List<ItemStack> after = empty();
        after.set(36, item(Items.DIAMOND, 3));
        after.set(5, item(Items.NETHERITE_HELMET, 1));

        List<Object> changes = reading(after, 0).changesSince(reading(before, 0));

        assertEquals(2, changes.size());
        ClientboundContainerSetSlotPacket helmet = assertInstanceOf(ClientboundContainerSetSlotPacket.class, changes.get(0));
        assertEquals(0, helmet.getContainerId());
        assertEquals(5, helmet.getSlot());
        assertTrue(helmet.getItem().is(Items.NETHERITE_HELMET));
        assertEquals(36, ((ClientboundContainerSetSlotPacket) changes.get(1)).getSlot());
    }

    @Test
    void aChangedCountIsAChange() {
        List<ItemStack> before = empty();
        before.set(36, item(Items.DIAMOND, 3));
        List<ItemStack> after = empty();
        after.set(36, item(Items.DIAMOND, 2));

        List<Object> changes = reading(after, 0).changesSince(reading(before, 0));

        assertEquals(1, changes.size());
        assertEquals(2, ((ClientboundContainerSetSlotPacket) changes.get(0)).getItem().getCount());
    }

    @Test
    void aNewSelectedSlotIsAChange() {
        List<Object> changes = reading(empty(), 4).changesSince(reading(empty(), 0));

        assertEquals(1, changes.size());
        assertEquals(4, assertInstanceOf(ClientboundSetHeldSlotPacket.class, changes.get(0)).slot());
    }

    @Test
    void manyChangedSlotsAreSentAsTheWholeWindow() {
        List<ItemStack> after = empty();
        for (int slot = 9; slot < 9 + Paper26_2SubjectStates.MAX_SLOT_CHANGES + 1; slot++) {
            after.set(slot, item(Items.STONE, 64));
        }

        List<Object> changes = reading(after, 0).changesSince(reading(empty(), 0));

        assertEquals(1, changes.size());
        ClientboundContainerSetContentPacket content =
                assertInstanceOf(ClientboundContainerSetContentPacket.class, changes.get(0));
        assertEquals(MENU_SLOTS, content.items().size());
    }

    @Test
    void theFirstReadingCarriesTheCompleteState() {
        List<Object> packets = reading(empty(), 2).packets();

        assertEquals(4, packets.size());
        assertInstanceOf(ClientboundContainerSetContentPacket.class, packets.get(0));
        assertEquals(2, assertInstanceOf(ClientboundSetHeldSlotPacket.class, packets.get(1)).slot());
    }

    private static Paper26_2SubjectStates.PlayerReading reading(List<ItemStack> items, int selected) {
        return new Paper26_2SubjectStates.PlayerReading(1, items, selected, 20.0F, 20, 5.0F, 0.0F, 0, 0);
    }

    private static List<ItemStack> empty() {
        return new ArrayList<>(Collections.nCopies(MENU_SLOTS, ItemStack.EMPTY));
    }

    private static ItemStack item(Item type, int count) {
        var holder = type.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        }
        return new ItemStack(holder, count);
    }
}
