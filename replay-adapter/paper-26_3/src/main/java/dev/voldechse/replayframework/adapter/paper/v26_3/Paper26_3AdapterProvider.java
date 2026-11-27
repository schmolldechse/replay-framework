package dev.voldechse.replayframework.adapter.paper.v26_3;

import dev.voldechse.replayframework.adapter.paper.protocol.PaperPacketRegistry;
import dev.voldechse.replayframework.adapter.PaperAdapterHandle;
import dev.voldechse.replayframework.adapter.PaperAdapterProvider;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.paper.capture.PaperCaptureBridge;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.Paper26_3CaptureHooks;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.adapter.paper.checkpoint.CheckpointAssembler;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3NativePacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3SnapshotProvider;
import dev.voldechse.replayframework.adapter.paper.playback.PaperPlaybackBridge;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3PlaybackHooks;
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
        PaperPacketRegistry registry = Paper26_3PacketRegistry.discover();
        Paper26_3SnapshotProvider snapshotProvider = null;
        Paper26_3NativePacketCodec nativePacketCodec = null;
        ExecutorService checkpointExecutor = null;
        try {
            nativePacketCodec = Paper26_3NativePacketCodec.create(registry);
            PaperCaptureBridge captureBridge = new PaperCaptureBridge(
                    plugin, new Paper26_3CaptureHooks(accessor, registry, nativePacketCodec));
            snapshotProvider = new Paper26_3SnapshotProvider(
                    plugin,
                    captureBridge::checkpointCapturePoint,
                    captureBridge::seedCheckpointEntityPosition);
            checkpointExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(128),
                    Thread.ofPlatform().name("replay-checkpoint-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            CheckpointAssembler checkpointEncoder = new CheckpointAssembler(
                    registry, Paper26_3ClientboundPacketCatalog.catalog(), snapshotProvider, nativePacketCodec,
                    checkpointExecutor);
            AtomicReference<Paper26_3ReplayAdapter> adapterReference = new AtomicReference<>();
            Paper26_3ReplayAdapter adapter = new Paper26_3ReplayAdapter(
                    captureBridge,
                    checkpointEncoder,
                    player -> {
                        Paper26_3ReplayAdapter current = adapterReference.get();
                        if (current == null) {
                            throw new IllegalStateException("Paper adapter is not initialized");
                        }
                        return new PaperPlaybackBridge(
                                new Paper26_3PlaybackHooks(accessor, current.descriptor(), current.packetRegistry()),
                                player,
                                playbackFailureHandler);
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
            if (snapshotProvider != null) {
                snapshotProvider.close();
            }
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
