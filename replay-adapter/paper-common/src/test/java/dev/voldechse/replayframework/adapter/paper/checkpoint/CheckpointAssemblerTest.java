package dev.voldechse.replayframework.adapter.paper.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.TrackSubject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class CheckpointAssemblerTest {

    private static final String WORLD = "minecraft:world";

    private static final TestDefinition CHUNK = new TestDefinition(
            10, "minecraft:test_chunk", PacketCheckpointFamily.CHUNK_LIGHT, false, true);
    private static final TestDefinition PLAYER_INFO = new TestDefinition(
            40, "minecraft:test_player_info", PacketCheckpointFamily.GLOBAL_STATE, true, true);
    private static final TestDefinition SPAWN = new TestDefinition(
            20, "minecraft:test_spawn", PacketCheckpointFamily.ENTITY_SPAWN, false, true);
    private static final TestDefinition SOUND = new TestDefinition(
            30, "minecraft:test_sound", null, false, false);

    @Test
    void ordersChecksAndNumbersTheScene() {
        CheckpointSnapshot snapshot = new CheckpointSnapshot(List.of(
                blueprint(SPAWN, "entity:10"),
                blueprint(SOUND, "sound:1"),
                blueprint(SPAWN, "entity:9"),
                blueprint(PLAYER_INFO, "player:1"),
                blueprint(CHUNK, "chunk:0,0")));

        CheckpointEncoder.CheckpointCapture capture = assembler(snapshot, CheckpointAssemblerTest::encode)
                .capture(request()).toCompletableFuture().join();

        List<RawPacketFrame> frames = capture.initializationFrames();
        assertEquals(List.of("chunk:0,0", "player:1", "entity:9", "entity:10"),
                frames.stream().map(frame -> new String(frame.payload(), StandardCharsets.UTF_8)).toList());
        assertEquals(List.of(0, 1, 2, 3), frames.stream().map(RawPacketFrame::sequence).toList());
        assertEquals(List.of(10, 40, 20, 20), frames.stream().map(RawPacketFrame::packetId).toList());
        // The tab list applies in every world, the rest in its own.
        assertEquals(Optional.of(WORLD), frames.get(0).world());
        assertEquals(Optional.empty(), frames.get(1).world());
    }

    @Test
    void encodesSubjectsAndBlockStatesNumberedOnTheirOwn() {
        CaptureSubject subject = new CaptureSubject(TrackSubject.entity(UUID.randomUUID()), "minecraft:llama");
        CheckpointSnapshot snapshot = new CheckpointSnapshot(
                Optional.empty(),
                List.of(),
                List.of(new CheckpointSnapshot.Subject(subject, List.of("a", "b"))),
                Map.of("minecraft:z", List.of("z"), "minecraft:a", List.of("a")));
        CheckpointCodec codec = new CheckpointCodec() {
            @Override
            public EncodedPacket encode(PacketBlueprint blueprint) {
                throw new AssertionError("no scene packets");
            }

            @Override
            public StatePacket encodeStatePacket(Object nativePacket) {
                return new StatePacket(7, nativePacket.toString().getBytes(StandardCharsets.UTF_8));
            }
        };

        CheckpointEncoder.CheckpointCapture capture = assembler(snapshot, codec)
                .capture(request()).toCompletableFuture().join();

        assertEquals(1, capture.subjects().size());
        assertEquals(subject, capture.subjects().getFirst().subject());
        assertEquals(List.of(0, 1),
                capture.subjects().getFirst().frames().stream().map(RawPacketFrame::sequence).toList());
        assertEquals(List.of("minecraft:a", "minecraft:z"),
                capture.blockStates().stream().map(CheckpointEncoder.BlockStates::world).toList());
        assertEquals(Optional.of("minecraft:a"), capture.blockStates().getFirst().frames().getFirst().world());
    }

    @Test
    void refusesACodecThatChangesTheTarget() {
        CheckpointSnapshot snapshot = new CheckpointSnapshot(List.of(blueprint(SPAWN, "entity:9")));
        CheckpointAssembler assembler = assembler(snapshot, blueprint -> new EncodedPacket(
                blueprint.descriptorTypeName(), PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND,
                SPAWN.packetId(), blueprint.family(), blueprint.worldKey(), "entity:10", new byte[0]));

        CompletableFuture<CheckpointEncoder.CheckpointCapture> capture =
                assembler.capture(request()).toCompletableFuture();

        Throwable failure = assertThrows(Exception.class, capture::join);
        assertTrue(failure.getCause() instanceof IncompatibleAdapterException, failure.toString());
    }

    @Test
    void refusesACheckpointPacketWithoutFamily() {
        TestDefinition orphan = new TestDefinition(50, "minecraft:test_orphan", null, false, true);
        PacketRegistry registry = PacketRegistry.of(List.of(orphan.descriptor()));

        assertThrows(IncompatibleAdapterException.class, () -> new CheckpointAssembler(
                registry,
                new ClientboundPacketDefinitionCatalog<>(List.of(orphan)),
                ignored -> CompletableFuture.completedFuture(new CheckpointSnapshot(List.of())),
                CheckpointAssemblerTest::encode,
                Runnable::run));
    }

    @Test
    void ordersTargetKeysByTheirNumbers() {
        assertTrue(CheckpointAssembler.compareTargetKeys("entity:9", "entity:10") < 0);
        assertTrue(CheckpointAssembler.compareTargetKeys("chunk:-1,2", "chunk:0,2") < 0);
        assertTrue(CheckpointAssembler.compareTargetKeys("chunk:0,0", "entity:0") < 0);
    }

    private static CheckpointAssembler assembler(CheckpointSnapshot snapshot, CheckpointCodec codec) {
        List<TestDefinition> definitions = List.of(CHUNK, PLAYER_INFO, SPAWN, SOUND);
        return new CheckpointAssembler(
                PacketRegistry.of(definitions.stream().map(TestDefinition::descriptor).toList()),
                new ClientboundPacketDefinitionCatalog<>(definitions),
                ignored -> CompletableFuture.completedFuture(snapshot),
                codec,
                Runnable::run);
    }

    private static EncodedPacket encode(PacketBlueprint blueprint) {
        int packetId = List.of(CHUNK, PLAYER_INFO, SPAWN, SOUND).stream()
                .filter(definition -> definition.typeName().equals(blueprint.descriptorTypeName()))
                .findFirst().orElseThrow().packetId();
        return new EncodedPacket(
                blueprint.descriptorTypeName(), PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND,
                packetId, blueprint.family(), blueprint.worldKey(), blueprint.targetKey(),
                blueprint.targetKey().getBytes(StandardCharsets.UTF_8));
    }

    private static PacketBlueprint blueprint(TestDefinition definition, String target) {
        return new PacketBlueprint(
                definition.typeName(),
                definition.checkpointFamily().orElse(PacketCheckpointFamily.GLOBAL_STATE),
                WORLD,
                target,
                new Object());
    }

    private static CheckpointEncoder.CheckpointRequest request() {
        return new CheckpointEncoder.CheckpointRequest(
                RecordingScope.builder().addWorld(net.kyori.adventure.key.Key.key(WORLD)).build(),
                0L, 0L, CheckpointEncoder.CheckpointKind.INITIAL);
    }

    /** A definition with nothing but its checkpoint family and placement. */
    private static final class TestDefinition extends ClientboundPacketDefinition<Object> {
        private final PacketCheckpointFamily family;
        private final boolean worldWide;
        private final boolean checkpointRelevant;

        private TestDefinition(
                int packetId, String identifier, PacketCheckpointFamily family, boolean worldWide,
                boolean checkpointRelevant) {
            super(packetId, identifier, checkpointRelevant
                    ? PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE)
                    : PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE));
            this.family = family;
            this.worldWide = worldWide;
            this.checkpointRelevant = checkpointRelevant;
        }

        @Override
        public Optional<PacketCheckpointFamily> checkpointFamily() {
            return Optional.ofNullable(family);
        }

        @Override
        public boolean worldWide() {
            return worldWide;
        }

        @Override
        public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
            return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
        }

        PacketDescriptor descriptor() {
            return new PacketDescriptor(typeName(), PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND,
                    packetId(), checkpointRelevant ? PacketDisposition.STATEFUL : PacketDisposition.EPHEMERAL,
                    true, true, checkpointRelevant, "test/" + typeName(), PacketDescriptor.PlaybackScope.SAFE);
        }
    }
}
