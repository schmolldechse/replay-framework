package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26PacketRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global Paper 26.2 capture installation shared by all active recording
 * sessions. Session filtering remains in the core router.
 */
public final class Paper26CaptureBridge implements CaptureBridge {

    static final String HANDLER_NAME = "replay-framework-capture";

    private final PaperConnectionAccessor accessor;
    private final PacketRegistry registry;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final ConcurrentMap<UUID, InstalledHandler> installedHandlers = new ConcurrentHashMap<>();
    private final Object handlerStateLock = new Object();
    private final Object sequenceLock = new Object();
    private final PaperConnectionAccessor.ConnectionLifecycleListener lifecycleListener =
            new PaperConnectionAccessor.ConnectionLifecycleListener() {
                @Override
                public void onConnected(PaperConnectionAccessor.ConnectionHandle connection) {
                    attach(connection);
                }

                @Override
                public void onDisconnected(PaperConnectionAccessor.ConnectionHandle connection) {
                    detach(connection.recipientId(), connection);
                }
            };

    private volatile CaptureBridge.PacketSink sink;
    private volatile CaptureBridge.FailureHandler failureHandler;
    private long sequenceTick = -1L;
    private int nextSequence;

    /** Creates a bridge and discovers the live Paper 26.2 registry. */
    public Paper26CaptureBridge(PaperConnectionAccessor accessor) {
        this(accessor, Paper26PacketRegistry.discover());
    }

    /**
     * Creates a bridge using an already verified Paper registry.
     *
     * @param accessor Paper connection boundary
     * @param registry registry published by the Paper 26.2 adapter
     */
    public Paper26CaptureBridge(PaperConnectionAccessor accessor, PacketRegistry registry) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
    }

    @Override
    public void install(PacketSink sink, FailureHandler failureHandler) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(failureHandler, "failureHandler");

        synchronized (handlerStateLock) {
            State current = state.get();
            if (current == State.INSTALLED) {
                if (this.sink == sink && this.failureHandler == failureHandler) {
                    return;
                }
                throw new IllegalStateException("Paper capture bridge is already installed");
            }
            if (current != State.NEW) {
                throw new IllegalStateException("Paper capture bridge cannot be installed in state " + current);
            }
            this.sink = sink;
            this.failureHandler = failureHandler;
            state.set(State.INSTALLED);
        }

        try {
            accessor.registerLifecycleListener(lifecycleListener);
            for (PaperConnectionAccessor.ConnectionHandle connection : accessor.activeConnections()) {
                attach(connection);
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @Override
    public void uninstall() {
        State previous;
        synchronized (handlerStateLock) {
            previous = state.getAndSet(State.UNINSTALLED);
        }
        if (previous == State.UNINSTALLED) {
            return;
        }

        // UNINSTALLED is an expected lifecycle end, not an integrity failure.
        cleanupHandlers();
        try {
            accessor.unregisterLifecycleListener(lifecycleListener);
        } catch (Throwable ignored) {
            // Paper may already have dismantled its event manager during shutdown.
        }
    }

    @Override
    public boolean installed() {
        return state.get() == State.INSTALLED;
    }

    private void attach(PaperConnectionAccessor.ConnectionHandle connection) {
        Objects.requireNonNull(connection, "connection");
        InstalledHandler installed;
        synchronized (handlerStateLock) {
            if (state.get() != State.INSTALLED
                    || installedHandlers.containsKey(connection.recipientId())) {
                return;
            }
            ReplayOutboundHandler handler = new ReplayOutboundHandler(
                    accessor,
                    connection,
                    registry,
                    packet -> {
                        CaptureBridge.PacketSink currentSink = sink;
                        if (currentSink != null) {
                            currentSink.accept(packet);
                        }
                    },
                    this::fail,
                    this::allocateSequence);
            installed = new InstalledHandler(connection, handler);
            installedHandlers.put(connection.recipientId(), installed);
        }

        try {
            accessor.installBeforeTransportCodec(connection, HANDLER_NAME, installed.handler());
        } catch (Throwable failure) {
            installedHandlers.remove(connection.recipientId(), installed);
            fail(failure);
        }
    }

    private void detach(UUID recipientId, PaperConnectionAccessor.ConnectionHandle connection) {
        InstalledHandler installed = installedHandlers.remove(recipientId);
        if (installed != null) {
            accessor.uninstallHandler(installed.connection(), HANDLER_NAME);
        } else {
            accessor.uninstallHandler(connection, HANDLER_NAME);
        }
    }

    private void fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (handlerStateLock) {
            State current = state.get();
            if (current == State.FAILED || current == State.UNINSTALLED) {
                return;
            }
            // FAILED means a capture-integrity invariant was lost. Active
            // recordings must observe this and cannot continue as available.
            state.set(State.FAILED);
        }

        cleanupHandlers();
        try {
            accessor.unregisterLifecycleListener(lifecycleListener);
        } catch (Throwable ignored) {
            // Failure cleanup is best effort and must remain one-shot.
        }

        if (failureReported.compareAndSet(false, true)) {
            CaptureBridge.FailureHandler currentFailureHandler = failureHandler;
            if (currentFailureHandler != null) {
                try {
                    currentFailureHandler.onFailure(failure);
                } catch (Throwable ignored) {
                    // A diagnostic failure handler must not escape a callback.
                }
            }
        }
    }

    private void cleanupHandlers() {
        List<InstalledHandler> handlers;
        synchronized (handlerStateLock) {
            handlers = new ArrayList<>(installedHandlers.values());
            installedHandlers.clear();
        }
        for (InstalledHandler installed : handlers) {
            try {
                accessor.uninstallHandler(installed.connection(), HANDLER_NAME);
            } catch (Throwable ignored) {
                // The channel may already be closed; cleanup remains best effort.
            }
        }
    }

    private int allocateSequence(long serverTick) {
        if (serverTick < 0L) {
            throw incompatible("Paper server tick is negative", null);
        }
        synchronized (sequenceLock) {
            if (serverTick < sequenceTick) {
                throw incompatible("Paper server tick moved backwards", null);
            }
            if (serverTick != sequenceTick) {
                sequenceTick = serverTick;
                nextSequence = 0;
            }
            if (nextSequence == Integer.MAX_VALUE) {
                throw incompatible("Paper capture sequence overflow", null);
            }
            return nextSequence++;
        }
    }

    private static PacketRegistry verifyRegistry(PacketRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (!(registry instanceof Paper26PacketRegistry)) {
            throw incompatible("Paper capture requires the Paper 26.2 packet registry", null);
        }
        String liveFingerprint = Paper26PacketRegistry.discover().fingerprint();
        if (!liveFingerprint.equals(registry.fingerprint())) {
            throw incompatible("Paper packet registry fingerprint changed", null);
        }
        return registry;
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    private record InstalledHandler(
            PaperConnectionAccessor.ConnectionHandle connection,
            ReplayOutboundHandler handler) {}

    /** Status values document whether capture is trusted, failed or shut down. */
    private enum State {
        /** No callback or lifecycle listener is installed. */
        NEW,
        /** The single callback is active and handlers are being maintained. */
        INSTALLED,
        /** Capture integrity failed; active recording must fail. */
        FAILED,
        /** Expected terminal lifecycle state after uninstall. */
        UNINSTALLED
    }
}
