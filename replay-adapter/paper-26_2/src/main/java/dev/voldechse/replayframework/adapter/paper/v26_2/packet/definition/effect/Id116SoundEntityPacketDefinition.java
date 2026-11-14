package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:sound_entity}.
 *
 * <p>This one-shot scene effect is attached to an entity, so playback remaps that entity ID and
 * preserves the sound event, category, volume, pitch and randomization seed.</p>
 */
final class Id116SoundEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSoundEntityPacket,
        Id116SoundEntityPacketDefinition.Value> {

    static final int PACKET_ID = 0x74;
    static final String IDENTIFIER = "minecraft:sound_entity";

    Id116SoundEntityPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSoundEntityPacket.class);
    }

    @Override
    protected Value read(ClientboundSoundEntityPacket packet) {
        return new Value(
                packet.getSound().value(),
                packet.getSource(),
                packet.getId(),
                packet.getVolume(),
                packet.getPitch(),
                packet.getSeed());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                value.sound(),
                value.source(),
                context.mapEntityId(value.entityId()),
                value.volume(),
                value.pitch(),
                value.seed()));
    }

    @Override
    protected ClientboundSoundEntityPacket rebuild(Value value) {
        // The native public constructor requires an Entity, but replay entity IDs are virtual.
        // Decode through the version-matched packet codec after writing the rewritten wire fields.
        // Using a direct SoundEvent holder is valid protocol data and avoids a target-registry
        // lookup for custom sounds while preserving the event identifier and fixed range.
        ByteBuf encoded = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf output =
                    new RegistryFriendlyByteBuf(encoded, RegistryAccess.EMPTY);
            SoundEvent.STREAM_CODEC.encode(output, Holder.direct(value.sound()));
            output.writeEnum(value.source());
            output.writeVarInt(value.entityId());
            output.writeFloat(value.volume());
            output.writeFloat(value.pitch());
            output.writeLong(value.seed());

            RegistryFriendlyByteBuf input =
                    new RegistryFriendlyByteBuf(encoded, RegistryAccess.EMPTY);
            ClientboundSoundEntityPacket packet =
                    ClientboundSoundEntityPacket.STREAM_CODEC.decode(input);
            if (input.isReadable()) {
                throw new IncompatibleAdapterException(
                        "sound_entity native codec left unexpected payload bytes");
            }
            return packet;
        } finally {
            encoded.release();
        }
    }

    record Value(
            SoundEvent sound,
            SoundSource source,
            int entityId,
            float volume,
            float pitch,
            long seed) {

        Value {
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
                        "sound_entity contains a non-finite " + field + " value");
            }
        }
    }
}
