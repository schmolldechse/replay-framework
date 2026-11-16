package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_2AuthoritativeStateIndexTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.server.Bootstrap.validate();
    }

    @Test
    void relativePositionsAreTrackedIndependentlyPerConnection() {
        var index = new Paper26_2AuthoritativeStateIndex();
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

    @Test
    void playerAuthorityAndRemovalAreScopedToEntityState() {
        var index = new Paper26_2AuthoritativeStateIndex();
        index.markMovementAuthoritative(300);
        index.markEquipmentAuthoritative(300);
        index.markSwingAuthoritative(300, 40);

        assertTrue(index.movementIsAuthoritative(300));
        assertTrue(index.equipmentIsAuthoritative(300));
        assertTrue(index.swingIsAuthoritative(300, 40));
        assertFalse(index.swingIsAuthoritative(300, 41));
        assertFalse(index.movementIsAuthoritative(301));
        assertFalse(index.equipmentIsAuthoritative(301));

        index.removeEntity(300);

        assertFalse(index.movementIsAuthoritative(300));
        assertFalse(index.equipmentIsAuthoritative(300));
        assertFalse(index.swingIsAuthoritative(300, 40));
    }

    @Test
    void respawnReplacementIsPendingOncePerConnectionAndCoversTheFollowingTick() {
        var index = new Paper26_2AuthoritativeStateIndex();
        java.util.UUID connection =
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000019");

        assertTrue(index.connectionEntityId(connection).isEmpty());
        index.connectionEntityId(connection, 19);
        assertEquals(19, index.connectionEntityId(connection).orElseThrow());

        assertFalse(index.consumeRespawnPending(connection));
        index.markRespawnPending(connection);
        assertTrue(index.consumeRespawnPending(connection));
        assertFalse(index.consumeRespawnPending(connection));

        index.markEntityReplaced(19, 40);
        assertFalse(index.entityReplacedAt(19, 39));
        assertTrue(index.entityReplacedAt(19, 40));
        assertTrue(index.entityReplacedAt(19, 41));
        assertFalse(index.entityReplacedAt(19, 42));
        assertFalse(index.entityReplacedAt(20, 40));

        index.markRespawnPending(connection);
        index.removeConnection(connection);
        assertFalse(index.consumeRespawnPending(connection));
        assertFalse(index.entityReplacedAt(19, 40));
    }

    @Test
    void equipmentDiffIncludesInitialStateChangesAndClearedSlots() {
        Map<EquipmentSlot, ItemStack> empty = emptyEquipment();
        assertEquals(
                EquipmentSlot.VALUES.size(),
                Paper26_2PlayerStateCapture.changedEquipment(null, empty).size());
        assertTrue(Paper26_2PlayerStateCapture.changedEquipment(empty, emptyEquipment()).isEmpty());

        Map<EquipmentSlot, ItemStack> heldItem = emptyEquipment();
        heldItem.put(EquipmentSlot.MAINHAND, stone());
        Map<EquipmentSlot, ItemStack> changed =
                Paper26_2PlayerStateCapture.changedEquipment(empty, heldItem);
        assertEquals(1, changed.size());
        assertTrue(ItemStack.isSameItemSameComponents(
                stone(), changed.get(EquipmentSlot.MAINHAND)));

        changed = Paper26_2PlayerStateCapture.changedEquipment(heldItem, emptyEquipment());
        assertEquals(1, changed.size());
        assertTrue(changed.get(EquipmentSlot.MAINHAND).isEmpty());
    }

    @Test
    void swingFactoryPreservesTheSelectedHand() {
        var mainHand = Paper26_2EntityPacketFactory.swing(300, InteractionHand.MAIN_HAND);
        var offHand = Paper26_2EntityPacketFactory.swing(300, InteractionHand.OFF_HAND);

        assertEquals(300, mainHand.getId());
        assertEquals(ClientboundAnimatePacket.SWING_MAIN_HAND, mainHand.getAction());
        assertEquals(300, offHand.getId());
        assertEquals(ClientboundAnimatePacket.SWING_OFF_HAND, offHand.getAction());
    }

    @Test
    void syntheticStateRetainsCaptureCoordinatesForPublication() {
        assertEquals(
                List.of("packet", "world", "position"),
                Arrays.stream(Paper26_2SyntheticEntityState.class.getRecordComponents())
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
}
