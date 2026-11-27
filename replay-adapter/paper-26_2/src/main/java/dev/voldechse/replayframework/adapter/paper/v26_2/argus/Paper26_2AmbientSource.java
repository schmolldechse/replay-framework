package dev.voldechse.replayframework.adapter.paper.v26_2.argus;

import dev.voldechse.replayframework.adapter.paper.argus.AmbientSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;

/**
 * Time and weather of one Paper 26.2 level, read from the level itself.
 *
 * <p>The server announces both only to listed players. The packets built
 * here are the ones {@code MinecraftServer.forceGameTimeSynchronization} and
 * {@code ServerPlayer.updateWeather} send, at the same cadence.</p>
 */
final class Paper26_2AmbientSource implements AmbientSource {

    /** The server synchronizes time once per second. */
    private static final int TIME_SYNC_INTERVAL_TICKS = 20;

    private final ServerLevel level;
    private boolean raining;
    private float rainLevel;
    private float thunderLevel;
    private int ticksUntilTimeSync;

    Paper26_2AmbientSource(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        // The initial state belongs to the checkpoint; only changes are sampled.
        this.raining = level.isRaining();
        this.rainLevel = level.rainLevel;
        this.thunderLevel = level.thunderLevel;
        this.ticksUntilTimeSync = TIME_SYNC_INTERVAL_TICKS;
    }

    @Override
    public List<Object> sample() {
        List<Object> changes = new ArrayList<>(3);
        if (--ticksUntilTimeSync <= 0) {
            ticksUntilTimeSync = TIME_SYNC_INTERVAL_TICKS;
            changes.add(new ClientboundSetTimePacket(level.getGameTime(), Map.of()));
        }
        if (raining != level.isRaining()) {
            raining = level.isRaining();
            changes.add(new ClientboundGameEventPacket(
                    raining
                            ? ClientboundGameEventPacket.START_RAINING
                            : ClientboundGameEventPacket.STOP_RAINING,
                    0.0F));
        }
        if (rainLevel != level.rainLevel) {
            rainLevel = level.rainLevel;
            changes.add(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel));
        }
        if (thunderLevel != level.thunderLevel) {
            thunderLevel = level.thunderLevel;
            changes.add(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel));
        }
        return changes;
    }
}
