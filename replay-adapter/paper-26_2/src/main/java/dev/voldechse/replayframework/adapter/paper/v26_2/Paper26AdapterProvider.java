package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.PaperAdapterHandle;
import dev.voldechse.replayframework.adapter.PaperAdapterProvider;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.Paper26CaptureBridge;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26NativePacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26SnapshotProvider;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26PlaybackBridge;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper 26.2 adapter bootstrap; NMS classes are touched only by {@link #open}. */
public final class Paper26AdapterProvider implements PaperAdapterProvider {

    @Override
    public String minecraftVersion() {
        return "26.2";
    }

    @Override
    public PaperAdapterHandle open(JavaPlugin plugin, Consumer<Throwable> playbackFailureHandler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(playbackFailureHandler, "playbackFailureHandler");
        PaperConnectionAccessor accessor = new PaperConnectionAccessor();
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();
        Paper26CaptureBridge captureBridge = new Paper26CaptureBridge(accessor, registry);
        Paper26SnapshotProvider snapshotProvider = new Paper26SnapshotProvider(plugin);
        Paper26NativePacketCodec nativePacketCodec = null;
        ExecutorService checkpointExecutor = null;
        try {
            nativePacketCodec = Paper26NativePacketCodec.create(registry);
            checkpointExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(128),
                    Thread.ofPlatform().name("replay-checkpoint-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            Paper26CheckpointEncoder checkpointEncoder = new Paper26CheckpointEncoder(
                    registry, snapshotProvider, nativePacketCodec, checkpointExecutor);
            AtomicReference<Paper26ReplayAdapter> adapterReference = new AtomicReference<>();
            Paper26ReplayAdapter adapter = new Paper26ReplayAdapter(
                    captureBridge,
                    checkpointEncoder,
                    player -> {
                        Paper26ReplayAdapter current = adapterReference.get();
                        if (current == null) {
                            throw new IllegalStateException("Paper adapter is not initialized");
                        }
                        return new Paper26PlaybackBridge(accessor, player,
                                current.descriptor(), current.packetRegistry(), playbackFailureHandler);
                    });
            adapterReference.set(adapter);
            return new Handle(adapter, snapshotProvider, nativePacketCodec, checkpointExecutor);
        } catch (Throwable failure) {
            try {
                closeResources(snapshotProvider, nativePacketCodec, checkpointExecutor);
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void closeResources(
            Paper26SnapshotProvider snapshotProvider,
            Paper26NativePacketCodec nativePacketCodec,
            ExecutorService checkpointExecutor) {
        try {
            snapshotProvider.close();
        } finally {
            try {
                if (nativePacketCodec != null) {
                    nativePacketCodec.close();
                }
            } finally {
                if (checkpointExecutor != null) {
                    checkpointExecutor.shutdownNow();
                }
            }
        }
    }

    private static final class Handle implements PaperAdapterHandle {
        private final ReplayAdapter adapter;
        private final Paper26SnapshotProvider snapshotProvider;
        private final Paper26NativePacketCodec nativePacketCodec;
        private final ExecutorService checkpointExecutor;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(ReplayAdapter adapter, Paper26SnapshotProvider snapshotProvider,
                Paper26NativePacketCodec nativePacketCodec, ExecutorService checkpointExecutor) {
            this.adapter = adapter;
            this.snapshotProvider = snapshotProvider;
            this.nativePacketCodec = nativePacketCodec;
            this.checkpointExecutor = checkpointExecutor;
        }

        @Override
        public ReplayAdapter adapter() {
            return adapter;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeResources(snapshotProvider, nativePacketCodec, checkpointExecutor);
            }
        }
    }
}
