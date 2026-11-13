package dev.voldechse.replayframework.adapter.paper.playback;

import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Session-local deterministic mapping for replay UUIDs and entity IDs. */
public final class ReplayIdentityMap {

    private final PlaybackIdentityContext context;
    private final Set<UUID> reservedUuids;
    private final Set<Integer> reservedEntityIds;
    private final Map<UUID, UUID> uuids = new HashMap<>();
    private final Set<UUID> usedUuids = new HashSet<>();
    private final Map<Integer, Integer> entityIds = new HashMap<>();
    private final Set<Integer> usedEntityIds = new HashSet<>();

    public ReplayIdentityMap(
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds) {
        this.context = Objects.requireNonNull(context, "context");
        this.reservedUuids = copyUuids(reservedUuids);
        this.reservedEntityIds = copyEntityIds(reservedEntityIds);
        resetUsedIdentities();
    }

    /** Returns the stable replay UUID for one recorded identity. */
    public UUID mapUuid(UUID recordedId) {
        Objects.requireNonNull(recordedId, "recordedId");
        return uuids.computeIfAbsent(recordedId, this::createUuid);
    }

    /** Returns the stable replay entity ID for one recorded runtime ID. */
    public int mapEntityId(int recordedId) {
        return entityIds.computeIfAbsent(recordedId, this::createEntityId);
    }

    /** Removes all generated mappings while retaining collision reservations. */
    public void clear() {
        uuids.clear();
        entityIds.clear();
        resetUsedIdentities();
    }

    private UUID createUuid(UUID recordedId) {
        int attempt = 0;
        while (true) {
            String suffix = attempt == 0 ? "" : ":" + attempt;
            byte[] seed = (context.playbackSessionId() + ":uuid:" + recordedId + suffix)
                    .getBytes(StandardCharsets.UTF_8);
            UUID candidate = UUID.nameUUIDFromBytes(seed);
            if (usedUuids.add(candidate)) {
                return candidate;
            }
            attempt++;
        }
    }

    private int createEntityId(int recordedId) {
        // Paper allocates live entity IDs from the positive range. Replay IDs
        // remain negative, deterministic and independent of mutable worlds.
        long mixed = context.playbackSessionId().getLeastSignificantBits()
                ^ (recordedId * 0x9E3779B9L);
        int candidate = -((int) (mixed & 0x3FFFFFFFL) + 1);
        // -1 is the wire sentinel for absent entity references in packets such
        // as damage_event, so replay identities must never be allocated there.
        while (candidate == -1 || !usedEntityIds.add(candidate)) {
            candidate = candidate == Integer.MIN_VALUE ? -2 : candidate - 1;
        }
        return candidate;
    }

    private void resetUsedIdentities() {
        usedUuids.clear();
        usedUuids.addAll(reservedUuids);
        usedEntityIds.clear();
        usedEntityIds.addAll(reservedEntityIds);
    }

    private static Set<UUID> copyUuids(Set<UUID> values) {
        Objects.requireNonNull(values, "reservedUuids");
        HashSet<UUID> copy = new HashSet<>();
        for (UUID value : values) {
            copy.add(Objects.requireNonNull(value, "reservedUuids entry"));
        }
        return Set.copyOf(copy);
    }

    private static Set<Integer> copyEntityIds(Set<Integer> values) {
        Objects.requireNonNull(values, "reservedEntityIds");
        HashSet<Integer> copy = new HashSet<>();
        for (Integer value : values) {
            copy.add(Objects.requireNonNull(value, "reservedEntityIds entry"));
        }
        return Set.copyOf(copy);
    }
}
