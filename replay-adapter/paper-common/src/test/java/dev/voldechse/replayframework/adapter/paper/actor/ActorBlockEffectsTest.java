package dev.voldechse.replayframework.adapter.paper.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActorBlockEffectsTest {

    private static final BlockPosition TARGET = new BlockPosition(0, 64, 0);
    private static final BlockPosition NEIGHBOR = new BlockPosition(0, 65, 0);
    private static final ActorAction USE = ActorAction.useOn(TARGET, NEIGHBOR);

    private final ActorBlockEffects<String> effects = new ActorBlockEffects<>(List.of(
            named("door", (before, after) -> before.startsWith("door")),
            named("any", (before, after) -> true)));

    @Test
    void letsTheFirstMatchingFamilyExplainAChange() {
        List<ActorEffect> result = effects.effects(
                USE, Map.of(TARGET, "door closed"), Map.of(TARGET, "door open"));

        assertEquals(List.of("door"), packets(result));
    }

    @Test
    void explainsEveryChangedPositionInProbeOrder() {
        List<ActorEffect> result = effects.effects(
                USE,
                Map.of(TARGET, "grass", NEIGHBOR, "air"),
                Map.of(TARGET, "stone", NEIGHBOR, "torch"));

        assertEquals(List.of("any", "any"), packets(result));
    }

    @Test
    void ignoresUnchangedAndUnloadedPositions() {
        assertTrue(effects.effects(
                USE, Map.of(TARGET, "stone", NEIGHBOR, "air"), Map.of(TARGET, "stone")).isEmpty());
        assertTrue(effects.effects(
                USE, Map.of(TARGET, "stone"), Map.of(TARGET, "stone", NEIGHBOR, "torch")).isEmpty());
    }

    @Test
    void probesOnlyTheTargetOfADestroyAction() {
        assertEquals(List.of(TARGET), ActorAction.destroy(TARGET).probedPositions());
        assertEquals(List.of(TARGET, NEIGHBOR), USE.probedPositions());
    }

    private static ActorBlockEffect<String> named(String name, Matcher matcher) {
        return (kind, position, before, after) -> matcher.matches(before, after)
                ? Optional.of(new ActorEffect(name, position.x(), position.y(), position.z(), 16.0))
                : Optional.empty();
    }

    private static List<Object> packets(List<ActorEffect> effects) {
        return effects.stream().map(ActorEffect::packet).toList();
    }

    @FunctionalInterface
    private interface Matcher {
        boolean matches(String before, String after);
    }
}
