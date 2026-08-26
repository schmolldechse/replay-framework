package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import java.time.Duration;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/** Builds the fixed-width ActionBar overlay from normal text and glyph tiles. */
final class PlaybackStatusLayout {
    private static final Key STATUS_FONT = Key.key("replay_example", "status");
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");
    private static final int PANEL_START = 0xE200;
    private static final int TRACK_EMPTY = 0xE20C;
    private static final int PULL = 0xE20E;
    private static final int TRACK_FILL_PIXEL = 0xE400 + 208;
    // The pull glyph's right-most painted pixel is x=9. Bitmap glyphs add one trailing pixel.
    private static final int PULL_ADVANCE = 11;
    private static final int ICON_PAUSE = 0xE20F;
    private static final int ICON_PLAY = 0xE210;
    private static final int ICON_ENDED = 0xE212;
    private static final int ICON_FAILED = 0xE213;
    private static final int RESET = 0xE300;
    private static final int LEADING = 0xE302;
    private static final int GLYPH_JOIN = 0xE306;
    private static final int PANEL_TILES = 16;
    private static final int TRACK_SEGMENTS = 8;
    private static final int TRACK_GLYPH_WIDTH = 16;
    private static final int PULL_VISIBLE_CENTER = 8;
    private static final int ICON_TO_POSITION_GAP = 6;
    private static final int POSITION_TO_TRACK_GAP = 8;
    private static final int TRACK_TO_DURATION_GAP = 8;
    private static final int DURATION_TO_SPEED_GAP = 8;

    private PlaybackStatusLayout() {
    }

    static Component component(PlaybackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Component result = panel()
                .append(spacePixels(-panelWidth()))
                .append(space(LEADING))
                .append(glyph(icon(snapshot.status())))
                .append(spacePixels(ICON_TO_POSITION_GAP))
                .append(text(formatTime(snapshot.position())))
                .append(spacePixels(POSITION_TO_TRACK_GAP))
                .append(track(snapshot));
        return result
                .append(spacePixels(TRACK_TO_DURATION_GAP))
                .append(text(formatTime(snapshot.duration())))
                .append(spacePixels(DURATION_TO_SPEED_GAP))
                .append(text(formatSpeed(snapshot)));
    }

    private static Component panel() {
        Component result = glyph(PANEL_START);
        for (int index = 1; index < PANEL_TILES - 1; index++) {
            result = result
                    .append(space(GLYPH_JOIN))
                    .append(glyph(PANEL_START + 1));
        }
        return result
                .append(space(GLYPH_JOIN))
                .append(glyph(PANEL_START + 11));
    }

    private static Component glyph(int codePoint) {
        return Component.text(new String(Character.toChars(codePoint))).font(STATUS_FONT);
    }

    private static Component space(int codePoint) {
        return Component.text(new String(Character.toChars(codePoint))).font(STATUS_FONT);
    }

    private static Component text(String value) {
        return Component.text(value).font(DEFAULT_FONT);
    }

    private static Component track(PlaybackSnapshot snapshot) {
        Component result = Component.empty();
        for (int index = 0; index < TRACK_SEGMENTS; index++) {
            if (index > 0) {
                result = result.append(space(GLYPH_JOIN));
            }
            result = result.append(glyph(TRACK_EMPTY));
        }

        int trackWidth = trackWidth();
        int filledPixels = filledPixels(snapshot, trackWidth);
        result = result
                .append(spacePixels(-trackWidth))
                .append(fillPixels(filledPixels))
                .append(spacePixels(trackWidth - filledPixels));

        int markerStart = markerStart(snapshot, trackWidth);
        return result
                .append(spacePixels(-trackWidth))
                .append(spacePixels(markerStart))
                .append(glyph(PULL))
                .append(spacePixels(trackWidth - markerStart - PULL_ADVANCE));
    }

    private static Component spacePixels(int pixels) {
        if (pixels == 0) {
            return Component.empty();
        }
        StringBuilder value = new StringBuilder();
        if (pixels > 0) {
            int positiveSpaces = (pixels + 7) / 8;
            value.append(codePoint(RESET + 1).repeat(positiveSpaces));
            value.append(codePoint(GLYPH_JOIN).repeat(positiveSpaces * 8 - pixels));
        } else {
            value.append(codePoint(GLYPH_JOIN).repeat(-pixels));
        }
        return Component.text(value.toString()).font(STATUS_FONT);
    }

    private static String codePoint(int value) {
        return new String(Character.toChars(value));
    }

    private static int trackWidth() {
        return TRACK_SEGMENTS * TRACK_GLYPH_WIDTH;
    }

    private static int panelWidth() {
        return PANEL_TILES * TRACK_GLYPH_WIDTH;
    }

    private static int markerStart(PlaybackSnapshot snapshot, int trackWidth) {
        double progress = progress(snapshot);
        int markerStart = (int) Math.round(progress * trackWidth - PULL_VISIBLE_CENTER);
        return Math.max(
                -PULL_VISIBLE_CENTER,
                Math.min(trackWidth - PULL_VISIBLE_CENTER, markerStart));
    }

    private static int icon(PlaybackStatus status) {
        return switch (status) {
            case PLAYING -> ICON_PAUSE;
            case PAUSED -> ICON_PLAY;
            case PREPARING, BUFFERING -> ICON_PLAY;
            case ENDED -> ICON_ENDED;
            case FAILED, CLOSED -> ICON_FAILED;
        };
    }

    private static Component fillPixels(int width) {
        if (width <= 0) {
            return Component.empty();
        }
        return Component.text(
                (codePoint(TRACK_FILL_PIXEL) + codePoint(GLYPH_JOIN)).repeat(width))
                .font(STATUS_FONT);
    }

    private static int filledPixels(PlaybackSnapshot snapshot, int trackWidth) {
        return Math.max(0, Math.min(trackWidth,
                (int) Math.round(progress(snapshot) * trackWidth)));
    }

    private static double progress(PlaybackSnapshot snapshot) {
        Duration duration = snapshot.duration();
        if (duration.isZero()) {
            return 1.0D;
        }
        Duration position = snapshot.position().compareTo(duration) > 0
                ? duration
                : snapshot.position();
        return Math.max(0.0D, Math.min(
                1.0D,
                position.toNanos() / (double) duration.toNanos()));
    }

    private static String formatTime(Duration duration) {
        long totalSeconds = Math.max(0L, duration.toSeconds());
        return String.format(
                java.util.Locale.ROOT,
                "%02d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L);
    }

    private static String formatSpeed(PlaybackSnapshot snapshot) {
        double multiplier = snapshot.speed().multiplier();
        return multiplier < 1.0D
                ? String.format(java.util.Locale.ROOT, "%.2fx", multiplier)
                : String.format(java.util.Locale.ROOT, "%.0fx", multiplier);
    }
}
