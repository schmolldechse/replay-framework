package dev.voldechse.replayframework.adapter;

import java.util.Objects;
import java.util.function.Function;
import org.bukkit.entity.Player;

/** Shared, NMS-free composition for one verified replay adapter. */
public abstract class AbstractReplayAdapter implements ReplayAdapter {

    private final AdapterDescriptor descriptor;
    private final PacketRegistry packetRegistry;
    private final CaptureBridge captureBridge;
    private final CheckpointEncoder checkpointEncoder;
    private final CheckpointSignalSource checkpointSignals;
    private final Function<Player, PlaybackBridge> playbackFactory;

    protected AbstractReplayAdapter(
            AdapterDescriptor descriptor,
            PacketRegistry packetRegistry,
            CaptureBridge captureBridge,
            CheckpointEncoder checkpointEncoder,
            CheckpointSignalSource checkpointSignals,
            Function<Player, PlaybackBridge> playbackFactory) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.packetRegistry = Objects.requireNonNull(packetRegistry, "packetRegistry");
        this.captureBridge = Objects.requireNonNull(captureBridge, "captureBridge");
        this.checkpointEncoder = Objects.requireNonNull(checkpointEncoder, "checkpointEncoder");
        this.checkpointSignals = Objects.requireNonNull(checkpointSignals, "checkpointSignals");
        this.playbackFactory = Objects.requireNonNull(playbackFactory, "playbackFactory");
        verifyDescriptorAndRegistry();
    }

    @Override
    public final AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public final PacketRegistry packetRegistry() {
        return packetRegistry;
    }

    @Override
    public final CaptureBridge captureBridge() {
        return captureBridge;
    }

    @Override
    public final CheckpointEncoder checkpointEncoder() {
        return checkpointEncoder;
    }

    @Override
    public final CheckpointSignalSource checkpointSignals() {
        return checkpointSignals;
    }

    @Override
    public final PlaybackBridge openPlayback(Player player) {
        Objects.requireNonNull(player, "player");
        verifyDescriptorAndRegistry();
        return Objects.requireNonNull(playbackFactory.apply(player), "playbackFactory returned null");
    }
}
