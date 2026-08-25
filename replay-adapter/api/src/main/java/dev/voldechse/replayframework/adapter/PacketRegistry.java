package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.format.PacketPhase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HexFormat;

/** Immutable lookup and compatibility contract for one adapter packet registry. */
public interface PacketRegistry {

    /**
     * Returns descriptors in the registry's canonical order.
     *
     * @return immutable canonical descriptor list
     */
    List<PacketDescriptor> descriptors();

    /**
     * Looks up one packet by phase, direction and numeric ID.
     *
     * @param phase protocol phase
     * @param direction protocol direction
     * @param packetId numeric packet ID
     * @return descriptor when the key is registered
     */
    Optional<PacketDescriptor> find(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId);

    /**
     * Determines whether the structural adapter rules allow capture.
     *
     * @param phase protocol phase
     * @param direction protocol direction
     * @param packetId numeric packet ID
     * @return {@code true} only for registered, capture-eligible clientbound packets
     */
    boolean captureAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId);

    /**
     * Determines whether the structural adapter rules allow playback.
     *
     * @param phase protocol phase
     * @param direction protocol direction
     * @param packetId numeric packet ID
     * @return {@code true} only for registered, replayable clientbound packets
     */
    boolean replayAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId);

    /**
     * Returns the deterministic SHA-256 registry fingerprint.
     *
     * @return lowercase 64-character SHA-256 digest
     */
    String fingerprint();

    /**
     * Creates an immutable packet registry.
     *
     * @param descriptors adapter-owned descriptors
     * @return immutable packet registry
     */
    static PacketRegistry of(Collection<PacketDescriptor> descriptors) {
        return new DefaultPacketRegistry(descriptors);
    }
}

/** Package-private immutable implementation kept behind the adapter API port. */
final class DefaultPacketRegistry implements PacketRegistry {

    private static final Comparator<PacketDescriptor> CANONICAL_ORDER =
            Comparator.comparingInt((PacketDescriptor descriptor) -> descriptor.phase().wireCode())
                    .thenComparingInt(descriptor -> descriptor.direction().wireCode())
                    .thenComparingInt(PacketDescriptor::packetId)
                    .thenComparing(PacketDescriptor::typeName)
                    .thenComparing(PacketDescriptor::codecKey);

    private final List<PacketDescriptor> descriptors;
    private final Map<LookupKey, PacketDescriptor> byKey;
    private final String fingerprint;

    DefaultPacketRegistry(Collection<PacketDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");

        List<PacketDescriptor> canonical = new ArrayList<>(descriptors.size());
        for (PacketDescriptor descriptor : descriptors) {
            canonical.add(Objects.requireNonNull(descriptor, "descriptors contains null"));
        }
        canonical.sort(CANONICAL_ORDER);

        Map<LookupKey, PacketDescriptor> lookup = new HashMap<>();
        for (PacketDescriptor descriptor : canonical) {
            LookupKey key = LookupKey.of(descriptor);
            if (lookup.putIfAbsent(key, descriptor) != null) {
                throw new IllegalArgumentException(
                        "duplicate packet registry key: " + key);
            }
        }

        this.descriptors = List.copyOf(canonical);
        this.byKey = Map.copyOf(lookup);
        this.fingerprint = computeFingerprint(this.descriptors);
    }

    @Override
    public List<PacketDescriptor> descriptors() {
        return descriptors;
    }

    @Override
    public Optional<PacketDescriptor> find(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(direction, "direction");
        if (packetId < 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(new LookupKey(phase, direction, packetId)));
    }

    @Override
    public boolean captureAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return find(phase, direction, packetId)
                .filter(descriptor -> descriptor.direction() == PacketDescriptor.Direction.CLIENTBOUND)
                .filter(descriptor -> descriptor.disposition() != PacketDisposition.CONTROL)
                .filter(descriptor -> descriptor.disposition() != PacketDisposition.UNSUPPORTED)
                .map(PacketDescriptor::captureByDefault)
                .orElse(false);
    }

    @Override
    public boolean replayAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return find(phase, direction, packetId)
                .filter(descriptor -> descriptor.direction() == PacketDescriptor.Direction.CLIENTBOUND)
                .filter(descriptor -> descriptor.disposition() != PacketDisposition.CONTROL)
                .filter(descriptor -> descriptor.disposition() != PacketDisposition.UNSUPPORTED)
                .map(PacketDescriptor::replayable)
                .orElse(false);
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }

    private static String computeFingerprint(List<PacketDescriptor> descriptors) {
        StringBuilder canonical = new StringBuilder();
        for (PacketDescriptor descriptor : descriptors) {
            canonical.append(descriptor.phase().wireCode()).append('\n');
            canonical.append(descriptor.direction().wireCode()).append('\n');
            canonical.append(descriptor.packetId()).append('\n');
            canonical.append(descriptor.typeName()).append('\n');
            canonical.append(descriptor.disposition().name()).append('\n');
            canonical.append(descriptor.captureByDefault()).append('\n');
            canonical.append(descriptor.replayable()).append('\n');
            canonical.append(descriptor.checkpointRelevant()).append('\n');
            canonical.append(descriptor.codecKey()).append('\n');
            canonical.append('\n');
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("JDK must provide SHA-256", exception);
        }
    }

    private record LookupKey(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {

        private static LookupKey of(PacketDescriptor descriptor) {
            return new LookupKey(descriptor.phase(), descriptor.direction(), descriptor.packetId());
        }
    }
}
