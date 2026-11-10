package dev.voldechse.replayframework.adapter.paper.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PacketDefinitionCatalogTest {

    @Test
    void keepsOneExplicitDefinitionPerType() {
        var definition = new SimplePacketDefinition<Object>(
                "minecraft:block_update",
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE));
        var catalog = new PacketDefinitionCatalog<>(java.util.List.of(definition));

        assertEquals(definition, catalog.require("play.clientbound.minecraft:block_update"));
        assertThrows(IllegalArgumentException.class, () -> new PacketDefinitionCatalog<>(
                java.util.List.of(definition, definition)));
    }

    @Test
    void refusesGenericIdentityRewriteDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new SimplePacketDefinition<>(
                "minecraft:add_entity",
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES)));
    }

    @Test
    void viewerLocalPolicyDropsBeforeDecode() {
        var definition = new SimplePacketDefinition<Object>(
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
