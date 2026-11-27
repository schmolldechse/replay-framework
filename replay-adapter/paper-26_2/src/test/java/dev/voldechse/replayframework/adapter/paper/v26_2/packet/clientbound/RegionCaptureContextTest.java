package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.Paper26_2CaptureTestState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A region recording accepts a packet by its position. These packets carry
 * one, or concern the whole world, and must say so.
 */
final class RegionCaptureContextTest {

    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String WORLD = "minecraft:overworld";

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void placesASoundWhereItPlays() {
        ClientboundSoundPacket sound = new ClientboundSoundPacket(
                Holder.direct(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                        net.minecraft.resources.Identifier.withDefaultNamespace("entity.cow.ambient"))), SoundSource.NEUTRAL,
                10.5, 64.0, -3.25, 1.0F, 1.0F, 7L);

        assertEquals(Optional.of(new BlockPosition(10, 64, -4)), context(0x75, sound).position());
    }

    @Test
    void placesParticlesWhereTheyAppear() {
        ClientboundLevelParticlesPacket particles = new ClientboundLevelParticlesPacket(
                ParticleTypes.FLAME, false, false, -0.5, 70.9, 12.0, 0.0F, 0.0F, 0.0F, 0.0F, 5);

        assertEquals(Optional.of(new BlockPosition(-1, 70, 12)), context(0x2F, particles).position());
    }

    @Test
    void placesALevelEventAtItsBlock() {
        ClientboundLevelEventPacket event = new ClientboundLevelEventPacket(
                1006, new BlockPos(4, 65, 9), 0, false);

        assertEquals(Optional.of(new BlockPosition(4, 65, 9)), context(0x2E, event).position());
    }

    @Test
    void marksTheTabListAsWorldWideAndNothingElseByDefault() {
        assertTrue(definition(0x46).worldWide(), "player_info_update");
        assertTrue(definition(0x45).worldWide(), "player_info_remove");
        assertFalse(definition(0x75).worldWide(), "sound");
        assertFalse(definition(0x08).worldWide(), "block_update");
    }

    private static CaptureContext context(int packetId, Object packet) {
        PacketCaptureState state = Paper26_2CaptureTestState.create();
        state.world(CONNECTION, WORLD);
        PacketDescriptor descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, packetId)
                .orElseThrow();
        CaptureContext context = definition(packetId)
                .captureContext(packet, descriptor, CONNECTION, state, 0L);
        assertEquals(Optional.of(net.kyori.adventure.key.Key.key(WORLD)), context.world());
        return context;
    }

    private static ClientboundPacketDefinition<?> definition(int packetId) {
        return Paper26_2ClientboundPacketCatalog.catalog().find(packetId).orElseThrow();
    }
}
