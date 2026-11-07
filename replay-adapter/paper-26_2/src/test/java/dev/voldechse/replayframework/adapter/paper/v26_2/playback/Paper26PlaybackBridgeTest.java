package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Paper26PlaybackBridgeTest {

    @Test
    void extractsPlayerFirstObserverAnchorsFromCheckpointEntities() {
        PacketRegistry registry = PacketRegistry.of(List.of(new PacketDescriptor(
                "minecraft:add_entity",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                7,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "test")));
        PacketCodec<Object> codec = new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                String entityKey = switch (frame.payload()[0]) {
                    case 1 -> "minecraft:player";
                    case 2 -> "minecraft:armor_stand";
                    default -> "minecraft:cow";
                };
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                        Identifier.parse(entityKey));
                return new ClientboundAddEntityPacket(
                        frame.sequence(),
                        UUID.randomUUID(),
                        frame.sequence() * 10.0D,
                        70.0D,
                        -20.0D,
                        15.0F,
                        30.0F,
                        type,
                        0,
                        Vec3.ZERO,
                        30.0D);
            }
        };
        List<RawPacketFrame> frames = List.of(
                new RawPacketFrame(0L, 0L, 1, PacketPhase.PLAY, 7, new byte[] {0}),
                new RawPacketFrame(0L, 0L, 2, PacketPhase.PLAY, 7, new byte[] {1}),
                new RawPacketFrame(0L, 0L, 3, PacketPhase.PLAY, 7, new byte[] {2}));

        List<ObserverAnchor> anchors = Paper26PlaybackBridge.observerAnchors(
                frames, registry, codec);

        assertEquals(2, anchors.size());
        assertEquals(ObserverAnchor.Kind.PLAYER, anchors.get(0).kind());
        assertEquals(20.0D, anchors.get(0).x());
        assertEquals(ObserverAnchor.Kind.ENTITY, anchors.get(1).kind());
    }
}
