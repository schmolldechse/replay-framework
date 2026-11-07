package dev.voldechse.replayframework.adapter.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ObserverAnchorTest {

    @Test
    void rejectsNonFiniteReplayCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new ObserverAnchor(
                ObserverAnchor.Kind.PLAYER,
                Double.NaN,
                64.0D,
                0.0D,
                0.0F,
                0.0F));
    }
}
