package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

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

    void removeConnection(UUID connectionId);

    /** Last known block-space position of an entity in a world. */
    record EntityPosition(String world, double x, double y, double z) {
        public EntityPosition {
            if (world == null || world.isBlank()
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("entity position must be finite and have a world");
            }
        }
    }
}
