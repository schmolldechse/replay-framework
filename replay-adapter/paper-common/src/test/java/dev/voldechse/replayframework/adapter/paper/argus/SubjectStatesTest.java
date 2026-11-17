package dev.voldechse.replayframework.adapter.paper.argus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.TrackSubject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class SubjectStatesTest {

    private static final UUID OBSERVATION = UUID.randomUUID();
    private static final CaptureSubject PLAYER =
            new CaptureSubject(TrackSubject.player(UUID.randomUUID()), "Alex");
    private static final CaptureSubject LLAMA =
            new CaptureSubject(TrackSubject.entity(UUID.randomUUID()), "minecraft:llama");
    private static final RecordingScope COVERAGE = RecordingScope.builder()
            .addRegion(new CuboidRegion(Key.key("minecraft:overworld"),
                    new BlockPosition(0, 0, 0), new BlockPosition(15, 15, 15)))
            .build();

    private final List<String> published = new ArrayList<>();
    private List<SubjectSample> current = List.of();

    @Test
    void publishesAPlayerOnceAndAnEntityWheneverItChanges() {
        SubjectStates states = new SubjectStates(services(), OBSERVATION, 1L);

        current = List.of(player(), llama(1L));
        states.tick(COVERAGE);
        current = List.of(player(), llama(1L));
        states.tick(COVERAGE);
        current = List.of(player(), llama(2L));
        states.tick(COVERAGE);

        assertEquals(List.of("Alex", "minecraft:llama:1", "minecraft:llama:2"), published);
    }

    @Test
    void publishesASubjectAgainWhenItReturns() {
        SubjectStates states = new SubjectStates(services(), OBSERVATION, 1L);

        current = List.of(player());
        states.tick(COVERAGE);
        current = List.of();
        states.tick(COVERAGE);
        current = List.of(player());
        states.tick(COVERAGE);

        assertEquals(List.of("Alex", "Alex"), published);
    }

    @Test
    void samplesOnlyEveryInterval() {
        SubjectStates states = new SubjectStates(services(), OBSERVATION, 3L);

        current = List.of(llama(1L));
        states.tick(COVERAGE);
        current = List.of(llama(2L));
        states.tick(COVERAGE);
        states.tick(COVERAGE);
        states.tick(COVERAGE);

        assertEquals(List.of("minecraft:llama:1", "minecraft:llama:2"), published);
    }

    private static SubjectSample player() {
        return new SubjectSample(PLAYER, SubjectSample.Source.OBSERVED, 0L, () -> List.of("Alex"));
    }

    private static SubjectSample llama(long signature) {
        return new SubjectSample(LLAMA, SubjectSample.Source.SAMPLED, signature,
                () -> List.of("minecraft:llama:" + signature));
    }

    private ArgusServices services() {
        ArgusPublisher publisher = new ArgusPublisher() {
            @Override
            public void publish(UUID observationId, UUID sceneId, String world, Object packet) {
            }

            @Override
            public void publishState(UUID observationId, CaptureSubject subject, Object packet) {
                assertEquals(OBSERVATION, observationId);
                published.add((String) packet);
            }

            @Override
            public void sceneClosed(UUID sceneId) {
            }
        };
        ArgusPlatform platform = new ArgusPlatform() {
            @Override
            public ArgusEye openEye(EyeSpec spec, Consumer<Object> packets) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Object> unbundle(Object packet) {
                return List.of(packet);
            }

            @Override
            public Optional<ClientboundPacketDefinition<?>> definition(Object packet) {
                return Optional.empty();
            }

            @Override
            public Object forgetChunk(int chunkX, int chunkZ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object removeEntities(int[] entityIds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int trackingRangeBlocks(String world) {
                return 48;
            }

            @Override
            public AmbientSource ambient(String world) {
                return List::of;
            }

            @Override
            public List<PlayerAnchor> players() {
                return List.of();
            }

            @Override
            public Object removePlayerInfo(List<UUID> players) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<SubjectSample> subjects(String world, Collection<CuboidRegion> areas) {
                assertEquals("minecraft:overworld", world);
                return current;
            }
        };
        return new ArgusServices(platform, publisher, () -> 0L, failure -> {
            throw new AssertionError(failure);
        }, () -> false);
    }
}
