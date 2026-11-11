package dev.voldechse.replayframework.adapter.paper.packet;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, explicit lookup of one definition for every supported PLAY packet. */
public final class PacketDefinitionCatalog<C> {

    private final List<PacketDefinition<C>> definitions;
    private final Map<String, PacketDefinition<C>> byTypeName;
    private final Map<Integer, PacketDefinition<C>> byPacketId;

    public PacketDefinitionCatalog(Collection<? extends PacketDefinition<C>> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        List<PacketDefinition<C>> ordered = new ArrayList<>(definitions.size());
        Map<String, PacketDefinition<C>> indexed = new HashMap<>();
        Map<Integer, PacketDefinition<C>> indexedById = new HashMap<>();
        for (PacketDefinition<C> definition : definitions) {
            PacketDefinition<C> value = Objects.requireNonNull(
                    definition, "definitions contains null");
            PacketDefinition<C> duplicate = indexed.putIfAbsent(value.typeName(), value);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "duplicate packet definition " + value.typeName());
            }
            PacketDefinition<C> duplicateId = indexedById.putIfAbsent(value.packetId(), value);
            if (duplicateId != null) {
                throw new IllegalArgumentException(
                        "duplicate packet definition ID " + value.packetId());
            }
            ordered.add(value);
        }
        this.definitions = List.copyOf(ordered);
        this.byTypeName = Map.copyOf(indexed);
        this.byPacketId = Map.copyOf(indexedById);
    }

    public List<PacketDefinition<C>> definitions() {
        return definitions;
    }

    public Optional<PacketDefinition<C>> find(String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        return Optional.ofNullable(byTypeName.get(typeName));
    }

    public Optional<PacketDefinition<C>> find(int packetId) {
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        return Optional.ofNullable(byPacketId.get(packetId));
    }

    public PacketDefinition<C> require(String typeName) {
        return find(typeName).orElseThrow(() -> new IncompatibleAdapterException(
                "Paper PLAY packet has no explicit implementation: " + typeName));
    }
}
