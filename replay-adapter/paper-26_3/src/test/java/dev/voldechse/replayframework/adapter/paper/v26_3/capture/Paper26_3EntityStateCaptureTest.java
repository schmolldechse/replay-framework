package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_3EntityStateCaptureTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.server.Bootstrap.validate();
    }

    @Test
    void authoritativeStateSuppressesOnlyMatchingRecipientDerivedPackets() {
        var index = new Paper26_3AuthoritativeStateIndex();
        index.markMovement(300);
        index.markEquipment(300);

        assertTrue(index.suppresses(
                "play.clientbound.minecraft:move_entity_pos", varInt(300), 40));
        assertTrue(index.suppresses(
                "play.clientbound.minecraft:entity_position_sync", varInt(300), 40));
        assertFalse(index.suppresses(
                "play.clientbound.minecraft:move_entity_pos", varInt(301), 40));
        assertFalse(index.suppresses(
                "play.clientbound.minecraft:set_entity_motion", varInt(300), 40));
        assertTrue(index.suppresses(
                "play.clientbound.minecraft:set_equipment", varInt(300), 40));
        assertFalse(index.suppresses(
                "play.clientbound.minecraft:set_equipment", varInt(301), 40));
        assertTrue(index.suppresses(
                "play.clientbound.minecraft:entity_event", entityEvent(300, 55), 40));
        assertFalse(index.suppresses(
                "play.clientbound.minecraft:entity_event", entityEvent(300, 3), 40));
    }

    @Test
    void playerSwingSuppressionIsTickScoped() {
        var index = new Paper26_3AuthoritativeStateIndex();
        index.markSwing(300, 40);

        assertTrue(index.suppresses(
                "play.clientbound.minecraft:swing_animation", varInt(300), 40));
        assertFalse(index.suppresses(
                "play.clientbound.minecraft:swing_animation", varInt(301), 40));
        assertFalse(index.suppresses(
                "play.clientbound.minecraft:swing_animation", varInt(300), 41));
    }

    @Test
    void equipmentDiffIncludesInitialStateChangesAndClearedSlots() {
        Map<EquipmentSlot, ItemStack> empty = emptyEquipment();
        assertEquals(
                EquipmentSlot.VALUES.size(),
                Paper26_3PlayerStateCapture.changedEquipment(null, empty).size());
        assertTrue(Paper26_3PlayerStateCapture.changedEquipment(empty, emptyEquipment()).isEmpty());

        Map<EquipmentSlot, ItemStack> heldItem = emptyEquipment();
        heldItem.put(EquipmentSlot.MAINHAND, stone());
        Map<EquipmentSlot, ItemStack> changed =
                Paper26_3PlayerStateCapture.changedEquipment(empty, heldItem);
        assertEquals(1, changed.size());
        assertTrue(ItemStack.isSameItemSameComponents(
                stone(), changed.get(EquipmentSlot.MAINHAND)));

        changed = Paper26_3PlayerStateCapture.changedEquipment(heldItem, emptyEquipment());
        assertEquals(1, changed.size());
        assertTrue(changed.get(EquipmentSlot.MAINHAND).isEmpty());
    }

    @Test
    void syntheticStateRetainsCaptureCoordinatesForPublication() {
        assertEquals(
                List.of("packet", "world", "position"),
                Arrays.stream(Paper26_3SyntheticEntityState.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
    }

    private static Map<EquipmentSlot, ItemStack> emptyEquipment() {
        Map<EquipmentSlot, ItemStack> result = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            result.put(slot, ItemStack.EMPTY);
        }
        return result;
    }

    private static ItemStack stone() {
        var holder = Items.STONE.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        }
        return new ItemStack(holder);
    }

    private static byte[] entityEvent(int entityId, int event) {
        return new byte[] {
            (byte) (entityId >>> 24),
            (byte) (entityId >>> 16),
            (byte) (entityId >>> 8),
            (byte) entityId,
            (byte) event
        };
    }

    private static byte[] varInt(int value) {
        byte[] result = new byte[5];
        int index = 0;
        do {
            int current = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                current |= 0x80;
            }
            result[index++] = (byte) current;
        } while (value != 0);
        return Arrays.copyOf(result, index);
    }
}
