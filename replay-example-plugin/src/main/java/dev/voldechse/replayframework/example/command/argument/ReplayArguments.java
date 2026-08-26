package dev.voldechse.replayframework.example.command.argument;

import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;

/** Pure parsers for the Example command argument boundary. */
public final class ReplayArguments {
    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);
    private static final Map<String, PlaybackSpeed> SPEEDS = Map.of(
            "0.25", PlaybackSpeed.QUARTER,
            "0.5", PlaybackSpeed.HALF,
            "1", PlaybackSpeed.NORMAL,
            "2", PlaybackSpeed.DOUBLE,
            "4", PlaybackSpeed.QUADRUPLE);

    private ReplayArguments() {
    }

    public static ReplayId parseReplayId(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            return ReplayId.parse(raw);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Replay ID must be a UUID", failure);
        }
    }

    public static RecordingSessionId parseRecordingSessionId(String raw) {
        Objects.requireNonNull(raw, "raw");
        try {
            return new RecordingSessionId(UUID.fromString(raw));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Recording session ID must be a UUID", failure);
        }
    }

    public static PlaybackSpeed parseSpeed(String raw) {
        Objects.requireNonNull(raw, "raw");
        PlaybackSpeed speed = SPEEDS.get(raw);
        if (speed == null) {
            throw new IllegalArgumentException("Speed must be one of 0.25, 0.5, 1, 2 or 4");
        }
        return speed;
    }

    public static Duration parseTime(String raw, boolean allowZero) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isBlank() || raw.startsWith("+") || raw.startsWith("-")) {
            throw new IllegalArgumentException("Time must be a non-negative value");
        }

        BigDecimal seconds;
        if (raw.indexOf(':') >= 0) {
            seconds = parseColonTime(raw);
        } else {
            try {
                seconds = new BigDecimal(raw);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "Time must use seconds, mm:ss or hh:mm:ss", failure);
            }
        }

        if (seconds.signum() < 0 || (!allowZero && seconds.signum() == 0)) {
            throw new IllegalArgumentException("Time must be positive");
        }
        try {
            BigDecimal nanos = seconds.multiply(NANOS_PER_SECOND)
                    .setScale(0, RoundingMode.UNNECESSARY);
            return Duration.ofNanos(nanos.longValueExact());
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Time is outside the supported duration range", failure);
        }
    }

    public static Key parseWorldKey(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isBlank() || !raw.equals(raw.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("World key must be lowercase and namespaced");
        }
        try {
            Key key = Key.key(raw);
            if (!raw.contains(":")) {
                throw new IllegalArgumentException("World key must contain a namespace");
            }
            return key;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("World key is invalid", failure);
        }
    }

    public static CuboidRegion parseRegion(
            Key world,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {
        Objects.requireNonNull(world, "world");
        try {
            return new CuboidRegion(
                    world,
                    new BlockPosition(minX, minY, minZ),
                    new BlockPosition(maxX, maxY, maxZ));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Region minimum must not exceed its maximum", failure);
        }
    }

    public static Set<UUID> parseParticipantList(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("Participant list must not be empty");
        }
        java.util.LinkedHashSet<UUID> values = new java.util.LinkedHashSet<>();
        for (String token : raw.split(",", -1)) {
            if (token.isBlank()) {
                throw new IllegalArgumentException("Participant list contains an empty UUID");
            }
            try {
                values.add(UUID.fromString(token));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Participant list contains an invalid UUID", failure);
            }
        }
        return Set.copyOf(values);
    }

    public static Object parseMetadataValue(ReplayMetadataKey<?> key, String raw) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(raw, "raw");
        return parseMetadataValueUnchecked(key, raw);
    }

    private static BigDecimal parseColonTime(String raw) {
        String[] parts = raw.split(":", -1);
        if (parts.length != 2 && parts.length != 3) {
            throw new IllegalArgumentException("Time must use mm:ss or hh:mm:ss");
        }
        try {
            int secondsIndex = parts.length - 1;
            BigDecimal secondsPart = new BigDecimal(parts[secondsIndex]);
            long minutePart;
            long hourPart = 0L;
            if (secondsPart.signum() < 0 || secondsPart.compareTo(BigDecimal.valueOf(60L)) >= 0) {
                throw new IllegalArgumentException("Seconds in a clock value must be below 60");
            }
            if (parts.length == 2) {
                minutePart = Long.parseLong(parts[0]);
            } else {
                hourPart = Long.parseLong(parts[0]);
                minutePart = Long.parseLong(parts[1]);
            }
            if (hourPart < 0L || minutePart < 0L || minutePart >= 60L) {
                throw new IllegalArgumentException("Clock values must use non-negative hours and minutes below 60");
            }
            return BigDecimal.valueOf(hourPart)
                    .multiply(BigDecimal.valueOf(3600L))
                    .add(BigDecimal.valueOf(minutePart).multiply(BigDecimal.valueOf(60L)))
                    .add(secondsPart);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Clock value contains a non-numeric component", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T parseMetadataValueUnchecked(
            ReplayMetadataKey<?> rawKey,
            String raw) {
        ReplayMetadataKey<T> key = (ReplayMetadataKey<T>) rawKey;
        TypeAdapter<T> codec = key.codec();
        try {
            T value = codec.fromJson(raw);
            if (value == null) {
                throw new IllegalArgumentException("Metadata values must not be null");
            }
            if (!key.type().getRawType().isInstance(value)) {
                throw new IllegalArgumentException("Metadata value has the wrong type");
            }
            return value;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("Metadata value is not valid JSON", failure);
        }
    }
}
