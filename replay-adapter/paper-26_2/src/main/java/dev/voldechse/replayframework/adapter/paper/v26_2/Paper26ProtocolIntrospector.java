package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;

/**
 * Reads the Paper 26.2 protocol registry and exposes only immutable adapter data.
 *
 * <p>All NMS access is deliberately kept in this class. The Paper packet registry
 * exposes stable {@code PacketType} identifiers and numeric positions through its
 * {@code ProtocolInfo.Details} visitor; those values form the adapter type name and
 * codec key without retaining a Paper packet object.</p>
 */
final class Paper26ProtocolIntrospector {

    private static final String CODEC_PREFIX = "paper-26.2/";
    private static final String TYPE_PREFIX = "play.clientbound.";

    /**
     * Inspects the current Paper protocol metadata and the PLAY clientbound list.
     *
     * @return immutable protocol snapshot
     * @throws IncompatibleAdapterException when Paper exposes an unexpected registry
     */
    ProtocolSnapshot inspect() {
        try {
            WorldVersion version = Objects.requireNonNull(
                    currentVersion(),
                    "Paper current version");
            String minecraftVersion = requireStableText(version.name(), "minecraftVersion");
            int protocolVersion = version.protocolVersion();
            if (protocolVersion < 0) {
                throw incompatible("Paper protocol version is negative", null);
            }

            var details = Objects.requireNonNull(
                    GameProtocols.CLIENTBOUND_TEMPLATE.details(),
                    "Paper PLAY clientbound protocol details");
            if (details.id() != ConnectionProtocol.PLAY || details.flow() != PacketFlow.CLIENTBOUND) {
                throw incompatible("Paper PLAY clientbound protocol details mismatch", null);
            }

            List<PacketType> packets = new ArrayList<>();
            details.listPackets((packetType, packetId) -> packets.add(
                    normalize(packetType, packetId)));
            packets.sort(Comparator.comparingInt(PacketType::packetId));
            validatePacketSet(packets);
            return new ProtocolSnapshot(minecraftVersion, protocolVersion, packets);
        } catch (IncompatibleAdapterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            throw incompatible("Paper PLAY clientbound registry cannot be inspected", exception);
        }
    }

    private static WorldVersion currentVersion() {
        SharedConstants.tryDetectVersion();
        return SharedConstants.getCurrentVersion();
    }

    private static PacketType normalize(
            net.minecraft.network.protocol.PacketType<?> packetType,
            int packetId) {
        if (packetType == null || packetId < 0) {
            throw incompatible("Paper PLAY clientbound registry contains an invalid entry", null);
        }
        if (packetType.flow() != PacketFlow.CLIENTBOUND) {
            throw incompatible("Paper PLAY registry contains a non-clientbound entry", null);
        }
        if (packetType.id() == null) {
            throw incompatible("Paper PLAY registry contains a packet without an identifier", null);
        }
        String typeIdentifier = packetType.id().toString();
        requireStableText(typeIdentifier, "packet type identifier");
        String typeName = requireStableText(TYPE_PREFIX + typeIdentifier, "packet type name");
        String codecKey = requireStableText(CODEC_PREFIX + typeName, "packet codec key");
        return new PacketType(
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                typeName,
                packetId,
                codecKey);
    }

    private static void validatePacketSet(List<PacketType> packets) {
        Set<Integer> packetIds = new HashSet<>();
        Set<String> typeNames = new HashSet<>();
        for (PacketType packet : packets) {
            if (!packetIds.add(packet.packetId())) {
                throw incompatible("Paper PLAY registry contains a duplicate packet ID", null);
            }
            if (!typeNames.add(packet.typeName())) {
                throw incompatible("Paper PLAY registry contains a duplicate packet type", null);
            }
        }
        if (packets.isEmpty()) {
            throw incompatible("Paper PLAY clientbound registry is empty", null);
        }
    }

    private static String requireStableText(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw incompatible("Paper registry contains an empty " + field, null);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw incompatible("Paper registry contains an invalid " + field, null);
            }
        }
        return value;
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    record ProtocolSnapshot(
            String minecraftVersion,
            int protocolVersion,
            List<PacketType> clientboundPlayPackets) {

        ProtocolSnapshot {
            minecraftVersion = requireStableText(minecraftVersion, "minecraftVersion");
            if (protocolVersion < 0) {
                throw incompatible("snapshot protocol version is negative", null);
            }
            Objects.requireNonNull(clientboundPlayPackets, "clientboundPlayPackets");
            clientboundPlayPackets = List.copyOf(clientboundPlayPackets);
        }
    }

    record PacketType(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            String typeName,
            int packetId,
            String codecKey) {

        PacketType {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(direction, "direction");
            typeName = requireStableText(typeName, "typeName");
            if (packetId < 0) {
                throw incompatible("snapshot packet ID is negative", null);
            }
            codecKey = requireStableText(codecKey, "codecKey");
        }
    }
}
