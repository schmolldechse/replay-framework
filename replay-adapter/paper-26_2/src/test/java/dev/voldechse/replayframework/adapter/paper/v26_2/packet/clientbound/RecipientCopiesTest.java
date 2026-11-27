package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FilterMask;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.SignedMessageBody;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins how every recorded player-only packet of 26.2 recognizes the copies
 * several players received, and checks the identities of the packets the
 * server builds per recipient.
 */
class RecipientCopiesTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void everyRecordedPlayerOnlyPacketDeclaresItsCopies() {
        Map<String, String> declared = new TreeMap<>();
        for (ClientboundPacketDefinition<?> definition : Paper26_2ClientboundPacketCatalog.catalog().definitions()) {
            if (definition.recordedFromPlayers()) {
                declared.put(definition.typeName().substring("play.clientbound.minecraft:".length()),
                        definition.recipientCopies().toString());
            }
        }

        Map<String, String> expected = new TreeMap<>();
        for (String identical : new String[] {
                "block_destruction", "block_event", "boss_event", "disguised_chat",
                "initialize_border", "level_particles", "player_info_remove", "player_info_update",
                "set_border_center", "set_border_lerp_size", "set_border_size",
                "set_border_warning_delay", "set_border_warning_distance",
                "set_default_spawn_position", "sound", "sound_entity", "stop_sound",
                "system_chat"}) {
            expected.put(identical, "identical payload");
        }
        expected.put("explode", "keyed by ClientboundExplodePacket");
        expected.put("level_event", "keyed by ClientboundLevelEventPacket");
        expected.put("player_chat", "keyed by ClientboundPlayerChatPacket");
        expected.put("game_event", "delivered by the scene");
        expected.put("set_time", "delivered by the scene");

        assertEquals(expected, declared);
    }

    @Test
    void explosionCopiesDifferOnlyInKnockback() {
        RecipientCopies copies = copies("explode");
        Vec3 center = new Vec3(10.5, 64, -3.5);

        Object alex = identity(copies, explosion(center, Optional.of(new Vec3(0.4, 0.2, 0))));
        Object steve = identity(copies, explosion(center, Optional.empty()));
        Object elsewhere = identity(copies, explosion(new Vec3(30, 64, 0), Optional.empty()));

        assertEquals(alex, steve);
        assertNotEquals(alex, elsewhere);
    }

    @Test
    void globalLevelEventCopiesDifferOnlyInTheirProjectedPosition() {
        RecipientCopies copies = copies("level_event");

        Object alex = identity(copies, new ClientboundLevelEventPacket(1023, new BlockPos(32, 70, 0), 0, true));
        Object steve = identity(copies, new ClientboundLevelEventPacket(1023, new BlockPos(-8, 64, 31), 0, true));
        Object local = identity(copies, new ClientboundLevelEventPacket(2001, new BlockPos(1, 64, 1), 5, false));
        Object otherLocal = identity(copies, new ClientboundLevelEventPacket(2001, new BlockPos(2, 64, 1), 5, false));

        assertEquals(alex, steve);
        assertNotEquals(local, otherLocal);
    }

    @Test
    void playerChatCopiesDifferOnlyInTheirRecipientIndex() {
        RecipientCopies copies = copies("player_chat");
        UUID sender = UUID.randomUUID();
        Instant sent = Instant.ofEpochSecond(1_000);

        Object alex = identity(copies, chat(0, sender, 4, "hello", sent));
        Object steve = identity(copies, chat(17, sender, 4, "hello", sent));
        Object next = identity(copies, chat(1, sender, 5, "hello", sent.plusSeconds(1)));

        assertEquals(alex, steve);
        assertNotEquals(alex, next);
    }

    private static RecipientCopies copies(String packet) {
        return Paper26_2ClientboundPacketCatalog.catalog()
                .require("play.clientbound.minecraft:" + packet)
                .recipientCopies();
    }

    private static Object identity(RecipientCopies copies, Object packet) {
        return copies.identity(packet, new byte[0]).orElseThrow();
    }

    private static ClientboundExplodePacket explosion(Vec3 center, Optional<Vec3> knockback) {
        return new ClientboundExplodePacket(
                center, 4.0F, 12, knockback, ParticleTypes.EXPLOSION,
                SoundEvents.GENERIC_EXPLODE, WeightedList.of());
    }

    private static ClientboundPlayerChatPacket chat(
            int globalIndex, UUID sender, int index, String content, Instant sent) {
        ChatType type = new ChatType(
                ChatTypeDecoration.withSender("chat.type.text"),
                ChatTypeDecoration.withSender("chat.type.text.narrate"));
        return new ClientboundPlayerChatPacket(
                globalIndex, sender, index, null,
                new SignedMessageBody.Packed(content, sent, 7L, LastSeenMessages.Packed.EMPTY),
                null, FilterMask.PASS_THROUGH,
                new ChatType.Bound(Holder.direct(type), Component.literal("Alex"), Optional.empty()));
    }
}
