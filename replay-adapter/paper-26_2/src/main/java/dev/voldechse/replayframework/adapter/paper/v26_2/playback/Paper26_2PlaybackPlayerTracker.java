package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import com.mojang.authlib.GameProfile;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

/**
 * Knows where every player of a replay is at the current playback position.
 *
 * <p>It reads the frames before playback filters them by world, so it also
 * follows players in worlds the viewer does not watch. It decodes only the
 * packets that concern players: tab-list entries for names, and spawn,
 * movement and removal of player entities. Movement packets of other
 * entities are skipped after reading their entity ID.</p>
 *
 * <p>A seek resets the view and replays checkpoint and frames, so the tracker
 * is cleared together with the view and rebuilds itself.</p>
 */
final class Paper26_2PlaybackPlayerTracker {

    private static final String PREFIX = "play.clientbound.minecraft:";

    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<Integer, PlayerEntity> entities = new HashMap<>();

    /**
     * Observes one frame.
     *
     * @param typeName the frame's descriptor type name
     * @param decoder decodes the frame into its native packet
     */
    synchronized void observe(RawPacketFrame frame, String typeName, Function<RawPacketFrame, Object> decoder) {
        if (!typeName.startsWith(PREFIX)) {
            return;
        }
        switch (typeName.substring(PREFIX.length())) {
            case "player_info_update" -> rememberNames(decoder.apply(frame));
            case "add_entity" -> spawn(frame, decoder.apply(frame));
            case "move_entity_pos", "move_entity_pos_rot" -> move(frame);
            case "entity_position_sync" -> {
                if (entities.containsKey(entityId(frame))) {
                    sync(decoder.apply(frame));
                }
            }
            case "teleport_entity" -> {
                if (entities.containsKey(entityId(frame))) {
                    teleport(decoder.apply(frame));
                }
            }
            case "remove_entities" -> {
                if (decoder.apply(frame) instanceof ClientboundRemoveEntitiesPacket remove) {
                    remove.getEntityIds().forEach((int id) -> entities.remove(id));
                }
            }
            default -> { }
        }
    }

    /** Forgets everything; the frames after a view reset rebuild it. */
    synchronized void clear() {
        names.clear();
        entities.clear();
    }

    /** Every known player, sorted by name, with its location when it is in the scene. */
    synchronized List<RecordedPlayer> players() {
        Map<UUID, RecordedPlayer.Location> locations = new HashMap<>();
        for (PlayerEntity entity : entities.values()) {
            locations.put(entity.id, new RecordedPlayer.Location(
                    Key.key(entity.world), entity.x, entity.y, entity.z, entity.yaw, entity.pitch));
        }
        Map<UUID, String> known = new LinkedHashMap<>(names);
        entities.values().forEach(entity -> known.putIfAbsent(entity.id, entity.id.toString()));
        List<RecordedPlayer> players = new ArrayList<>();
        known.forEach((id, name) -> players.add(
                new RecordedPlayer(id, name, Optional.ofNullable(locations.get(id)))));
        players.sort(Comparator.comparing(RecordedPlayer::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(players);
    }

    private void rememberNames(Object packet) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket update)) {
            return;
        }
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : update.entries()) {
            GameProfile profile = entry.profile();
            if (profile != null) {
                names.put(entry.profileId(), profile.name());
            }
        }
    }

    private void spawn(RawPacketFrame frame, Object packet) {
        if (!(packet instanceof ClientboundAddEntityPacket add)
                || add.getType() != EntityTypes.PLAYER
                || frame.world().isEmpty()) {
            return;
        }
        entities.put(add.getId(), new PlayerEntity(
                add.getUUID(), frame.world().get(),
                add.getX(), add.getY(), add.getZ(), add.getYRot(), add.getXRot()));
    }

    /** Relative movement: entity ID, then three signed shorts in 1/4096 blocks. */
    private void move(RawPacketFrame frame) {
        byte[] payload = frame.payload();
        int[] cursor = {0};
        int id = readVarInt(payload, cursor);
        PlayerEntity entity = entities.get(id);
        if (entity == null || payload.length < cursor[0] + 6) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload, cursor[0], 6);
        entity.x += buffer.getShort() / 4096.0;
        entity.y += buffer.getShort() / 4096.0;
        entity.z += buffer.getShort() / 4096.0;
    }

    private void sync(Object packet) {
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            PlayerEntity entity = entities.get(sync.id());
            if (entity != null) {
                entity.place(sync.values());
            }
        }
    }

    private void teleport(Object packet) {
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            PlayerEntity entity = entities.get(teleport.id());
            if (entity != null) {
                entity.place(PositionMoveRotation.calculateAbsolute(
                        entity.current(), teleport.change(), teleport.relatives()));
            }
        }
    }

    private static int entityId(RawPacketFrame frame) {
        return readVarInt(frame.payload(), new int[] {0});
    }

    private static int readVarInt(byte[] bytes, int[] cursor) {
        int value = 0;
        int shift = 0;
        while (cursor[0] < bytes.length && shift < 35) {
            int next = bytes[cursor[0]++] & 0xFF;
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    /** One player entity of the replay and its last known position. */
    private static final class PlayerEntity {
        private final UUID id;
        private final String world;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private PlayerEntity(UUID id, String world, double x, double y, double z, float yaw, float pitch) {
            this.id = Objects.requireNonNull(id, "id");
            this.world = Objects.requireNonNull(world, "world");
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private PositionMoveRotation current() {
            return new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch);
        }

        private void place(PositionMoveRotation values) {
            x = values.position().x;
            y = values.position().y;
            z = values.position().z;
            yaw = values.yRot();
            pitch = values.xRot();
        }
    }
}
