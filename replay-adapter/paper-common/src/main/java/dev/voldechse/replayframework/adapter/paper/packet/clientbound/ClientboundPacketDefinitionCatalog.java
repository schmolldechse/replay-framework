package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, explicit lookup of one definition for every supported PLAY packet. */
public final class ClientboundPacketDefinitionCatalog<C> {

    private final List<ClientboundPacketDefinition<C>> definitions;
    private final Map<String, ClientboundPacketDefinition<C>> byTypeName;
    private final Map<Integer, ClientboundPacketDefinition<C>> byPacketId;

    public ClientboundPacketDefinitionCatalog(Collection<? extends ClientboundPacketDefinition<C>> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        List<ClientboundPacketDefinition<C>> ordered = new ArrayList<>(definitions.size());
        Map<String, ClientboundPacketDefinition<C>> indexed = new HashMap<>();
        Map<Integer, ClientboundPacketDefinition<C>> indexedById = new HashMap<>();
        for (ClientboundPacketDefinition<C> definition : definitions) {
            ClientboundPacketDefinition<C> value = Objects.requireNonNull(
                    definition, "definitions contains null");
            ClientboundPacketDefinition<C> duplicate = indexed.putIfAbsent(value.typeName(), value);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "duplicate packet definition " + value.typeName());
            }
            ClientboundPacketDefinition<C> duplicateId = indexedById.putIfAbsent(value.packetId(), value);
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

    public List<ClientboundPacketDefinition<C>> definitions() {
        return definitions;
    }

    public Optional<ClientboundPacketDefinition<C>> find(String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        return Optional.ofNullable(byTypeName.get(typeName));
    }

    public Optional<ClientboundPacketDefinition<C>> find(int packetId) {
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        return Optional.ofNullable(byPacketId.get(packetId));
    }

    public ClientboundPacketDefinition<C> require(String typeName) {
        return find(typeName).orElseThrow(() -> new IncompatibleAdapterException(
                "Paper PLAY packet has no explicit implementation: " + typeName));
    }
}
