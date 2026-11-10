package dev.voldechse.replayframework.prototype.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AddEntityPacketHandlerTest {

    private static final int ADD_ENTITY_PACKET_ID = 1;

    @Test
    void rewritesIdentityAndRebuildsANewNativePacketBeforeEncoding() {
        UUID recordedUuid = UUID.fromString("72efb4c2-3a75-45be-9071-564c0b0c0c41");
        ClientboundAddEntityPacket recorded = new ClientboundAddEntityPacket(
                37,
                recordedUuid,
                12.5,
                70.0,
                -4.25,
                15.0F,
                90.0F,
                EntityType.ARMOR_STAND,
                0,
                new Vec3(0.1, 0.0, -0.1),
                90.0D);
        FakeCodec codec = new FakeCodec(recorded);
        PlaybackContext context = new PlaybackContext(
                id -> id + 10_000,
                id -> UUID.nameUUIDFromBytes(
                        ("playback:" + id).getBytes(StandardCharsets.UTF_8)));

        PlaybackResult result = new AddEntityPacketHandler(ADD_ENTITY_PACKET_ID).handle(
                new RawPacketFrame(ADD_ENTITY_PACKET_ID, new byte[] {1, 2, 3}),
                codec,
                context);

        assertEquals(PlaybackResult.Decision.SEND, result.decision());
        assertEquals(ADD_ENTITY_PACKET_ID, result.packet().orElseThrow().packetId());
        ClientboundAddEntityPacket rebuilt = (ClientboundAddEntityPacket) codec.encodedValue;
        assertNotSame(recorded, rebuilt);
        assertEquals(10_037, rebuilt.getId());
        assertEquals(context.mapUuid(recordedUuid), rebuilt.getUUID());
        assertEquals(recorded.getX(), rebuilt.getX());
        assertEquals(recorded.getMovement(), rebuilt.getMovement());
        assertEquals(EntityType.ARMOR_STAND, rebuilt.getType());
    }

    private static final class FakeCodec implements PacketCodecSession {
        private final Object decodedValue;
        private Object encodedValue;

        private FakeCodec(Object decodedValue) {
            this.decodedValue = decodedValue;
        }

        @Override
        public Object decode(RawPacketFrame frame) {
            return decodedValue;
        }

        @Override
        public EncodedPacket encode(Object packet) {
            encodedValue = packet;
            return new EncodedPacket(ADD_ENTITY_PACKET_ID, new byte[] {9, 8, 7});
        }
    }
}
