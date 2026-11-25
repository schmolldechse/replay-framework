package dev.voldechse.replayframework.adapter.paper.playback;

import dev.voldechse.replayframework.adapter.PacketRegistry;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;

/**
 * The version-specific half of playback, implemented once per Minecraft
 * version. Lifecycle, the outbound queue, the world filter, the gate and the
 * replay writer are shared in {@link PaperPlaybackBridge}.
 */
public interface PlaybackHooks {

    /** The live packet registry the adapter verified. */
    PacketRegistry registry();

    /** The open connection of a viewer. */
    Channel channel(Player viewer);

    /** Creates the version parts of one viewer's playback. */
    ViewerParts open(Player viewer, Channel channel, ViewerParts.Callbacks callbacks);
}
