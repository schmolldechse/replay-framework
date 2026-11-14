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
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/** Complete read, preserve, and rebuild contract for {@code minecraft:stop_sound}. */
final class Id119StopSoundPacketDefinition extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x77;
    static final String IDENTIFIER = "minecraft:stop_sound";

    Id119StopSoundPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE));
    }

    @Override
    protected Object decode(RawPacketFrame frame, PacketCodec<Object> codec) {
        Object decoded = super.decode(frame, codec);
        if (!(decoded instanceof ClientboundStopSoundPacket packet)) {
            throw new IncompatibleAdapterException(
                    "decoded type for " + typeName() + " was "
                            + decoded.getClass().getName()
                            + ", expected " + ClientboundStopSoundPacket.class.getName());
        }

        WireFields fields = readWireFields(frame.payload());
        if (!Objects.equals(packet.getSource(), fields.source().orElse(null))
                || !Objects.equals(packet.getName(), fields.sound().orElse(null))) {
            throw new IncompatibleAdapterException(
                    "stop_sound native codec and raw payload fields disagree");
        }
        return new Value(fields.source(), fields.sound());
    }

    @Override
    protected PacketRewriteResult rewriteSafe(
            Object decodedPacket,
            Paper26_2ReplayPacketRewriter context) {
        if (!(decodedPacket instanceof Value value)) {
            throw new IncompatibleAdapterException(
                    "stop_sound rewrite requires verified optional-field flags");
        }
        return PacketRewriteResult.send(new ClientboundStopSoundPacket(
                value.sound().orElse(null),
                value.source().orElse(null)));
    }

    private static WireFields readWireFields(byte[] payload) {
        ByteBuf bytes = Unpooled.wrappedBuffer(payload);
        try {
            FriendlyByteBuf input = new FriendlyByteBuf(bytes);
            int flags = input.readUnsignedByte();
            if ((flags & ~0x03) != 0) {
                throw new IncompatibleAdapterException(
                        "stop_sound contains unsupported reserved flag bits");
            }

            Optional<SoundSource> source = (flags & 0x01) != 0
                    ? Optional.of(input.readEnum(SoundSource.class))
                    : Optional.empty();
            Optional<Identifier> sound = (flags & 0x02) != 0
                    ? Optional.of(input.readIdentifier())
                    : Optional.empty();
            if (input.isReadable()) {
                throw new IncompatibleAdapterException("stop_sound contains trailing payload bytes");
            }
            return new WireFields(source, sound);
        } catch (IncompatibleAdapterException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IncompatibleAdapterException(
                    "stop_sound payload is truncated or malformed", exception);
        } finally {
            bytes.release();
        }
    }

    private record WireFields(Optional<SoundSource> source, Optional<Identifier> sound) {
        private WireFields {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sound, "sound");
        }
    }

    private record Value(Optional<SoundSource> source, Optional<Identifier> sound) {
        private Value {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sound, "sound");
        }
    }
}
