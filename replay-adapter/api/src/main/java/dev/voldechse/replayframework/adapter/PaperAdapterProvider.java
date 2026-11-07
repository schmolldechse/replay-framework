package dev.voldechse.replayframework.adapter;

import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;

/** NMS-free discovery and creation boundary for one Paper Minecraft version. */
public interface PaperAdapterProvider {

    /** Exact Minecraft version supported by this provider, such as {@code 26.2}. */
    String minecraftVersion();

    /** Opens the adapter on the server thread and transfers all owned resources to the handle. */
    PaperAdapterHandle open(JavaPlugin plugin, Consumer<Throwable> playbackFailureHandler);
}
