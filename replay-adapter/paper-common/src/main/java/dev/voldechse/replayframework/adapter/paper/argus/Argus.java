package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.SceneMembership;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.HashMap;
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
 * merges what they see into one scene per world.
 *
 * <p>This class is shared by all adapters. It owns planning, lifecycle and
 * merging; an {@link ArgusPlatform} supplies the version-specific eye and
 * packets, and an {@link ArgusPublisher} receives the merged scene.</p>
 *
 * <p>{@link #watch(RecordingScope)} and closing a watch are safe from any
 * thread. They take effect on the next {@link #tick()}, which the adapter
 * calls once per server tick on the server thread.</p>
 */
public final class Argus implements SceneObservation, AutoCloseable {

    private final ArgusPlatform platform;
    private final EyePlacement placement;
    private final ArgusPublisher publisher;
    private final LongSupplier serverTick;
    private final Consumer<Throwable> failureHandler;
    private final Queue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Map<String, WorldScene> scenes = new HashMap<>();
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

    @Override
    public Watch watch(RecordingScope scope) {
        Objects.requireNonNull(scope, "scope");
        List<EyeSpec> specs = new ArrayList<>();
        for (CuboidRegion region : scope.regions()) {
            specs.addAll(placement.plan(
                    region, platform.trackingRangeBlocks(region.world().asString())));
        }
        ArgusWatch watch = new ArgusWatch(specs);
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
            for (WorldScene scene : scenes.values()) {
                scene.tick();
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
        for (WorldScene scene : scenes.values()) {
            scene.watches.forEach(watch -> watch.eyes.forEach(OpenEye::closeQuietly));
        }
        scenes.clear();
    }

    private void open(ArgusWatch watch) {
        if (watch.closed) {
            return;
        }
        for (EyeSpec spec : watch.specs) {
            WorldScene scene = scenes.computeIfAbsent(spec.world(), WorldScene::new);
            if (!scene.watches.contains(watch)) {
                scene.watches.add(watch);
            }
            UUID eyeId = UUID.randomUUID();
            BlockPosition anchor = new BlockPosition(
                    (int) Math.floor(spec.x()), (int) Math.floor(spec.y()), (int) Math.floor(spec.z()));
            ArgusEye eye = platform.openEye(
                    spec, packet -> scene.observe(eyeId, anchor, packet));
            watch.eyes.add(new OpenEye(eyeId, anchor, eye, scene));
        }
        watch.opened = true;
    }

    private void close(ArgusWatch watch) {
        if (!watch.opened) {
            return;
        }
        for (OpenEye open : watch.eyes) {
            // Close first: the eye must not report its own teardown.
            open.eye.close();
            open.scene.release(open);
        }
        watch.eyes.clear();
        scenes.values().removeIf(scene -> {
            scene.watches.remove(watch);
            return scene.watches.isEmpty();
        });
    }

    /** One recording's eyes. */
    private final class ArgusWatch implements Watch {
        private final List<EyeSpec> specs;
        private final List<OpenEye> eyes = new ArrayList<>();
        private volatile boolean closed;
        private boolean opened;

        private ArgusWatch(List<EyeSpec> specs) {
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

    private record OpenEye(UUID id, BlockPosition anchor, ArgusEye eye, WorldScene scene) {
        private void closeQuietly() {
            try {
                eye.close();
            } catch (RuntimeException ignored) {
                // Shutdown must reach every eye.
            }
        }
    }

    /** The merged scene of one world and the watches that feed it. */
    private final class WorldScene {
        private final String world;
        private final UUID sceneId = UUID.randomUUID();
        private final ArgusScene scene = new ArgusScene();
        private final AmbientSource ambient;
        private final List<ArgusWatch> watches = new ArrayList<>();

        private WorldScene(String world) {
            this.world = world;
            this.ambient = platform.ambient(world);
        }

        private void tick() {
            for (ArgusWatch watch : watches) {
                watch.eyes.forEach(open -> open.eye.tick());
            }
            List<Object> changes = ambient.sample();
            if (changes.isEmpty()) {
                return;
            }
            // World state has no position. Each watch gets it at one of its
            // own eyes so that the scope of its recording accepts it.
            for (ArgusWatch watch : watches) {
                if (!watch.eyes.isEmpty()) {
                    BlockPosition anchor = watch.eyes.get(0).anchor;
                    changes.forEach(packet -> publisher.publish(sceneId, world, packet, anchor));
                }
            }
        }

        /** Entry point for every packet an eye receives; must not throw into the server. */
        private void observe(UUID eye, BlockPosition anchor, Object packet) {
            if (closed) {
                return;
            }
            try {
                List<Object> parts = platform.unbundle(packet);
                for (Object part : parts) {
                    if (!merge(eye, anchor, part) && parts.size() > 1) {
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
        private boolean merge(UUID eye, BlockPosition anchor, Object packet) {
            Optional<PacketDefinition<?>> definition = platform.definition(packet);
            if (definition.isEmpty()) {
                return true;
            }
            switch (definition.get().distribution()) {
                case VIEW_LOCAL -> { }
                case BROADCAST, PLAYER_ONLY -> {
                    if (scene.broadcast(eye, packet, serverTick.getAsLong())) {
                        publisher.publish(sceneId, world, packet, anchor);
                    }
                }
                case VIEW_MEMBERSHIP -> {
                    return mergeMembership(
                            eye, anchor, packet, definition.get().sceneMembership(packet));
                }
            }
            return true;
        }

        private boolean mergeMembership(
                UUID eye, BlockPosition anchor, Object packet, SceneMembership membership) {
            switch (membership.kind()) {
                case CHUNK_GAINED -> {
                    if (scene.chunkGained(eye, membership.chunkX(), membership.chunkZ())) {
                        publisher.publish(sceneId, world, packet, anchor);
                    }
                }
                case CHUNK_LOST -> {
                    if (scene.chunkLost(eye, membership.chunkX(), membership.chunkZ())) {
                        publisher.publish(sceneId, world, packet, anchor);
                    }
                }
                case ENTITY_GAINED -> {
                    if (!scene.entityGained(eye, membership.entityIds()[0])) {
                        return false;
                    }
                    publisher.publish(sceneId, world, packet, anchor);
                }
                case ENTITIES_LOST -> {
                    int[] all = membership.entityIds();
                    int[] gone = scene.entitiesLost(eye, all);
                    if (gone.length == all.length) {
                        publisher.publish(sceneId, world, packet, anchor);
                    } else if (gone.length > 0) {
                        publisher.publish(sceneId, world, platform.removeEntities(gone), anchor);
                    }
                }
            }
            return true;
        }

        /** Publishes the unloads of everything only this eye was holding. */
        private void release(OpenEye open) {
            ArgusScene.Released released = scene.release(open.id);
            for (ArgusScene.ChunkPosition chunk : released.chunks()) {
                publisher.publish(
                        sceneId, world, platform.forgetChunk(chunk.x(), chunk.z()), open.anchor);
            }
            if (released.entityIds().length > 0) {
                publisher.publish(
                        sceneId, world, platform.removeEntities(released.entityIds()), open.anchor);
            }
        }
    }
}
