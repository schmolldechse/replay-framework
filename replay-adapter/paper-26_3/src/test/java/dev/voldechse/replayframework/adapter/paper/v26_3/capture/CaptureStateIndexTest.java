package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import dev.voldechse.replayframework.adapter.paper.capture.CaptureStateIndex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaptureStateIndexTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.server.Bootstrap.validate();
    }

    @Test
    void relativePositionsAreTrackedIndependentlyPerConnection() {
        var index = new CaptureStateIndex();
        UUID firstConnection = UUID.randomUUID();
        UUID secondConnection = UUID.randomUUID();
        Key overworld = Key.key("minecraft:overworld");
        index.world(firstConnection, overworld.toString());
        index.world(secondConnection, overworld.toString());
        index.seedEntityPosition(300, overworld.toString(), 10.25, 64.0, -4.5);

        index.moveEntity(firstConnection, 300, 0.25, 0.0, 0.0);
        index.moveEntity(secondConnection, 300, 0.25, 0.0, 0.0);

        assertEquals(10.5, index.entityPosition(firstConnection, 300).orElseThrow().x());
        assertEquals(10.5, index.entityPosition(secondConnection, 300).orElseThrow().x());

        index.world(firstConnection, "minecraft:the_nether");
        assertTrue(index.entityPosition(firstConnection, 300).isEmpty());
        assertEquals(10.5, index.entityPosition(secondConnection, 300).orElseThrow().x());
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
}
