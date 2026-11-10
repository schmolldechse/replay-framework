package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:login}. */
final class LoginPacketDefinition extends CatalogPacketDefinition {

    LoginPacketDefinition(PacketClassification classification) {
        super("minecraft:login", classification);
    }
}

