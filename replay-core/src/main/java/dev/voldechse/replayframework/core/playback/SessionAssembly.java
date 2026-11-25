package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.adapter.playback.ViewerInteractions;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.playback.ObserverPlacement;
import dev.voldechse.replayframework.api.playback.PlaybackRequest;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.core.playback.buffer.PlaybackBuffer;
import dev.voldechse.replayframework.format.CorruptReplayArtifactException;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.format.TrackKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Builds one playback session from a verified replay, step by step: open the
 * scene tracks, open the adapter's bridge, build the timeline, wire up state
 * tracks and interactions, place the viewer and pick the world it watches,
 * and register the session.
 *
 * <p>One assembly builds one session and owns what it opened until the
 * session takes it over. When a step fails, {@link #closeAfterFailure()}
 * closes everything opened so far.</p>
 */
final class SessionAssembly {

    private final Services services;
    private final PlaybackSessionId sessionId;
    private final UUID viewerId;
    private final PlaybackRequest request;
    private final PlaybackTimeline.Listener listener;

    private volatile List<PlaybackBuffer> buffers = List.of();
    private volatile PlaybackBridge bridge;
    private volatile SceneTracks scene;
    private volatile PlaybackTimeline timeline;
    private volatile StateTracks states;

    SessionAssembly(
            Services services,
            PlaybackSessionId sessionId,
            UUID viewerId,
            PlaybackRequest request,
            PlaybackTimeline.Listener listener) {
        this.services = Objects.requireNonNull(services, "services");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.request = Objects.requireNonNull(request, "request");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Builds and registers the session for a verified replay. */
    CompletionStage<PlaybackSession> assemble(ReplayArtifactReader.VerifiedReplay verified) {
        ReplayManifest manifest = verified.manifest();
        return prepareScene(verified)
                .thenCompose(prepared -> {
                    SceneParts parts = openScene(prepared);
                    openBridge();
                    scene = new SceneTracks(
                            parts.global(), parts.worlds(), parts.reference(), manifest.durationNanos(), bridge);
                    buildTimeline(manifest, parts.reference());
                    wireStateAndInteractions(verified, parts.reference());
                    return placeViewer(parts);
                })
                .thenCompose(ignored -> timeline.prepare())
                .thenApply(this::register);
    }

    /** Closes what this assembly opened; for a session that failed to open. */
    void closeAfterFailure() {
        PlaybackTimeline currentTimeline = timeline;
        if (currentTimeline != null) {
            currentTimeline.closeAsync().exceptionally(ignored -> null);
            return;
        }
        buffers.forEach(PlaybackBuffer::close);
        StateTracks currentStates = states;
        if (currentStates != null) {
            currentStates.close();
        }
        PlaybackBridge currentBridge = bridge;
        if (currentBridge != null) {
            currentBridge.close();
        }
    }

    /**
     * Prepares the tracks with the scene role, all before any is opened. State
     * tracks are read only when a subject's state is asked for.
     */
    private CompletionStage<List<TrackOpener.PreparedTrack>> prepareScene(
            ReplayArtifactReader.VerifiedReplay verified) {
        List<CompletableFuture<TrackOpener.PreparedTrack>> preparing = verified.manifest().tracks().stream()
                .filter(track -> track.kind().role() == TrackKind.Role.SCENE)
                .map(track -> services.opener().prepare(verified, track).toCompletableFuture())
                .toList();
        return CompletableFuture.allOf(preparing.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> preparing.stream().map(CompletableFuture::join).toList());
    }

    /** Opens the prepared scene tracks: the global track and one per world. */
    private SceneParts openScene(List<TrackOpener.PreparedTrack> prepared) {
        TrackOpener.PreparedTrack reference = prepared.stream()
                .filter(SessionAssembly::isGlobal)
                .findFirst()
                .orElseThrow(() -> corrupt("replay has no global track"));
        MergedTrackData.Track global = null;
        Map<String, MergedTrackData.Track> worlds = new LinkedHashMap<>();
        List<PlaybackBuffer> opened = new ArrayList<>(prepared.size());
        for (TrackOpener.PreparedTrack track : prepared) {
            TrackOpener.OpenedTrack open = services.opener().open(track, request.bufferOptions());
            opened.add(open.buffer());
            if (isGlobal(track)) {
                global = open.data();
            } else {
                worlds.put(track.track().world().orElseThrow(), open.data());
            }
        }
        buffers = List.copyOf(opened);
        return new SceneParts(global, worlds, reference.index());
    }

    /** Opens the adapter's bridge for the viewer, bound to this session's identities. */
    private void openBridge() {
        bridge = Objects.requireNonNull(
                services.adapter().openPlayback(
                        request.viewer(), new PlaybackIdentityContext(viewerId, sessionId.value())),
                "adapter.openPlayback result");
    }

    private void buildTimeline(ReplayManifest manifest, ReplayIndex reference) {
        PlaybackTimeline built = new PlaybackTimeline(
                Duration.ofNanos(manifest.durationNanos()),
                reference,
                scene.timeline(),
                bridge,
                request.bufferOptions(),
                services.commandExecutor(),
                System::nanoTime,
                listener);
        bridge.setFailureHandler(built::failFromBridge);
        timeline = built;
    }

    /** Opens state tracks on demand and reports the viewer's clicks on the replay. */
    private void wireStateAndInteractions(ReplayArtifactReader.VerifiedReplay verified, ReplayIndex reference) {
        StateTracks wired = new StateTracks(
                verified.manifest().tracks(),
                reference,
                verified.manifest().durationNanos(),
                bridge,
                track -> services.opener().open(verified, track, request.bufferOptions())
                        .thenApply(TrackOpener.OpenedTrack::data));
        states = wired;
        PlaybackEventDispatcher events = services.eventDispatcher();
        bridge.setInteractionHandler(interaction -> events.entityInteracted(
                sessionId, request.replayId(), viewerId,
                new ViewerInteractions.EntityInteraction(
                        wired.withSubject(interaction.entity()),
                        interaction.attack(),
                        interaction.hand())));
        bridge.setBlockInteractionHandler(interaction -> events.blockInteracted(
                sessionId, request.replayId(), viewerId, interaction));
    }

    /**
     * Places the viewer by the initial checkpoint, lets the timeline play the
     * world the viewer was placed in, and isolates the viewer from the live
     * world.
     */
    private CompletionStage<Void> placeViewer(SceneParts parts) {
        SeekPoint initialPoint = parts.reference()
                .seekCheckpointFloor(Duration.ZERO)
                .orElseThrow(() -> corrupt("replay has no initial checkpoint"));
        return scene.all().checkpointFrames(initialPoint)
                .thenCompose(this::placeObserver)
                .thenRun(() -> {
                    String world = bridge.viewedWorld().orElseGet(() -> {
                        String first = parts.worlds().keySet().stream().findFirst().orElse(null);
                        if (first != null) {
                            bridge.viewWorld(first);
                        }
                        return first;
                    });
                    if (world != null) {
                        scene.view(world);
                    }
                })
                .thenRun(bridge::activateReplayIsolation);
    }

    private CompletionStage<Void> placeObserver(List<RawPacketFrame> initialFrames) {
        ObserverPlacement placement = request.observerPlacement().orElseGet(
                () -> ObserverPlacement.automatic(request.viewer().getWorld()));
        List<Location> candidates;
        if (placement instanceof ObserverPlacement.Fixed fixed) {
            candidates = List.of(fixed.target());
        } else if (placement instanceof ObserverPlacement.Automatic automatic) {
            World world = automatic.targetWorld();
            List<ObserverAnchor> anchors = bridge.observerAnchors(initialFrames);
            if (anchors.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Replay contains no safe observer anchor"));
            }
            // The viewer watches the recorded world of the best anchor, and
            // only anchors of that world are candidates for the placement.
            Optional<String> viewedWorld = anchors.get(0).world();
            viewedWorld.ifPresent(bridge::viewWorld);
            anchors = anchors.stream()
                    .filter(anchor -> anchor.world().equals(viewedWorld))
                    .toList();
            List<Location> derived = new ArrayList<>(anchors.size() * 2);
            for (ObserverAnchor anchor : anchors) {
                derived.add(new Location(
                        world, anchor.x(), anchor.y() + 2.5D, anchor.z(),
                        anchor.yaw(), anchor.pitch()));
                derived.add(new Location(
                        world, anchor.x(), anchor.y(), anchor.z(),
                        anchor.yaw(), anchor.pitch()));
            }
            candidates = List.copyOf(derived);
        } else {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Unsupported observer placement"));
        }
        return services.observerPlacement().place(request.viewer(), candidates);
    }

    /** Hands everything over to the session and registers it. */
    private PlaybackSession register(PlaybackSnapshot snapshot) {
        if (snapshot.status() != PlaybackStatus.PAUSED || !snapshot.position().isZero()) {
            throw new IllegalStateException("initial playback view is not paused at zero");
        }
        DefaultPlaybackSession session = new DefaultPlaybackSession(
                sessionId,
                request.replayId(),
                viewerId,
                timeline,
                services.coordinator(),
                states,
                scene);
        services.coordinator().register(session);
        services.diagnostics().playbackStarted(session);
        return session;
    }

    private static boolean isGlobal(TrackOpener.PreparedTrack track) {
        return track.track().kind().keyedBy() == TrackKind.KeyedBy.NOTHING;
    }

    private static CompletionException corrupt(String message) {
        return new CompletionException(new CorruptReplayArtifactException(message));
    }

    /**
     * The opened scene tracks.
     *
     * @param global the track that applies in every world
     * @param worlds the track of each world, in manifest order
     * @param reference the index the timeline seeks on
     */
    private record SceneParts(
            MergedTrackData.Track global, Map<String, MergedTrackData.Track> worlds, ReplayIndex reference) {
    }

    /**
     * What every assembly of one playback service shares.
     *
     * @param adapter opens the viewer's bridge
     * @param opener opens the tracks
     * @param commandExecutor runs the timeline's commands
     * @param coordinator registers the session
     * @param eventDispatcher reports the viewer's interactions
     * @param diagnostics records the started session
     * @param observerPlacement places the viewer
     */
    record Services(
            ReplayAdapter adapter,
            TrackOpener opener,
            Executor commandExecutor,
            PlaybackCoordinator coordinator,
            PlaybackEventDispatcher eventDispatcher,
            ReplayDiagnostics diagnostics,
            ObserverPlacementService observerPlacement) {
        Services {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(opener, "opener");
            Objects.requireNonNull(commandExecutor, "commandExecutor");
            Objects.requireNonNull(coordinator, "coordinator");
            Objects.requireNonNull(eventDispatcher, "eventDispatcher");
            Objects.requireNonNull(diagnostics, "diagnostics");
            Objects.requireNonNull(observerPlacement, "observerPlacement");
        }
    }
}
