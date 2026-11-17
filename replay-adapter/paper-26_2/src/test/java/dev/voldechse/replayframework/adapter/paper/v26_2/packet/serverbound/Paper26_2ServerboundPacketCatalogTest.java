package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Every serverbound PLAY packet of 26.2 has exactly one explicit definition. */
class Paper26_2ServerboundPacketCatalogTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void definesEveryPacketOfTheLiveRegistryUnderItsIdAndName() {
        var details = GameProtocols.SERVERBOUND_TEMPLATE.details();
        assertEquals(PacketFlow.SERVERBOUND, details.flow());
        Map<Integer, String> registry = new TreeMap<>();
        details.listPackets((type, id) -> registry.put(id, "play.serverbound." + type.id()));

        Map<Integer, String> catalog = new TreeMap<>();
        for (ServerboundPacketDefinition definition
                : Paper26_2ServerboundPacketCatalog.catalog().definitions()) {
            catalog.put(definition.packetId(), definition.typeName());
        }

        assertEquals(69, registry.size());
        assertEquals(registry, catalog);
    }

    @Test
    void derivesABlockBreakWhenDiggingStartsOrFinishes() {
        for (var action : new ServerboundPlayerActionPacket.Action[] {
            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
        }) {
            ServerboundReaction reaction = react(new ServerboundPlayerActionPacket(
                    action, new BlockPos(3, 64, -7), Direction.UP));

            assertEquals(
                    Optional.of(ActorAction.destroy(new BlockPosition(3, 64, -7))),
                    reaction.actorAction());
            assertTrue(reaction.samplePlayerState());
        }
    }

    @Test
    void samplesOnlyTheSenderForOtherPlayerActions() {
        for (var action : new ServerboundPlayerActionPacket.Action[] {
            ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
            ServerboundPlayerActionPacket.Action.DROP_ITEM,
            ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
            ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            ServerboundPlayerActionPacket.Action.STAB
        }) {
            assertEquals(ServerboundReaction.PLAYER_STATE, react(new ServerboundPlayerActionPacket(
                    action, BlockPos.ZERO, Direction.DOWN)), action.name());
        }
    }

    @Test
    void probesTheUsedBlockAndTheNeighborAPlacedBlockWouldOccupy() {
        BlockHitResult hit = new BlockHitResult(
                new Vec3(10.5, 65.0, 2.5), Direction.UP, new BlockPos(10, 64, 2), false);

        ServerboundReaction reaction = react(
                new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 4));

        assertEquals(
                Optional.of(ActorAction.useOn(
                        new BlockPosition(10, 64, 2), new BlockPosition(10, 65, 2))),
                reaction.actorAction());
    }

    @Test
    void reportsTheSwungHand() {
        assertEquals(
                ServerboundReaction.swing(ServerboundReaction.SwingHand.OFF_HAND),
                react(new ServerboundSwingPacket(InteractionHand.OFF_HAND)));
        assertEquals(
                ServerboundReaction.swing(ServerboundReaction.SwingHand.MAIN_HAND),
                react(new ServerboundSwingPacket(InteractionHand.MAIN_HAND)));
    }

    @Test
    void samplesTheSenderAfterMovementAndEveryClientTick() {
        assertEquals(ServerboundReaction.PLAYER_STATE, react(
                new ServerboundMovePlayerPacket.Pos(1.0, 2.0, 3.0, true, false)));
        assertEquals(ServerboundReaction.PLAYER_STATE, react(ServerboundClientTickEndPacket.INSTANCE));
    }

    @Test
    void triggersNothingForConnectionPackets() {
        assertEquals(ServerboundReaction.NONE, react(new ServerboundPingRequestPacket(42L)));
    }

    private static ServerboundReaction react(net.minecraft.network.protocol.Packet<?> packet) {
        return Paper26_2ServerboundPacketCatalog.catalog()
                .require("play.serverbound." + packet.type().id())
                .react(packet);
    }
}
