package dev.voldechse.replayframework.format;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Metadata stored before a compressed replay segment body.
 *
 * @param formatVersion binary segment format revision
 * @param adapterId adapter identifier that produced the segment
 * @param startElapsedNanos elapsed time of the first frame
 * @param endElapsedNanos elapsed time of the last frame
 * @param frameCount number of frames in the segment
 * @param compressionId compression used for the frame body
 * @param uncompressedLength byte length of the uncompressed frame body
 */
public record SegmentHeader(
        int formatVersion,
        String adapterId,
        long startElapsedNanos,
        long endElapsedNanos,
        long frameCount,
        CompressionId compressionId,
        long uncompressedLength) {

    /** Revision one is the first supported segment wire format. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    public SegmentHeader {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported segment format version: " + formatVersion);
        }
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isEmpty()) {
            throw new IllegalArgumentException("adapterId must not be empty");
        }
        if (adapterIdBytes(adapterId).length > 255) {
            throw new IllegalArgumentException("adapterId must fit in 255 UTF-8 bytes");
        }
        if (startElapsedNanos < 0 || endElapsedNanos < 0) {
            throw new IllegalArgumentException("segment times must not be negative");
        }
        if (endElapsedNanos < startElapsedNanos) {
            throw new IllegalArgumentException("endElapsedNanos must not precede startElapsedNanos");
        }
        if (frameCount < 0) {
            throw new IllegalArgumentException("frameCount must not be negative");
        }
        Objects.requireNonNull(compressionId, "compressionId");
        if (uncompressedLength < 0) {
            throw new IllegalArgumentException("uncompressedLength must not be negative");
        }
    }

    /**
     * Encodes an adapter id with strict UTF-8 validation.
     *
     * @param adapterId adapter id
     * @return UTF-8 bytes
     * @throws IllegalArgumentException for malformed UTF-16 input
     */
    static byte[] adapterIdBytes(String adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(adapterId));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("adapterId is not valid UTF-8", exception);
        }
    }

    /**
     * Compression identifiers are explicit wire values, never enum ordinals.
     */
    public enum CompressionId {
        /** Zstandard stream containing the uncompressed frame records. */
        ZSTD(1);

        private final int wireCode;

        CompressionId(int wireCode) {
            this.wireCode = wireCode;
        }

        /**
         * Returns the stable persisted compression code.
         *
         * @return compression wire code
         */
        public int wireCode() {
            return wireCode;
        }

        /**
         * Resolves a persisted compression code.
         *
         * @param wireCode persisted code
         * @return matching compression
         * @throws IllegalArgumentException when the code is unknown
         */
        public static CompressionId fromWireCode(int wireCode) {
            for (CompressionId compressionId : values()) {
                if (compressionId.wireCode == wireCode) {
                    return compressionId;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown segment compression wire code: " + wireCode);
        }
    }
}
