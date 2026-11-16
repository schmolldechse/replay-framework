package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ViewTeardownState;
import dev.voldechse.replayframework.adapter.paper.packet.ViewTeardownState.ChunkCoordinate;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Paper26_2ViewTeardownIndexTest {

    private static final UUID CONNECTION =
            UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID OTHER =
            UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final String WORLD = "minecraft:overworld";

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void unloadsOfALivingPlayerAreNeverWithheld() {
        var index = new Paper26_2ViewTeardownIndex();

        assertFalse(index.withholdChunkUnload(CONNECTION, 0, 0));
        assertFalse(index.tearingDown(CONNECTION));
        index.withholdEntityRemoval(CONNECTION, 40, Optional.of(new ChunkCoordinate(0, 0)));
        index.playerRespawned(CONNECTION, 10L, true);

        assertTrue(index.drainDue(CONNECTION, 1_000L).isEmpty());
    }

    @Test
    void chunksAndEntitiesSentAgainAfterTheRespawnAreNeverUnloaded() {
        var index = new Paper26_2ViewTeardownIndex();
        index.viewRadius(CONNECTION, 2);
        index.viewCenter(CONNECTION, 0, 0);
        index.playerDied(CONNECTION, WORLD);
        assertFalse(index.tearingDown(CONNECTION));

        assertTrue(index.withholdChunkUnload(CONNECTION, 0, 0));
        assertTrue(index.withholdChunkUnload(CONNECTION, 1, 0));
        assertTrue(index.tearingDown(CONNECTION));
        assertFalse(index.tearingDown(OTHER));
        index.withholdEntityRemoval(CONNECTION, 40, Optional.of(new ChunkCoordinate(1, 0)));
        assertTrue(index.drainDue(CONNECTION, 500L).isEmpty());

        index.playerRespawned(CONNECTION, 556L, true);
        assertFalse(index.tearingDown(CONNECTION));
        index.viewRadius(CONNECTION, 2);
        index.viewCenter(CONNECTION, 0, 0);
        index.chunkLoaded(CONNECTION, 0, 0, 561L);
        index.chunkLoaded(CONNECTION, 1, 0, 562L);
        index.entityAdded(CONNECTION, 40);

        assertTrue(index.drainDue(CONNECTION, 10_000L).isEmpty());
    }

    @Test
    void releasesChunksAndTheirEntitiesOutsideTheRespawnedView() {
        var index = new Paper26_2ViewTeardownIndex();
        index.playerDied(CONNECTION, WORLD);
        index.withholdChunkUnload(CONNECTION, 0, 0);
        index.withholdChunkUnload(CONNECTION, 3, 0);
        index.withholdChunkUnload(CONNECTION, 40, 40);
        index.withholdEntityRemoval(CONNECTION, 40, Optional.of(new ChunkCoordinate(40, 40)));
        index.withholdEntityRemoval(CONNECTION, 41, Optional.of(new ChunkCoordinate(0, 0)));

        // The center is stale until the respawn; nothing is released while dead.
        index.viewRadius(CONNECTION, 2);
        index.viewCenter(CONNECTION, 100, 100);
        assertTrue(index.drainDue(CONNECTION, 550L).isEmpty());

        index.playerRespawned(CONNECTION, 556L, true);
        index.viewRadius(CONNECTION, 2);
        index.viewCenter(CONNECTION, 0, 0);

        // Radius 2 plus the one-column margin keeps chunk (3, 0) withheld.
        ViewTeardownState.Unloads unloads = index.drainDue(CONNECTION, 556L);
        assertEquals(Optional.of(WORLD), unloads.world());
        assertEquals(List.of(new ChunkCoordinate(40, 40)), unloads.chunks());
        assertEquals(List.of(40), unloads.entityIds());
        assertTrue(index.drainDue(CONNECTION, 556L).isEmpty());

        // Moving the view away later releases what was never sent again.
        index.viewCenter(CONNECTION, 10, 0);
        unloads = index.drainDue(CONNECTION, 600L);
        assertEquals(2, unloads.chunks().size());
        assertEquals(List.of(41), unloads.entityIds());
    }

    @Test
    void releasesAnEntityThatDoesNotReappearAfterItsChunkWasSentAgain() {
        var index = new Paper26_2ViewTeardownIndex();
        long grace = Paper26_2ViewTeardownIndex.ENTITY_RESEND_GRACE_TICKS;
        index.playerDied(CONNECTION, WORLD);
        index.withholdChunkUnload(CONNECTION, 0, 0);
        index.withholdEntityRemoval(CONNECTION, 40, Optional.of(new ChunkCoordinate(0, 0)));
        index.withholdEntityRemoval(CONNECTION, 41, Optional.empty());
        index.playerRespawned(CONNECTION, 556L, true);

        // Entity 41 has no chunk to wait for; entity 40 waits for chunk (0, 0).
        assertTrue(index.drainDue(CONNECTION, 556L + grace - 1).isEmpty());
        assertEquals(List.of(41), index.drainDue(CONNECTION, 556L + grace).entityIds());

        index.chunkLoaded(CONNECTION, 0, 0, 700L);
        assertTrue(index.drainDue(CONNECTION, 700L + grace - 1).isEmpty());
        ViewTeardownState.Unloads unloads = index.drainDue(CONNECTION, 700L + grace);
        assertTrue(unloads.chunks().isEmpty());
        assertEquals(List.of(40), unloads.entityIds());
    }

    @Test
    void aRespawnIntoAnotherWorldReleasesEverythingAndDisconnectsDropTheState() {
        var index = new Paper26_2ViewTeardownIndex();
        index.playerDied(CONNECTION, WORLD);
        index.withholdChunkUnload(CONNECTION, 0, 0);
        index.withholdEntityRemoval(CONNECTION, 40, Optional.of(new ChunkCoordinate(0, 0)));
        index.playerRespawned(CONNECTION, 556L, false);

        ViewTeardownState.Unloads unloads = index.drainDue(CONNECTION, 556L);
        assertEquals(List.of(new ChunkCoordinate(0, 0)), unloads.chunks());
        assertEquals(List.of(40), unloads.entityIds());

        index.playerDied(CONNECTION, WORLD);
        index.withholdChunkUnload(CONNECTION, 0, 0);
        index.removeConnection(CONNECTION);
        assertFalse(index.tearingDown(CONNECTION));
        assertTrue(index.drainDue(CONNECTION, 10_000L).isEmpty());
    }

    @Test
    void packetDefinitionsWithholdOnlyTheDeadConnectionsUnloads() {
        var state = new Paper26_2AuthoritativeStateIndex();
        state.world(CONNECTION, WORLD);
        state.world(OTHER, WORLD);
        state.entityPosition(CONNECTION, 40, WORLD, 20.0, 64.0, -1.0);
        state.markMovementAuthoritative(40);
        var forget = new ClientboundForgetLevelChunkPacket(new ChunkPos(1, -1));
        var remove = new ClientboundRemoveEntitiesPacket(40);

        // Alive: unloads are scene changes and entity state is dropped.
        observe(37, forget, CONNECTION, state);
        assertFalse(suppressed(37, forget, CONNECTION, state));

        observe(104, new ClientboundSetHealthPacket(4.0F, 20, 5.0F), CONNECTION, state);
        assertFalse(state.viewTeardown().tearingDown(CONNECTION));
        observe(104, new ClientboundSetHealthPacket(0.0F, 20, 5.0F), CONNECTION, state);

        observe(37, forget, CONNECTION, state);
        assertTrue(suppressed(37, forget, CONNECTION, state));
        assertFalse(suppressed(37, forget, OTHER, state));
        observe(77, remove, CONNECTION, state);
        assertTrue(suppressed(77, remove, CONNECTION, state));
        assertTrue(state.movementIsAuthoritative(40));
        assertTrue(state.entityPosition(CONNECTION, 40).isPresent());

        // The respawned view is centered far away: both unloads are released.
        state.viewTeardown().playerRespawned(CONNECTION, 556L, true);
        observe(95, new ClientboundSetChunkCacheRadiusPacket(2), CONNECTION, state);
        observe(94, new ClientboundSetChunkCacheCenterPacket(50, 50), CONNECTION, state);
        ViewTeardownState.Unloads unloads = state.viewTeardown().drainDue(CONNECTION, 556L);
        assertEquals(List.of(new ChunkCoordinate(1, -1)), unloads.chunks());
        assertEquals(List.of(40), unloads.entityIds());

        // Another recipient's removal of the same entity stays a scene change.
        observe(77, remove, OTHER, state);
        assertFalse(suppressed(77, remove, OTHER, state));
        assertFalse(state.movementIsAuthoritative(40));
    }

    private static void observe(
            int packetId, Object packet, UUID connectionId, Paper26_2AuthoritativeStateIndex state) {
        PacketDefinition<?> definition = definition(packetId);
        definition.captureContext(packet, descriptor(definition), connectionId, state, 1L);
    }

    private static boolean suppressed(
            int packetId, Object packet, UUID connectionId, Paper26_2AuthoritativeStateIndex state) {
        return definition(packetId).suppressesRecipientState(packet, connectionId, state, 1L);
    }

    private static PacketDefinition<?> definition(int packetId) {
        return Paper26_2PacketCatalog.catalog().find(packetId).orElseThrow();
    }

    private static PacketDescriptor descriptor(PacketDefinition<?> definition) {
        return new PacketDescriptor(
                definition.typeName(),
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                definition.packetId(),
                PacketDisposition.STATEFUL,
                true,
                true,
                false,
                "paper-26.2/" + definition.typeName(),
                definition.classification().playbackScope());
    }
}
