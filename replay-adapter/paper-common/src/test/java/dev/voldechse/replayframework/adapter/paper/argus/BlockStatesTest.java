package dev.voldechse.replayframework.adapter.paper.argus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class BlockStatesTest {

    private static final UUID OBSERVATION = UUID.randomUUID();
    private static final String WORLD = "minecraft:overworld";
    /** Four chunks: 0..1 on both axes. */
    private static final RecordingScope COVERAGE = RecordingScope.builder()
            .addRegion(new CuboidRegion(Key.key(WORLD),
                    new BlockPosition(0, 0, 0), new BlockPosition(31, 15, 31)))
            .build();
    private static final BlockPosition CHEST = new BlockPosition(1, 64, 1);
    private static final BlockPosition BARREL = new BlockPosition(2, 64, 1);

    private final List<String> published = new ArrayList<>();
    private final Map<String, List<BlockSample>> chunks = new HashMap<>();
    private final List<String> reads = new ArrayList<>();

    @Test
    void publishesNewAndChangedBlocksAndTheirRemoval() {
        BlockStates states = new BlockStates(services(), OBSERVATION, 1L);

        chunks.put("0,0", List.of(block(CHEST, "a"), block(BARREL, "x")));
        states.tick(COVERAGE);
        chunks.put("0,0", List.of(block(CHEST, "a"), block(BARREL, "y")));
        states.tick(COVERAGE);
        chunks.put("0,0", List.of(block(BARREL, "y")));
        states.tick(COVERAGE);

        assertEquals(List.of("1,64,1=a", "2,64,1=x", "2,64,1=y", "removed 1,64,1"), published);
    }

    @Test
    void readsEveryChunkOncePerIntervalSpreadOverItsTicks() {
        BlockStates states = new BlockStates(services(), OBSERVATION, 4L);

        for (int tick = 0; tick < 8; tick++) {
            states.tick(COVERAGE);
        }

        assertEquals(8, reads.size());
        for (String chunk : List.of("0,0", "0,1", "1,0", "1,1")) {
            assertEquals(2, reads.stream().filter(chunk::equals).count(), chunk);
        }
    }

    @Test
    void publishesAChunkCompletelyAgainAfterItUnloaded() {
        BlockStates states = new BlockStates(services(), OBSERVATION, 1L);

        chunks.put("0,0", List.of(block(CHEST, "a")));
        states.tick(COVERAGE);
        chunks.remove("0,0");
        states.tick(COVERAGE);
        chunks.put("0,0", List.of(block(CHEST, "a")));
        states.tick(COVERAGE);

        assertEquals(List.of("1,64,1=a", "1,64,1=a"), published);
    }

    @Test
    void coversEveryChunkTheRegionsTouch() {
        assertEquals(4, BlockStates.chunks(COVERAGE).size());
    }

    private static BlockSample block(BlockPosition position, String state) {
        return new BlockSample(position, state.hashCode(),
                () -> position.x() + "," + position.y() + "," + position.z() + "=" + state);
    }

    private ArgusServices services() {
        ArgusPublisher publisher = new ArgusPublisher() {
            @Override
            public void publish(UUID observationId, UUID sceneId, String world, Object packet) {
            }

            @Override
            public void publishState(UUID observationId, CaptureSubject subject, Object packet) {
            }

            @Override
            public void publishBlockState(UUID observationId, String world, Object packet) {
                assertEquals(OBSERVATION, observationId);
                assertEquals(WORLD, world);
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
            public List<SubjectSample> subjects(
                    String world, Collection<CuboidRegion> areas, SubjectSample.Cadence cadence) {
                return List.of();
            }

            @Override
            public Optional<List<BlockSample>> blockStates(String world, int chunkX, int chunkZ) {
                String key = chunkX + "," + chunkZ;
                reads.add(key);
                // Chunk 0,0 is unloaded unless a test put blocks in it.
                if (key.equals("0,0")) {
                    return Optional.ofNullable(chunks.get(key));
                }
                return Optional.of(List.of());
            }

            @Override
            public Object blockStateRemoved(BlockPosition position) {
                return "removed " + position.x() + "," + position.y() + "," + position.z();
            }
        };
        return new ArgusServices(platform, publisher, () -> 0L, failure -> {
            throw new AssertionError(failure);
        }, () -> false);
    }
}
