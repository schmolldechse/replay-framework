package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import static org.junit.jupiter.api.Assertions.*;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NeutralObserverTest {
    private static Paper26_3PacketRegistry registry;
    private final UUID observer = UUID.randomUUID();

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        registry = Paper26_3PacketRegistry.discover();
    }

    @Test
    void everyRecordedConnectionLocalPacketIsDroppedBeforeDecodingItsContents() {
        var rewriter = rewriter();
        var locals = registry.descriptors().stream()
                .filter(d -> d.playbackScope() == PacketDescriptor.PlaybackScope.VIEWER_LOCAL).toList();
        assertTrue(locals.size() >= 45);
        for (var descriptor : locals) {
            assertEquals(Paper26_3ReplayPacketRewriter.RewriteResult.Decision.DROP_LOCAL,
                    rewriter.rewrite(new Object(), descriptor).decision(), descriptor.typeName());
        }
        for (String name : List.of("set_health", "set_experience", "player_position", "player_rotation",
                "player_abilities", "set_camera", "respawn", "set_player_inventory", "set_title_text",
                "tab_list", "commands", "custom_payload", "player_combat_kill", "set_objective",
                "set_display_objective", "set_player_team", "set_score", "reset_score", "waypoint")) {
            assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor(name).playbackScope(), name);
        }
    }

    @Test
    void actionBarAndGameModeCannotBeReplacedByRecordedPresentationPackets() {
        var rewriter = rewriter();
        assertEquals(Paper26_3ReplayPacketRewriter.RewriteResult.Decision.DROP_LOCAL,
                rewriter.rewrite(new ClientboundSystemChatPacket(Component.literal("old HUD"), true),
                        descriptor("system_chat")).decision());
        var chat = new ClientboundSystemChatPacket(Component.literal("recorded chat"), false);
        assertSame(chat, rewriter.rewrite(chat, descriptor("system_chat")).packet());
        assertEquals(Paper26_3ReplayPacketRewriter.RewriteResult.Decision.DROP_LOCAL,
                rewriter.rewrite(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 0),
                        descriptor("game_event")).decision());
    }

    @Test
    void entitiesAndUuidsAreStableWithinAReplayButIsolatedAcrossViewersAndSessions() {
        var first = rewriter();
        var second = rewriter();
        int mapped = first.mapEntityId(42);
        assertNotEquals(42, mapped);
        assertEquals(mapped, first.mapEntityId(42));
        assertNotEquals(mapped, second.mapEntityId(42));
        assertNotEquals(observer, first.mapUuid(observer));
        assertEquals(first.mapUuid(observer), first.mapUuid(observer));
        assertNotEquals(first.mapUuid(observer), second.mapUuid(observer));
        var hurt = (ClientboundHurtAnimationPacket) first.rewrite(
                new ClientboundHurtAnimationPacket(42, 90), descriptor("hurt_animation")).packet();
        assertEquals(mapped, hurt.id());
        var remove = (ClientboundRemoveEntitiesPacket) first.rewrite(
                new ClientboundRemoveEntitiesPacket(42), descriptor("remove_entities")).packet();
        assertEquals(mapped, remove.entityIds().getInt(0));
    }

    @Test
    void playerInfoPreservesTheRecordedProfileNameWhileIsolatingItsIdentity() {
        var profile = new com.mojang.authlib.GameProfile(
                observer, "PlayerA", new com.mojang.authlib.properties.PropertyMap());
        var entry = new ClientboundPlayerInfoUpdatePacket.Entry(
                observer,
                profile,
                true,
                42,
                net.minecraft.world.level.GameType.SURVIVAL,
                null,
                true,
                0,
                null);
        var packet = new ClientboundPlayerInfoUpdatePacket(
                java.util.EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                List.of(entry));

        var rewritten = (ClientboundPlayerInfoUpdatePacket) rewriter()
                .rewrite(packet, descriptor("player_info_update"))
                .packet();
        var rewrittenEntry = rewritten.entries().getFirst();

        assertNotEquals(observer, rewrittenEntry.profileId());
        assertEquals(rewrittenEntry.profileId(), rewrittenEntry.profile().id());
        assertEquals("PlayerA", rewrittenEntry.profile().name());
        assertEquals(profile.properties(), rewrittenEntry.profile().properties());
        assertFalse(rewrittenEntry.listed());
    }

    @Test
    void animationAndEntityEventsRewriteTheirWireEntityIdWithoutChangingTheEffect() {
        var rewriter = rewriter();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(42).writeByte(ClientboundAnimatePacket.CRITICAL_HIT);
            var animation = (ClientboundAnimatePacket) rewriter.rewrite(
                    ClientboundAnimatePacket.STREAM_CODEC.decode(buffer), descriptor("animate")).packet();
            assertEquals(rewriter.mapEntityId(42), animation.getId());
            assertEquals(ClientboundAnimatePacket.CRITICAL_HIT, animation.getAction());
            buffer.clear();
            buffer.writeInt(42).writeByte(3);
            var event = (ClientboundEntityEventPacket) rewriter.rewrite(
                    ClientboundEntityEventPacket.STREAM_CODEC.decode(buffer), descriptor("entity_event")).packet();
            buffer.clear();
            ClientboundEntityEventPacket.STREAM_CODEC.encode(buffer, event);
            assertEquals(rewriter.mapEntityId(42), buffer.readInt());
            assertEquals(3, buffer.readByte());
        } finally {
            buffer.release();
        }
    }

    @Test
    void leashLinksRemapBothEndsButPreserveTheUnlinkSentinel() {
        var rewriter = rewriter();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int holder : new int[] {43, 0}) {
                buffer.clear();
                buffer.writeInt(42).writeInt(holder);
                var link = (ClientboundSetEntityLinkPacket) rewriter.rewrite(
                        ClientboundSetEntityLinkPacket.STREAM_CODEC.decode(buffer), descriptor("set_entity_link")).packet();
                assertEquals(rewriter.mapEntityId(42), link.getSourceId());
                assertEquals(holder == 0 ? 0 : rewriter.mapEntityId(holder), link.getDestId());
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    void explosionPreservesSceneEffectsButRemovesObserverKnockback() {
        var original = new ClientboundExplodePacket(new net.minecraft.world.phys.Vec3(1, 2, 3), 4, 2,
                java.util.Optional.of(new net.minecraft.world.phys.Vec3(4, 5, 6)),
                net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,
                net.minecraft.util.random.WeightedList.of(), true);
        var result = (ClientboundExplodePacket) rewriter().rewrite(original, descriptor("explode")).packet();
        assertTrue(result.playerKnockback().isEmpty());
        assertEquals(original.center(), result.center());
        assertEquals(original.radius(), result.radius());
        assertEquals(original.explosionParticle(), result.explosionParticle());
        assertEquals(original.explosionSound(), result.explosionSound());
        assertTrue(original.playerKnockback().isPresent());
    }

    @Test
    void recordedChatBecomesUnsignedPresentationAndRespectsTheOriginalFilter() {
        var chatType = new net.minecraft.network.chat.ChatType(
                net.minecraft.network.chat.ChatType.DEFAULT_CHAT_DECORATION,
                net.minecraft.network.chat.ChatType.DEFAULT_CHAT_DECORATION);
        var bound = new net.minecraft.network.chat.ChatType.Bound(net.minecraft.core.Holder.direct(chatType),
                Component.literal("RecordedPlayer"), java.util.Optional.empty());
        var body = new net.minecraft.network.chat.SignedMessageBody.Packed("secret", java.time.Instant.EPOCH,
                0, net.minecraft.network.chat.LastSeenMessages.Packed.EMPTY);
        var partial = new net.minecraft.network.chat.FilterMask(6);
        for (int i = 0; i < 6; i++) partial.setFiltered(i);
        for (var mask : List.of(net.minecraft.network.chat.FilterMask.PASS_THROUGH, partial,
                net.minecraft.network.chat.FilterMask.FULLY_FILTERED)) {
            var packet = new ClientboundPlayerChatPacket(0, observer, 0, java.util.Optional.empty(), body,
                    java.util.Optional.of(Component.literal("secret")), mask, bound);
            var result = rewriter().rewrite(packet, descriptor("player_chat"));
            if (mask.isFullyFiltered()) {
                assertEquals(Paper26_3ReplayPacketRewriter.RewriteResult.Decision.DROP_LOCAL, result.decision());
            } else {
                var chat = assertInstanceOf(ClientboundSystemChatPacket.class, result.packet());
                assertFalse(chat.overlay());
                assertEquals(mask.isEmpty(), chat.content().getString().contains("secret"));
            }
        }
    }

    @Test
    void resetOnlyRemovesReplayOwnedStateAndDoesNotClearObserverUi() {
        var sent = new java.util.ArrayList<Object>();
        var resetter = new Paper26_3ViewResetter(registry, sent::add, failure -> { throw new AssertionError(failure); });
        resetter.reset();
        assertTrue(sent.isEmpty(), "Empty replay must not clear live titles, inventory or scoreboard");
        resetter.observe(new ClientboundSetEntityDataPacket(10000, List.of()));
        resetter.reset();
        assertEquals(1, sent.size());
        assertInstanceOf(ClientboundRemoveEntitiesPacket.class, sent.getFirst());
        sent.clear();
        resetter.reset();
        assertTrue(sent.isEmpty(), "Reset must be idempotent");
    }

    @Test
    void blockBreakOverlayUsesReplayIdsAndIsRemovedOnReset() {
        var original = new ClientboundBlockDestructionPacket(42, new net.minecraft.core.BlockPos(1, 64, 2), 7);
        var rewriter = rewriter();
        var rewritten = (ClientboundBlockDestructionPacket) rewriter.rewrite(original, descriptor("block_destruction")).packet();
        assertEquals(rewriter.mapEntityId(42), rewritten.getId());
        assertEquals(original.getPos(), rewritten.getPos());
        assertEquals(7, rewritten.getProgress());
        var sent = new java.util.ArrayList<Object>();
        var resetter = new Paper26_3ViewResetter(registry, sent::add, failure -> { throw new AssertionError(failure); });
        resetter.observe(rewritten);
        resetter.reset();
        var clear = sent.stream().filter(ClientboundBlockDestructionPacket.class::isInstance)
                .map(ClientboundBlockDestructionPacket.class::cast).findFirst().orElseThrow();
        assertEquals(rewritten.getId(), clear.getId());
        assertEquals(-1, clear.getProgress());
        sent.clear();
        resetter.reset();
        assertTrue(sent.isEmpty());
    }

    @Test
    void minecartMotionPreservesInterpolationStepsForTheMappedEntity() {
        var original = new ClientboundMoveMinecartPacket(42,
                List.of(net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior.MinecartStep.ZERO));
        var rewriter = rewriter();
        var result = (ClientboundMoveMinecartPacket) rewriter.rewrite(original, descriptor("move_minecart_along_track")).packet();
        assertEquals(rewriter.mapEntityId(42), result.entityId());
        assertEquals(original.lerpSteps(), result.lerpSteps());
    }

    @Test
    void entitySoundPreservesAudioFieldsAndRemapsTheTarget() {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(),
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY));
        try {
            net.minecraft.sounds.SoundEvent.STREAM_CODEC.encode(buffer, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE);
            buffer.writeEnum(net.minecraft.sounds.SoundSource.NEUTRAL);
            buffer.writeVarInt(42);
            buffer.writeFloat(0.5f);
            buffer.writeFloat(1.2f);
            buffer.writeLong(17);
            var original = ClientboundSoundEntityPacket.STREAM_CODEC.decode(buffer);
            var rewriter = rewriter();
            var result = (ClientboundSoundEntityPacket) rewriter.rewrite(original, descriptor("sound_entity")).packet();
            assertEquals(rewriter.mapEntityId(42), result.getId());
            assertEquals(original.getSound(), result.getSound());
            assertEquals(original.getSource(), result.getSource());
            assertEquals(0.5f, result.getVolume());
            assertEquals(1.2f, result.getPitch());
            assertEquals(17, result.getSeed());
        } finally {
            buffer.release();
        }
    }

    @Test
    void swingAnimationUsesTheNew26_3PacketWithAnIsolatedEntityId() {
        var original = new ClientboundSwingAnimationPacket(42, net.minecraft.world.InteractionHand.OFF_HAND,
                net.minecraft.world.item.component.SwingAnimation.DEFAULT);
        var rewriter = rewriter();
        var result = (ClientboundSwingAnimationPacket) rewriter.rewrite(original, descriptor("swing_animation")).packet();
        assertEquals(rewriter.mapEntityId(42), result.entityId());
        assertEquals(original.hand(), result.hand());
        assertEquals(original.animation(), result.animation());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor("post_effects").playbackScope());
    }

    @Test
    void transientBlockIsReplayedAsAWorldEffectAndClearedWithItsReplayChunk() {
        var packet = new net.minecraft.network.protocol.game.ClientboundAddTransientBlockPacket(
                new net.minecraft.core.BlockPos(4, 70, 9),
                net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
        var result = rewriter().rewrite(packet, descriptor("add_transient_block"));
        assertEquals(Paper26_3ReplayPacketRewriter.RewriteResult.Decision.SEND, result.decision());
        assertSame(packet, result.packet());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor("add_transient_block").playbackScope());
        assertFalse(descriptor("add_transient_block").checkpointRelevant());
    }

    private Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(registry,
                new PlaybackIdentityContext(observer, UUID.randomUUID()), Set.of(observer), Set.of(42));
    }

    private static PacketDescriptor descriptor(String name) {
        return registry.descriptors().stream()
                .filter(d -> d.typeName().equals("play.clientbound.minecraft:" + name)).findFirst().orElseThrow();
    }
}

