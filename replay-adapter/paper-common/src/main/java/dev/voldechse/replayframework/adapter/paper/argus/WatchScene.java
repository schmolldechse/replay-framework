package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.SceneMembership;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The merged scene of one watch in one world: its eyes, their reference
 * counts and the world's sampled time and weather.
 *
 * <p>Not thread-safe; used on the server thread only.</p>
 */
final class WatchScene {

    private final ArgusServices services;
    private final UUID observationId;
    private final String world;
    private final UUID sceneId = UUID.randomUUID();
    private final ArgusScene scene = new ArgusScene();
    private final AmbientSource ambient;
    private final Map<UUID, OpenEye> eyes = new LinkedHashMap<>();

    WatchScene(ArgusServices services, UUID observationId, String world) {
        this.services = services;
        this.observationId = observationId;
        this.world = world;
        this.ambient = services.platform().ambient(world);
    }

    String world() {
        return world;
    }

    boolean isEmpty() {
        return eyes.isEmpty();
    }

    /** The current position and view of every open eye. */
    List<EyeSpec> eyes() {
        return eyes.values().stream().map(OpenEye::spec).toList();
    }

    /** Opens an eye and returns its identity within this scene. */
    UUID openEye(EyeSpec spec) {
        UUID eyeId = UUID.randomUUID();
        ArgusEye eye = services.platform().openEye(spec, packet -> observe(eyeId, packet));
        eyes.put(eyeId, new OpenEye(eye, spec));
        return eyeId;
    }

    /** Moves an eye; its view follows on the next ticks. */
    void moveEye(UUID eyeId, double x, double y, double z) {
        OpenEye open = eyes.get(eyeId);
        if (open == null) {
            return;
        }
        EyeSpec spec = open.spec();
        if (spec.x() == x && spec.y() == y && spec.z() == z) {
            return;
        }
        open.eye().moveTo(x, y, z);
        eyes.put(eyeId, new OpenEye(open.eye(), new EyeSpec(spec.world(), x, y, z, spec.viewDistance())));
    }

    /** Closes an eye and publishes the unloads of everything only it was holding. */
    void closeEye(UUID eyeId) {
        OpenEye open = eyes.remove(eyeId);
        if (open == null) {
            return;
        }
        // Close first: the eye must not report its own teardown.
        open.eye().close();
        ArgusScene.Released released = scene.release(eyeId);
        for (ArgusScene.ChunkPosition chunk : released.chunks()) {
            publish(services.platform().forgetChunk(chunk.x(), chunk.z()));
        }
        if (released.entityIds().length > 0) {
            publish(services.platform().removeEntities(released.entityIds()));
        }
        if (eyes.isEmpty()) {
            services.publisher().sceneClosed(sceneId);
        }
    }

    /** Closes every eye and publishes the unloads of the whole scene. */
    void closeAll() {
        new ArrayList<>(eyes.keySet()).forEach(this::closeEye);
    }

    /** Closes every eye without publishing anything; for shutdown. */
    void closeQuietly() {
        for (OpenEye open : eyes.values()) {
            try {
                open.eye().close();
            } catch (RuntimeException ignored) {
                // Shutdown must reach every eye.
            }
        }
        eyes.clear();
    }

    void tick() {
        eyes.values().forEach(open -> open.eye().tick());
        ambient.sample().forEach(this::publish);
    }

    void publish(Object packet) {
        services.publisher().publish(observationId, sceneId, world, packet);
    }

    /** Entry point for every packet an eye receives; must not throw into the server. */
    private void observe(UUID eye, Object packet) {
        if (services.closed().getAsBoolean()) {
            return;
        }
        try {
            List<Object> parts = services.platform().unbundle(packet);
            for (Object part : parts) {
                if (!merge(eye, part) && parts.size() > 1) {
                    // A bundle pairs an entity with its initial state. When
                    // another eye already holds the entity, the rest of the
                    // bundle repeats known state.
                    return;
                }
            }
        } catch (Throwable failure) {
            services.failureHandler().accept(failure);
        }
    }

    /** @return {@code false} only when an entity spawn was a duplicate */
    private boolean merge(UUID eye, Object packet) {
        Optional<ClientboundPacketDefinition<?>> definition = services.platform().definition(packet);
        if (definition.isEmpty()) {
            return true;
        }
        switch (definition.get().distribution()) {
            case VIEW_LOCAL -> { }
            case BROADCAST, PLAYER_ONLY -> {
                if (scene.broadcast(eye, packet, services.serverTick().getAsLong())) {
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
                    publish(services.platform().removeEntities(gone));
                }
            }
        }
        return true;
    }

    private record OpenEye(ArgusEye eye, EyeSpec spec) {}
}
