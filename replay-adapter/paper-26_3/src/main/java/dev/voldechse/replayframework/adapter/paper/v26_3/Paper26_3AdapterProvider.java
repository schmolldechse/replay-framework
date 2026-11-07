package dev.voldechse.replayframework.adapter.paper.v26_3;

import dev.voldechse.replayframework.adapter.PaperAdapterHandle;
import dev.voldechse.replayframework.adapter.PaperAdapterProvider;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.Paper26_3CaptureBridge;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3NativePacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3SnapshotProvider;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3PlaybackBridge;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper 26.3 adapter bootstrap; NMS classes are touched only by {@link #open}. */
public final class Paper26_3AdapterProvider implements PaperAdapterProvider {

    @Override
    public String minecraftVersion() {
        return "26.3";
    }

    @Override
    public PaperAdapterHandle open(JavaPlugin plugin, Consumer<Throwable> playbackFailureHandler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(playbackFailureHandler, "playbackFailureHandler");
        PaperConnectionAccessor accessor = new PaperConnectionAccessor();
        Paper26_3PacketRegistry registry = Paper26_3PacketRegistry.discover();
        Paper26_3SnapshotProvider snapshotProvider = new Paper26_3SnapshotProvider(plugin);
        Paper26_3NativePacketCodec nativePacketCodec = null;
        ExecutorService checkpointExecutor = null;
        try {
            nativePacketCodec = Paper26_3NativePacketCodec.create(registry);
            Paper26_3CaptureBridge captureBridge = new Paper26_3CaptureBridge(
                    plugin, accessor, registry, nativePacketCodec);
            checkpointExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(128),
                    Thread.ofPlatform().name("replay-checkpoint-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            Paper26_3CheckpointEncoder checkpointEncoder = new Paper26_3CheckpointEncoder(
                    registry, snapshotProvider, nativePacketCodec, checkpointExecutor);
            AtomicReference<Paper26_3ReplayAdapter> adapterReference = new AtomicReference<>();
            Paper26_3ReplayAdapter adapter = new Paper26_3ReplayAdapter(
                    captureBridge,
                    checkpointEncoder,
                    player -> {
                        Paper26_3ReplayAdapter current = adapterReference.get();
                        if (current == null) {
                            throw new IllegalStateException("Paper adapter is not initialized");
                        }
                        return new Paper26_3PlaybackBridge(accessor, player,
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
            Paper26_3SnapshotProvider snapshotProvider,
            Paper26_3NativePacketCodec nativePacketCodec,
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
        private final Paper26_3SnapshotProvider snapshotProvider;
        private final Paper26_3NativePacketCodec nativePacketCodec;
        private final ExecutorService checkpointExecutor;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Handle(ReplayAdapter adapter, Paper26_3SnapshotProvider snapshotProvider,
                Paper26_3NativePacketCodec nativePacketCodec, ExecutorService checkpointExecutor) {
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
