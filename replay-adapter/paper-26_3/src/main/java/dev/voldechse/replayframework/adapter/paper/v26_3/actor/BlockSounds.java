package dev.voldechse.replayframework.adapter.paper.v26_3.actor;

import dev.voldechse.replayframework.adapter.paper.actor.ActorEffect;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;

/** Builds block sounds exactly as {@code Level.playSound(except, BlockPos, ...)} sends them. */
final class BlockSounds {

    private static final Field TRAPDOOR_TYPE = typeField(TrapDoorBlock.class);
    private static final Field BUTTON_TYPE = typeField(ButtonBlock.class);
    private static final Field FENCE_GATE_TYPE = typeField(FenceGateBlock.class);

    private BlockSounds() {}

    /** A sound at the center of a block, with a fresh seed like the server's. */
    static ActorEffect at(BlockPosition position, SoundEvent sound, float volume, float pitch) {
        double x = position.x() + 0.5;
        double y = position.y() + 0.5;
        double z = position.z() + 0.5;
        ClientboundSoundPacket packet = new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                SoundSource.BLOCKS,
                x, y, z,
                volume, pitch,
                ThreadLocalRandom.current().nextLong());
        return new ActorEffect(packet, x, y, z, sound.getRange(volume));
    }

    /** The pitch doors, trapdoors and fence gates use: {@code random * 0.1 + 0.9}. */
    static float hingePitch() {
        // The server draws it from the level's random. Drawing from it here
        // would shift the world's random sequence, so a separate one is used.
        return ThreadLocalRandom.current().nextFloat() * 0.1F + 0.9F;
    }

    static BlockSetType type(TrapDoorBlock block) {
        return (BlockSetType) read(TRAPDOOR_TYPE, block);
    }

    static BlockSetType type(ButtonBlock block) {
        return (BlockSetType) read(BUTTON_TYPE, block);
    }

    static WoodType type(FenceGateBlock block) {
        return (WoodType) read(FENCE_GATE_TYPE, block);
    }

    private static Object read(Field field, Object block) {
        try {
            return field.get(block);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Paper 26.3 block type is not readable", exception);
        }
    }

    private static Field typeField(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("type");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
