package dev.voldechse.replayframework.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Convenience access to the framework installed by the Paper runtime.
 *
 * <p>{@link #install(ReplayFramework)} and {@link #clear()} are runtime-only
 * lifecycle hooks. They are binary-visible because the runtime is a separate
 * Gradle module, but they are not an integrator replacement for the Paper
 * service registration.</p>
 */
public final class ReplayFrameworkProvider {
    private static final AtomicReference<ReplayFramework> CURRENT = new AtomicReference<>();

    private ReplayFrameworkProvider() {
    }

    /**
     * Returns the currently installed framework.
     *
     * @return installed framework
     * @throws IllegalStateException when the runtime has not installed one
     */
    public static ReplayFramework get() {
        ReplayFramework framework = CURRENT.get();
        if (framework == null) {
            throw new IllegalStateException(
                    "ReplayFramework has not been installed by the Paper runtime");
        }
        return framework;
    }

    /**
     * Installs the framework for runtime consumers.
     *
     * <p>The compare-and-set prevents a second runtime instance from silently
     * replacing a live service graph.</p>
     *
     * @param framework framework implementation
     * @throws NullPointerException when {@code framework} is {@code null}
     * @throws IllegalStateException when another framework is installed
     */
    public static void install(ReplayFramework framework) {
        Objects.requireNonNull(framework, "framework");
        if (!CURRENT.compareAndSet(null, framework)) {
            throw new IllegalStateException("ReplayFramework is already installed");
        }
    }

    /**
     * Clears the runtime installation. Repeated calls are safe.
     */
    public static void clear() {
        CURRENT.set(null);
    }
}
