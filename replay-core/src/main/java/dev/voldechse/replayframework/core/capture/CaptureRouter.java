package dev.voldechse.replayframework.core.capture;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Routes one adapter capture callback to the immutable snapshot of active sinks.
 *
 * <p>Capture callbacks are synchronous on the Netty event loop. Registration
 * uses a short coordination lock, while packet delivery only reads the atomic
 * snapshot and never waits for registration or another sink.</p>
 */
public final class CaptureRouter implements CaptureBridge.PacketSink, AutoCloseable {

    private static final PacketDescriptor.Direction CLIENTBOUND =
            PacketDescriptor.Direction.CLIENTBOUND;

    private final PacketRegistry registry;
    private final Consumer<Throwable> adapterFailureHandler;
    private final BiConsumer<RecordingSessionId, Throwable> sinkFailureHandler;
    private final AtomicReference<List<CaptureSink>> sinks =
            new AtomicReference<>(List.of());
    private final AtomicBoolean adapterFailureReported = new AtomicBoolean();
    private final Object registrationLock = new Object();

    /**
     * Creates an open router with required failure channels.
     *
     * @param registry immutable adapter packet registry
     * @param adapterFailureHandler receives the first fatal adapter failure
     * @param sinkFailureHandler receives a failure isolated to one session sink
     */
    public CaptureRouter(
            PacketRegistry registry,
            Consumer<Throwable> adapterFailureHandler,
            BiConsumer<RecordingSessionId, Throwable> sinkFailureHandler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.adapterFailureHandler = Objects.requireNonNull(
                adapterFailureHandler, "adapterFailureHandler");
        this.sinkFailureHandler = Objects.requireNonNull(
                sinkFailureHandler, "sinkFailureHandler");
    }

    /** Registers one unique session sink while the router is open. */
    public void register(CaptureSink sink) {
        Objects.requireNonNull(sink, "sink");
        RecordingSessionId sessionId = Objects.requireNonNull(sink.sessionId(), "sink.sessionId()");
        synchronized (registrationLock) {
            if (closed()) {
                throw new IllegalStateException("capture router is closed");
            }
            List<CaptureSink> current = sinks.get();
            if (current.stream().anyMatch(existing -> existing.sessionId().equals(sessionId))) {
                throw new IllegalArgumentException("capture sink already registered: " + sessionId);
            }
            List<CaptureSink> next = new ArrayList<>(current.size() + 1);
            next.addAll(current);
            next.add(sink);
            sinks.set(List.copyOf(next));
        }
    }

    /** Removes a sink by session identity; repeated removal is safe. */
    public boolean unregister(RecordingSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        synchronized (registrationLock) {
            List<CaptureSink> current = sinks.get();
            int existingIndex = -1;
            for (int index = 0; index < current.size(); index++) {
                if (current.get(index).sessionId().equals(sessionId)) {
                    existingIndex = index;
                    break;
                }
            }
            if (existingIndex < 0) {
                return false;
            }
            List<CaptureSink> next = new ArrayList<>(current);
            next.remove(existingIndex);
            sinks.set(List.copyOf(next));
            return true;
        }
    }

    /**
     * Receives one adapter event and fans it out without blocking.
     *
     * @param packet adapter-owned defensive capture event
     */
    @Override
    public void accept(CaptureBridge.CapturePacket packet) {
        if (closed()) {
            return;
        }
        Objects.requireNonNull(packet, "packet");

        Optional<PacketDescriptor> descriptor = registry.find(
                packet.phase(), CLIENTBOUND, packet.packetId());
        if (descriptor.isEmpty()) {
            reportAdapterFailure(new IllegalStateException(
                    "capture packet is not registered: " + packet.phase() + ":" + packet.packetId()));
            return;
        }
        if (!registry.captureAllowed(packet.phase(), CLIENTBOUND, packet.packetId())) {
            return;
        }

        final CapturedPacket captured;
        try {
            captured = CapturedPacket.from(packet);
        } catch (Throwable failure) {
            reportAdapterFailure(failure);
            return;
        }

        for (CaptureSink sink : sinks.get()) {
            try {
                if (sink.accepts(captured)) {
                    sink.enqueue(captured);
                }
            } catch (Throwable failure) {
                removeSinkInstance(sink);
                reportSinkFailure(sink, failure);
            }
        }
    }

    @Override
    public boolean hasActiveSinks() {
        return !closed() && !sinks.get().isEmpty();
    }

    /**
     * Closes the router and atomically removes all sinks. This is an expected
     * lifecycle state, not an adapter failure.
     */
    @Override
    public void close() {
        synchronized (registrationLock) {
            sinks.set(List.of());
            closed = true;
        }
    }

    private volatile boolean closed;

    private boolean closed() {
        return closed;
    }

    private void removeSinkInstance(CaptureSink failedSink) {
        synchronized (registrationLock) {
            List<CaptureSink> current = sinks.get();
            List<CaptureSink> next = new ArrayList<>(current.size());
            boolean removed = false;
            for (CaptureSink candidate : current) {
                if (!removed && candidate == failedSink) {
                    removed = true;
                } else {
                    next.add(candidate);
                }
            }
            if (removed) {
                sinks.set(List.copyOf(next));
            }
        }
    }

    private void reportAdapterFailure(Throwable failure) {
        if (!adapterFailureReported.compareAndSet(false, true)) {
            return;
        }
        try {
            adapterFailureHandler.accept(failure);
        } catch (Throwable ignoredFailureHandlerError) {
            // A diagnostic callback must not escape into the Netty event loop.
        }
    }

    private void reportSinkFailure(CaptureSink sink, Throwable failure) {
        try {
            sinkFailureHandler.accept(sink.sessionId(), failure);
        } catch (Throwable ignoredSinkFailureHandlerError) {
            // Session diagnostics must not prevent other sinks from receiving packets.
        }
    }
}
