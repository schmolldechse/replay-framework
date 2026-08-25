package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Paper 26.2 adapter composition root for the version-neutral replay ports.
 *
 * <p>This class verifies adapter compatibility before publication. It does not
 * install a network handler, prepare a world, manage a player state or implement
 * capture, checkpoint or playback behavior; those responsibilities are injected
 * by the later adapter tasks.</p>
 */
public final class Paper26ReplayAdapter implements ReplayAdapter {

    private static final String ADAPTER_ID = "paper-26.2";
    private static final String MINECRAFT_VERSION = "26.2";
    private static final int ADAPTER_FORMAT_REVISION = 1;

    private final AdapterDescriptor descriptor;
    private final PacketRegistry packetRegistry;
    private final CaptureBridge captureBridge;
    private final CheckpointEncoder checkpointEncoder;
    private final Function<Player, PlaybackBridge> playbackFactory;

    /**
     * Creates a verified adapter with the later task implementations injected.
     *
     * @param captureBridge capture port supplied by the capture task
     * @param checkpointEncoder checkpoint port supplied by the checkpoint task
     * @param playbackFactory factory supplied by the playback task
     * @throws IncompatibleAdapterException when the live Paper registry is not 26.2
     * @throws NullPointerException when an adapter port is absent
     */
    public Paper26ReplayAdapter(
            CaptureBridge captureBridge,
            CheckpointEncoder checkpointEncoder,
            Function<Player, PlaybackBridge> playbackFactory) {
        this.captureBridge = Objects.requireNonNull(captureBridge, "captureBridge");
        this.checkpointEncoder = Objects.requireNonNull(checkpointEncoder, "checkpointEncoder");
        this.playbackFactory = Objects.requireNonNull(playbackFactory, "playbackFactory");

        Paper26ProtocolIntrospector.ProtocolSnapshot snapshot =
                new Paper26ProtocolIntrospector().inspect();
        if (!MINECRAFT_VERSION.equals(snapshot.minecraftVersion())) {
            throw new IncompatibleAdapterException(
                    "unsupported Paper Minecraft version " + snapshot.minecraftVersion());
        }

        this.packetRegistry = Paper26PacketRegistry.fromSnapshot(snapshot);
        this.descriptor = new AdapterDescriptor(
                ADAPTER_ID,
                snapshot.protocolVersion(),
                ADAPTER_FORMAT_REVISION,
                packetRegistry.fingerprint());

        // This is the publication barrier: no caller receives the adapter before
        // the descriptor, registry and all required ports agree.
        verifyDescriptorAndRegistry();
    }

    @Override
    public AdapterDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public PacketRegistry packetRegistry() {
        return packetRegistry;
    }

    @Override
    public CaptureBridge captureBridge() {
        return captureBridge;
    }

    @Override
    public CheckpointEncoder checkpointEncoder() {
        return checkpointEncoder;
    }

    /**
     * Creates only the injected playback boundary for a prepared Paper viewer.
     * No handler installation or player-state mutation occurs here.
     *
     * @param player prepared Paper viewer
     * @return non-null playback bridge
     */
    @Override
    public PlaybackBridge openPlayback(Player player) {
        Objects.requireNonNull(player, "player");
        verifyDescriptorAndRegistry();
        return Objects.requireNonNull(
                playbackFactory.apply(player),
                "playbackFactory returned null");
    }
}
