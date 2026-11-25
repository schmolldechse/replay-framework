package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A playback bridge for tests that does nothing: it shows no scene, knows no
 * state and sends nothing. Tests override what they observe.
 */
public class StubPlaybackBridge implements PlaybackBridge {

    @Override
    public void setIdentityContext(PlaybackIdentityContext context) {
    }

    @Override
    public void setFailureHandler(Consumer<Throwable> failureHandler) {
    }

    @Override
    public void activateReplayIsolation() {
    }

    @Override
    public void send(RawPacketFrame frame) {
    }

    @Override
    public void sendBatch(List<RawPacketFrame> frames, PlaybackEmissionMode mode) {
        for (RawPacketFrame frame : List.copyOf(frames)) {
            send(frame);
        }
    }

    @Override
    public void resetView() {
    }

    @Override
    public CompletionStage<Void> awaitOutboundIdle() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void discardQueuedReplayPackets() {
    }

    @Override
    public void viewWorld(String recordedWorld) {
    }

    @Override
    public Optional<String> viewedWorld() {
        return Optional.empty();
    }

    @Override
    public List<ObserverAnchor> observerAnchors(List<RawPacketFrame> checkpointFrames) {
        return List.of();
    }

    @Override
    public Optional<RecordedEntity> entity(int clientEntityId) {
        return Optional.empty();
    }

    @Override
    public List<RecordedEntity> entities() {
        return List.of();
    }

    @Override
    public List<RecordedPlayer> recordedPlayers() {
        return List.of();
    }

    @Override
    public List<RecordedPlayer> players(List<RawPacketFrame> frames) {
        return List.of();
    }

    @Override
    public Optional<SubjectState> decodeState(RecordedSubject subject, List<RawPacketFrame> frames) {
        return Optional.empty();
    }

    @Override
    public Optional<SubjectState> decodeBlock(BlockPosition position, List<RawPacketFrame> frames) {
        return Optional.empty();
    }

    @Override
    public void setInteractionHandler(Consumer<EntityInteraction> handler) {
    }

    @Override
    public void setBlockInteractionHandler(Consumer<BlockInteraction> handler) {
    }

    @Override
    public void close() {
    }
}
