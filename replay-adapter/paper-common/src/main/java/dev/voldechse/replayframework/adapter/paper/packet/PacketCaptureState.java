package dev.voldechse.replayframework.adapter.paper.packet;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import java.util.Optional;
import java.util.UUID;

/** Mutable, adapter-owned state available while packet definitions interpret capture observations. */
public interface PacketCaptureState {

    Optional<String> world(UUID connectionId);

    void world(UUID connectionId, String world);

    /** Creates a packet context using this adapter's version-specific world representation. */
    CaptureContext contextForConnection(UUID connectionId, PacketDisposition disposition);

    void connectionEntityId(UUID connectionId, int entityId);

    Optional<EntityPosition> entityPosition(UUID connectionId, int entityId);

    void entityPosition(UUID connectionId, int entityId, String world, double x, double y, double z);

    /** Seeds an absolute baseline obtained from an authoritative checkpoint snapshot. */
    void seedEntityPosition(int entityId, String world, double x, double y, double z);

    void moveEntity(UUID connectionId, int entityId, double deltaX, double deltaY, double deltaZ);

    void removeEntity(int entityId);

    void markMovementAuthoritative(int entityId);

    void markEquipmentAuthoritative(int entityId);

    void markSwingAuthoritative(int entityId, long serverTick);

    boolean movementIsAuthoritative(int entityId);

    boolean equipmentIsAuthoritative(int entityId);

    boolean swingIsAuthoritative(int entityId, long serverTick);

    void removeConnection(UUID connectionId);

    /** Immutable exact position retained between relative movement packets. */
    record EntityPosition(String world, double x, double y, double z) {
        public EntityPosition {
            if (world == null || world.isBlank()
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("entity position must be finite and have a world");
            }
        }

        public int blockX() {
            return floor(x);
        }

        public int blockY() {
            return floor(y);
        }

        public int blockZ() {
            return floor(z);
        }

        private static int floor(double value) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("entity position exceeds block-coordinate bounds");
            }
            return (int) Math.floor(value);
        }
    }
}
