package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Contract tests shared by all adapter implementations, with no NMS types. */
class AbstractReplayAdapterTest {
    private final PacketRegistry registry = PacketRegistry.of(List.of());
    private final CaptureBridge capture = new CaptureBridge() {
        public void install(PacketSink sink, FailureHandler failure) {}
        public void uninstall() {}
        public boolean installed() { return false; }
    };
    private final CheckpointEncoder checkpoints = request -> CompletableFuture.failedFuture(
            new AssertionError("composition must not encode a checkpoint"));

    @Test
    void preservesPortsAndBindsEachViewerToItsOwnIdentity() {
        AtomicReference<Player> openedFor = new AtomicReference<>();
        var adapter = adapter(registry.fingerprint(), player -> {
            openedFor.set(player);
            return new Bridge();
        });
        assertSame(capture, adapter.captureBridge());
        assertSame(checkpoints, adapter.checkpointEncoder());
        assertSame(registry, adapter.packetRegistry());
        var first = new PlaybackIdentityContext(UUID.randomUUID(), UUID.randomUUID());
        var second = new PlaybackIdentityContext(UUID.randomUUID(), UUID.randomUUID());
        Player viewer = viewer();
        Bridge a = (Bridge) adapter.openPlayback(viewer, first);
        Bridge b = (Bridge) adapter.openPlayback(viewer, second);
        assertSame(viewer, openedFor.get());
        assertNotSame(a, b);
        assertEquals(first, a.identity);
        assertEquals(second, b.identity);
    }

    @Test
    void rejectsMismatchedRegistryAndInvalidPlaybackFactoryResults() {
        assertThrows(IncompatibleAdapterException.class, () -> adapter("0".repeat(64), player -> new Bridge()));
        var adapter = adapter(registry.fingerprint(), player -> null);
        assertThrows(NullPointerException.class, () -> adapter.openPlayback(viewer()));
        assertThrows(NullPointerException.class, () -> adapter.openPlayback(null));
    }

    private ReplayAdapter adapter(String fingerprint, Function<Player, PlaybackBridge> factory) {
        return new AbstractReplayAdapter(new AdapterDescriptor("fixture", 1, 3, fingerprint),
                registry, capture, checkpoints, CheckpointSignalSource.noop(), factory) {};
    }

    private static Player viewer() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> { throw new AssertionError("adapter composition mutated or read player state: " + method); });
    }

    private static final class Bridge implements PlaybackBridge {
        private PlaybackIdentityContext identity;
        public void setIdentityContext(PlaybackIdentityContext context) { identity = context; }
        public void send(RawPacketFrame frame) {}
        public void resetView() {}
        public void close() {}
    }
}
