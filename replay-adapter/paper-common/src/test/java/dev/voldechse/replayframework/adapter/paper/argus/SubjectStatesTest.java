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
    void publishesTheCompleteStateFirstAndThenOnlyTheChanges() {
        SubjectStates states = new SubjectStates(services(), OBSERVATION, 1L);

        current = List.of(player("a"));
        states.tick(COVERAGE);
        current = List.of(player("a"));
        states.tick(COVERAGE);
        current = List.of(player("b"));
        states.tick(COVERAGE);

        assertEquals(List.of("Alex:a", "Alex:a->b"), published);
    }

    @Test
    void publishesASubjectCompletelyAgainWhenItReturns() {
        SubjectStates states = new SubjectStates(services(), OBSERVATION, 1L);

        current = List.of(player("a"));
        states.tick(COVERAGE);
        current = List.of();
        states.tick(COVERAGE);
        current = List.of(player("a"));
        states.tick(COVERAGE);

        assertEquals(List.of("Alex:a", "Alex:a"), published);
    }

    @Test
    void looksAtPlayersEveryTickAndAtEntitiesEveryInterval() {
        SubjectStates states = new SubjectStates(services(), OBSERVATION, 3L);

        current = List.of(player("a"), llama("1"));
        states.tick(COVERAGE);
        current = List.of(player("b"), llama("2"));
        states.tick(COVERAGE);
        states.tick(COVERAGE);
        states.tick(COVERAGE);

        assertEquals(List.of("Alex:a", "minecraft:llama:1", "Alex:a->b", "minecraft:llama:1->2"), published);
    }

    private static SubjectSample player(String state) {
        return sample(PLAYER, SubjectSample.Cadence.EVERY_TICK, state);
    }

    private static SubjectSample llama(String state) {
        return sample(LLAMA, SubjectSample.Cadence.INTERVAL, state);
    }

    private static SubjectSample sample(CaptureSubject subject, SubjectSample.Cadence cadence, String state) {
        return new SubjectSample(subject, cadence, state.hashCode(), () -> new Reading(subject.label(), state));
    }

    /** A reading that names its changes after the states it connects. */
    private record Reading(String label, String state) implements SubjectSample.Reading {
        @Override
        public List<Object> packets() {
            return List.of(label + ":" + state);
        }

        @Override
        public List<Object> changesSince(SubjectSample.Reading previous) {
            return List.of(label + ":" + ((Reading) previous).state() + "->" + state);
        }
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
            public void publishBlockState(UUID observationId, String world, Object packet) {
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
            public List<SubjectSample> subjects(
                    String world, Collection<CuboidRegion> areas, SubjectSample.Cadence cadence) {
                assertEquals("minecraft:overworld", world);
                return current.stream().filter(sample -> sample.cadence() == cadence).toList();
            }

            @Override
            public Optional<List<BlockSample>> blockStates(String world, int chunkX, int chunkZ) {
                return Optional.of(List.of());
            }

            @Override
            public Object blockStateRemoved(BlockPosition position) {
                return position;
            }
        };
        return new ArgusServices(platform, publisher, () -> 0L, failure -> {
            throw new AssertionError(failure);
        }, () -> false);
    }
}
