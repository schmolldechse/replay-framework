package dev.voldechse.replayframework.adapter.paper.protocol;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, version-neutral view of a server's clientbound protocol registries.
 *
 * <p>A version adapter reads its live registries and hands the result to
 * {@link PaperPacketRegistry}; no Minecraft object is retained.</p>
 *
 * @param minecraftVersion the server's Minecraft version name
 * @param protocolVersion the server's protocol version
 * @param clientboundPackets every clientbound packet of every phase
 */
public record ProtocolSnapshot(
        String minecraftVersion,
        int protocolVersion,
        List<ProtocolPacket> clientboundPackets) {

    public ProtocolSnapshot {
        minecraftVersion = requireStableText(minecraftVersion, "minecraftVersion");
        if (protocolVersion < 0) {
            throw new IncompatibleAdapterException("snapshot protocol version is negative");
        }
        Objects.requireNonNull(clientboundPackets, "clientboundPackets");
        clientboundPackets = List.copyOf(clientboundPackets);
    }

    /**
     * Rejects empty text and text with whitespace or control characters.
     *
     * @return the unchanged value
     * @throws IncompatibleAdapterException when the value is not stable text
     */
    public static String requireStableText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IncompatibleAdapterException("Paper registry contains an empty " + field);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new IncompatibleAdapterException("Paper registry contains an invalid " + field);
            }
        }
        return value;
    }
}
