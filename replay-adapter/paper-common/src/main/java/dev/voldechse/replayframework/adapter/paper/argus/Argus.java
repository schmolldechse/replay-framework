package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Argus, the virtual-observer system: places eyes for recording scopes and
 * merges what the eyes of one recording see into one scene per world.
 *
 * <p>Bounded regions get a lattice of fixed eyes. Worlds get companion eyes
 * that follow the recording's players (see {@link ArgusWatch}). Every watch
 * has scenes of its own, so nothing a watch publishes depends on another
 * recording.</p>
 *
 * <p>This class is shared by all adapters. It owns planning, lifecycle and
 * merging; an {@link ArgusPlatform} supplies the version-specific eye and
 * packets, and an {@link ArgusPublisher} receives the merged scenes.</p>
 *
 * <p>{@link #watch(Request)}, {@link #coverage(Request)} and closing a watch
 * are safe from any thread. Changes take effect on the next {@link #tick()},
 * which the adapter calls once per server tick on the server thread.</p>
 */
public final class Argus implements SceneObservation, AutoCloseable {

    private final ArgusServices services;
    private final Queue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final List<ArgusWatch> watches = new ArrayList<>();
    private volatile List<PlayerAnchor> online = List.of();
    private Set<UUID> onlineIds = Set.of();
    private volatile boolean closed;

    public Argus(
            ArgusPlatform platform,
            ArgusPublisher publisher,
            LongSupplier serverTick,
            Consumer<Throwable> failureHandler) {
        this.services = new ArgusServices(
                Objects.requireNonNull(platform, "platform"),
                Objects.requireNonNull(publisher, "publisher"),
                Objects.requireNonNull(serverTick, "serverTick"),
                Objects.requireNonNull(failureHandler, "failureHandler"),
                () -> closed);
    }

    /** Every scope is observed: regions by fixed eyes, worlds by companions. */
    @Override
    public boolean observes(RecordingScope scope) {
        Objects.requireNonNull(scope, "scope");
        return true;
    }

    @Override
    public RecordingScope coverage(Request request) {
        Objects.requireNonNull(request, "request");
        List<EyeSpec> eyes = new ArrayList<>(fixedEyes(request));
        ArgusWatch.followed(request, online).forEach(player -> eyes.add(player.eye()));
        return ArgusWatch.coverage(request.scope(), eyes);
    }

    @Override
    public Watch watch(Request request) {
        Objects.requireNonNull(request, "request");
        List<EyeSpec> fixed = fixedEyes(request);
        ArgusWatch watch = new ArgusWatch(
                services, request, fixed, coverage(request),
                closing -> commands.add(() -> close(closing)));
        commands.add(() -> open(watch));
        return watch;
    }

    /** Applies pending watch changes, follows players, drives the eyes and samples world state. */
    public void tick() {
        if (closed) {
            return;
        }
        try {
            List<PlayerAnchor> players = List.copyOf(services.platform().players());
            online = players;
            for (Runnable command = commands.poll(); command != null; command = commands.poll()) {
                command.run();
            }
            long serverTick = services.serverTick().getAsLong();
            for (ArgusWatch watch : watches) {
                watch.tick(players, serverTick);
            }
            announceDepartures(players);
        } catch (Throwable failure) {
            services.failureHandler().accept(failure);
        }
    }

    /** Closes every eye without publishing their teardown. */
    @Override
    public void close() {
        closed = true;
        commands.clear();
        watches.forEach(ArgusWatch::closeQuietly);
        watches.clear();
    }

    /**
     * The server removes a departing player from the tab list only for the
     * players that stay. With nobody left online, no connection receives it,
     * so it is published from the server's own player list instead.
     */
    private void announceDepartures(List<PlayerAnchor> players) {
        Set<UUID> now = new HashSet<>();
        players.forEach(player -> now.add(player.id()));
        List<UUID> departed = new ArrayList<>();
        for (UUID id : onlineIds) {
            if (!now.contains(id)) {
                departed.add(id);
            }
        }
        onlineIds = Set.copyOf(now);
        if (!departed.isEmpty() && now.isEmpty()) {
            for (ArgusWatch watch : watches) {
                watch.playersLeft(List.copyOf(departed));
            }
        }
    }

    private List<EyeSpec> fixedEyes(Request request) {
        EyePlacement placement = new LatticeEyePlacement(
                request.options().maxEyesPerRegion(), request.options().maxViewDistance());
        List<EyeSpec> specs = new ArrayList<>();
        for (CuboidRegion region : request.scope().regions()) {
            specs.addAll(placement.plan(
                    region, services.platform().trackingRangeBlocks(region.world().asString())));
        }
        return specs;
    }

    private void open(ArgusWatch watch) {
        if (watch.closeRequested()) {
            return;
        }
        watch.open();
        watches.add(watch);
    }

    private void close(ArgusWatch watch) {
        if (watches.remove(watch)) {
            watch.closeEyes();
        }
    }
}
