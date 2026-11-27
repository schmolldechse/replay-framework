package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:lock_difficulty}.
 *
 * Locks the difficulty. A setting, not part of the scene.
 */
final class Id029LockDifficultyPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x1D;
    static final String IDENTIFIER = "minecraft:lock_difficulty";

    Id029LockDifficultyPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
