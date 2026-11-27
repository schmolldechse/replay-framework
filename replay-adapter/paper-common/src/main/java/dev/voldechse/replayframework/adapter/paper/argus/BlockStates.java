package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The blocks of one watch with state the scene does not show, such as the
 * contents of chests, inside what the watch's eyes cover.
 *
 * <p>Every chunk of the coverage is read once per sampling interval. The
 * chunks are spread over the ticks of the interval by a fixed hash, so a
 * large storage hall costs a little every tick instead of a lot at once. A
 * block that is new to the recording, or whose signature changed, gets its
 * complete state published; a block that is gone from a chunk gets a removal.
 * A chunk that leaves the coverage or unloads is forgotten silently and read
 * completely again when it returns.</p>
 *
 * <p>Runs on the server thread.</p>
 */
final class BlockStates {

    private final ArgusServices services;
    private final UUID observationId;
    private final long intervalTicks;
    private final Map<Chunk, Map<BlockPosition, Long>> known = new HashMap<>();
    private long tick;

    BlockStates(ArgusServices services, UUID observationId, long intervalTicks) {
        this.services = Objects.requireNonNull(services, "services");
        this.observationId = Objects.requireNonNull(observationId, "observationId");
        if (intervalTicks < 1L) {
            throw new IllegalArgumentException("intervalTicks must be positive");
        }
        this.intervalTicks = intervalTicks;
    }

    /** Reads the chunks of the coverage whose turn it is this tick. */
    void tick(RecordingScope coverage) {
        Set<Chunk> chunks = chunks(coverage);
        known.keySet().retainAll(chunks);
        long phase = Math.floorMod(tick++, intervalTicks);
        for (Chunk chunk : chunks) {
            if (Math.floorMod(chunk.hashCode(), intervalTicks) == phase) {
                read(chunk);
            }
        }
    }

    private void read(Chunk chunk) {
        Optional<List<BlockSample>> samples = services.platform().blockStates(chunk.world(), chunk.x(), chunk.z());
        if (samples.isEmpty()) {
            known.remove(chunk);
            return;
        }
        Map<BlockPosition, Long> before = known.getOrDefault(chunk, Map.of());
        Map<BlockPosition, Long> now = new HashMap<>();
        for (BlockSample sample : samples.get()) {
            now.put(sample.position(), sample.signature());
            Long previous = before.get(sample.position());
            if (previous == null || previous != sample.signature()) {
                publish(chunk.world(), sample.packet().get());
            }
        }
        for (BlockPosition gone : before.keySet()) {
            if (!now.containsKey(gone)) {
                publish(chunk.world(), services.platform().blockStateRemoved(gone));
            }
        }
        known.put(chunk, now);
    }

    private void publish(String world, Object packet) {
        services.publisher().publishBlockState(observationId, world, packet);
    }

    /** Every chunk the coverage touches, in a stable order. */
    static Set<Chunk> chunks(RecordingScope coverage) {
        Set<Chunk> chunks = new LinkedHashSet<>();
        for (CuboidRegion region : coverage.regions()) {
            String world = region.world().asString();
            int minX = region.min().x() >> 4;
            int maxX = region.max().x() >> 4;
            int minZ = region.min().z() >> 4;
            int maxZ = region.max().z() >> 4;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    chunks.add(new Chunk(world, x, z));
                }
            }
        }
        return chunks;
    }

    /** A chunk of a world. */
    record Chunk(String world, int x, int z) {
    }
}
