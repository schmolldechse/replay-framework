package dev.voldechse.replayframework.runtime;

import dev.voldechse.replayframework.adapter.PaperAdapterHandle;
import dev.voldechse.replayframework.adapter.PaperAdapterProvider;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Selects exactly one adapter without loading another version's NMS implementation. */
final class PaperAdapterSelector {

    private PaperAdapterSelector() {}

    static PaperAdapterHandle open(JavaPlugin plugin, Consumer<Throwable> playbackFailureHandler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(playbackFailureHandler, "playbackFailureHandler");
        PaperAdapterProvider selected = select(Bukkit.getMinecraftVersion(), ServiceLoader.load(
                PaperAdapterProvider.class, plugin.getClass().getClassLoader()));
        PaperAdapterHandle handle = Objects.requireNonNull(
                selected.open(plugin, playbackFailureHandler), "adapter provider returned null");
        try {
            Objects.requireNonNull(handle.adapter(), "adapter handle returned null adapter")
                    .verifyDescriptorAndRegistry();
            return handle;
        } catch (Throwable failure) {
            try {
                handle.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** Pure selection; opening a provider remains confined to the server thread. */
    static PaperAdapterProvider select(String minecraftVersion, Iterable<PaperAdapterProvider> providers) {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(providers, "providers");
        PaperAdapterProvider selected = null;
        for (PaperAdapterProvider provider : providers) {
            if (!minecraftVersion.equals(provider.minecraftVersion())) {
                continue;
            }
            if (selected != null) {
                throw new IllegalStateException(
                        "multiple Paper replay adapters support Minecraft " + minecraftVersion);
            }
            selected = provider;
        }
        if (selected == null) {
            throw new IllegalStateException(
                    "no Paper replay adapter supports Minecraft " + minecraftVersion);
        }
        return selected;
    }
}
