package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.paper.protocol.ProtocolSnapshot;
import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.AbstractReplayAdapter;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import java.util.function.Function;
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
    private static final int ADAPTER_FORMAT_REVISION = 5;

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
        this(discover(), captureBridge, checkpointEncoder, playbackFactory);
    }

    private Paper26_2ReplayAdapter(
            Components components,
            CaptureBridge captureBridge,
            CheckpointEncoder checkpointEncoder,
            Function<Player, PlaybackBridge> playbackFactory) {
        super(components.descriptor(), components.registry(), captureBridge,
                checkpointEncoder, playbackFactory);
    }

    @Override
    public dev.voldechse.replayframework.adapter.SceneObservation sceneObservation() {
        return captureBridge() instanceof
                        dev.voldechse.replayframework.adapter.paper.capture.PaperCaptureBridge bridge
                ? bridge.sceneObservation()
                : dev.voldechse.replayframework.adapter.SceneObservation.none();
    }

    private static Components discover() {
        ProtocolSnapshot snapshot =
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

}
