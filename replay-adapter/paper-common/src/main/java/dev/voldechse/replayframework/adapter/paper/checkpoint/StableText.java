package dev.voldechse.replayframework.adapter.paper.checkpoint;

import java.util.Objects;

/** Keys of checkpoint packets: non-empty, without whitespace or control characters. */
final class StableText {

    private StableText() {
    }

    static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain whitespace or control characters");
            }
        }
        return value;
    }
}
