package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Session-local deterministic mapping for replay UUIDs and entity IDs. */
final class ReplayIdentityMap {
    private final PlaybackIdentityContext context;
    private final Map<UUID, UUID> uuids = new HashMap<>();
    private final Set<UUID> usedUuids = new HashSet<>();
    private final Map<Integer, Integer> entityIds = new HashMap<>();
    private final Set<Integer> usedEntityIds = new HashSet<>();
    private final Set<String> reservedNames = new HashSet<>();
    private final Map<String, String> names = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Map<String, String> objectiveNames = new HashMap<>();
    private final Set<String> usedObjectiveNames = new HashSet<>();
    private final Map<String, String> teamNames = new HashMap<>();
    private final Set<String> usedTeamNames = new HashSet<>();

    ReplayIdentityMap(PlaybackIdentityContext context, int observerEntityId) {
        this(context, Set.of(context.observerId()), Set.of(observerEntityId));
    }

    ReplayIdentityMap(
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds) {
        this(context, reservedUuids, reservedEntityIds, Set.of());
    }

    ReplayIdentityMap(
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds,
            Set<String> reservedNames) {
        this.context = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(reservedUuids, "reservedUuids");
        Objects.requireNonNull(reservedEntityIds, "reservedEntityIds");
        Objects.requireNonNull(reservedNames, "reservedNames");
        for (UUID reservedUuid : reservedUuids) {
            usedUuids.add(Objects.requireNonNull(reservedUuid, "reservedUuids entry"));
        }
        for (Integer reservedEntityId : reservedEntityIds) {
            if (reservedEntityId == null) {
                throw new NullPointerException("reservedEntityIds entry");
            }
            usedEntityIds.add(reservedEntityId);
        }
        for (String reservedName : reservedNames) {
            String normalized = normalizeName(Objects.requireNonNull(reservedName, "reservedNames entry"));
            this.reservedNames.add(normalized);
            usedNames.add(normalized);
        }
    }

    UUID mapUuid(UUID recordedId) {
        Objects.requireNonNull(recordedId, "recordedId");
        return uuids.computeIfAbsent(recordedId, this::createUuid);
    }

    int mapEntityId(int recordedId) {
        return entityIds.computeIfAbsent(recordedId, this::createEntityId);
    }

    String mapName(String recordedName) {
        Objects.requireNonNull(recordedName, "recordedName");
        return names.computeIfAbsent(normalizeName(recordedName), ignored -> createName(recordedName));
    }

    String mapObjectiveName(String recordedName) {
        Objects.requireNonNull(recordedName, "recordedName");
        return objectiveNames.computeIfAbsent(
                recordedName,
                ignored -> createScopedName("objective", recordedName, usedObjectiveNames));
    }

    String mapTeamName(String recordedName) {
        Objects.requireNonNull(recordedName, "recordedName");
        return teamNames.computeIfAbsent(
                recordedName,
                ignored -> createScopedName("team", recordedName, usedTeamNames));
    }

    void clear() {
        uuids.clear();
        usedUuids.clear();
        entityIds.clear();
        usedEntityIds.clear();
        names.clear();
        usedNames.clear();
        objectiveNames.clear();
        usedObjectiveNames.clear();
        teamNames.clear();
        usedTeamNames.clear();
    }

    private UUID createUuid(UUID recordedId) {
        byte[] seed = (context.playbackSessionId() + ":uuid:" + recordedId)
                .getBytes(StandardCharsets.UTF_8);
        UUID mapped = UUID.nameUUIDFromBytes(seed);
        int attempt = 0;
        while (mapped.equals(context.observerId()) || !usedUuids.add(mapped)) {
            attempt++;
            mapped = UUID.nameUUIDFromBytes((context.playbackSessionId() + ":uuid:" + recordedId
                    + ":" + attempt).getBytes(StandardCharsets.UTF_8));
        }
        return mapped;
    }

    private int createEntityId(int recordedId) {
        // Paper allocates live entity IDs from the positive range. Keeping
        // replay IDs in the negative protocol range avoids querying Bukkit's
        // mutable world/entity collections from the asynchronous playback
        // preparation thread and prevents collisions with live entities.
        long mixed = context.playbackSessionId().getLeastSignificantBits()
                ^ (recordedId * 0x9E3779B9L);
        int candidate = -((int) (mixed & 0x3FFFFFFFL) + 1);
        while (usedEntityIds.contains(candidate)) {
            candidate = candidate == Integer.MIN_VALUE + 1 ? -1 : candidate - 1;
        }
        usedEntityIds.add(candidate);
        return candidate;
    }

    private String createName(String recordedName) {
        String normalized = normalizeName(recordedName);
        if (!reservedNames.contains(normalized) && usedNames.add(normalized)) {
            return recordedName;
        }
        return createScopedName("name", recordedName, usedNames);
    }

    private String createScopedName(String namespace, String recordedName, Set<String> used) {
        byte[] seed = (context.playbackSessionId() + ":" + namespace + ":" + recordedName)
                .getBytes(StandardCharsets.UTF_8);
        String hash = UUID.nameUUIDFromBytes(seed).toString().replace("-", "");
        String candidate = "r" + hash.substring(0, 15);
        int attempt = 0;
        while (!used.add(normalizeName(candidate))) {
            attempt++;
            candidate = "r" + UUID.nameUUIDFromBytes(
                    (context.playbackSessionId() + ":" + namespace + ":" + recordedName + ":" + attempt)
                            .getBytes(StandardCharsets.UTF_8))
                    .toString()
                    .replace("-", "")
                    .substring(0, 15);
        }
        return candidate;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
