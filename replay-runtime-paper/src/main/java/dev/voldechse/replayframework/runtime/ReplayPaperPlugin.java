package dev.voldechse.replayframework.runtime;

import org.bukkit.plugin.java.JavaPlugin;

public final class ReplayPaperPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("Replay Framework bootstrap verified on Java "
                + Runtime.version().feature());
    }
}
