package dev.voldechse.replayframework.storage;

import java.util.Objects;

/**
 * Canonical relative path of one replay artifact.
 *
 * @param value canonical slash-separated artifact path
 */
public record ArtifactKey(String value) {

    /** Validates and canonicalizes an artifact path. */
    public ArtifactKey {
        value = canonicalize(value);
    }

    /**
     * Creates a canonical artifact key from an external string.
     *
     * @param value external relative artifact path
     * @return validated canonical artifact key
     */
    public static ArtifactKey of(String value) {
        return new ArtifactKey(value);
    }

    private static String canonicalize(String input) {
        Objects.requireNonNull(input, "value");
        if (input.isEmpty() || input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("artifact key must not be empty or contain NUL");
        }

        String normalized = input.replace('\\', '/');
        if (normalized.startsWith("/")
                || (normalized.length() >= 2
                && Character.isLetter(normalized.charAt(0))
                && normalized.charAt(1) == ':')) {
            throw new IllegalArgumentException("artifact key must be relative");
        }

        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "artifact key contains an empty or traversal segment: " + input);
            }
        }
        return String.join("/", segments);
    }
}
