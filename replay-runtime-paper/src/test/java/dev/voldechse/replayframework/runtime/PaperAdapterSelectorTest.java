package dev.voldechse.replayframework.runtime;

import dev.voldechse.replayframework.adapter.PaperAdapterHandle;
import dev.voldechse.replayframework.adapter.PaperAdapterProvider;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperAdapterSelectorTest {
    @Test
    void selectsOnlyExactVersionWithoutOpeningAnyProvider() {
        var older = provider("26.2");
        var newer = provider("26.3");
        assertSame(older, PaperAdapterSelector.select("26.2", List.of(newer, older)));
        assertSame(newer, PaperAdapterSelector.select("26.3", List.of(older, newer)));
        assertThrows(IllegalStateException.class,
                () -> PaperAdapterSelector.select("26.4", List.of(older, newer)));
        assertThrows(IllegalStateException.class,
                () -> PaperAdapterSelector.select("26.3.1", List.of(older, newer)));
    }

    @Test
    void rejectsMissingOrAmbiguousProviders() {
        assertThrows(IllegalStateException.class,
                () -> PaperAdapterSelector.select("26.3", List.of()));
        assertThrows(IllegalStateException.class,
                () -> PaperAdapterSelector.select("26.3", List.of(provider("26.3"), provider("26.3"))));
    }

    private static PaperAdapterProvider provider(String version) {
        return new PaperAdapterProvider() {
            public String minecraftVersion() { return version; }
            public PaperAdapterHandle open(JavaPlugin plugin, Consumer<Throwable> failures) {
                throw new AssertionError("selection must not initialize NMS or allocate resources");
            }
        };
    }
}
