package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import dev.voldechse.replayframework.adapter.paper.playback.scene.SceneModel;
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

/** The 26.3 scene decoder feeding the shared scene model, with real packets. */
class Paper26_3SceneDecoderTest {
    private static final String PREFIX = "play.clientbound.minecraft:";
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final Map<RawPacketFrame, Object> packets = new IdentityHashMap<>();
    private final SceneModel scene = new SceneModel(new Paper26_3SceneDecoder(this::decode));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void metadataIdsMatchTheEntityDataOfThisVersion() throws ReflectiveOperationException {
        // The scene decoder names two Entity data IDs by number because their
        // accessors are not public; they must match the running server.
        assertEquals(entityDataId("DATA_SHARED_FLAGS_ID"), Paper26_3SceneDecoder.SHARED_FLAGS);
        assertEquals(entityDataId("DATA_CUSTOM_NAME"), Paper26_3SceneDecoder.CUSTOM_NAME);
    }

    private static int entityDataId(String field) throws ReflectiveOperationException {
        java.lang.reflect.Field accessor = net.minecraft.world.entity.Entity.class.getDeclaredField(field);
        accessor.setAccessible(true);
        return ((net.minecraft.network.syncher.EntityDataAccessor<?>) accessor.get(null)).id();
    }

    @Test
    void followsSteppedMovementByTheSumOfItsSteps() {
        observe("player_info_update", 0, info(ALEX, "Alex"), Optional.empty());
        observe("add_entity", 7, spawn(7, ALEX, 0, 64, 0), Optional.of("minecraft:overworld"));
        observe("move_entity_pos", 7, new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos(
                7,
                new net.minecraft.network.protocol.game.VecDelta.Stepped(List.of(
                        new net.minecraft.network.protocol.game.VecDelta.Stepped.DeltaStep(
                                (short) 4096, (short) 0, (short) 2048, 1),
                        new net.minecraft.network.protocol.game.VecDelta.Stepped.DeltaStep(
                                (short) 4096, (short) -4096, (short) 0, 1))),
                false), Optional.empty());

        RecordedPlayer.Location moved = scene.players().getFirst().location().orElseThrow();
        assertEquals(2, moved.x(), 1.0e-9);
        assertEquals(63, moved.y(), 1.0e-9);
        assertEquals(0.5, moved.z(), 1.0e-9);
    }

    @Test
    void followsSpawnMovementTeleportAndRemoval() {
        observe("player_info_update", 0, info(ALEX, "Alex"), Optional.empty());
        observe("add_entity", 7, spawn(7, ALEX, 10, 64, 20), Optional.of("minecraft:the_nether"));

        RecordedPlayer alex = scene.players().getFirst();
        assertEquals("Alex", alex.name());
        assertEquals(new RecordedPlayer.Location(Key.key("minecraft:the_nether"), 10, 64, 20, 0, 0),
                alex.location().orElseThrow());

        observe("move_entity_pos", 7, movement(7, 4096, -2048, 8192), Optional.empty());
        RecordedPlayer.Location moved = scene.players().getFirst().location().orElseThrow();
        assertEquals(11, moved.x(), 1.0e-9);
        assertEquals(63.5, moved.y(), 1.0e-9);
        assertEquals(22, moved.z(), 1.0e-9);

        observe("teleport_entity", 7, new ClientboundTeleportEntityPacket(
                7, new PositionMoveRotation(new Vec3(-5, 70, 3), Vec3.ZERO, 90, 10), Set.of(), true),
                Optional.empty());
        assertEquals(new RecordedPlayer.Location(Key.key("minecraft:the_nether"), -5, 70, 3, 90, 10),
                scene.players().getFirst().location().orElseThrow());

        observe("remove_entities", 7, new ClientboundRemoveEntitiesPacket(7), Optional.empty());
        RecordedPlayer removed = scene.players().getFirst();
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
        observe("move_entity_pos", 3, movement(3, 4096, 0, 0), Optional.empty());

        List<RecordedPlayer> players = scene.players();
        assertEquals(List.of("Alex", "steve"), players.stream().map(RecordedPlayer::name).toList());
        assertTrue(players.stream().noneMatch(RecordedPlayer::inScene));
    }

    @Test
    void showsHealthFlagsAndTheEntitiesOfAWorld() {
        observe("add_entity", 7, spawn(7, ALEX, 0, 64, 0), Optional.of("minecraft:overworld"));
        observe("add_entity", 3, new ClientboundAddEntityPacket(
                3, UUID.randomUUID(), 0, 0, 0, 0, 0, EntityTypes.ZOMBIE, 0, Vec3.ZERO, 0),
                Optional.of("minecraft:the_nether"));
        observe("set_entity_data", 7, new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(7, List.of(
                new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(
                        Paper26_3SceneDecoder.SHARED_FLAGS,
                        net.minecraft.network.syncher.EntityDataSerializers.BYTE, (byte) ((1 << 1) | (1 << 6))),
                new net.minecraft.network.syncher.SynchedEntityData.DataValue<>(
                        net.minecraft.world.entity.LivingEntity.DATA_HEALTH_ID.id(),
                        net.minecraft.network.syncher.EntityDataSerializers.FLOAT, 12.5F))),
                Optional.empty());

        var alex = scene.entity(7).orElseThrow();
        assertEquals(ALEX, alex.id());
        assertEquals(net.kyori.adventure.key.Key.key("minecraft:player"), alex.type());
        assertEquals(java.util.OptionalDouble.of(12.5), alex.health());
        assertEquals(java.util.Set.of(
                dev.voldechse.replayframework.api.playback.RecordedEntity.Flag.SNEAKING,
                dev.voldechse.replayframework.api.playback.RecordedEntity.Flag.GLOWING), alex.flags());
        assertEquals(List.of(ALEX), scene.entities("minecraft:overworld").stream()
                .map(dev.voldechse.replayframework.api.playback.RecordedEntity::id).toList());
        assertEquals(1, scene.entities("minecraft:the_nether").size());
    }

    @Test
    void readsTheDataIdsOfTheEntityClass() throws Exception {
        assertEquals(Paper26_3SceneDecoder.SHARED_FLAGS, accessorId("DATA_SHARED_FLAGS_ID"));
        assertEquals(Paper26_3SceneDecoder.CUSTOM_NAME, accessorId("DATA_CUSTOM_NAME"));
    }

    private static int accessorId(String field) throws Exception {
        var declared = net.minecraft.world.entity.Entity.class.getDeclaredField(field);
        declared.setAccessible(true);
        return ((net.minecraft.network.syncher.EntityDataAccessor<?>) declared.get(null)).id();
    }

    @Test
    void clearForgetsEverything() {
        observe("player_info_update", 0, info(ALEX, "Alex"), Optional.empty());
        observe("add_entity", 7, spawn(7, ALEX, 0, 0, 0), Optional.of("minecraft:overworld"));

        scene.clear();

        assertTrue(scene.players().isEmpty());
    }

    private void observe(String type, int entityId, Object packet, Optional<String> world) {
        RawPacketFrame frame = new RawPacketFrame(
                0L, 0L, 0, PacketPhase.PLAY, 1, varInt(entityId), world);
        packets.put(frame, packet);
        scene.observe(frame, PREFIX + type);
    }

    /** Decodes the frames this test built. */
    private Object decode(RawPacketFrame frame) {
        Object packet = packets.get(frame);
        if (packet == null) {
            throw new AssertionError("frame was not built by this test");
        }
        return packet;
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

    /** Paper 26.3: the scene decodes relative movement from the native packet. */
    private static net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos movement(
            int id, int dx, int dy, int dz) {
        return new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos(
                id,
                new net.minecraft.network.protocol.game.VecDelta.Linear((short) dx, (short) dy, (short) dz),
                true);
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
