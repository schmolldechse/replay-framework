package dev.voldechse.replayframework.adapter.paper.packet.serverbound;

import java.util.Objects;

/**
 * One explicit serverbound packet implementation.
 *
 * <p>Serverbound packets are never recorded or replayed. A definition only
 * decides what its packet triggers in the recording: a sample of the
 * sender's own state, or an effect the server sends to everyone except the
 * sender. Most packets trigger nothing, because whatever they change reaches
 * the recording as clientbound packets anyway; their definitions say so and
 * why.</p>
 *
 * <p>{@link #react(Object)} runs on the sender's network thread before the
 * server queues the packet. It may read fields of the packet only, never
 * mutable server state.</p>
 */
public abstract class ServerboundPacketDefinition {

    private static final String TYPE_PREFIX = "play.serverbound.";

    private final int packetId;
    private final String typeName;

    protected ServerboundPacketDefinition(int packetId, String identifier) {
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        Objects.requireNonNull(identifier, "identifier");
        if (!identifier.startsWith("minecraft:") || identifier.length() == "minecraft:".length()) {
            throw new IllegalArgumentException("identifier must be a Minecraft packet identifier");
        }
        this.packetId = packetId;
        this.typeName = typeName(identifier);
    }

    /** The catalog type name of a serverbound PLAY packet identifier, such as {@code minecraft:use_item_on}. */
    public static String typeName(String identifier) {
        return TYPE_PREFIX + Objects.requireNonNull(identifier, "identifier");
    }

    /** Returns the expected numeric ID in this version's PLAY serverbound registry. */
    public final int packetId() {
        return packetId;
    }

    public final String typeName() {
        return typeName;
    }

    /**
     * Decides what this packet triggers. The default is nothing.
     *
     * @param decodedPacket the server's decoded packet object
     */
    public ServerboundReaction react(Object decodedPacket) {
        Objects.requireNonNull(decodedPacket, "decodedPacket");
        return ServerboundReaction.NONE;
    }
}
