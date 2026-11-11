package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;

/** Tracks entity-state families owned by recipient-independent capture. */
final class Paper26_2AuthoritativeStateIndex {

    private static final Set<String> MOVEMENT_PACKETS = Set.of(
            "play.clientbound.minecraft:entity_position_sync",
            "play.clientbound.minecraft:move_entity_pos",
            "play.clientbound.minecraft:move_entity_pos_rot",
            "play.clientbound.minecraft:move_entity_rot",
            "play.clientbound.minecraft:rotate_head",
            "play.clientbound.minecraft:teleport_entity");
    private static final String EQUIPMENT_PACKET =
            "play.clientbound.minecraft:set_equipment";
    private static final String ENTITY_EVENT_PACKET =
            "play.clientbound.minecraft:entity_event";
    private static final String ANIMATION_PACKET =
            "play.clientbound.minecraft:animate";
    private static final int SWAP_HANDS_EVENT = 55;

    private final Set<Integer> movementEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> equipmentEntityIds = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> swingTicks = new ConcurrentHashMap<>();

    void markMovement(int entityId) {
        movementEntityIds.add(entityId);
    }

    void markEquipment(int entityId) {
        equipmentEntityIds.add(entityId);
    }

    void markSwing(int entityId, long tick) {
        if (tick < 0L) {
            throw new IllegalArgumentException("tick must not be negative");
        }
        swingTicks.put(entityId, tick);
    }

    void remove(int entityId) {
        movementEntityIds.remove(entityId);
        equipmentEntityIds.remove(entityId);
        swingTicks.remove(entityId);
    }

    boolean suppresses(String descriptorTypeName, byte[] payload, long observedServerTick) {
        if (MOVEMENT_PACKETS.contains(descriptorTypeName)) {
            return movementEntityIds.contains(readVarInt(payload).value());
        }
        if (EQUIPMENT_PACKET.equals(descriptorTypeName)) {
            return equipmentEntityIds.contains(readVarInt(payload).value());
        }
        if (ENTITY_EVENT_PACKET.equals(descriptorTypeName)) {
            return payload.length >= 5
                    && equipmentEntityIds.contains(readInt(payload))
                    && Byte.toUnsignedInt(payload[4]) == SWAP_HANDS_EVENT;
        }
        if (ANIMATION_PACKET.equals(descriptorTypeName)) {
            DecodedVarInt entityId = readVarInt(payload);
            if (entityId.nextIndex() >= payload.length) {
                throw new IllegalArgumentException("animate packet has no action");
            }
            int action = Byte.toUnsignedInt(payload[entityId.nextIndex()]);
            return (action == ClientboundAnimatePacket.SWING_MAIN_HAND
                            || action == ClientboundAnimatePacket.SWING_OFF_HAND)
                    && swingTicks.getOrDefault(entityId.value(), Long.MIN_VALUE)
                            == observedServerTick;
        }
        return false;
    }

    void clear() {
        movementEntityIds.clear();
        equipmentEntityIds.clear();
        swingTicks.clear();
    }

    private static DecodedVarInt readVarInt(byte[] payload) {
        int value = 0;
        int shift = 0;
        for (int index = 0; index < payload.length; index++) {
            int current = payload[index] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new DecodedVarInt(value, index + 1);
            }
            shift += 7;
            if (shift >= 35) {
                break;
            }
        }
        throw new IllegalArgumentException("entity packet has no valid entity ID");
    }

    private static int readInt(byte[] payload) {
        if (payload.length < Integer.BYTES) {
            throw new IllegalArgumentException("entity event has no complete entity ID");
        }
        return (payload[0] & 0xFF) << 24
                | (payload[1] & 0xFF) << 16
                | (payload[2] & 0xFF) << 8
                | payload[3] & 0xFF;
    }

    private record DecodedVarInt(int value, int nextIndex) {}
}
