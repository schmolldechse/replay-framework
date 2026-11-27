package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One look at a block with state the scene does not show, such as a chest and
 * its contents, on the server thread.
 *
 * @param position the block's position
 * @param signature a value that changes whenever the recorded state changes
 * @param packet builds the native packet that carries the complete state; only
 *               called when it is published
 */
public record BlockSample(BlockPosition position, long signature, Supplier<Object> packet) {

    public BlockSample {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(packet, "packet");
    }
}
