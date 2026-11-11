package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.AbstractReplayAdapter;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.CheckpointSignalSource;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;

/**
 * Paper 26.2 adapter composition root for the version-neutral replay ports.
 *
 * <p>This class verifies adapter compatibility before publication. It does not
 * install a network handler, prepare a world, manage a player state or implement
 * capture, checkpoint or playback behavior; those responsibilities are supplied
 * by the surrounding runtime composition.</p>
 */
public final class Paper26_2ReplayAdapter extends AbstractReplayAdapter {

    private static final String ADAPTER_ID = "paper-26.2";
    private static final String MINECRAFT_VERSION = "26.2";
    private static final int ADAPTER_FORMAT_REVISION = 4;

    /**
     * Creates a verified adapter with its runtime ports injected.
     *
     * @param captureBridge capture port supplied by the runtime composition
     * @param checkpointEncoder checkpoint port supplied by the runtime composition
     * @param playbackFactory playback factory supplied by the runtime composition
     * @throws IncompatibleAdapterException when the live Paper registry is not 26.2
     * @throws NullPointerException when an adapter port is absent
     */
    public Paper26_2ReplayAdapter(
            CaptureBridge captureBridge,
            CheckpointEncoder checkpointEncoder,
            Function<Player, PlaybackBridge> playbackFactory) {
        this(captureBridge, checkpointEncoder, playbackFactory, new Paper26_2CheckpointSignals());
    }

    /** Creates a verified adapter with an explicit semantic signal source. */
    public Paper26_2ReplayAdapter(
            CaptureBridge captureBridge,
            CheckpointEncoder checkpointEncoder,
            Function<Player, PlaybackBridge> playbackFactory,
            CheckpointSignalSource checkpointSignals) {
        this(discover(), captureBridge, checkpointEncoder, playbackFactory, checkpointSignals);
    }

    private Paper26_2ReplayAdapter(
            Components components,
            CaptureBridge captureBridge,
            CheckpointEncoder checkpointEncoder,
            Function<Player, PlaybackBridge> playbackFactory,
            CheckpointSignalSource checkpointSignals) {
        super(components.descriptor(), components.registry(), captureBridge,
                checkpointEncoder, checkpointSignals, playbackFactory);
    }

    private static Components discover() {
        Paper26_2ProtocolIntrospector.ProtocolSnapshot snapshot =
                new Paper26_2ProtocolIntrospector().inspect();
        if (!MINECRAFT_VERSION.equals(snapshot.minecraftVersion())) {
            throw new IncompatibleAdapterException(
                    "unsupported Paper Minecraft version " + snapshot.minecraftVersion());
        }
        PacketRegistry registry = Paper26_2PacketRegistry.fromSnapshot(snapshot);
        return new Components(new AdapterDescriptor(ADAPTER_ID, snapshot.protocolVersion(),
                ADAPTER_FORMAT_REVISION, registry.fingerprint()), registry);
    }

    private record Components(AdapterDescriptor descriptor, PacketRegistry registry) {}

    /** Adapter-local signal source used by the 26.2 capture hooks. */
    static final class Paper26_2CheckpointSignals implements CheckpointSignalSource.Emitter {
        private final CopyOnWriteArrayList<CheckpointSignalListener> listeners =
                new CopyOnWriteArrayList<>();

        @Override
        public void install(CheckpointSignalListener candidate) {
            Objects.requireNonNull(candidate, "listener");
            if (!listeners.contains(candidate)) {
                // The adapter source is a fan-out because overlapping replay
                // sessions each own an independent scheduler/listener.
                listeners.add(candidate);
            }
        }

        @Override
        public void uninstall(CheckpointSignalListener candidate) {
            Objects.requireNonNull(candidate, "listener");
            listeners.remove(candidate);
        }

        @Override
        public void emitDimensionChange(long captureTimeNanos, long serverTick) {
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must not be negative");
            }
            for (CheckpointSignalListener current : listeners) {
                current.onDimensionChange(captureTimeNanos, serverTick);
            }
        }
    }
}
