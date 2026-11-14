package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Complete decode, preserve, and rebuild contract for {@code minecraft:sound}. */
final class Id117SoundPacketDefinition extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x75;
    static final String IDENTIFIER = "minecraft:sound";

    Id117SoundPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE));
    }

    @Override
    protected Object decode(RawPacketFrame frame, PacketCodec<Object> codec) {
        Object decoded = super.decode(frame, codec);
        if (!(decoded instanceof ClientboundSoundPacket packet)) {
            throw new IncompatibleAdapterException(
                    "decoded type for " + typeName() + " was "
                            + decoded.getClass().getName()
                            + ", expected " + ClientboundSoundPacket.class.getName());
        }

        WireFields wireFields = readWireFields(frame.payload());
        if (packet.getSource() != wireFields.source()
                || Float.floatToRawIntBits(packet.getVolume())
                        != Float.floatToRawIntBits(wireFields.volume())
                || Float.floatToRawIntBits(packet.getPitch())
                        != Float.floatToRawIntBits(wireFields.pitch())
                || packet.getSeed() != wireFields.seed()
                || (wireFields.inlineSound() != null
                        && !packet.getSound().value().equals(wireFields.inlineSound()))) {
            throw new IncompatibleAdapterException(
                    "sound native codec and raw payload fields disagree");
        }

        return new Value(
                packet.getSound().value(),
                packet.getSource(),
                wireFields.x(),
                wireFields.y(),
                wireFields.z(),
                packet.getVolume(),
                packet.getPitch(),
                packet.getSeed());
    }

    @Override
    protected PacketRewriteResult rewriteSafe(
            Object decodedPacket,
            Paper26_2ReplayPacketRewriter context) {
        if (!(decodedPacket instanceof Value value)) {
            throw new IncompatibleAdapterException(
                    "sound rewrite requires the original fixed-point coordinate values");
        }
        return PacketRewriteResult.send(rebuild(value));
    }

    private static ClientboundSoundPacket rebuild(Value value) {
        // The native getters divide raw Int coordinates by 8.0F, which loses low bits at large
        // positions. Convert the preserved Int values through double; division and multiplication
        // by eight are exact in binary floating point, so the native constructor restores them.
        return new ClientboundSoundPacket(
                Holder.direct(value.sound()),
                value.source(),
                value.x() / 8.0D,
                value.y() / 8.0D,
                value.z() / 8.0D,
                value.volume(),
                value.pitch(),
                value.seed());
    }

    private static WireFields readWireFields(byte[] payload) {
        ByteBuf bytes = Unpooled.wrappedBuffer(payload);
        try {
            RegistryFriendlyByteBuf input = new RegistryFriendlyByteBuf(bytes, RegistryAccess.EMPTY);
            int holderId = VarInt.read(input);
            SoundEvent inlineSound = null;
            if (holderId == 0) {
                inlineSound = SoundEvent.DIRECT_STREAM_CODEC.decode(input);
            } else if (holderId < 0) {
                throw new IncompatibleAdapterException("sound contains an invalid sound-event holder id");
            }

            SoundSource source = input.readEnum(SoundSource.class);
            int x = input.readInt();
            int y = input.readInt();
            int z = input.readInt();
            float volume = input.readFloat();
            float pitch = input.readFloat();
            long seed = input.readLong();
            if (input.isReadable()) {
                throw new IncompatibleAdapterException("sound contains trailing payload bytes");
            }
            return new WireFields(inlineSound, source, x, y, z, volume, pitch, seed);
        } catch (IncompatibleAdapterException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IncompatibleAdapterException("sound payload is truncated or malformed", exception);
        } finally {
            bytes.release();
        }
    }

    private record WireFields(
            SoundEvent inlineSound,
            SoundSource source,
            int x,
            int y,
            int z,
            float volume,
            float pitch,
            long seed) {}

    private record Value(
            SoundEvent sound,
            SoundSource source,
            int x,
            int y,
            int z,
            float volume,
            float pitch,
            long seed) {

        private Value {
            Objects.requireNonNull(sound, "sound");
            Objects.requireNonNull(sound.location(), "sound.location");
            Objects.requireNonNull(sound.fixedRange(), "sound.fixedRange");
            Objects.requireNonNull(source, "source");
            requireFinite(volume, "volume");
            requireFinite(pitch, "pitch");
            sound.fixedRange().ifPresent(range -> requireFinite(range, "sound.fixedRange"));
        }

        private static void requireFinite(float value, String field) {
            if (!Float.isFinite(value)) {
                throw new IncompatibleAdapterException(
                        "sound contains a non-finite " + field + " value");
            }
        }
    }
}
