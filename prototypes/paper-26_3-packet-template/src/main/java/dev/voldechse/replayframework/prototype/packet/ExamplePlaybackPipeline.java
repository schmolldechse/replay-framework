package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;

/** Small composition example connecting catalog output to the channel writer. */
public final class ExamplePlaybackPipeline {

    private final PacketHandlerCatalog catalog;
    private final PacketCodecSession codec;
    private final PlaybackContext context;
    private final PacketEventsReplayWriter writer;

    public ExamplePlaybackPipeline(
            PacketHandlerCatalog catalog,
            PacketCodecSession codec,
            PlaybackContext context,
            PacketEventsReplayWriter writer) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.context = Objects.requireNonNull(context, "context");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public PlaybackResult send(RawPacketFrame frame) {
        PlaybackResult result = catalog.handle(frame, codec, context);
        if (result.decision() == PlaybackResult.Decision.SEND) {
            writer.writeAndFlush(result.packet().orElseThrow());
        } else if (result.decision() == PlaybackResult.Decision.FAIL) {
            throw new IllegalStateException(result.reason());
        }
        return result;
    }
}
