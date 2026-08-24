package dev.voldechse.replayframework.api.recording;

/**
 * Immutable block coordinate used by recording regions.
 *
 * @param x block coordinate on the X axis
 * @param y block coordinate on the Y axis
 * @param z block coordinate on the Z axis
 */
public record BlockPosition(int x, int y, int z) {
}
