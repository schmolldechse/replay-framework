package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.scores.DisplaySlot;

/**
 * Tracks only replay-visible state that can be removed with clientbound
 * Paper-26.2 packets. The writer is owned by the bridge and validates each
 * generated packet against the active transport registry before sending it.
 */
final class Paper26ViewResetter {

    private final PacketRegistry registry;
    private final Consumer<Object> writer;
    private final Consumer<Throwable> failureHandler;
    private final ReplayViewState state = new ReplayViewState();

    Paper26ViewResetter(
            PacketRegistry registry,
            Consumer<Object> writer,
            Consumer<Throwable> failureHandler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    /** Observes one successfully decoded and planned replay packet. */
    void observe(Object packet) {
        Objects.requireNonNull(packet, "packet");

        if (packet instanceof ClientboundAddEntityPacket addEntity) {
            state.entityIds.add(addEntity.getId());
        } else if (packet instanceof ClientboundRemoveEntitiesPacket removeEntities) {
            removeEntities.getEntityIds().forEach((int id) -> state.entityIds.remove(id));
        } else if (packet instanceof ClientboundSetEntityDataPacket entityData) {
            state.entityIds.add(entityData.id());
        } else {
            observeEntityIdByConvention(packet);
        }

        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            state.chunkPositions.add(new ChunkKey(chunk.getX(), chunk.getZ()));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
            state.chunkPositions.remove(new ChunkKey(forget.pos().x(), forget.pos().z()));
        }

        if (packet instanceof ClientboundOpenScreenPacket openScreen) {
            state.containerId = openScreen.getContainerId();
        } else if (packet instanceof ClientboundContainerClosePacket close) {
            state.containerId = state.containerId != null && state.containerId == close.getContainerId()
                    ? null
                    : state.containerId;
        }

        if (packet instanceof ClientboundBossEventPacket bossEvent) {
            bossEvent.dispatch(new ClientboundBossEventPacket.Handler() {
                @Override
                public void add(UUID id, net.minecraft.network.chat.Component name, float progress,
                        net.minecraft.world.BossEvent.BossBarColor color,
                        net.minecraft.world.BossEvent.BossBarOverlay overlay,
                        boolean darkenSky, boolean playMusic, boolean createWorldFog) {
                    state.bossBarIds.add(id);
                }

                @Override
                public void remove(UUID id) {
                    state.bossBarIds.remove(id);
                }
            });
        }

        if (packet instanceof ClientboundSetPlayerTeamPacket team) {
            if (team.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                state.teamNames.add(team.getName());
            } else if (team.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                state.teamNames.remove(team.getName());
            }
        }

        if (packet instanceof ClientboundSetObjectivePacket objective) {
            if (objective.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
                state.objectiveNames.remove(objective.getObjectiveName());
            } else {
                state.objectiveNames.add(objective.getObjectiveName());
            }
        } else if (packet instanceof ClientboundSetDisplayObjectivePacket display) {
            if (display.getObjectiveName() == null) {
                state.displaySlots.remove(display.getSlot());
            } else {
                state.displaySlots.add(display.getSlot());
            }
        } else if (packet instanceof ClientboundSetScorePacket score) {
            state.scores.put(new ScoreKey(score.owner(), score.objectiveName()), Boolean.TRUE);
        } else if (packet instanceof ClientboundResetScorePacket score) {
            state.scores.remove(new ScoreKey(score.owner(), score.objectiveName()));
        }

        if (packet instanceof ClientboundPlayerInfoUpdatePacket update) {
            state.tabListProfileIds.addAll(update.entries().stream()
                    .map(ClientboundPlayerInfoUpdatePacket.Entry::profileId)
                    .toList());
        } else if (packet instanceof ClientboundPlayerInfoRemovePacket remove) {
            state.tabListProfileIds.removeAll(remove.profileIds());
        }
    }

    /**
     * Sends a deterministic clear sequence. State is cleared only after all
     * writes have been accepted by the bridge writer.
     */
    void reset() {
        List<Object> packets = new ArrayList<>();
        packets.add(new ClientboundClearTitlesPacket(true));

        if (!state.entityIds.isEmpty()) {
            packets.add(new ClientboundRemoveEntitiesPacket(state.entityIds.stream()
                    .mapToInt(Integer::intValue)
                    .toArray()));
        }
        for (ChunkKey chunk : state.chunkPositions) {
            packets.add(new ClientboundForgetLevelChunkPacket(new ChunkPos(chunk.x(), chunk.z())));
        }
        if (state.containerId != null) {
            packets.add(new ClientboundContainerClosePacket(state.containerId));
        }
        for (UUID id : state.bossBarIds) {
            packets.add(ClientboundBossEventPacket.createRemovePacket(id));
        }
        for (String teamName : state.teamNames) {
            packets.add(teamRemoval(teamName));
        }
        for (DisplaySlot slot : state.displaySlots) {
            packets.add(new ClientboundSetDisplayObjectivePacket(slot, null));
        }
        for (String objectiveName : state.objectiveNames) {
            packets.add(objectiveRemoval(objectiveName));
        }
        for (ScoreKey score : state.scores.keySet()) {
            packets.add(new ClientboundResetScorePacket(score.owner(), score.objectiveName()));
        }
        if (!state.tabListProfileIds.isEmpty()) {
            packets.add(new ClientboundPlayerInfoRemovePacket(new ArrayList<>(state.tabListProfileIds)));
        }

        try {
            for (Object packet : packets) {
                writer.accept(packet);
            }
            state.clear();
        } catch (Throwable failure) {
            reportFailure(failure);
            throw failure;
        }
    }

    private void observeEntityIdByConvention(Object packet) {
        String simpleName = packet.getClass().getSimpleName();
        if (!simpleName.contains("Entity")) {
            return;
        }
        try {
            Integer id = invokeInt(packet, "getId");
            if (id == null) {
                id = invokeInt(packet, "id");
            }
            if (id != null) {
                state.entityIds.add(id);
            }
        } catch (ReflectiveOperationException ignored) {
            // Packet families without a stable entity accessor do not create
            // a tracked removal entry; they remain replay-valid packets.
        }
    }

    private static Integer invokeInt(Object target, String methodName)
            throws ReflectiveOperationException {
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value instanceof Integer integer ? integer : null;
    }

    private static ClientboundSetPlayerTeamPacket teamRemoval(String name) {
        return decodePacket(
                buffer -> {
                    buffer.writeUtf(name);
                    buffer.writeByte(1);
                },
                ClientboundSetPlayerTeamPacket.STREAM_CODEC);
    }

    private static ClientboundSetObjectivePacket objectiveRemoval(String name) {
        return decodePacket(
                buffer -> {
                    buffer.writeUtf(name);
                    buffer.writeByte(ClientboundSetObjectivePacket.METHOD_REMOVE);
                },
                ClientboundSetObjectivePacket.STREAM_CODEC);
    }

    private static <T> T decodePacket(
            Consumer<RegistryFriendlyByteBuf> encoder,
            net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        ByteBuf source = Unpooled.buffer();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(source, RegistryAccess.EMPTY);
        try {
            encoder.accept(buffer);
            return codec.decode(buffer);
        } finally {
            source.release();
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            failureHandler.accept(Objects.requireNonNull(failure, "failure"));
        } catch (Throwable ignored) {
            // Reset failure reporting must not escape the event loop.
        }
    }

    private final class ReplayViewState {
        private final Set<Integer> entityIds = new LinkedHashSet<>();
        private final Set<ChunkKey> chunkPositions = new LinkedHashSet<>();
        private final Set<UUID> bossBarIds = new LinkedHashSet<>();
        private final Set<String> teamNames = new LinkedHashSet<>();
        private final Set<String> objectiveNames = new LinkedHashSet<>();
        private final Set<DisplaySlot> displaySlots = new LinkedHashSet<>();
        private final Set<UUID> tabListProfileIds = new LinkedHashSet<>();
        private final LinkedHashMap<ScoreKey, Boolean> scores = new LinkedHashMap<>();
        private Integer containerId;

        private void clear() {
            entityIds.clear();
            chunkPositions.clear();
            bossBarIds.clear();
            teamNames.clear();
            objectiveNames.clear();
            displaySlots.clear();
            tabListProfileIds.clear();
            scores.clear();
            containerId = null;
        }
    }

    private record ChunkKey(int x, int z) {}

    private record ScoreKey(String owner, String objectiveName) {
        private ScoreKey {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(objectiveName, "objectiveName");
        }
    }
}
