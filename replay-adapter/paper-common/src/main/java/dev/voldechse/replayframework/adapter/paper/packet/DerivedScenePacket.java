package dev.voldechse.replayframework.adapter.paper.packet;

import java.util.Objects;

/**
 * One adapter-native clientbound packet that a captured observation implies
 * for the recipient-independent scene, together with the world and position
 * the recording router evaluates its scope against.
 *
 * @param packet adapter-native clientbound packet object
 * @param position world and exact position of the affected entity
 */
public record DerivedScenePacket(Object packet, PacketCaptureState.EntityPosition position) {

    public DerivedScenePacket {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(position, "position");
    }
}
