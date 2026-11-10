package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;

/** Small version-local base used by one named definition per PLAY packet. */
abstract class CatalogPacketDefinition extends PacketDefinition<Paper26_3ReplayPacketRewriter> {

    private final Behavior behavior;

    CatalogPacketDefinition(String identifier, PacketClassification classification) {
        this(identifier, classification, Behavior.DEFAULT);
    }

    CatalogPacketDefinition(
            String identifier,
            PacketClassification classification,
            Behavior behavior) {
        super(identifier, classification);
        this.behavior = behavior;
    }

    @Override
    protected PacketRewriteResult rewriteSafe(
            Object packet,
            Paper26_3ReplayPacketRewriter context) {
        return switch (behavior) {
            case DEFAULT -> PacketRewriteResult.send(packet);
            case RECORDED_CHAT -> PacketRewriteResult.drop(
                    "recorded chat belongs to its original connection");
            case EXPLOSION -> context.rewriteExplosion(require(packet, ClientboundExplodePacket.class));
            case GAME_EVENT -> context.rewriteGameEvent(require(packet, ClientboundGameEventPacket.class));
            case IDENTITY, BOSS_EVENT -> throw new IllegalStateException(
                    "identity behavior reached the safe rewrite hook");
        };
    }

    @Override
    protected PacketRewriteResult rewriteIdentities(
            Object packet,
            Paper26_3ReplayPacketRewriter context) {
        return switch (behavior) {
            case IDENTITY -> PacketRewriteResult.send(
                    context.rewriteIdentityBearing(packet, typeName()
                            .substring("play.clientbound.".length())));
            case BOSS_EVENT -> context.rewriteBossEvent(require(packet, ClientboundBossEventPacket.class));
            case RECORDED_CHAT -> PacketRewriteResult.drop(
                    "recorded chat belongs to its original connection");
            case DEFAULT, EXPLOSION, GAME_EVENT ->
                    throw new IllegalStateException(
                            "safe behavior reached the identity rewrite hook");
        };
    }

    private <P> P require(Object packet, Class<P> type) {
        if (!type.isInstance(packet)) {
            throw new IncompatibleAdapterException(
                    "decoded type for " + typeName() + " was " + packet.getClass().getName()
                            + ", expected " + type.getName());
        }
        return type.cast(packet);
    }

    enum Behavior {
        DEFAULT,
        RECORDED_CHAT,
        EXPLOSION,
        GAME_EVENT,
        IDENTITY,
        BOSS_EVENT
    }
}
