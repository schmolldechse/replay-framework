package dev.voldechse.replayframework.storage;

/**
 * Positive half-open byte range used by artifact reads.
 *
 * @param startInclusive first byte included in the range
 * @param endExclusive first byte excluded from the range
 */
public record ByteRange(long startInclusive, long endExclusive) {

    /** Validates the half-open range. */
    public ByteRange {
        if (startInclusive < 0 || endExclusive <= startInclusive) {
            throw new IllegalArgumentException(
                    "range must satisfy 0 <= startInclusive < endExclusive");
        }
    }

    /**
     * Returns the exact number of bytes in this range.
     *
     * @return endExclusive minus startInclusive
     */
    public long length() {
        return endExclusive - startInclusive;
    }
}
