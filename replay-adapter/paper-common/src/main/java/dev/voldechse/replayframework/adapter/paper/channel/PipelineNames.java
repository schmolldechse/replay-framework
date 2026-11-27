package dev.voldechse.replayframework.adapter.paper.channel;

/**
 * Names of the handlers in a Paper player connection's Netty pipeline.
 *
 * <p>From head to tail the server builds {@code timeout, [decrypt], splitter,
 * [decompress], decoder, [bundler], [encrypt], prepender, [compress],
 * encoder, [unbundler], packet_handler}; bracketed handlers exist only once
 * encryption, compression or bundling is active. Outbound messages travel
 * from the tail to the head. The framework adds its own handlers at fixed
 * points relative to these names.</p>
 */
public final class PipelineNames {

    /** Encodes packet objects into bytes. */
    public static final String ENCODER = "encoder";
    /** Prefixes each packet with its length. */
    public static final String PREPENDER = "prepender";
    /** Compresses packets once compression is enabled. */
    public static final String COMPRESS = "compress";
    /** The server's connection: the tail of the pipeline. */
    public static final String PACKET_HANDLER = "packet_handler";

    /** Sees every clientbound packet object a player is sent. */
    public static final String CLIENTBOUND_TAP = "replay_framework_clientbound";
    /** Hands every serverbound packet object a player sends to its definition. */
    public static final String SERVERBOUND_TAP = "replay_framework_serverbound";
    /** Writes encoded replay packets so that only compression, framing and encryption follow. */
    public static final String WRITE_ANCHOR = "replay_framework_write";

    private PipelineNames() {
    }
}
