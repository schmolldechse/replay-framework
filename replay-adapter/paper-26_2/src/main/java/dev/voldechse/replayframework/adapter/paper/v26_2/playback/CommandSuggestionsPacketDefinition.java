package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:command_suggestions}. */
final class CommandSuggestionsPacketDefinition extends CatalogPacketDefinition {

    CommandSuggestionsPacketDefinition(PacketClassification classification) {
        super("minecraft:command_suggestions", classification);
    }
}

