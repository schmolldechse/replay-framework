package dev.voldechse.replayframework.api.playback;

import dev.voldechse.replayframework.api.id.ReplayId;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * Immutable request to open one replay for one Paper viewer.
 *
 * @param replayId replay to open
 * @param viewer Paper viewer at the integration boundary
 * @param bufferOptions session-local buffering options
 */
public record PlaybackRequest(
        ReplayId replayId,
        Player viewer,
        PlaybackBufferOptions bufferOptions) {

    /** Validates required request values. */
    public PlaybackRequest {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(bufferOptions, "bufferOptions");
    }

    /**
     * Creates a request builder with the default buffer options.
     *
     * @return request builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for an immutable playback request. */
    public static final class Builder {
        private ReplayId replayId;
        private Player viewer;
        private PlaybackBufferOptions bufferOptions = PlaybackBufferOptions.builder().build();

        private Builder() {
        }

        /**
         * Sets the replay to open.
         *
         * @param replayId replay identifier
         * @return this builder
         */
        public Builder replay(ReplayId replayId) {
            this.replayId = Objects.requireNonNull(replayId, "replayId");
            return this;
        }

        /**
         * Sets the Paper viewer. Online and world checks are performed by the
         * asynchronous playback service, not by this builder.
         *
         * @param viewer Paper viewer
         * @return this builder
         */
        public Builder viewer(Player viewer) {
            this.viewer = Objects.requireNonNull(viewer, "viewer");
            return this;
        }

        /**
         * Sets session-local buffering options.
         *
         * @param bufferOptions buffer options
         * @return this builder
         */
        public Builder buffer(PlaybackBufferOptions bufferOptions) {
            this.bufferOptions = Objects.requireNonNull(bufferOptions, "bufferOptions");
            return this;
        }

        /**
         * Builds the immutable request.
         *
         * @return playback request
         */
        public PlaybackRequest build() {
            return new PlaybackRequest(replayId, viewer, bufferOptions);
        }
    }
}
