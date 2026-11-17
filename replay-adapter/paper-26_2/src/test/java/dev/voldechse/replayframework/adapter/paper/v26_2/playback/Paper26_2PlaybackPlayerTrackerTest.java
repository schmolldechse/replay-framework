package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.io.ByteArrayOutputStream;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_2PlaybackPlayerTrackerTest {
    private static final String PREFIX = "play.clientbound.minecraft:";
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final Map<RawPacketFrame, Object> packets = new IdentityHashMap<>();
    private final Paper26_2PlaybackPlayerTracker tracker = new Paper26_2PlaybackPlayerTracker();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void followsSpawnMovementTeleportAndRemoval() {
        observe("player_info_update", 0, info(ALEX, "Alex"), Optional.empty());
        observe("add_entity", 7, spawn(7, ALEX, 10, 64, 20), Optional.of("minecraft:the_nether"));

        RecordedPlayer alex = tracker.players().getFirst();
        assertEquals("Alex", alex.name());
        assertEquals(new RecordedPlayer.Location(Key.key("minecraft:the_nether"), 10, 64, 20, 0, 0),
                alex.location().orElseThrow());

        observeRaw("move_entity_pos", movement(7, 4096, -2048, 8192));
        RecordedPlayer.Location moved = tracker.players().getFirst().location().orElseThrow();
        assertEquals(11, moved.x(), 1.0e-9);
        assertEquals(63.5, moved.y(), 1.0e-9);
        assertEquals(22, moved.z(), 1.0e-9);

        observe("teleport_entity", 7, new ClientboundTeleportEntityPacket(
                7, new PositionMoveRotation(new Vec3(-5, 70, 3), Vec3.ZERO, 90, 10), Set.of(), true),
                Optional.empty());
        assertEquals(new RecordedPlayer.Location(Key.key("minecraft:the_nether"), -5, 70, 3, 90, 10),
                tracker.players().getFirst().location().orElseThrow());

        observe("remove_entities", 7, new ClientboundRemoveEntitiesPacket(7), Optional.empty());
        RecordedPlayer removed = tracker.players().getFirst();
        assertEquals("Alex", removed.name());
        assertFalse(removed.inScene());
    }

    @Test
    void ignoresOtherEntitiesAndSortsByName() {
        observe("player_info_update", 0, info(STEVE, "steve"), Optional.empty());
        observe("player_info_update", 0, info(ALEX, "Alex"), Optional.empty());
        observe("add_entity", 3, new ClientboundAddEntityPacket(
                3, UUID.randomUUID(), 0, 0, 0, 0, 0, EntityTypes.ZOMBIE, 0, Vec3.ZERO, 0),
                Optional.of("minecraft:overworld"));
        observeRaw("move_entity_pos", movement(3, 4096, 0, 0));

        List<RecordedPlayer> players = tracker.players();
        assertEquals(List.of("Alex", "steve"), players.stream().map(RecordedPlayer::name).toList());
        assertTrue(players.stream().noneMatch(RecordedPlayer::inScene));
    }

    @Test
    void clearForgetsEverything() {
        observe("player_info_update", 0, info(ALEX, "Alex"), Optional.empty());
        observe("add_entity", 7, spawn(7, ALEX, 0, 0, 0), Optional.of("minecraft:overworld"));

        tracker.clear();

        assertTrue(tracker.players().isEmpty());
    }

    private void observe(String type, int entityId, Object packet, Optional<String> world) {
        RawPacketFrame frame = new RawPacketFrame(
                0L, 0L, 0, PacketPhase.PLAY, 1, varInt(entityId), world);
        packets.put(frame, packet);
        tracker.observe(frame, PREFIX + type, packets::get);
    }

    private void observeRaw(String type, byte[] payload) {
        RawPacketFrame frame = new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 1, payload);
        tracker.observe(frame, PREFIX + type, ignored -> {
            throw new AssertionError("movement must not be decoded");
        });
    }

    private static ClientboundAddEntityPacket spawn(int id, UUID uuid, double x, double y, double z) {
        return new ClientboundAddEntityPacket(id, uuid, x, y, z, 0, 0, EntityTypes.PLAYER, 0, Vec3.ZERO, 0);
    }

    private static ClientboundPlayerInfoUpdatePacket info(UUID id, String name) {
        return new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                new ClientboundPlayerInfoUpdatePacket.Entry(
                        id, new GameProfile(id, name), true, 0, GameType.SURVIVAL, null, true, 0, null));
    }

    private static byte[] movement(int id, int dx, int dy, int dz) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(varInt(id));
        for (int delta : new int[] {dx, dy, dz}) {
            out.write((delta >> 8) & 0xFF);
            out.write(delta & 0xFF);
        }
        out.write(1);
        return out.toByteArray();
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        return out.toByteArray();
    }
}
