package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.Paper26SyntheticStateCollector;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26CheckpointEncoder;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplayCheckpointWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Paper26CheckpointFixtureTest {

    @Test
    void encodesAllCheckpointFamiliesInStableOrder(@TempDir Path temporaryDirectory) throws Exception {
        List<DescriptorSpec> specifications = List.of(
                descriptor("minecraft:login", 1),
                descriptor("minecraft:level_chunk_with_light", 2),
                descriptor("minecraft:block_entity_data", 3),
                descriptor("minecraft:set_time", 4),
                descriptor("minecraft:add_entity", 5),
                descriptor("minecraft:set_entity_data", 6),
                descriptor("minecraft:set_equipment", 7),
                descriptor("minecraft:set_passengers", 8),
                descriptor("minecraft:update_mob_effect", 9));
        PacketRegistry registry = PacketRegistry.of(
                specifications.stream().map(DescriptorSpec::descriptor).toList());

        Map<String, Integer> packetIds = new HashMap<>();
        for (DescriptorSpec specification : specifications) {
            packetIds.put(specification.descriptor().typeName(), specification.packetId());
        }

        Paper26CheckpointEncoder.CheckpointSnapshot snapshot =
                new Paper26CheckpointEncoder.CheckpointSnapshot(List.of(
                        blueprint("minecraft:update_mob_effect", Paper26CheckpointEncoder.CheckpointPacketFamily.RUNNING_EFFECT, "minecraft:world", "entity:9"),
                        blueprint("minecraft:set_equipment", Paper26CheckpointEncoder.CheckpointPacketFamily.EQUIPMENT, "minecraft:world", "entity:9"),
                        blueprint("minecraft:add_entity", Paper26CheckpointEncoder.CheckpointPacketFamily.ENTITY_SPAWN, "minecraft:world", "entity:9"),
                        blueprint("minecraft:block_entity_data", Paper26CheckpointEncoder.CheckpointPacketFamily.BLOCK_ENTITY, "minecraft:world", "block:1,64,1"),
                        blueprint("minecraft:login", Paper26CheckpointEncoder.CheckpointPacketFamily.DIMENSION_SPAWN, "minecraft:world", "spawn"),
                        blueprint("minecraft:set_entity_data", Paper26CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA, "minecraft:world", "entity:9"),
                        blueprint("minecraft:set_time", Paper26CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE, "minecraft:world", "time"),
                        blueprint("minecraft:set_passengers", Paper26CheckpointEncoder.CheckpointPacketFamily.PASSENGERS, "minecraft:world", "entity:9"),
                        blueprint("minecraft:level_chunk_with_light", Paper26CheckpointEncoder.CheckpointPacketFamily.CHUNK_LIGHT, "minecraft:world", "chunk:0,0")));

        Paper26CheckpointEncoder encoder = new Paper26CheckpointEncoder(
                registry,
                ignored -> CompletableFuture.completedFuture(snapshot),
                blueprint -> new Paper26CheckpointEncoder.EncodedPacket(
                        blueprint.descriptorTypeName(),
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        packetIds.get(blueprint.descriptorTypeName()),
                        blueprint.family(),
                        blueprint.worldKey(),
                        blueprint.targetKey(),
                        new byte[]{(byte) packetIds.get(blueprint.descriptorTypeName()).intValue()}),
                directExecutor());

        ReplayCheckpoint checkpoint = encoder.encode(new CheckpointEncoder.CheckpointRequest(
                RecordingScope.builder().build(),
                42L,
                7L,
                CheckpointEncoder.CheckpointKind.INITIAL)).toCompletableFuture().join();

        assertEquals(0, checkpoint.ordinal());
        assertEquals(42L, checkpoint.elapsedNanos());
        assertIterableEquals(
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9),
                checkpoint.initializationFrames().stream()
                        .map(RawPacketFrame::packetId)
                        .toList());
        assertIterableEquals(
                java.util.stream.IntStream.range(0, 9).boxed().toList(),
                checkpoint.initializationFrames().stream()
                        .map(RawPacketFrame::sequence)
                        .toList());
        assertEquals(
                List.of(42L, 42L, 42L, 42L, 42L, 42L, 42L, 42L, 42L),
                checkpoint.initializationFrames().stream()
                        .map(RawPacketFrame::elapsedNanos)
                        .toList());
        byte[] firstPayload = checkpoint.initializationFrames().get(0).payload();
        assertNotSame(firstPayload, checkpoint.initializationFrames().get(0).payload());

        Path checkpointPath = temporaryDirectory.resolve("initial.rfc");
        new ReplayCheckpointWriter(checkpointPath, Paper26CheckpointEncoder.ADAPTER_ID)
                .write(checkpoint);
        ReplayCheckpoint decoded = new ReplayCheckpointReader().read(checkpointPath);
        assertEquals(checkpoint.ordinal(), decoded.ordinal());
        assertEquals(checkpoint.elapsedNanos(), decoded.elapsedNanos());
        assertEquals(checkpoint.initializationFrames(), decoded.initializationFrames());

        byte[] truncated = Arrays.copyOf(Files.readAllBytes(checkpointPath),
                Files.size(checkpointPath) > 0L ? Math.toIntExact(Files.size(checkpointPath) - 1L) : 0);
        Path truncatedPath = temporaryDirectory.resolve("initial-truncated.rfc");
        Files.write(truncatedPath, truncated);
        assertThrows(CorruptReplayArtifactException.class,
                () -> new ReplayCheckpointReader().read(truncatedPath));
    }

    @Test
    void emitsOnlyUnobservedStateChangesInsideTheRecordingScope() {
        PacketDescriptor descriptor = descriptor("minecraft:block_update", 11).descriptor();
        PacketRegistry registry = PacketRegistry.of(List.of(descriptor));
        RecordingScope scope = RecordingScope.builder()
                .addRegion(new CuboidRegion(
                        Key.key("minecraft:world"),
                        new BlockPosition(0, 0, 0),
                        new BlockPosition(15, 255, 15)))
                .build();

        Paper26SyntheticStateCollector collector = new Paper26SyntheticStateCollector(
                registry,
                scope,
                packet -> new Paper26SyntheticStateCollector.ObservedStateKey(
                        packet.serverTick(),
                        Paper26CheckpointEncoder.CheckpointPacketFamily.BLOCK_ENTITY,
                        "minecraft:world",
                        "block:1,64,1"),
                delta -> new Paper26SyntheticStateCollector.SyntheticStatePacket(
                        delta.captureTimeNanos(),
                        delta.serverTick(),
                        PacketPhase.PLAY,
                        delta.packetId(),
                        delta.family(),
                        delta.worldKey(),
                        delta.targetKey(),
                        delta.payload()));

        collector.observe(new CaptureBridge.CapturePacket(
                UUID.randomUUID(),
                100L,
                4L,
                0,
                PacketPhase.PLAY,
                11,
                new byte[]{7}));

        Paper26SyntheticStateCollector.StateDelta observed = stateDelta(
                101L, 4L, "block:1,64,1", new BlockPosition(1, 64, 1));
        assertTrue(collector.collect(observed).isEmpty());

        Paper26SyntheticStateCollector.StateDelta unobserved = stateDelta(
                102L, 4L, "block:2,64,2", new BlockPosition(2, 64, 2));
        List<Paper26SyntheticStateCollector.SyntheticStatePacket> emitted =
                collector.collect(unobserved);
        assertEquals(1, emitted.size());
        assertEquals("block:2,64,2", emitted.get(0).targetKey());
        assertArrayEquals(new byte[]{2}, emitted.get(0).payload());

        byte[] mutablePayload = emitted.get(0).payload();
        mutablePayload[0] = 99;
        assertArrayEquals(new byte[]{2}, emitted.get(0).payload());
        assertTrue(collector.collect(unobserved).isEmpty());

        Paper26SyntheticStateCollector.StateDelta nextTick = stateDelta(
                200L, 5L, "block:2,64,2", new BlockPosition(2, 64, 2));
        assertEquals(1, collector.collect(nextTick).size());

        Paper26SyntheticStateCollector.StateDelta outsideScope = stateDelta(
                201L, 5L, "block:20,64,20", new BlockPosition(20, 64, 20));
        assertTrue(collector.collect(outsideScope).isEmpty());
    }

    @Test
    void refusesCheckpointRelevantDescriptorWithoutSafeGenerator() {
        PacketRegistry registry = PacketRegistry.of(List.of(
                descriptor("minecraft:block_changed_ack", 12).descriptor()));

        assertThrows(IncompatibleAdapterException.class,
                () -> new Paper26CheckpointEncoder(
                        registry,
                        ignored -> CompletableFuture.completedFuture(
                                new Paper26CheckpointEncoder.CheckpointSnapshot(List.of())),
                        ignored -> null,
                        directExecutor()));
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }

    private static DescriptorSpec descriptor(String typeName, int packetId) {
        return new DescriptorSpec(
                typeName,
                packetId,
                new PacketDescriptor(
                        "play.clientbound." + typeName,
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        packetId,
                        PacketDisposition.STATEFUL,
                        true,
                        true,
                        true,
                        "test/" + typeName));
    }

    private static Paper26CheckpointEncoder.PacketBlueprint blueprint(
            String typeName,
            Paper26CheckpointEncoder.CheckpointPacketFamily family,
            String worldKey,
            String targetKey) {
        return new Paper26CheckpointEncoder.PacketBlueprint(
                "play.clientbound." + typeName,
                family,
                worldKey,
                targetKey,
                new ClientboundChangeDifficultyPacket(Difficulty.NORMAL, false));
    }

    private static Paper26SyntheticStateCollector.StateDelta stateDelta(
            long captureTimeNanos,
            long serverTick,
            String targetKey,
            BlockPosition position) {
        return new Paper26SyntheticStateCollector.StateDelta(
                captureTimeNanos,
                serverTick,
                Paper26CheckpointEncoder.CheckpointPacketFamily.BLOCK_ENTITY,
                "minecraft:world",
                targetKey,
                position,
                11,
                new byte[]{(byte) position.x()});
    }

    private record DescriptorSpec(String typeName, int packetId, PacketDescriptor descriptor) {
    }
}
