package dev.voldechse.replayframework.adapter.paper.packet.serverbound;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Lookup of one version's serverbound definitions; owns uniqueness. */
public final class ServerboundPacketDefinitionCatalog {

    private final List<ServerboundPacketDefinition> definitions;
    private final Map<String, ServerboundPacketDefinition> byTypeName;
    private final Map<Integer, ServerboundPacketDefinition> byPacketId;

    public ServerboundPacketDefinitionCatalog(
            Collection<? extends ServerboundPacketDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        List<ServerboundPacketDefinition> ordered = new ArrayList<>(definitions.size());
        Map<String, ServerboundPacketDefinition> indexedByName = new HashMap<>();
        Map<Integer, ServerboundPacketDefinition> indexedById = new HashMap<>();
        for (ServerboundPacketDefinition definition : definitions) {
            ServerboundPacketDefinition value =
                    Objects.requireNonNull(definition, "definitions contains null");
            if (indexedByName.putIfAbsent(value.typeName(), value) != null) {
                throw new IllegalArgumentException(
                        "duplicate serverbound packet definition " + value.typeName());
            }
            if (indexedById.putIfAbsent(value.packetId(), value) != null) {
                throw new IllegalArgumentException(
                        "duplicate serverbound packet definition ID " + value.packetId());
            }
            ordered.add(value);
        }
        this.definitions = List.copyOf(ordered);
        this.byTypeName = Map.copyOf(indexedByName);
        this.byPacketId = Map.copyOf(indexedById);
    }

    public List<ServerboundPacketDefinition> definitions() {
        return definitions;
    }

    public Optional<ServerboundPacketDefinition> find(String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        return Optional.ofNullable(byTypeName.get(typeName));
    }

    public Optional<ServerboundPacketDefinition> find(int packetId) {
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        return Optional.ofNullable(byPacketId.get(packetId));
    }

    public ServerboundPacketDefinition require(String typeName) {
        return find(typeName).orElseThrow(() -> new IncompatibleAdapterException(
                "Paper PLAY serverbound packet has no explicit implementation: " + typeName));
    }
}
