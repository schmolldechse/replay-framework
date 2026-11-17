package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReplayEntityInteractionsTest {

    private static final int REPLAY_ENTITY = -42;
    private static final RecordedEntity LLAMA = new RecordedEntity(
            UUID.randomUUID(), Key.key("minecraft:llama"),
            new RecordedPlayer.Location(Key.key("minecraft:overworld"), 0, 64, 0, 0, 0),
            Optional.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
            Map.of(), List.of(), Set.of(), Optional.empty());

    private final List<PlaybackBridge.EntityInteraction> interactions = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void consumesUseAndAttackOfReplayEntities() {
        EmbeddedChannel channel = channel();

        channel.writeInbound(new ServerboundInteractPacket(REPLAY_ENTITY, InteractionHand.OFF_HAND, Vec3.ZERO, false));
        channel.writeInbound(new ServerboundAttackPacket(REPLAY_ENTITY));

        assertNull(channel.readInbound());
        assertEquals(List.of(
                new PlaybackBridge.EntityInteraction(LLAMA, false, EquipmentSlot.OFF_HAND),
                new PlaybackBridge.EntityInteraction(LLAMA, true, EquipmentSlot.HAND)), interactions);
        assertTrue(failures.isEmpty());
    }

    @Test
    void passesInteractionsWithLiveEntities() {
        EmbeddedChannel channel = channel();
        ServerboundAttackPacket live = new ServerboundAttackPacket(17);

        channel.writeInbound(live);

        assertSame(live, channel.readInbound());
        assertTrue(interactions.isEmpty());
    }

    private EmbeddedChannel channel() {
        return new EmbeddedChannel(new ReplayEntityInteractions(
                id -> id == REPLAY_ENTITY ? Optional.of(LLAMA) : Optional.empty(),
                interactions::add,
                failures::add));
    }
}
