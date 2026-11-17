package dev.voldechse.replayframework.adapter.paper.v26_2.argus;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.argus.AmbientSource;
import dev.voldechse.replayframework.adapter.paper.argus.ArgusEye;
import dev.voldechse.replayframework.adapter.paper.argus.ArgusPlatform;
import dev.voldechse.replayframework.adapter.paper.argus.EyeSpec;
import dev.voldechse.replayframework.adapter.paper.argus.PlayerAnchor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.spigotmc.SpigotWorldConfig;

/** Paper 26.2 implementation of everything Argus needs from the server. */
public final class Paper26_2ArgusPlatform implements ArgusPlatform {

    private static final String TYPE_PREFIX = "play.clientbound.";

    @Override
    public ArgusEye openEye(EyeSpec spec, Consumer<Object> packets) {
        return Paper26_2ArgusEye.open(
                level(spec.world()), spec.x(), spec.y(), spec.z(), spec.viewDistance(), packets);
    }

    @Override
    public List<Object> unbundle(Object packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Object> parts = new ArrayList<>();
            bundle.subPackets().forEach(parts::add);
            return parts;
        }
        return List.of(packet);
    }

    @Override
    public Optional<ClientboundPacketDefinition<?>> definition(Object packet) {
        if (!(packet instanceof Packet<?> nativePacket) || nativePacket.type() == null) {
            return Optional.empty();
        }
        return Paper26_2ClientboundPacketCatalog.catalog()
                .find(TYPE_PREFIX + nativePacket.type().id())
                .map(definition -> (ClientboundPacketDefinition<?>) definition);
    }

    @Override
    public Object forgetChunk(int chunkX, int chunkZ) {
        return new ClientboundForgetLevelChunkPacket(new ChunkPos(chunkX, chunkZ));
    }

    @Override
    public Object removeEntities(int[] entityIds) {
        return new ClientboundRemoveEntitiesPacket(entityIds);
    }

    @Override
    public int trackingRangeBlocks(String world) {
        SpigotWorldConfig config = level(world).spigotConfig;
        int smallest = Math.min(
                Math.min(config.playerTrackingRange, config.animalTrackingRange),
                Math.min(config.monsterTrackingRange, config.miscTrackingRange));
        return Math.min(smallest, Math.min(config.displayTrackingRange, config.otherTrackingRange));
    }

    @Override
    public AmbientSource ambient(String world) {
        return new Paper26_2AmbientSource(level(world));
    }

    @Override
    public List<PlayerAnchor> players() {
        List<PlayerAnchor> players = new ArrayList<>();
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            players.add(new PlayerAnchor(
                    player.getUUID(),
                    player.level().getWorld().getKey().asString(),
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.isAlive(),
                    // Paper's per-player send distance: what this player receives.
                    player.getBukkitEntity().getSendViewDistance()));
        }
        return players;
    }

    @Override
    public Object removePlayerInfo(List<UUID> players) {
        return new ClientboundPlayerInfoRemovePacket(List.copyOf(players));
    }

    private static ServerLevel level(String worldKey) {
        NamespacedKey key = NamespacedKey.fromString(worldKey);
        World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null) {
            throw new IncompatibleAdapterException("Argus world is not loaded: " + worldKey);
        }
        return ((CraftWorld) world).getHandle();
    }
}
