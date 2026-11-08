package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Paper26EntityStateCaptureTest {

    @Test
    void authoritativeEntityMovementSuppressesRecipientDerivedDuplicates() {
        var capture = new Paper26EntityStateCapture.AuthoritativeMovementIndex();
        capture.mark(300);

        assertTrue(capture.suppresses(
                "play.clientbound.minecraft:move_entity_pos", varInt(300)));
        assertTrue(capture.suppresses(
                "play.clientbound.minecraft:entity_position_sync", varInt(300)));
        assertFalse(capture.suppresses(
                "play.clientbound.minecraft:move_entity_pos", varInt(301)));
        assertFalse(capture.suppresses(
                "play.clientbound.minecraft:set_entity_motion", varInt(300)));
    }

    private static byte[] varInt(int value) {
        byte[] result = new byte[5];
        int index = 0;
        do {
            int current = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                current |= 0x80;
            }
            result[index++] = (byte) current;
        } while (value != 0);
        return java.util.Arrays.copyOf(result, index);
    }
}
