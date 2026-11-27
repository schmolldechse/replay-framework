package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientboundPacketDefinitionCatalogTest {

    @Test
    void keepsOneExplicitDefinitionPerType() {
        var definition = declared(
                7,
                "minecraft:block_update",
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE));
        var catalog = new ClientboundPacketDefinitionCatalog<>(java.util.List.of(definition));

        assertEquals(definition, catalog.require("play.clientbound.minecraft:block_update"));
        assertEquals(definition, catalog.find(7).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new ClientboundPacketDefinitionCatalog<>(
                java.util.List.of(definition, definition)));
    }

    @Test
    void rejectsTwoDefinitionsForTheSamePacketId() {
        var first = declared(
                7,
                "minecraft:block_update",
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE));
        var second = declared(
                7,
                "minecraft:block_event",
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE));

        assertThrows(IllegalArgumentException.class, () -> new ClientboundPacketDefinitionCatalog<>(
                java.util.List.of(first, second)));
    }

    @Test
    void refusesGenericIdentityRewriteDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new SimplePacketDefinition<>(
                1,
                "minecraft:add_entity",
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES)));
    }

    @Test
    void viewerLocalPolicyDropsBeforeDecode() {
        var definition = new SimplePacketDefinition<Object>(
                7,
                "minecraft:set_health",
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
        PacketDescriptor descriptor = descriptor(
                "minecraft:set_health", PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = codec(decodes);

        PacketRewriteResult result = definition.handlePlayback(
                frame(descriptor), descriptor, codec, new Object());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertEquals(0, decodes.get());
    }

    @Test
    void rebuildingDefinitionOwnsReadRewriteRebuildAndValidatedEncode() {
        var definition = new RebuildingPacketDefinition<Object, String, Integer>(
                7,
                "minecraft:block_update",
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                String.class) {
            @Override
            protected Integer read(String packet) {
                return packet.length();
            }

            @Override
            protected ValueRewriteResult<Integer> rewrite(Integer value, Object context) {
                return ValueRewriteResult.send(value + 1);
            }

            @Override
            protected String rebuild(Integer value) {
                return "x".repeat(value);
            }
        };
        PacketDescriptor descriptor = descriptor(
                "minecraft:block_update", PacketDescriptor.PlaybackScope.SAFE);
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = codec(decodes);

        PacketRewriteResult result = definition.handlePlayback(
                frame(descriptor), descriptor, codec, new Object());
        PacketCodec.EncodedPacket encoded = definition.encode(
                result.packet().orElseThrow(), descriptor, codec);

        assertEquals("xxxx", result.packet().orElseThrow());
        assertEquals(1, decodes.get());
        assertEquals(descriptor.packetId(), encoded.packetId());
    }

    @Test
    void rejectsAPlayerOnlyDefinitionWithoutDeclaredRecipientCopies() {
        var undeclared = new SimplePacketDefinition<Object>(
                7,
                "minecraft:sound",
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ClientboundPacketDefinitionCatalog<>(java.util.List.of(undeclared)));
        assertTrue(failure.getMessage().contains("play.clientbound.minecraft:sound"));
    }

    @Test
    void viewerLocalDefinitionsNeedNoRecipientCopies() {
        var local = new SimplePacketDefinition<Object>(
                7,
                "minecraft:set_health",
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));

        assertEquals(1, new ClientboundPacketDefinitionCatalog<>(java.util.List.of(local))
                .definitions().size());
    }

    @Test
    void recipientCopiesNameTheEventTheirCopiesShare() {
        assertTrue(RecipientCopies.unique().identity("packet", new byte[] {1}).isEmpty());
        assertEquals(
                RecipientCopies.identicalPayload().identity("a", new byte[] {1, 2}),
                RecipientCopies.identicalPayload().identity("b", new byte[] {1, 2}));
        assertNotEquals(
                RecipientCopies.identicalPayload().identity("a", new byte[] {1, 2}),
                RecipientCopies.identicalPayload().identity("a", new byte[] {1, 3}));

        RecipientCopies byLength = RecipientCopies.keyedBy(String.class, String::length);
        assertEquals(byLength.identity("abc", new byte[] {1}), byLength.identity("xyz", new byte[] {2}));
        assertThrows(IncompatibleAdapterException.class, () -> byLength.identity(7, new byte[0]));

        assertTrue(RecipientCopies.deliveredByScene().fromSceneOnly());
        assertTrue(RecipientCopies.deliveredByScene().declared());
        assertFalse(RecipientCopies.undeclared().declared());
    }

    private static ClientboundPacketDefinition<Object> declared(
            int packetId, String identifier, PacketClassification classification) {
        return new ClientboundPacketDefinition<>(packetId, identifier, classification) {
            @Override
            public RecipientCopies recipientCopies() {
                return RecipientCopies.unique();
            }
        };
    }

    private static PacketDescriptor descriptor(
            String identifier,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                "play.clientbound." + identifier,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                7,
                PacketDisposition.STATEFUL,
                true,
                true,
                scope != PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                "test/" + identifier,
                scope);
    }

    private static RawPacketFrame frame(PacketDescriptor descriptor) {
        return new RawPacketFrame(
                0L, 0L, 0, descriptor.phase(), descriptor.packetId(), new byte[] {1});
    }

    private static PacketCodec<Object> codec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        7,
                        packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                return "abc";
            }
        };
    }
}
