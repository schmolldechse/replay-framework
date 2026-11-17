package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;

/**
 * One recording's eyes: fixed eyes for its regions and companion eyes that
 * follow its players.
 *
 * <p>A companion follows its player while the player is alive and online. It
 * stays where the player died until the respawn, moves to another world
 * together with the player, and stays where the player left for the
 * recording's grace period.</p>
 *
 * <p>Everything except {@link #coverage()} and {@link #close()} runs on the
 * server thread.</p>
 */
final class ArgusWatch implements SceneObservation.Watch {

    private static final Logger LOGGER = Logger.getLogger(ArgusWatch.class.getName());
    private static final int CHUNK_BLOCKS = 16;
    private static final int WORLD_MIN_Y = -2048;
    private static final int WORLD_MAX_Y = 2047;
    private static final long MILLIS_PER_TICK = 50L;

    private final ArgusServices services;
    private final SceneObservation.Request request;
    private final List<EyeSpec> fixedEyes;
    private final Consumer<ArgusWatch> onClose;
    private final Map<String, WatchScene> scenes = new LinkedHashMap<>();
    private final Map<UUID, Companion> companions = new LinkedHashMap<>();
    private final Set<UUID> overBudget = new HashSet<>();
    private final SubjectStates subjects;
    private volatile RecordingScope coverage;
    private volatile boolean closeRequested;
    private boolean opened;

    ArgusWatch(
            ArgusServices services,
            SceneObservation.Request request,
            List<EyeSpec> fixedEyes,
            RecordingScope initialCoverage,
            Consumer<ArgusWatch> onClose) {
        this.services = services;
        this.request = request;
        this.fixedEyes = List.copyOf(fixedEyes);
        this.coverage = initialCoverage;
        this.onClose = onClose;
        this.subjects = new SubjectStates(services, request.observationId(),
                Math.max(1L, request.options().stateSampleInterval().toMillis() / MILLIS_PER_TICK));
    }

    @Override
    public RecordingScope coverage() {
        return coverage;
    }

    @Override
    public void close() {
        if (!closeRequested) {
            closeRequested = true;
            onClose.accept(this);
        }
    }

    boolean closeRequested() {
        return closeRequested;
    }

    /** Whether a scope follows players: it names worlds, or nothing at all. */
    static boolean followsPlayers(RecordingScope scope) {
        return !scope.worlds().isEmpty() || scope.regions().isEmpty();
    }

    /** The players a request follows among the online ones. */
    static List<PlayerAnchor> followed(SceneObservation.Request request, Collection<PlayerAnchor> players) {
        if (!followsPlayers(request.scope())) {
            return List.of();
        }
        List<PlayerAnchor> followed = new ArrayList<>();
        for (PlayerAnchor player : players) {
            boolean participant = request.participants().isEmpty()
                    || request.participants().contains(player.id());
            boolean inScope = request.scope().worlds().isEmpty()
                    || request.scope().worlds().contains(Key.key(player.world()));
            if (participant && inScope) {
                followed.add(player);
            }
        }
        return followed;
    }

    /**
     * The scope the given eyes see: one rectangle per eye over the full
     * height of its world. Without eyes it is the requested scope.
     */
    static RecordingScope coverage(RecordingScope requested, Collection<EyeSpec> eyes) {
        if (eyes.isEmpty()) {
            return requested;
        }
        RecordingScope.Builder builder = RecordingScope.builder()
                .chunkLoadingPolicy(requested.chunkLoadingPolicy());
        requested.worlds().forEach(builder::addWorld);
        for (EyeSpec eye : eyes) {
            // The loader sends one chunk more than the view distance.
            int reach = (eye.viewDistance() + 1) * CHUNK_BLOCKS;
            int x = (int) Math.floor(eye.x());
            int z = (int) Math.floor(eye.z());
            builder.addRegion(new CuboidRegion(
                    Key.key(eye.world()),
                    new BlockPosition(x - reach, WORLD_MIN_Y, z - reach),
                    new BlockPosition(x + reach, WORLD_MAX_Y, z + reach)));
        }
        return builder.build();
    }

    /** Opens the fixed eyes. */
    void open() {
        for (EyeSpec spec : fixedEyes) {
            scene(spec.world()).openEye(spec);
        }
        opened = true;
    }

    boolean opened() {
        return opened;
    }

    /** Follows the online players, drives every eye, samples world state and the subjects' state. */
    void tick(List<PlayerAnchor> online, long serverTick) {
        follow(online, serverTick);
        new ArrayList<>(scenes.values()).forEach(WatchScene::tick);
        refreshCoverage();
        if (!scenes.isEmpty()) {
            subjects.tick(coverage);
        }
    }

    /**
     * Publishes the tab-list removal of players who left the server, for a
     * moment in which no online player received it.
     */
    void playersLeft(List<UUID> players) {
        if (scenes.isEmpty() || players.isEmpty()) {
            return;
        }
        Companion companion = companions.get(players.get(0));
        WatchScene scene = companion != null && scenes.containsKey(companion.world)
                ? scenes.get(companion.world)
                : scenes.values().iterator().next();
        scene.publish(services.platform().removePlayerInfo(players));
    }

    /** Closes every eye and publishes the unloads of what they held. */
    void closeEyes() {
        new ArrayList<>(scenes.values()).forEach(WatchScene::closeAll);
        scenes.clear();
        companions.clear();
        refreshCoverage();
    }

    /** Closes every eye without publishing; for shutdown. */
    void closeQuietly() {
        scenes.values().forEach(WatchScene::closeQuietly);
        scenes.clear();
        companions.clear();
    }

    private void follow(List<PlayerAnchor> online, long serverTick) {
        Map<UUID, PlayerAnchor> present = new LinkedHashMap<>();
        for (PlayerAnchor player : followed(request, online)) {
            present.put(player.id(), player);
        }
        for (PlayerAnchor player : present.values()) {
            Companion companion = companions.get(player.id());
            if (companion == null) {
                startFollowing(player);
            } else if (!companion.world.equals(player.world())) {
                // A world change: the old view ends, a new one starts.
                stopFollowing(companion);
                startFollowing(player);
            } else {
                companion.goneSinceTick = -1L;
                if (player.alive()) {
                    scenes.get(companion.world).moveEye(companion.eyeId, player.x(), player.y(), player.z());
                }
            }
        }
        long graceTicks = request.options().gracePeriod().toMillis() / MILLIS_PER_TICK;
        for (Companion companion : new ArrayList<>(companions.values())) {
            if (present.containsKey(companion.playerId)) {
                continue;
            }
            if (companion.goneSinceTick < 0L) {
                companion.goneSinceTick = serverTick;
            }
            if (serverTick - companion.goneSinceTick >= graceTicks) {
                stopFollowing(companion);
            }
        }
        overBudget.retainAll(present.keySet());
    }

    private void startFollowing(PlayerAnchor player) {
        var limit = request.options().maxCompanions();
        if (limit.isPresent() && companions.size() >= limit.getAsInt()) {
            if (overBudget.add(player.id())) {
                LOGGER.warning("Recording " + request.observationId() + " follows "
                        + limit.getAsInt() + " players already; " + player.id() + " is not followed");
            }
            return;
        }
        UUID eyeId = scene(player.world()).openEye(player.eye());
        companions.put(player.id(), new Companion(player.id(), player.world(), eyeId));
    }

    private void stopFollowing(Companion companion) {
        companions.remove(companion.playerId);
        WatchScene scene = scenes.get(companion.world);
        if (scene == null) {
            return;
        }
        scene.closeEye(companion.eyeId);
        if (scene.isEmpty()) {
            scenes.remove(companion.world);
        }
    }

    private WatchScene scene(String world) {
        return scenes.computeIfAbsent(
                world, key -> new WatchScene(services, request.observationId(), key));
    }

    private void refreshCoverage() {
        List<EyeSpec> eyes = new ArrayList<>();
        scenes.values().forEach(scene -> eyes.addAll(scene.eyes()));
        coverage = coverage(request.scope(), eyes);
    }

    /** One followed player and the eye that follows it. */
    private static final class Companion {
        private final UUID playerId;
        private final String world;
        private final UUID eyeId;
        private long goneSinceTick = -1L;

        private Companion(UUID playerId, String world, UUID eyeId) {
            this.playerId = playerId;
            this.world = world;
            this.eyeId = eyeId;
        }
    }
}
