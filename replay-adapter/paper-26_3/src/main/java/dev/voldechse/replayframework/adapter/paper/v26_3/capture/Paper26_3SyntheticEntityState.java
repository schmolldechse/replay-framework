package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import net.kyori.adventure.key.Key;

/** One recipient-independent Paper 26.3 entity-state packet. */
record Paper26_3SyntheticEntityState(
        Paper26_3NativePacketCodec.EncodedStatePacket packet,
        Key world,
        BlockPosition position) {

    Paper26_3SyntheticEntityState {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");
    }
}
