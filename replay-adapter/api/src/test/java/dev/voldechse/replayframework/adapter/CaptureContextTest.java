package dev.voldechse.replayframework.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.format.TrackSubject;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class CaptureContextTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");
    private static final CaptureSubject ALEX = new CaptureSubject(TrackSubject.player(UUID.randomUUID()), "Alex");

    @Test
    void aNewContextBelongsToTheScene() {
        CaptureContext context = context(Optional.of(OVERWORLD));

        assertEquals(TrackRoute.SCENE, context.route());
        assertFalse(context.worldWide());
    }

    @Test
    void theLastRouteWins() {
        CaptureContext context = context(Optional.of(OVERWORLD)).asWorldWide().withSubject(ALEX);

        assertEquals(TrackRoute.subject(ALEX), context.route());
        assertFalse(context.worldWide());
    }

    @Test
    void otherChangesKeepTheRoute() {
        CaptureContext context = context(Optional.of(OVERWORLD)).asBlockState()
                .observedBy(UUID.randomUUID())
                .asRecipientCopy(new RecipientCopy(UUID.randomUUID(), "event"));

        assertEquals(TrackRoute.BLOCK_STATE, context.route());
        assertTrue(context.asWorldWide().worldWide());
    }

    @Test
    void blockStateNeedsAWorld() {
        CaptureContext context = context(Optional.empty());

        assertThrows(IllegalStateException.class, context::asBlockState);
        assertThrows(IllegalArgumentException.class, () -> context.routedTo(TrackRoute.BLOCK_STATE));
    }

    private static CaptureContext context(Optional<Key> world) {
        return new CaptureContext(
                PacketDisposition.STATEFUL, world, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
