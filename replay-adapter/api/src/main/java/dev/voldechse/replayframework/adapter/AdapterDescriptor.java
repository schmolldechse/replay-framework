package dev.voldechse.replayframework.adapter;

import java.util.Objects;

/**
 * Immutable identity and compatibility metadata for one replay adapter.
 *
 * @param adapterId stable adapter identifier, such as {@code paper-26.2}
 * @param protocolVersion Minecraft protocol version understood by the adapter
 * @param adapterFormatRevision adapter-owned format revision
 * @param registryFingerprint SHA-256 fingerprint of the adapter packet registry
 */
public record AdapterDescriptor(
        String adapterId,
        int protocolVersion,
        int adapterFormatRevision,
        String registryFingerprint) {

    /** Validates adapter identity and fingerprint invariants. */
    public AdapterDescriptor {
        adapterId = requireStableText(adapterId, "adapterId");
        if (protocolVersion < 0) {
            throw new IllegalArgumentException("protocolVersion must not be negative");
        }
        if (adapterFormatRevision < 0) {
            throw new IllegalArgumentException("adapterFormatRevision must not be negative");
        }
        registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
        if (!registryFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "registryFingerprint must contain 64 lowercase hexadecimal characters");
        }
    }

    private static String requireStableText(String value, String field) {
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
