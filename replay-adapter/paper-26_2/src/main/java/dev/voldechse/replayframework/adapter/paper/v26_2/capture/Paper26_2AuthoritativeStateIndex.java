package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;

/** Packet-derived world, entity-position and player-authority state for Paper 26.2 capture. */
final class Paper26_2AuthoritativeStateIndex implements PacketCaptureState {

    private final Map<UUID, String> connectionWorlds = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> connectionEntityIds = new ConcurrentHashMap<>();
    private final Map<ConnectionEntity, EntityPosition> connectionEntityPositions =
            new ConcurrentHashMap<>();
    private final Map<Integer, EntityPosition> checkpointEntityPositions =
            new ConcurrentHashMap<>();
    private final Set<Integer> movementEntityIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> equipmentEntityIds = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> swingTicks = new ConcurrentHashMap<>();

    @Override
    public Optional<String> world(UUID connectionId) {
        return connectionId == null
                ? Optional.empty()
                : Optional.ofNullable(connectionWorlds.get(connectionId));
    }

    @Override
    public void world(UUID connectionId, String world) {
        if (connectionId != null) {
            String nextWorld = java.util.Objects.requireNonNull(world, "world");
            String previousWorld = connectionWorlds.put(connectionId, nextWorld);
            if (previousWorld != null && !previousWorld.equals(nextWorld)) {
                connectionEntityPositions.keySet().removeIf(key -> key.connectionId().equals(connectionId));
            }
        }
    }

    @Override
    public CaptureContext contextForConnection(
            UUID connectionId, PacketDisposition disposition) {
        return new CaptureContext(
                disposition,
                world(connectionId).map(Key::key),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @Override
    public void connectionEntityId(UUID connectionId, int entityId) {
        if (connectionId == null) {
            return;
        }
        Integer previous = connectionEntityIds.put(connectionId, entityId);
        if (previous != null && previous != entityId) {
            remove(previous);
        }
    }

    @Override
    public Optional<EntityPosition> entityPosition(UUID connectionId, int entityId) {
        if (connectionId == null) {
            return Optional.ofNullable(checkpointEntityPositions.get(entityId));
        }
        EntityPosition connectionPosition = connectionEntityPositions.get(
                new ConnectionEntity(connectionId, entityId));
        if (connectionPosition != null) {
            return Optional.of(connectionPosition);
        }
        EntityPosition checkpointPosition = checkpointEntityPositions.get(entityId);
        String connectionWorld = connectionWorlds.get(connectionId);
        return checkpointPosition != null && checkpointPosition.world().equals(connectionWorld)
                ? Optional.of(checkpointPosition)
                : Optional.empty();
    }

    @Override
    public void entityPosition(UUID connectionId, int entityId, String world, double x, double y, double z) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must not be negative");
        }
        EntityPosition position = new EntityPosition(world, x, y, z);
        if (connectionId == null) {
            checkpointEntityPositions.put(entityId, position);
        } else {
            connectionEntityPositions.put(new ConnectionEntity(connectionId, entityId), position);
        }
    }

    @Override
    public void seedEntityPosition(int entityId, String world, double x, double y, double z) {
        if (entityId < 0) {
            throw new IllegalArgumentException("entityId must not be negative");
        }
        checkpointEntityPositions.put(entityId, new EntityPosition(world, x, y, z));
    }

    @Override
    public void moveEntity(UUID connectionId, int entityId, double deltaX, double deltaY, double deltaZ) {
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)) {
            throw new IllegalArgumentException("entity movement delta must be finite");
        }
        entityPosition(connectionId, entityId).ifPresent(previous -> entityPosition(
                connectionId,
                entityId,
                previous.world(),
                previous.x() + deltaX,
                previous.y() + deltaY,
                previous.z() + deltaZ));
    }

    @Override
    public void removeEntity(int entityId) {
        remove(entityId);
    }

    void remove(int entityId) {
        checkpointEntityPositions.remove(entityId);
        connectionEntityPositions.keySet().removeIf(key -> key.entityId() == entityId);
        movementEntityIds.remove(entityId);
        equipmentEntityIds.remove(entityId);
        swingTicks.remove(entityId);
        connectionEntityIds.values().removeIf(value -> value == entityId);
    }

    @Override
    public void markMovementAuthoritative(int entityId) {
        movementEntityIds.add(entityId);
    }

    @Override
    public void markEquipmentAuthoritative(int entityId) {
        equipmentEntityIds.add(entityId);
    }

    @Override
    public void markSwingAuthoritative(int entityId, long serverTick) {
        if (serverTick < 0L) {
            throw new IllegalArgumentException("serverTick must not be negative");
        }
        swingTicks.put(entityId, serverTick);
    }

    @Override
    public boolean movementIsAuthoritative(int entityId) {
        return movementEntityIds.contains(entityId);
    }

    @Override
    public boolean equipmentIsAuthoritative(int entityId) {
        return equipmentEntityIds.contains(entityId);
    }

    @Override
    public boolean swingIsAuthoritative(int entityId, long serverTick) {
        return swingTicks.getOrDefault(entityId, Long.MIN_VALUE) == serverTick;
    }

    @Override
    public void removeConnection(UUID connectionId) {
        if (connectionId == null) {
            return;
        }
        connectionWorlds.remove(connectionId);
        connectionEntityPositions.keySet().removeIf(key -> key.connectionId().equals(connectionId));
        Integer entityId = connectionEntityIds.remove(connectionId);
        if (entityId != null) {
            remove(entityId);
        }
    }

    void clear() {
        connectionWorlds.clear();
        connectionEntityIds.clear();
        connectionEntityPositions.clear();
        checkpointEntityPositions.clear();
        movementEntityIds.clear();
        equipmentEntityIds.clear();
        swingTicks.clear();
    }

    private record ConnectionEntity(UUID connectionId, int entityId) {
    }
}
