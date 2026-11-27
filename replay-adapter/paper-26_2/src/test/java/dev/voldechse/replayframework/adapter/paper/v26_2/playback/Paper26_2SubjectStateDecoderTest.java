package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_2SubjectStateDecoderTest {

    private static final RecordedSubject PLAYER = RecordedSubject.player(UUID.randomUUID());
    private static final RecordedSubject LLAMA = RecordedSubject.entity(UUID.randomUUID());

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void buildsAPlayerFromItsInventoryWindowAndLaterChanges() {
        List<ItemStack> window = new ArrayList<>(Collections.nCopies(46, ItemStack.EMPTY));
        window.set(5, item(Items.IRON_HELMET));
        window.set(9, item(Items.STONE, 32));
        window.set(36, item(Items.DIAMOND_SWORD));
        window.set(45, item(Items.SHIELD));

        SubjectState state = Paper26_2SubjectStateDecoder.decode(PLAYER, List.of(
                new ClientboundContainerSetContentPacket(0, 1, window, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(0, 2, 37, item(Items.BREAD, 5)),
                new ClientboundSetPlayerInventoryPacket(35, item(Items.TORCH, 16)),
                new ClientboundSetHeldSlotPacket(1),
                new ClientboundSetHealthPacket(15.0F, 18, 2.5F),
                new ClientboundSetExperiencePacket(0.5F, 30, 3))).orElseThrow();

        assertEquals(Material.IRON_HELMET, state.equipment().get(EquipmentSlot.HEAD).getType());
        assertEquals(Material.SHIELD, state.equipment().get(EquipmentSlot.OFF_HAND).getType());
        assertEquals(Material.BREAD, state.equipment().get(EquipmentSlot.HAND).getType());
        SubjectState.Section hotbar = state.section(SubjectState.HOTBAR).orElseThrow();
        assertEquals(9, hotbar.items().size());
        assertEquals(OptionalInt.of(1), hotbar.selected());
        assertEquals(Material.DIAMOND_SWORD, hotbar.items().get(0).getType());
        SubjectState.Section main = state.section(SubjectState.INVENTORY).orElseThrow();
        assertEquals(27, main.items().size());
        assertEquals(32, main.items().get(0).getAmount());
        assertEquals(Material.TORCH, main.items().get(26).getType());
        assertEquals(new SubjectState.Vitals(15.0, 18, 2.5F), state.vitals().orElseThrow());
        assertEquals(new SubjectState.Experience(3, 0.5F, 30), state.experience().orElseThrow());
    }

    @Test
    void anEntityHoldsItsContainer() {
        SubjectState state = Paper26_2SubjectStateDecoder.decode(LLAMA, List.of(
                new ClientboundContainerSetContentPacket(0, 0, List.of(ItemStack.EMPTY), ItemStack.EMPTY),
                new ClientboundContainerSetContentPacket(0, 0,
                        List.of(item(Items.WHEAT, 3), ItemStack.EMPTY), ItemStack.EMPTY)))
                .orElseThrow();

        assertTrue(state.equipment().isEmpty());
        SubjectState.Section container = state.section(SubjectState.CONTAINER).orElseThrow();
        assertEquals(2, container.items().size());
        assertEquals(Material.WHEAT, container.items().get(0).getType());
    }

    private static ItemStack item(net.minecraft.world.item.Item type) {
        return item(type, 1);
    }

    private static ItemStack item(net.minecraft.world.item.Item type, int count) {
        var holder = type.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        }
        return new ItemStack(holder, count);
    }

    @Test
    void knowsNothingWithoutStatePackets() {
        assertEquals(Optional.empty(), Paper26_2SubjectStateDecoder.decode(PLAYER, List.of(
                new ClientboundContainerSetContentPacket(3, 0, List.of(), ItemStack.EMPTY))));
    }
}
