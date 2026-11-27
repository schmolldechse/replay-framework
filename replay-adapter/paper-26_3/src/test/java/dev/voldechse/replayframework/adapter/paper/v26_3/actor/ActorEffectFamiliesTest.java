package dev.voldechse.replayframework.adapter.paper.v26_3.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.actor.ActorBlockEffects;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffect;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.WoodType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Each family rebuilds the packet the server sends to everyone except the actor. */
class ActorEffectFamiliesTest {

    private static final BlockPosition TARGET = new BlockPosition(4, 64, -9);
    private static final BlockPosition NEIGHBOR = new BlockPosition(4, 65, -9);
    private static final ActorAction USE = ActorAction.useOn(TARGET, NEIGHBOR);
    private static final ActorAction DESTROY = ActorAction.destroy(TARGET);

    private static ActorBlockEffects<BlockState> families;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        families = new ActorBlockEffects<>(List.of(
                new HingedBlockEffect(),
                new SwitchEffect(),
                new BlockBreakEffect(),
                new BlockPlaceEffect()));
    }

    @Test
    void breakingABlockSendsItsBreakEventWithTheBrokenBlock() {
        BlockState stone = Blocks.STONE.defaultBlockState();

        ClientboundLevelEventPacket event = assertInstanceOf(
                ClientboundLevelEventPacket.class,
                only(DESTROY, Map.of(TARGET, stone), Map.of(TARGET, Blocks.AIR.defaultBlockState())));

        assertEquals(2001, event.getType());
        assertEquals(new BlockPos(4, 64, -9), event.getPos());
        assertEquals(Block.getId(stone), event.getData());
    }

    @Test
    void breakingAWaterloggedBlockLeavesWaterAndStillCounts() {
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);

        assertInstanceOf(ClientboundLevelEventPacket.class, only(
                DESTROY, Map.of(TARGET, slab), Map.of(TARGET, Blocks.WATER.defaultBlockState())));
    }

    @Test
    void diggingThatDidNotBreakTheBlockSendsNothing() {
        BlockState stone = Blocks.STONE.defaultBlockState();

        assertTrue(families.effects(DESTROY, Map.of(TARGET, stone), Map.of(TARGET, stone)).isEmpty());
    }

    @Test
    void placingABlockSendsItsPlaceSound() {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();

        ClientboundSoundPacket sound = sound(only(USE,
                Map.of(TARGET, Blocks.STONE.defaultBlockState(), NEIGHBOR, Blocks.AIR.defaultBlockState()),
                Map.of(TARGET, Blocks.STONE.defaultBlockState(), NEIGHBOR, planks)));

        assertEquals(planks.getSoundType().getPlaceSound(), sound.getSound().value());
        assertEquals(SoundSource.BLOCKS, sound.getSource());
        assertEquals(4.5, sound.getX(), 0.125);
        assertEquals(65.5, sound.getY(), 0.125);
        assertEquals(-8.5, sound.getZ(), 0.125);
        assertEquals((planks.getSoundType().getVolume() + 1.0F) / 2.0F, sound.getVolume());
        assertEquals(planks.getSoundType().getPitch() * 0.8F, sound.getPitch());
    }

    @Test
    void placingIntoReplaceablePlantsCountsAsPlacing() {
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        ClientboundSoundPacket sound = sound(only(USE,
                Map.of(TARGET, grass, NEIGHBOR, Blocks.AIR.defaultBlockState()),
                Map.of(TARGET, stone, NEIGHBOR, Blocks.AIR.defaultBlockState())));

        assertEquals(stone.getSoundType().getPlaceSound(), sound.getSound().value());
    }

    @Test
    void openingAndClosingADoorUsesItsTypesSounds() {
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState();
        BlockState open = closed.setValue(BlockStateProperties.OPEN, true);

        assertHinge(closed, open, BlockSetType.OAK.doorOpen());
        assertHinge(open, closed, BlockSetType.OAK.doorClose());
    }

    @Test
    void openingATrapdoorUsesItsTypesSound() {
        BlockState closed = Blocks.SPRUCE_TRAPDOOR.defaultBlockState();

        assertHinge(closed, closed.setValue(BlockStateProperties.OPEN, true),
                BlockSetType.SPRUCE.trapdoorOpen());
    }

    @Test
    void closingAFenceGateUsesItsWoodSound() {
        BlockState open = Blocks.BIRCH_FENCE_GATE.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, true);

        assertHinge(open, open.setValue(BlockStateProperties.OPEN, false),
                WoodType.BIRCH.fenceGateClose());
    }

    @Test
    void pullingALeverClicksHigherWhenItTurnsOn() {
        BlockState off = Blocks.LEVER.defaultBlockState();
        BlockState on = off.setValue(BlockStateProperties.POWERED, true);

        ClientboundSoundPacket turnedOn = sound(only(USE, Map.of(TARGET, off), Map.of(TARGET, on)));
        ClientboundSoundPacket turnedOff = sound(only(USE, Map.of(TARGET, on), Map.of(TARGET, off)));

        assertEquals(SoundEvents.LEVER_CLICK, turnedOn.getSound().value());
        assertEquals(0.3F, turnedOn.getVolume());
        assertEquals(0.6F, turnedOn.getPitch());
        assertEquals(0.5F, turnedOff.getPitch());
    }

    @Test
    void pressingAButtonClicksAndItsReleaseIsNotTheActors() {
        BlockState released = Blocks.STONE_BUTTON.defaultBlockState();
        BlockState pressed = released.setValue(BlockStateProperties.POWERED, true);

        ClientboundSoundPacket press = sound(only(USE, Map.of(TARGET, released), Map.of(TARGET, pressed)));

        assertEquals(BlockSetType.STONE.buttonClickOn(), press.getSound().value());
        assertTrue(families.effects(USE, Map.of(TARGET, pressed), Map.of(TARGET, released)).isEmpty());
    }

    @Test
    void usingABlockWithoutAKnownSoundSendsNothing() {
        BlockState repeater = Blocks.REPEATER.defaultBlockState();
        BlockState delayed = repeater.setValue(BlockStateProperties.DELAY, 2);

        assertTrue(families.effects(USE, Map.of(TARGET, repeater), Map.of(TARGET, delayed)).isEmpty());
    }

    @Test
    void reportsTheRangeTheServerWouldUse() {
        BlockState stone = Blocks.STONE.defaultBlockState();

        List<ActorEffect> broken = families.effects(
                DESTROY, Map.of(TARGET, stone), Map.of(TARGET, Blocks.AIR.defaultBlockState()));
        List<ActorEffect> placed = families.effects(
                USE, Map.of(TARGET, stone, NEIGHBOR, Blocks.AIR.defaultBlockState()),
                Map.of(TARGET, stone, NEIGHBOR, stone));

        assertEquals(64.0, broken.get(0).range());
        assertEquals(stone.getSoundType().getPlaceSound().getRange(
                (stone.getSoundType().getVolume() + 1.0F) / 2.0F), placed.get(0).range());
    }

    private static void assertHinge(BlockState before, BlockState after, SoundEvent expected) {
        ClientboundSoundPacket sound = sound(only(USE, Map.of(TARGET, before), Map.of(TARGET, after)));
        assertEquals(expected, sound.getSound().value());
        assertEquals(1.0F, sound.getVolume());
        assertTrue(sound.getPitch() >= 0.9F && sound.getPitch() < 1.0F, "pitch " + sound.getPitch());
    }

    private static Object only(
            ActorAction action, Map<BlockPosition, BlockState> before, Map<BlockPosition, BlockState> after) {
        List<ActorEffect> effects = families.effects(action, before, after);
        assertEquals(1, effects.size(), "effects " + effects);
        return effects.get(0).packet();
    }

    private static ClientboundSoundPacket sound(Object packet) {
        return assertInstanceOf(ClientboundSoundPacket.class, packet);
    }
}
