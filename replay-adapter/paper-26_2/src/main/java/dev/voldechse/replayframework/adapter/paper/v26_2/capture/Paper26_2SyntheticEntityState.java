package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import net.kyori.adventure.key.Key;

/** One recipient-independent Paper 26.2 entity-state packet. */
record Paper26_2SyntheticEntityState(
        Paper26_2NativePacketCodec.EncodedStatePacket packet,
        Key world,
        BlockPosition position) {

    Paper26_2SyntheticEntityState {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");
    }
}
