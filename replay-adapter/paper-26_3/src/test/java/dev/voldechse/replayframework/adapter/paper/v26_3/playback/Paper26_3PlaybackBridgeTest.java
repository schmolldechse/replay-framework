package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackEmissionMode;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Paper26_3PlaybackBridgeTest {

    @Test
    void replaysChunkBiomesDuringLivePlaybackAndReconstruction() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 13)
                .orElseThrow();
        RawPacketFrame chunksBiomes = frame(13);

        assertEquals("play.clientbound.minecraft:chunks_biomes", descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                chunksBiomes, PlaybackEmissionMode.LIVE, registry));
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                chunksBiomes, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void neverReplaysRecordedChunkBatchFlowControl() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();

        for (int packetId : new int[] {11, 12}) {
            PacketDescriptor descriptor = registry.find(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            packetId)
                    .orElseThrow();
            RawPacketFrame marker = frame(packetId);

            assertEquals(PacketDisposition.CONTROL, descriptor.disposition());
            assertFalse(descriptor.replayable());
            assertFalse(descriptor.checkpointRelevant());
            assertEquals(PacketDescriptor.PlaybackScope.UNSAFE, descriptor.playbackScope());
            assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                    marker, PlaybackEmissionMode.LIVE, registry));
            assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                    marker, PlaybackEmissionMode.RECONSTRUCTION, registry));
        }
    }

    @Test
    void routesViewerLocalDifficultyOnlyThroughLivePolicyBoundary() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 10)
                .orElseThrow();
        RawPacketFrame changeDifficulty = frame(10);

        assertEquals("play.clientbound.minecraft:change_difficulty", descriptor.typeName());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                descriptor.playbackScope());
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                changeDifficulty, PlaybackEmissionMode.LIVE, registry));
        assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                changeDifficulty, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void replaysBossEventsDuringLivePlaybackAndReconstruction() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 9)
                .orElseThrow();
        RawPacketFrame bossEvent = frame(9);

        assertEquals("play.clientbound.minecraft:boss_event", descriptor.typeName());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                bossEvent, PlaybackEmissionMode.LIVE, registry));
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                bossEvent, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void replaysBlockUpdatesDuringLivePlaybackAndReconstruction() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 8)
                .orElseThrow();
        RawPacketFrame blockUpdate = frame(8);

        assertEquals("play.clientbound.minecraft:block_update", descriptor.typeName());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                blockUpdate, PlaybackEmissionMode.LIVE, registry));
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                blockUpdate, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void replaysBlockEventsOnlyDuringLivePlayback() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 7)
                .orElseThrow();
        RawPacketFrame blockEvent = frame(7);

        assertEquals("play.clientbound.minecraft:block_event", descriptor.typeName());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                blockEvent, PlaybackEmissionMode.LIVE, registry));
        assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                blockEvent, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void replaysBlockEntityDataDuringLivePlaybackAndReconstruction() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 6)
                .orElseThrow();
        RawPacketFrame blockEntityData = frame(6);

        assertEquals("play.clientbound.minecraft:block_entity_data", descriptor.typeName());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                blockEntityData, PlaybackEmissionMode.LIVE, registry));
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                blockEntityData, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void replaysBlockDestructionOnlyDuringLivePlayback() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 5)
                .orElseThrow();
        RawPacketFrame blockDestruction = frame(5);

        assertEquals("play.clientbound.minecraft:block_destruction", descriptor.typeName());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                blockDestruction, PlaybackEmissionMode.LIVE, registry));
        assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                blockDestruction, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void neverReplaysRecordedBlockChangedAcknowledgements() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PacketRegistry registry = Paper26_3PacketRegistry.discover();
        PacketDescriptor descriptor = registry.find(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 4)
                .orElseThrow();
        RawPacketFrame acknowledgement = frame(4);

        assertEquals("play.clientbound.minecraft:block_changed_ack", descriptor.typeName());
        assertFalse(descriptor.replayable());
        assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                acknowledgement, PlaybackEmissionMode.LIVE, registry));
        assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                acknowledgement, PlaybackEmissionMode.RECONSTRUCTION, registry));
    }

    @Test
    void reconstructionKeepsDurableStateAndSkipsTransientFrames() {
        PacketRegistry registry = PacketRegistry.of(List.of(
                descriptor(7, PacketDisposition.STATEFUL, true),
                descriptor(8, PacketDisposition.EPHEMERAL, false)));
        RawPacketFrame durable = frame(7);
        RawPacketFrame transientFrame = frame(8);

        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                durable, PlaybackEmissionMode.RECONSTRUCTION, registry));
        assertFalse(Paper26_3PlaybackBridge.shouldReplay(
                transientFrame, PlaybackEmissionMode.RECONSTRUCTION, registry));
        assertTrue(Paper26_3PlaybackBridge.shouldReplay(
                transientFrame, PlaybackEmissionMode.LIVE, registry));
    }

    @Test
    void extractsPlayerFirstObserverAnchorsFromCheckpointEntities() {
        PacketRegistry registry = PacketRegistry.of(List.of(new PacketDescriptor(
                "minecraft:add_entity",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                7,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "test")));
        PacketCodec<Object> codec = new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                String entityKey = switch (frame.payload()[0]) {
                    case 1 -> "minecraft:player";
                    case 2 -> "minecraft:armor_stand";
                    default -> "minecraft:cow";
                };
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.parse(entityKey));
                return new ClientboundAddEntityPacket(
                        frame.sequence(),
                        UUID.randomUUID(),
                        frame.sequence() * 10.0D,
                        70.0D,
                        -20.0D,
                        15.0F,
                        30.0F,
                        type,
                        0,
                        Vec3.ZERO,
                        30.0D);
            }
        };
        List<RawPacketFrame> frames = List.of(
                new RawPacketFrame(0L, 0L, 1, PacketPhase.PLAY, 7, new byte[] {0}),
                new RawPacketFrame(0L, 0L, 2, PacketPhase.PLAY, 7, new byte[] {1}),
                new RawPacketFrame(0L, 0L, 3, PacketPhase.PLAY, 7, new byte[] {2}));

        List<ObserverAnchor> anchors = Paper26_3PlaybackBridge.observerAnchors(
                frames, registry, codec);

        assertEquals(2, anchors.size());
        assertEquals(ObserverAnchor.Kind.PLAYER, anchors.get(0).kind());
        assertEquals(20.0D, anchors.get(0).x());
        assertEquals(ObserverAnchor.Kind.ENTITY, anchors.get(1).kind());
    }

    private static PacketDescriptor descriptor(
            int packetId,
            PacketDisposition disposition,
            boolean checkpointRelevant) {
        return new PacketDescriptor(
                "minecraft:test_" + packetId,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                disposition,
                true,
                true,
                checkpointRelevant,
                "test/" + packetId);
    }

    private static RawPacketFrame frame(int packetId) {
        return new RawPacketFrame(
                0L, 0L, packetId, PacketPhase.PLAY, packetId, new byte[] {1});
    }
}
