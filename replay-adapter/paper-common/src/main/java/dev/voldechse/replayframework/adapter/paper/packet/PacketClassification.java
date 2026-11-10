package dev.voldechse.replayframework.adapter.paper.packet;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import java.util.Objects;

/** Immutable capture, playback and checkpoint policy owned by one packet definition. */
public record PacketClassification(
        PacketDisposition disposition,
        boolean captureByDefault,
        boolean replayable,
        boolean checkpointRelevant,
        PacketDescriptor.PlaybackScope playbackScope) {

    public PacketClassification {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(playbackScope, "playbackScope");
        if ((disposition == PacketDisposition.CONTROL
                        || disposition == PacketDisposition.UNSUPPORTED)
                && (replayable || checkpointRelevant)) {
            throw new IllegalArgumentException(
                    disposition + " packets cannot be replayable or checkpoint-relevant");
        }
        if (playbackScope == PacketDescriptor.PlaybackScope.UNSAFE && replayable) {
            throw new IllegalArgumentException("unsafe packets cannot be replayable");
        }
        if (playbackScope == PacketDescriptor.PlaybackScope.VIEWER_LOCAL
                && checkpointRelevant) {
            throw new IllegalArgumentException(
                    "viewer-local packets cannot be checkpoint-relevant");
        }
    }

    public static PacketClassification stateful(PacketDescriptor.PlaybackScope scope) {
        return new PacketClassification(
                PacketDisposition.STATEFUL,
                true,
                true,
                scope != PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                scope);
    }

    public static PacketClassification ephemeral(PacketDescriptor.PlaybackScope scope) {
        return new PacketClassification(
                PacketDisposition.EPHEMERAL, true, true, false, scope);
    }

    public static PacketClassification configurable(PacketDescriptor.PlaybackScope scope) {
        return new PacketClassification(
                PacketDisposition.CONFIGURABLE, true, true, false, scope);
    }

    public static PacketClassification control() {
        return new PacketClassification(
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    public static PacketClassification unsupported() {
        return new PacketClassification(
                PacketDisposition.UNSUPPORTED,
                true,
                false,
                false,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }
}
