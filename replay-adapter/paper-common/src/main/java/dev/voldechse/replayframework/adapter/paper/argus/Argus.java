package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.SceneMembership;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Argus, the virtual-observer system: places eyes for recording scopes and
 * merges what the eyes of one recording see into one scene per world.
 *
 * <p>This class is shared by all adapters. It owns planning, lifecycle and
 * merging; an {@link ArgusPlatform} supplies the version-specific eye and
 * packets, and an {@link ArgusPublisher} receives the merged scene.</p>
 *
 * <p>Every watch has scenes of its own. Its eyes define what its recording
 * contains, so nothing a watch publishes depends on another recording.</p>
 *
 * <p>{@link #watch(RecordingScope, UUID)} and closing a watch are safe from
 * any thread. They take effect on the next {@link #tick()}, which the
 * adapter calls once per server tick on the server thread.</p>
 */
public final class Argus implements SceneObservation, AutoCloseable {

    private static final int CHUNK_BLOCKS = 16;
    /** Bounds no world height exceeds. */
    private static final int WORLD_MIN_Y = -2048;
    private static final int WORLD_MAX_Y = 2047;

    private final ArgusPlatform platform;
    private final EyePlacement placement;
    private final ArgusPublisher publisher;
    private final LongSupplier serverTick;
    private final Consumer<Throwable> failureHandler;
    private final Queue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final List<ArgusWatch> watches = new ArrayList<>();
    private boolean closed;

    public Argus(
            ArgusPlatform platform,
            EyePlacement placement,
            ArgusPublisher publisher,
            LongSupplier serverTick,
            Consumer<Throwable> failureHandler) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.placement = Objects.requireNonNull(placement, "placement");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    /** Bounded regions are observed by eyes; whole worlds are not yet. */
    @Override
    public boolean observes(RecordingScope scope) {
        return !Objects.requireNonNull(scope, "scope").regions().isEmpty();
    }

    /**
     * Expands every region to the chunks its eyes receive, over the full
     * height of the world. The entity tracker reaches no farther than that.
     */
    @Override
    public RecordingScope coverage(RecordingScope scope) {
        Objects.requireNonNull(scope, "scope");
        RecordingScope.Builder coverage = RecordingScope.builder()
                .chunkLoadingPolicy(scope.chunkLoadingPolicy());
        scope.worlds().forEach(coverage::addWorld);
        for (CuboidRegion region : scope.regions()) {
            int minX = region.min().x();
            int minZ = region.min().z();
            int maxX = region.max().x();
            int maxZ = region.max().z();
            for (EyeSpec eye : placement.plan(
                    region, platform.trackingRangeBlocks(region.world().asString()))) {
                // The loader sends one chunk more than the view distance.
                int reach = (eye.viewDistance() + 1) * CHUNK_BLOCKS;
                minX = Math.min(minX, (int) Math.floor(eye.x()) - reach);
                minZ = Math.min(minZ, (int) Math.floor(eye.z()) - reach);
                maxX = Math.max(maxX, (int) Math.floor(eye.x()) + reach);
                maxZ = Math.max(maxZ, (int) Math.floor(eye.z()) + reach);
            }
            coverage.addRegion(new CuboidRegion(
                    region.world(),
                    new BlockPosition(minX, WORLD_MIN_Y, minZ),
                    new BlockPosition(maxX, WORLD_MAX_Y, maxZ)));
        }
        return coverage.build();
    }

    @Override
    public Watch watch(RecordingScope scope, UUID observationId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(observationId, "observationId");
        List<EyeSpec> specs = new ArrayList<>();
        for (CuboidRegion region : scope.regions()) {
            specs.addAll(placement.plan(
                    region, platform.trackingRangeBlocks(region.world().asString())));
        }
        ArgusWatch watch = new ArgusWatch(observationId, specs);
        commands.add(() -> open(watch));
        return watch;
    }

    /** Applies pending watch changes, drives the eyes and samples world state. */
    public void tick() {
        if (closed) {
            return;
        }
        try {
            for (Runnable command = commands.poll(); command != null; command = commands.poll()) {
                command.run();
            }
            for (ArgusWatch watch : watches) {
                watch.scenes.values().forEach(WatchScene::tick);
            }
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }

    /** Closes every eye without publishing their teardown. */
    @Override
    public void close() {
        closed = true;
        commands.clear();
        for (ArgusWatch watch : watches) {
            watch.scenes.values().forEach(WatchScene::closeQuietly);
        }
        watches.clear();
    }

    private void open(ArgusWatch watch) {
        if (watch.closed) {
            return;
        }
        for (EyeSpec spec : watch.specs) {
            WatchScene scene = watch.scenes.computeIfAbsent(
                    spec.world(), world -> new WatchScene(watch.observationId, world));
            UUID eyeId = UUID.randomUUID();
            scene.eyes.put(eyeId, platform.openEye(spec, packet -> scene.observe(eyeId, packet)));
        }
        watches.add(watch);
    }

    private void close(ArgusWatch watch) {
        if (watches.remove(watch)) {
            watch.scenes.values().forEach(WatchScene::closeAndUnload);
            watch.scenes.clear();
        }
    }

    /** One recording's eyes. */
    private final class ArgusWatch implements Watch {
        private final UUID observationId;
        private final List<EyeSpec> specs;
        private final Map<String, WatchScene> scenes = new LinkedHashMap<>();
        private volatile boolean closed;

        private ArgusWatch(UUID observationId, List<EyeSpec> specs) {
            this.observationId = observationId;
            this.specs = List.copyOf(specs);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                commands.add(() -> Argus.this.close(this));
            }
        }
    }

    /** The merged scene of one watch in one world. */
    private final class WatchScene {
        private final UUID observationId;
        private final String world;
        private final UUID sceneId = UUID.randomUUID();
        private final ArgusScene scene = new ArgusScene();
        private final AmbientSource ambient;
        private final Map<UUID, ArgusEye> eyes = new LinkedHashMap<>();

        private WatchScene(UUID observationId, String world) {
            this.observationId = observationId;
            this.world = world;
            this.ambient = platform.ambient(world);
        }

        private void tick() {
            eyes.values().forEach(ArgusEye::tick);
            ambient.sample().forEach(this::publish);
        }

        /** Entry point for every packet an eye receives; must not throw into the server. */
        private void observe(UUID eye, Object packet) {
            if (closed) {
                return;
            }
            try {
                List<Object> parts = platform.unbundle(packet);
                for (Object part : parts) {
                    if (!merge(eye, part) && parts.size() > 1) {
                        // A bundle pairs an entity with its initial state.
                        // When another eye already holds the entity, the
                        // rest of the bundle repeats known state.
                        return;
                    }
                }
            } catch (Throwable failure) {
                failureHandler.accept(failure);
            }
        }

        /** @return {@code false} only when an entity spawn was a duplicate */
        private boolean merge(UUID eye, Object packet) {
            Optional<PacketDefinition<?>> definition = platform.definition(packet);
            if (definition.isEmpty()) {
                return true;
            }
            switch (definition.get().distribution()) {
                case VIEW_LOCAL -> { }
                case BROADCAST, PLAYER_ONLY -> {
                    if (scene.broadcast(eye, packet, serverTick.getAsLong())) {
                        publish(packet);
                    }
                }
                case VIEW_MEMBERSHIP -> {
                    return mergeMembership(eye, packet, definition.get().sceneMembership(packet));
                }
            }
            return true;
        }

        private boolean mergeMembership(UUID eye, Object packet, SceneMembership membership) {
            switch (membership.kind()) {
                case CHUNK_GAINED -> {
                    if (scene.chunkGained(eye, membership.chunkX(), membership.chunkZ())) {
                        publish(packet);
                    }
                }
                case CHUNK_LOST -> {
                    if (scene.chunkLost(eye, membership.chunkX(), membership.chunkZ())) {
                        publish(packet);
                    }
                }
                case ENTITY_GAINED -> {
                    if (!scene.entityGained(eye, membership.entityIds()[0])) {
                        return false;
                    }
                    publish(packet);
                }
                case ENTITIES_LOST -> {
                    int[] all = membership.entityIds();
                    int[] gone = scene.entitiesLost(eye, all);
                    if (gone.length == all.length) {
                        publish(packet);
                    } else if (gone.length > 0) {
                        publish(platform.removeEntities(gone));
                    }
                }
            }
            return true;
        }

        /** Closes the eyes of a finished watch and publishes the unloads of what they held. */
        private void closeAndUnload() {
            for (Map.Entry<UUID, ArgusEye> eye : eyes.entrySet()) {
                // Close first: the eye must not report its own teardown.
                eye.getValue().close();
                ArgusScene.Released released = scene.release(eye.getKey());
                for (ArgusScene.ChunkPosition chunk : released.chunks()) {
                    publish(platform.forgetChunk(chunk.x(), chunk.z()));
                }
                if (released.entityIds().length > 0) {
                    publish(platform.removeEntities(released.entityIds()));
                }
            }
            eyes.clear();
            publisher.sceneClosed(sceneId);
        }

        private void closeQuietly() {
            for (ArgusEye eye : eyes.values()) {
                try {
                    eye.close();
                } catch (RuntimeException ignored) {
                    // Shutdown must reach every eye.
                }
            }
            eyes.clear();
        }

        private void publish(Object packet) {
            publisher.publish(observationId, sceneId, world, packet);
        }
    }
}
