package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.playback.ReplayIdentityMap;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.EntityType;

/**
 * Version-local dispatch boundary that routes recorded frames through the
 * packet catalog. It never decodes or encodes blocked control packets.
 */
public final class Paper26_3ReplayPacketRewriter
        implements dev.voldechse.replayframework.adapter.paper.playback.ViewerRewriter {

    private final ReplayIdentityMap identities;
    private final Map<Integer, EntityType<?>> recordedEntityTypes = new HashMap<>();

    public Paper26_3ReplayPacketRewriter(
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds) {
        this.identities = new ReplayIdentityMap(
                Objects.requireNonNull(context, "context"),
                Objects.requireNonNull(reservedUuids, "reservedUuids"),
                Objects.requireNonNull(reservedEntityIds, "reservedEntityIds"));
    }

    @Override
    public PacketRewriteResult rewrite(
            RawPacketFrame frame,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec) {
        PacketRewriteResult result = Paper26_3ClientboundPacketCatalog.catalog()
                .require(Objects.requireNonNull(descriptor, "descriptor").typeName())
                .handlePlayback(
                        Objects.requireNonNull(frame, "frame"),
                        descriptor,
                        Objects.requireNonNull(codec, "codec"),
                        this);
        return result;
    }

    @Override
    public PacketCodec.EncodedPacket encode(
            Object rebuiltPacket,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec) {
        return Paper26_3ClientboundPacketCatalog.catalog()
                .require(Objects.requireNonNull(descriptor, "descriptor").typeName())
                .encode(
                        Objects.requireNonNull(rebuiltPacket, "rebuiltPacket"),
                        descriptor,
                        Objects.requireNonNull(codec, "codec"));
    }

    /** Maps a recorded UUID into this playback session's isolated identity space. */
    public UUID mapUuid(UUID recordedId) {
        return identities.mapUuid(recordedId);
    }

    /** Maps a recorded entity ID into this playback session's isolated ID space. */
    public int mapEntityId(int recordedId) {
        return identities.mapEntityId(recordedId);
    }

    /** Records the Paper entity type needed to interpret type-dependent metadata values. */
    /** Returns the recorded runtime ID behind a replay entity ID, when the replay uses it. */
    @Override
    public java.util.OptionalInt recordedEntityId(int replayId) {
        return identities.recordedEntityId(replayId);
    }

    public void trackEntityType(int recordedId, EntityType<?> entityType) {
        recordedEntityTypes.put(recordedId, Objects.requireNonNull(entityType, "entityType"));
    }

    /** Returns the recorded type of an entity whose spawn packet was replayed. */
    public Optional<EntityType<?>> recordedEntityType(int recordedId) {
        return Optional.ofNullable(recordedEntityTypes.get(recordedId));
    }

    /** Removes type information when the corresponding recorded entity despawns. */
    public void forgetEntityType(int recordedId) {
        recordedEntityTypes.remove(recordedId);
    }

    @Override
    public void clear() {
        identities.clear();
        recordedEntityTypes.clear();
    }

    @Override
    public void resetView() {
        // Keep deterministic ID mappings for this playback session, but forget metadata types
        // because the next checkpoint snapshot rebuilds the viewer's entity set from scratch.
        recordedEntityTypes.clear();
    }
}
