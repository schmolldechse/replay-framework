package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.playback.ViewerScene;
import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * What a Paper 26.2 viewer's playback knows about the scene: the entities and
 * players of {@link Paper26_2SceneEntityTracker}, the containers of
 * {@link Paper26_2SceneBlocks}, and the decoding of subject and block state.
 */
final class Paper26_2ViewerScene implements ViewerScene {

    private final PacketRegistry registry;
    private final PacketCodec<Object> codec;
    private final Paper26_2SceneEntityTracker entities = new Paper26_2SceneEntityTracker();
    private final Paper26_2SceneBlocks blocks = new Paper26_2SceneBlocks();

    Paper26_2ViewerScene(PacketRegistry registry, PacketCodec<Object> codec) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void observe(RawPacketFrame frame, String typeName) {
        entities.observe(frame, typeName, codec::decode);
        blocks.observe(frame, typeName, codec::decode);
    }

    @Override
    public void clear() {
        entities.clear();
        blocks.clear();
    }

    @Override
    public List<RecordedPlayer> players() {
        return entities.players();
    }

    /** Reads the players of frames that are not played, with a tracker of their own. */
    @Override
    public List<RecordedPlayer> players(List<RawPacketFrame> frames) {
        Paper26_2SceneEntityTracker tracker = new Paper26_2SceneEntityTracker();
        for (RawPacketFrame frame : frames) {
            typeName(frame).ifPresent(typeName -> tracker.observe(frame, typeName, codec::decode));
        }
        return tracker.players();
    }

    @Override
    public Optional<RecordedEntity> entity(int recordedEntityId) {
        return entities.entity(recordedEntityId);
    }

    @Override
    public List<RecordedEntity> entities(String world) {
        return entities.entities(world);
    }

    @Override
    public Optional<Key> container(String world, BlockPosition position) {
        return blocks.block(world, new BlockPos(position.x(), position.y(), position.z()));
    }

    @Override
    public Optional<SubjectState> decodeState(RecordedSubject subject, List<RawPacketFrame> frames) {
        List<Object> packets = new ArrayList<>(frames.size());
        for (RawPacketFrame frame : frames) {
            packets.add(codec.decode(frame));
        }
        return Paper26_2SubjectStateDecoder.decode(subject, packets);
    }

    @Override
    public Optional<SubjectState> decodeBlock(BlockPosition position, List<RawPacketFrame> frames) {
        return Paper26_2BlockStateDecoder.decode(
                position,
                frames,
                frame -> typeName(frame)
                        .map(typeName -> typeName.endsWith(":block_entity_data") || typeName.endsWith(":block_update"))
                        .orElse(false),
                codec::decode,
                net.minecraft.server.MinecraftServer.getServer().registryAccess());
    }

    @Override
    public List<ObserverAnchor> observerAnchors(List<RawPacketFrame> checkpointFrames) {
        return observerAnchors(checkpointFrames, registry, codec);
    }

    /**
     * The players and living entities of a checkpoint, players first: where a
     * viewer can be placed so that it sees the scene.
     */
    static List<ObserverAnchor> observerAnchors(
            List<RawPacketFrame> checkpointFrames, PacketRegistry registry, PacketCodec<Object> codec) {
        Objects.requireNonNull(checkpointFrames, "checkpointFrames");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(codec, "codec");
        List<ObserverAnchor> anchors = new ArrayList<>();
        for (RawPacketFrame frame : checkpointFrames) {
            PacketDescriptor descriptor = registry.find(
                            frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                    .orElse(null);
            if (descriptor == null || !descriptor.typeName().endsWith(":add_entity")) {
                continue;
            }
            if (codec.decode(frame) instanceof ClientboundAddEntityPacket packet) {
                EntityType<?> entityType = packet.getType();
                boolean player = "player".equals(EntityType.getKey(entityType).getPath());
                if (!player && entityType.getCategory() == MobCategory.MISC) {
                    continue;
                }
                anchors.add(new ObserverAnchor(
                        player ? ObserverAnchor.Kind.PLAYER : ObserverAnchor.Kind.ENTITY,
                        packet.getX(), packet.getY(), packet.getZ(),
                        packet.getYRot(), packet.getXRot(),
                        frame.world()));
            }
        }
        anchors.sort(Comparator.comparing(ObserverAnchor::kind));
        return List.copyOf(anchors);
    }

    private Optional<String> typeName(RawPacketFrame frame) {
        return registry.find(frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .map(PacketDescriptor::typeName);
    }
}
