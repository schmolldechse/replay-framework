package dev.voldechse.replayframework.adapter;

import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * Complete adapter contract consumed by core and runtime composition.
 *
 * <p>The Paper {@link Player} is accepted only at the prepared playback
 * boundary. No NMS, Netty or player-state type is exposed by this contract.</p>
 */
public interface ReplayAdapter {

    /**
     * Returns the immutable adapter compatibility descriptor.
     *
     * @return adapter descriptor
     */
    AdapterDescriptor descriptor();

    /**
     * Returns the immutable packet registry for this adapter instance.
     *
     * @return packet registry
     */
    PacketRegistry packetRegistry();

    /**
     * Returns the adapter-owned capture bridge.
     *
     * @return capture bridge
     */
    CaptureBridge captureBridge();

    /**
     * Returns the adapter-owned checkpoint encoder.
     *
     * @return checkpoint encoder
     */
    CheckpointEncoder checkpointEncoder();

    /**
     * Returns adapter-verified semantic checkpoint signals. Legacy adapters
     * without such a source use the safe no-op default.
     */
    default CheckpointSignalSource checkpointSignals() {
        return CheckpointSignalSource.noop();
    }

    /**
     * Opens a bridge for one prepared viewer.
     *
     * @param player Paper viewer at the integration boundary
     * @return open playback bridge
     * @throws IncompatibleAdapterException when the adapter was not verified
     */
    PlaybackBridge openPlayback(Player player);

    /**
     * Verifies that the adapter descriptor and immutable registry agree before
     * capture or playback is installed.
     *
     * @throws NullPointerException when a required adapter port is absent
     * @throws IncompatibleAdapterException when fingerprints differ
     */
    default void verifyDescriptorAndRegistry() {
        AdapterDescriptor adapterDescriptor = Objects.requireNonNull(descriptor(), "descriptor");
        PacketRegistry registry = Objects.requireNonNull(packetRegistry(), "packetRegistry");
        Objects.requireNonNull(captureBridge(), "captureBridge");
        Objects.requireNonNull(checkpointEncoder(), "checkpointEncoder");
        Objects.requireNonNull(checkpointSignals(), "checkpointSignals");
        String registryFingerprint = Objects.requireNonNull(
                registry.fingerprint(), "registry.fingerprint");
        if (!adapterDescriptor.registryFingerprint().equals(registryFingerprint)) {
            throw new IncompatibleAdapterException(
                    "adapter descriptor fingerprint " + adapterDescriptor.registryFingerprint()
                            + " differs from registry fingerprint " + registryFingerprint);
        }
    }
}
