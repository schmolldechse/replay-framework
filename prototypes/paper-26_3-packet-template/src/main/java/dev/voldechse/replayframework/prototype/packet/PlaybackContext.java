package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;

/** Session-local identity operations supplied to otherwise stateless handlers. */
public record PlaybackContext(
        IntUnaryOperator entityIdMapper,
        UnaryOperator<UUID> uuidMapper) {

    public PlaybackContext {
        Objects.requireNonNull(entityIdMapper, "entityIdMapper");
        Objects.requireNonNull(uuidMapper, "uuidMapper");
    }

    public int mapEntityId(int recordedId) {
        if (recordedId < 0) {
            throw new IllegalArgumentException("recorded entity ID must not be negative");
        }
        int mapped = entityIdMapper.applyAsInt(recordedId);
        if (mapped < 0) {
            throw new IllegalStateException("mapped entity ID must not be negative");
        }
        return mapped;
    }

    public UUID mapUuid(UUID recordedId) {
        return Objects.requireNonNull(
                uuidMapper.apply(Objects.requireNonNull(recordedId, "recordedId")),
                "uuidMapper result");
    }
}
