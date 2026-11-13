package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.definition.AbstractBlockedPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Periodic, type-dependent server debug telemetry requested by the client.
 *
 * <p>The sample array's interpretation depends on its sample type; the current
 * tick-time type reports four nanosecond metrics. The server only sends these
 * samples to operators, and the values describe the recording server rather
 * than the observer's connection or replay scene.</p>
 */
final class Id030DebugSamplePacketDefinition
        extends AbstractBlockedPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x1E;
    static final String IDENTIFIER = "minecraft:debug_sample";

    Id030DebugSamplePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
