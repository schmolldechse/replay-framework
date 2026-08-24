package dev.voldechse.replayframework.format;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayManifestCodecTest {

    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void encodeIsDeterministicAndRoundTripsCanonicalManifest() throws Exception {
        ReplayManifest.ArtifactFile segment = new ReplayManifest.ArtifactFile(
                ReplayManifest.ArtifactType.SEGMENT,
                "segments/00000000.segment",
                32L,
                SHA);
        ReplayManifest.ArtifactFile index = new ReplayManifest.ArtifactFile(
                ReplayManifest.ArtifactType.INDEX,
                "index.bin",
                18L,
                SHA);
        ReplayManifest.ArtifactFile checkpoint = new ReplayManifest.ArtifactFile(
                ReplayManifest.ArtifactType.CHECKPOINT,
                "checkpoints/00000000.checkpoint",
                44L,
                SHA);
        ReplayManifest first = new ReplayManifest(
                "123e4567-e89b-12d3-a456-426614174000",
                "paper-26.2",
                775,
                "registry-ä",
                1,
                30_000L,
                List.of(segment, checkpoint, index));
        ReplayManifest second = new ReplayManifest(
                first.replayId(),
                first.adapterId(),
                first.protocolVersion(),
                first.registryFingerprint(),
                first.formatRevision(),
                first.durationNanos(),
                List.of(index, segment, checkpoint));
        ReplayManifestCodec codec = new ReplayManifestCodec(new GsonBuilder().create());

        byte[] firstBytes = codec.encode(first);
        byte[] secondBytes = codec.encode(second);

        assertArrayEquals(firstBytes, secondBytes);
        ReplayManifest decoded = codec.decode(firstBytes);
        assertEquals(first, decoded);
        assertEquals(
                "123e4567-e89b-12d3-a456-426614174000",
                decoded.replayId());
        assertTrue(new String(firstBytes, StandardCharsets.UTF_8).contains("registry-ä"));
        assertEquals("registry-ä", decoded.registryFingerprint());
    }

    @Test
    void invalidManifestValuesFailBeforeEncoding() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayManifest(
                        "not-a-uuid", "paper-26.2", 775, "registry", 1, 0L, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayManifest(
                        "123e4567-e89b-12d3-a456-426614174000",
                        "paper-26.2", 775, "registry", 1, 0L,
                        List.of(new ReplayManifest.ArtifactFile(
                                ReplayManifest.ArtifactType.INDEX,
                                "/index.bin", 1L, SHA))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayManifest(
                        "123e4567-e89b-12d3-a456-426614174000",
                        "paper-26.2", 775, "registry", 1, 0L,
                        List.of(new ReplayManifest.ArtifactFile(
                                ReplayManifest.ArtifactType.INDEX,
                                "../index.bin", 1L, SHA))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayManifest(
                        "123e4567-e89b-12d3-a456-426614174000",
                        "paper-26.2", 775, "registry", 1, 0L,
                        List.of(new ReplayManifest.ArtifactFile(
                                ReplayManifest.ArtifactType.INDEX,
                                "index.bin", 1L, SHA.toUpperCase()))));
    }

    @Test
    void malformedJsonAndUnsupportedRevisionFailAsCorruptArtifact() throws Exception {
        ReplayManifestCodec codec = new ReplayManifestCodec(new GsonBuilder().create());
        String validJson = "{"
                + "\"replayId\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"adapterId\":\"paper-26.2\","
                + "\"protocolVersion\":775,"
                + "\"registryFingerprint\":\"registry\","
                + "\"formatRevision\":1,"
                + "\"durationNanos\":0,"
                + "\"files\":[]"
                + "}";

        assertEquals(validJson, new String(codec.encode(codec.decode(validJson.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8));
        assertThrows(
                CorruptReplayArtifactException.class,
                () -> codec.decode(validJson.replace("\"formatRevision\":1", "\"formatRevision\":2")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                CorruptReplayArtifactException.class,
                () -> codec.decode("{\"replayId\":\"123e4567-e89b-12d3-a456-426614174000\"}"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void malformedUtf8FailsAsCorruptArtifact() {
        ReplayManifestCodec codec = new ReplayManifestCodec(new GsonBuilder().create());
        String json = "{"
                + "\"replayId\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"adapterId\":\"paper-26.2\","
                + "\"protocolVersion\":775,"
                + "\"registryFingerprint\":\"registry\","
                + "\"formatRevision\":1,"
                + "\"durationNanos\":0,"
                + "\"files\":[]"
                + "}";
        byte[] malformed = json.getBytes(StandardCharsets.UTF_8);
        int registryOffset = json.indexOf(
                "\"registry\"", json.indexOf("\"registryFingerprint\"") + 1) + 1;
        malformed[registryOffset] = (byte) 0xC3;
        malformed[registryOffset + 1] = 0x28;

        assertThrows(
                CorruptReplayArtifactException.class,
                () -> codec.decode(malformed));
    }
}
