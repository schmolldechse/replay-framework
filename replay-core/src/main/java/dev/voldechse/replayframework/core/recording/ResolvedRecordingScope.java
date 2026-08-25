package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.api.recording.CapturePolicy;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;

/**
 * Immutable recording-scope configuration plus an atomically replaceable
 * membership snapshot.
 *
 * <p>Paper and chunk resolution happen before this object is handed to a
 * capture sink. The sink only reads the already-resolved predicate and policy
 * values, so it never performs world access, chunk loading or I/O on a Netty
 * event loop.</p>
 */
final class ResolvedRecordingScope {
    private final RecordingScope requested;
    private final long initialServerTick;
    private final Set<Key> worlds;
    private final List<CuboidRegion> regions;
    private final CapturePolicy capturePolicy;
    private final Set<UUID> participants;
    private final AtomicReference<Membership> membership;

    ResolvedRecordingScope(
            RecordingScope requested,
            long initialServerTick,
            Set<Key> worlds,
            List<CuboidRegion> regions,
            CapturePolicy capturePolicy,
            Set<UUID> participants,
            Membership membership) {
        this.requested = Objects.requireNonNull(requested, "requested");
        if (initialServerTick < 0L) {
            throw new IllegalArgumentException("initialServerTick must not be negative");
        }
        this.initialServerTick = initialServerTick;
        this.worlds = Set.copyOf(Objects.requireNonNull(worlds, "worlds"));
        this.regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
        this.participants = Set.copyOf(Objects.requireNonNull(participants, "participants"));
        this.membership = new AtomicReference<>(Objects.requireNonNull(membership, "membership"));
    }

    RecordingScope requested() {
        return requested;
    }

    long initialServerTick() {
        return initialServerTick;
    }

    Set<Key> worlds() {
        return worlds;
    }

    List<CuboidRegion> regions() {
        return regions;
    }

    CapturePolicy capturePolicy() {
        return capturePolicy;
    }

    /** Participants are informational selection state, not a recipient filter. */
    Set<UUID> participants() {
        return participants;
    }

    /**
     * Replaces the membership snapshot atomically for future packet events.
     * Runtime integration uses this for chunk loads and player movement; an
     * in-flight event observes either the old or the new complete snapshot.
     */
    void replaceMembership(Membership nextMembership) {
        membership.set(Objects.requireNonNull(nextMembership, "nextMembership"));
    }

    /**
     * Applies scope and policy without any external calls. Unknown semantic
     * context is rejected rather than guessed from a type name or payload.
     */
    boolean accepts(CapturedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        CaptureContext context = packet.context();
        PacketDisposition disposition = context.disposition().orElse(null);
        if (disposition == null || disposition == PacketDisposition.CONTROL
                || disposition == PacketDisposition.UNSUPPORTED) {
            return false;
        }
        if (isExcluded(disposition)) {
            return false;
        }
        if (!acceptsConfigurable(context, disposition)) {
            return false;
        }
        return membership.get().matches(packet);
    }

    private boolean isExcluded(PacketDisposition disposition) {
        return switch (disposition) {
            case STATEFUL -> capturePolicy.excludedPacketCategories()
                    .contains(CapturePolicy.PacketCategory.STATEFUL);
            case EPHEMERAL -> capturePolicy.excludedPacketCategories()
                    .contains(CapturePolicy.PacketCategory.EPHEMERAL);
            case CONFIGURABLE -> capturePolicy.excludedPacketCategories()
                    .contains(CapturePolicy.PacketCategory.CONFIGURABLE);
            case CONTROL, UNSUPPORTED -> true;
        };
    }

    private boolean acceptsConfigurable(CaptureContext context, PacketDisposition disposition) {
        if (disposition != PacketDisposition.CONFIGURABLE) {
            return true;
        }
        if (context.normalizedChat().isPresent()) {
            return capturePolicy.includeChat()
                    && capturePolicy.chatFilter().test(context.normalizedChat().orElseThrow());
        }
        if (context.customPayloadChannel().isPresent()) {
            return capturePolicy.allowedCustomPayloadKeys()
                    .contains(context.customPayloadChannel().orElseThrow());
        }
        // Titles, action bars and other explicitly classified configurable
        // packets are not chat or custom payloads and remain eligible unless
        // the whole CONFIGURABLE category was excluded above.
        return true;
    }

    /** Adapter/runtime-owned, already resolved membership predicate. */
    @FunctionalInterface
    interface Membership {
        boolean matches(CapturedPacket packet);
    }
}
