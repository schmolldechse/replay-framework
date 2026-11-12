package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:boss_event}.
 *
 * <p>The UUID identifies a client-side boss-bar lifecycle rather than an
 * entity or profile. It must nevertheless be rewritten consistently for the
 * add, update and remove operations so replay bars cannot collide with the
 * observer's live bars.</p>
 */
final class Id009BossEventPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundBossEventPacket,
        Id009BossEventPacketDefinition.Value> {

    static final int PACKET_ID = 0x09;
    static final String IDENTIFIER = "minecraft:boss_event";

    Id009BossEventPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundBossEventPacket.class);
    }

    @Override
    protected Value read(ClientboundBossEventPacket packet) {
        AtomicReference<Value> value = new AtomicReference<>();
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(
                    UUID id,
                    Component name,
                    float progress,
                    BossEvent.BossBarColor color,
                    BossEvent.BossBarOverlay overlay,
                    boolean darkenScreen,
                    boolean playMusic,
                    boolean createWorldFog) {
                complete(value, new Value(id, new Add(
                        name,
                        progress,
                        color,
                        overlay,
                        darkenScreen,
                        playMusic,
                        createWorldFog)));
            }

            @Override
            public void remove(UUID id) {
                complete(value, new Value(id, new Remove()));
            }

            @Override
            public void updateProgress(UUID id, float progress) {
                complete(value, new Value(id, new UpdateProgress(progress)));
            }

            @Override
            public void updateName(UUID id, Component name) {
                complete(value, new Value(id, new UpdateName(name)));
            }

            @Override
            public void updateStyle(
                    UUID id,
                    BossEvent.BossBarColor color,
                    BossEvent.BossBarOverlay overlay) {
                complete(value, new Value(id, new UpdateStyle(color, overlay)));
            }

            @Override
            public void updateProperties(
                    UUID id,
                    boolean darkenScreen,
                    boolean playMusic,
                    boolean createWorldFog) {
                complete(value, new Value(id, new UpdateProperties(
                        darkenScreen, playMusic, createWorldFog)));
            }
        });
        Value result = value.get();
        if (result == null) {
            throw new IncompatibleAdapterException(
                    "boss_event did not dispatch a supported operation");
        }
        return result;
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapUuid(value.id()), value.operation()));
    }

    @Override
    protected ClientboundBossEventPacket rebuild(Value value) {
        Operation operation = value.operation();
        if (operation instanceof Remove) {
            return ClientboundBossEventPacket.createRemovePacket(value.id());
        }
        if (operation instanceof Add add) {
            SnapshotBossEvent event = new SnapshotBossEvent(
                    value.id(), add.name(), add.color(), add.overlay());
            event.setProgress(add.progress());
            event.setDarkenScreen(add.darkenScreen());
            event.setPlayBossMusic(add.playMusic());
            event.setCreateWorldFog(add.createWorldFog());
            return ClientboundBossEventPacket.createAddPacket(event);
        }
        if (operation instanceof UpdateProgress update) {
            SnapshotBossEvent event = defaults(value.id());
            event.setProgress(update.progress());
            return ClientboundBossEventPacket.createUpdateProgressPacket(event);
        }
        if (operation instanceof UpdateName update) {
            SnapshotBossEvent event = new SnapshotBossEvent(
                    value.id(), update.name(),
                    BossEvent.BossBarColor.PINK,
                    BossEvent.BossBarOverlay.PROGRESS);
            return ClientboundBossEventPacket.createUpdateNamePacket(event);
        }
        if (operation instanceof UpdateStyle update) {
            SnapshotBossEvent event = new SnapshotBossEvent(
                    value.id(), Component.empty(), update.color(), update.overlay());
            return ClientboundBossEventPacket.createUpdateStylePacket(event);
        }
        if (operation instanceof UpdateProperties update) {
            SnapshotBossEvent event = defaults(value.id());
            event.setDarkenScreen(update.darkenScreen());
            event.setPlayBossMusic(update.playMusic());
            event.setCreateWorldFog(update.createWorldFog());
            return ClientboundBossEventPacket.createUpdatePropertiesPacket(event);
        }
        throw new IncompatibleAdapterException(
                "boss_event contains an unsupported operation "
                        + operation.getClass().getName());
    }

    private static SnapshotBossEvent defaults(UUID id) {
        return new SnapshotBossEvent(
                id,
                Component.empty(),
                BossEvent.BossBarColor.PINK,
                BossEvent.BossBarOverlay.PROGRESS);
    }

    private static void complete(AtomicReference<Value> target, Value value) {
        if (!target.compareAndSet(null, value)) {
            throw new IncompatibleAdapterException(
                    "boss_event dispatched more than one operation");
        }
    }

    record Value(UUID id, Operation operation) {
        Value {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(operation, "operation");
        }
    }

    sealed interface Operation permits
            Add, Remove, UpdateProgress, UpdateName, UpdateStyle, UpdateProperties {}

    record Add(
            Component name,
            float progress,
            BossEvent.BossBarColor color,
            BossEvent.BossBarOverlay overlay,
            boolean darkenScreen,
            boolean playMusic,
            boolean createWorldFog) implements Operation {
        Add {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(overlay, "overlay");
        }
    }

    record Remove() implements Operation {}

    record UpdateProgress(float progress) implements Operation {}

    record UpdateName(Component name) implements Operation {
        UpdateName {
            Objects.requireNonNull(name, "name");
        }
    }

    record UpdateStyle(
            BossEvent.BossBarColor color,
            BossEvent.BossBarOverlay overlay) implements Operation {
        UpdateStyle {
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(overlay, "overlay");
        }
    }

    record UpdateProperties(
            boolean darkenScreen,
            boolean playMusic,
            boolean createWorldFog) implements Operation {}

    /** Detached value carrier used only by the native packet factories. */
    private static final class SnapshotBossEvent extends BossEvent {
        private SnapshotBossEvent(
                UUID id,
                Component name,
                BossBarColor color,
                BossBarOverlay overlay) {
            super(id, name, color, overlay);
        }
    }
}
