package dev.voldechse.replayframework.api.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable page of query results with an opaque continuation cursor.
 *
 * @param <T> result element type
 */
public final class ReplayPage<T> {
    private final List<T> items;
    private final Optional<String> nextCursor;

    /**
     * Creates a result page.
     *
     * @param items result items
     * @param nextCursor opaque cursor for the next page, if present
     */
    public ReplayPage(List<T> items, Optional<String> nextCursor) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        this.nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (this.nextCursor.isPresent() && this.nextCursor.get().isBlank()) {
            throw new IllegalArgumentException("nextCursor must not be blank");
        }
    }

    /**
     * Returns immutable result items.
     *
     * @return result items
     */
    public List<T> items() {
        return items;
    }

    /**
     * Returns the opaque continuation cursor.
     *
     * @return next cursor, or empty when this is the last page
     */
    public Optional<String> nextCursor() {
        return nextCursor;
    }

    /**
     * Tests whether a subsequent page exists.
     *
     * @return true when nextCursor is present
     */
    public boolean hasNext() {
        return nextCursor.isPresent();
    }
}
