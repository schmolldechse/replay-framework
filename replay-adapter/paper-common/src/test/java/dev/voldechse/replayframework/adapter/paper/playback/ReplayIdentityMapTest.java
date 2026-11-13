package dev.voldechse.replayframework.adapter.paper.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReplayIdentityMapTest {

    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void mapsIdentitiesStablyWithoutUsingReservedValues() {
        var identities = identities(SESSION_ID, Set.of(-100));
        UUID recordedUuid = UUID.fromString("00000000-0000-0000-0000-000000000003");

        UUID mappedUuid = identities.mapUuid(recordedUuid);
        int mappedEntityId = identities.mapEntityId(42);

        assertEquals(mappedUuid, identities.mapUuid(recordedUuid));
        assertEquals(mappedEntityId, identities.mapEntityId(42));
        assertNotEquals(OBSERVER_ID, mappedUuid);
        assertNotEquals(recordedUuid, mappedUuid);
        assertNotEquals(-100, mappedEntityId);
        assertTrue(mappedEntityId < 0);
    }

    @Test
    void separatesRecordedAndPlaybackSessionIdentities() {
        UUID recordedUuid = UUID.fromString("00000000-0000-0000-0000-000000000004");
        var first = identities(SESSION_ID, Set.of());
        var second = identities(
                UUID.fromString("00000000-0000-0000-0000-000000000005"), Set.of());

        assertNotEquals(first.mapUuid(recordedUuid), second.mapUuid(recordedUuid));
        assertNotEquals(first.mapEntityId(7), first.mapEntityId(8));
        assertNotEquals(first.mapUuid(recordedUuid), first.mapUuid(UUID.randomUUID()));
    }

    @Test
    void acceptsEveryRecordedEntityIdAsAMapKey() {
        var identities = identities(SESSION_ID, Set.of());

        assertNotEquals(identities.mapEntityId(Integer.MIN_VALUE), identities.mapEntityId(0));
        assertNotEquals(identities.mapEntityId(0), identities.mapEntityId(Integer.MAX_VALUE));
    }

    @Test
    void neverAllocatesMinusOneWhichIsReservedForAbsentEntityReferences() {
        UUID sessionId = new UUID(0L, 0x9E3779B9L);
        var identities = identities(sessionId, Set.of());

        int mappedEntityId = identities.mapEntityId(1);

        assertNotEquals(-1, mappedEntityId);
        assertEquals(mappedEntityId, identities.mapEntityId(1));
    }

    @Test
    void clearRemovesMappingsButRetainsReservations() {
        UUID recordedUuid = UUID.fromString("00000000-0000-0000-0000-000000000006");
        var identities = identities(SESSION_ID, Set.of(-200));
        UUID firstUuid = identities.mapUuid(recordedUuid);
        int firstEntityId = identities.mapEntityId(11);

        identities.clear();

        assertEquals(firstUuid, identities.mapUuid(recordedUuid));
        assertEquals(firstEntityId, identities.mapEntityId(11));
        assertNotEquals(OBSERVER_ID, identities.mapUuid(recordedUuid));
        assertNotEquals(-200, identities.mapEntityId(12));
    }

    @Test
    void rejectsNullReservations() {
        PlaybackIdentityContext context = new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID);

        assertThrows(NullPointerException.class, () ->
                new ReplayIdentityMap(context, Set.of(), null));
    }

    private static ReplayIdentityMap identities(UUID sessionId, Set<Integer> reservedEntityIds) {
        return new ReplayIdentityMap(
                new PlaybackIdentityContext(OBSERVER_ID, sessionId),
                Set.of(OBSERVER_ID),
                reservedEntityIds);
    }
}
