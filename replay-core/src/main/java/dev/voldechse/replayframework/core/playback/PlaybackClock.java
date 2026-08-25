package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.playback.PlaybackSpeed;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Session-local monotone playback clock. */
final class PlaybackClock {

    private final long durationNanos;
    private final LongSupplier nowNanos;
    private long positionAtLastChangeNanos;
    private long timeAtLastChangeNanos;
    private PlaybackSpeed speed;
    private boolean playing;

    PlaybackClock(long durationNanos, PlaybackSpeed speed) {
        this(durationNanos, speed, System::nanoTime);
    }

    PlaybackClock(long durationNanos, PlaybackSpeed speed, LongSupplier nowNanos) {
        if (durationNanos < 0L) {
            throw new IllegalArgumentException("durationNanos must not be negative");
        }
        this.durationNanos = durationNanos;
        this.speed = Objects.requireNonNull(speed, "speed");
        this.nowNanos = Objects.requireNonNull(nowNanos, "nowNanos");
        this.timeAtLastChangeNanos = nowNanos.getAsLong();
    }

    long positionNanos() {
        return positionNanos(nowNanos.getAsLong());
    }

    long nowNanos() {
        return nowNanos.getAsLong();
    }

    long positionNanos(long nowNanos) {
        if (!playing) {
            return positionAtLastChangeNanos;
        }
        long elapsedNanos = elapsedSinceLastChange(nowNanos);
        long scaledNanos = scale(elapsedNanos, speed);
        return clamp(saturatingAdd(positionAtLastChangeNanos, scaledNanos));
    }

    void play(long nowNanos) {
        long position = positionNanos(nowNanos);
        positionAtLastChangeNanos = position;
        timeAtLastChangeNanos = nowNanos;
        playing = position < durationNanos;
    }

    void pause(long nowNanos) {
        positionAtLastChangeNanos = positionNanos(nowNanos);
        timeAtLastChangeNanos = nowNanos;
        playing = false;
    }

    void setSpeed(PlaybackSpeed speed, long nowNanos) {
        Objects.requireNonNull(speed, "speed");
        positionAtLastChangeNanos = positionNanos(nowNanos);
        timeAtLastChangeNanos = nowNanos;
        this.speed = speed;
    }

    void seekTo(long targetNanos, long nowNanos) {
        positionAtLastChangeNanos = clamp(targetNanos);
        timeAtLastChangeNanos = nowNanos;
        if (positionAtLastChangeNanos >= durationNanos) {
            playing = false;
        }
    }

    long durationNanos() {
        return durationNanos;
    }

    PlaybackSpeed speed() {
        return speed;
    }

    boolean playing() {
        return playing;
    }

    private long elapsedSinceLastChange(long nowNanos) {
        if (nowNanos <= timeAtLastChangeNanos) {
            return 0L;
        }
        return nowNanos - timeAtLastChangeNanos;
    }

    private static long scale(long elapsedNanos, PlaybackSpeed speed) {
        return switch (speed) {
            case QUARTER -> elapsedNanos / 4L;
            case HALF -> elapsedNanos / 2L;
            case NORMAL -> elapsedNanos;
            case DOUBLE -> saturatingMultiply(elapsedNanos, 2L);
            case QUADRUPLE -> saturatingMultiply(elapsedNanos, 4L);
        };
    }

    private long clamp(long value) {
        return Math.max(0L, Math.min(durationNanos, value));
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
