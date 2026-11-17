package dev.voldechse.replayframework.adapter.paper.argus;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** What every part of Argus shares: the platform, the output and the clock. */
record ArgusServices(
        ArgusPlatform platform,
        ArgusPublisher publisher,
        LongSupplier serverTick,
        Consumer<Throwable> failureHandler,
        BooleanSupplier closed) {
}
