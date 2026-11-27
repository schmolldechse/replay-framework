package dev.voldechse.replayframework.adapter.paper.v26_3;

import dev.voldechse.replayframework.adapter.paper.checkpoint.CheckpointAssembler;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.adapter.paper.checkpoint.CheckpointAssembler;
import dev.voldechse.replayframework.adapter.paper.checkpoint.CheckpointSnapshot;
import dev.voldechse.replayframework.adapter.paper.checkpoint.EncodedPacket;
import dev.voldechse.replayframework.adapter.paper.checkpoint.PacketBlueprint;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplayCheckpointWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.kyori.adventure.key.Key;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Paper26_3CheckpointFixtureTest {
    @BeforeAll
    static void bootstrapPaperRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.server.Bootstrap.validate();
    }

    private static final Packet<PacketListener> TEST_PACKET = new Packet<>() {
        @Override
        public PacketType<? extends Packet<PacketListener>> type() {
            throw new UnsupportedOperationException("fixture packet has no wire type");
        }

        @Override
        public void handle(PacketListener listener) {
            throw new UnsupportedOperationException("fixture packet cannot be handled");
        }
    };

    @Test
    void ordersQualifiedEntityTargetsWithStableTotalOrder() {
        DescriptorSpec metadata = descriptor("minecraft:set_entity_data", 6);
        DescriptorSpec attributes = descriptor("minecraft:update_attributes", 7);
        PacketRegistry registry = PacketRegistry.of(List.of(
                metadata.descriptor(),
                attributes.descriptor()));
        Map<String, Integer> packetIds = Map.of(
                metadata.descriptor().typeName(), metadata.packetId(),
                attributes.descriptor().typeName(), attributes.packetId());

        List<PacketBlueprint> packets = new ArrayList<>();
        for (int entityId = 48; entityId >= 1; entityId--) {
            packets.add(blueprint(
                    "minecraft:update_attributes",
                    PacketCheckpointFamily.ENTITY_METADATA,
                    "minecraft:world",
                    "entity:" + entityId + ":attributes"));
            packets.add(blueprint(
                    "minecraft:set_entity_data",
                    PacketCheckpointFamily.ENTITY_METADATA,
                    "minecraft:world",
                    "entity:" + entityId));
        }

        List<String> encodedTargets = new ArrayList<>();
        CheckpointAssembler encoder = new CheckpointAssembler(
                registry,
                Paper26_3ClientboundPacketCatalog.catalog(),
                ignored -> CompletableFuture.completedFuture(
                        new CheckpointSnapshot(packets)),
                blueprint -> {
                    encodedTargets.add(blueprint.targetKey());
                    int packetId = packetIds.get(blueprint.descriptorTypeName());
                    return new EncodedPacket(
                            blueprint.descriptorTypeName(),
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            packetId,
                            blueprint.family(),
                            blueprint.worldKey(),
                            blueprint.targetKey(),
                            new byte[]{(byte) packetId});
                },
                directExecutor());

        ReplayCheckpoint checkpoint = encoder.encode(new CheckpointEncoder.CheckpointRequest(
                RecordingScope.builder().build(),
                0L,
                0L,
                CheckpointEncoder.CheckpointKind.INITIAL)).toCompletableFuture().join();

        List<String> expectedTargets = new ArrayList<>();
        for (int entityId = 1; entityId <= 48; entityId++) {
            expectedTargets.add("entity:" + entityId);
            expectedTargets.add("entity:" + entityId + ":attributes");
        }
        assertEquals(expectedTargets, encodedTargets);
        assertEquals(expectedTargets.size(), checkpoint.initializationFrames().size());
    }

    @Test
    void encodesAllCheckpointFamiliesInStableOrder(@TempDir Path temporaryDirectory) throws Exception {
        List<DescriptorSpec> specifications = List.of(
                descriptor("minecraft:set_default_spawn_position", 1),
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

        CheckpointSnapshot snapshot =
                new CheckpointSnapshot(
                        new CheckpointEncoder.CapturePoint(150L, 8L, 4),
                        List.of(
                        blueprint("minecraft:update_mob_effect", PacketCheckpointFamily.RUNNING_EFFECT, "minecraft:world", "entity:9"),
                        blueprint("minecraft:set_equipment", PacketCheckpointFamily.EQUIPMENT, "minecraft:world", "entity:9"),
                        blueprint("minecraft:add_entity", PacketCheckpointFamily.ENTITY_SPAWN, "minecraft:world", "entity:9"),
                        blueprint("minecraft:block_entity_data", PacketCheckpointFamily.BLOCK_ENTITY, "minecraft:world", "block:1,64,1"),
                        blueprint("minecraft:set_default_spawn_position", PacketCheckpointFamily.DIMENSION_SPAWN, "minecraft:world", "spawn"),
                        blueprint("minecraft:set_entity_data", PacketCheckpointFamily.ENTITY_METADATA, "minecraft:world", "entity:9"),
                        blueprint("minecraft:set_time", PacketCheckpointFamily.GLOBAL_STATE, "minecraft:world", "time"),
                        blueprint("minecraft:set_passengers", PacketCheckpointFamily.PASSENGERS, "minecraft:world", "entity:9"),
                        blueprint("minecraft:level_chunk_with_light", PacketCheckpointFamily.CHUNK_LIGHT, "minecraft:world", "chunk:0,0")));

        CheckpointAssembler encoder = new CheckpointAssembler(
                registry,
                Paper26_3ClientboundPacketCatalog.catalog(),
                ignored -> CompletableFuture.completedFuture(snapshot),
                blueprint -> new EncodedPacket(
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

        CheckpointEncoder.CheckpointCapture capture = encoder.capture(
                new CheckpointEncoder.CheckpointRequest(
                        RecordingScope.builder().build(),
                        42L,
                        7L,
                        CheckpointEncoder.CheckpointKind.PERIODIC,
                        100L))
                .toCompletableFuture()
                .join();
        assertEquals(new CheckpointEncoder.CapturePoint(150L, 8L, 4), capture.point());
        assertTrue(capture.initializationFrames().stream()
                .allMatch(frame -> frame.elapsedNanos() == 50L && frame.serverTick() == 8L));

        dev.voldechse.replayframework.format.TrackDescriptor track = checkpoint.initializationFrames()
                .getFirst().world()
                .map(world -> dev.voldechse.replayframework.format.TrackDescriptor.world(1, world))
                .orElse(dev.voldechse.replayframework.format.TrackDescriptor.global(0));
        Path checkpointPath = temporaryDirectory.resolve("initial.rfc");
        new ReplayCheckpointWriter(checkpointPath, "paper-26.3", track)
                .write(checkpoint);
        ReplayCheckpoint decoded = new ReplayCheckpointReader().read(checkpointPath, track);
        assertEquals(checkpoint.ordinal(), decoded.ordinal());
        assertEquals(checkpoint.elapsedNanos(), decoded.elapsedNanos());
        assertEquals(checkpoint.initializationFrames(), decoded.initializationFrames());

        byte[] truncated = Arrays.copyOf(Files.readAllBytes(checkpointPath),
                Files.size(checkpointPath) > 0L ? Math.toIntExact(Files.size(checkpointPath) - 1L) : 0);
        Path truncatedPath = temporaryDirectory.resolve("initial-truncated.rfc");
        Files.write(truncatedPath, truncated);
        assertThrows(CorruptReplayArtifactException.class,
                () -> new ReplayCheckpointReader().read(truncatedPath, track));
    }

    @Test
    void refusesCheckpointRelevantDescriptorWithoutSafeGenerator() {
        PacketRegistry registry = PacketRegistry.of(List.of(
                descriptor("minecraft:block_changed_ack", 12).descriptor()));

        assertThrows(IncompatibleAdapterException.class,
                () -> new CheckpointAssembler(
                        registry,
                        Paper26_3ClientboundPacketCatalog.catalog(),
                        ignored -> CompletableFuture.completedFuture(
                                new CheckpointSnapshot(List.of())),
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

    private static PacketBlueprint blueprint(
            String typeName,
            PacketCheckpointFamily family,
            String worldKey,
            String targetKey) {
        return new PacketBlueprint(
                "play.clientbound." + typeName,
                family,
                worldKey,
                targetKey,
                TEST_PACKET);
    }

    private record DescriptorSpec(String typeName, int packetId, PacketDescriptor descriptor) {
    }
}
