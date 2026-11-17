package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;

/** Small helpers for packet definitions to add only protocol-proven context. */
public final class Paper26_2CaptureContexts {

    private Paper26_2CaptureContexts() {}

    public static CaptureContext atPosition(
            CaptureContext base,
            Key world,
            double x,
            double y,
            double z) {
        Objects.requireNonNull(base, "base");
        BlockPosition position = new BlockPosition(floor(x), floor(y), floor(z));
        return new CaptureContext(
                base.disposition(),
                Optional.of(Objects.requireNonNull(world, "world")),
                Optional.of(position),
                base.normalizedChat(),
                base.customPayloadChannel());
    }

    public static CaptureContext atBlockPosition(CaptureContext base, BlockPosition position) {
        Objects.requireNonNull(base, "base");
        return new CaptureContext(
                base.disposition(),
                base.world(),
                Optional.of(Objects.requireNonNull(position, "position")),
                base.normalizedChat(),
                base.customPayloadChannel());
    }

    public static CaptureContext atEntity(
            CaptureContext base,
            PacketCaptureState state,
            UUID connectionId,
            int entityId) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(state, "state");
        return state.entityPosition(connectionId, entityId)
                .map(position -> atPosition(
                        base, Key.key(position.world()), position.x(), position.y(), position.z()))
                .orElse(base);
    }

    public static CaptureContext withChat(CaptureContext base, String text) {
        Objects.requireNonNull(base, "base");
        return new CaptureContext(
                base.disposition(),
                base.world(),
                base.position(),
                Optional.of(Objects.requireNonNull(text, "text")),
                base.customPayloadChannel());
    }

    public static CaptureContext withCustomPayloadChannel(CaptureContext base, Key channel) {
        Objects.requireNonNull(base, "base");
        return new CaptureContext(
                base.disposition(),
                base.world(),
                base.position(),
                base.normalizedChat(),
                Optional.of(Objects.requireNonNull(channel, "channel")));
    }

    private static int floor(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("packet position exceeds block-coordinate bounds");
        }
        return (int) Math.floor(value);
    }
}
