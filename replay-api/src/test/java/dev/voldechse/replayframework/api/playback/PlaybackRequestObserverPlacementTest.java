package dev.voldechse.replayframework.api.playback;

import dev.voldechse.replayframework.api.id.ReplayId;
import java.lang.reflect.Proxy;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackRequestObserverPlacementTest {

    @Test
    void preservesExplicitAutomaticPlacement() {
        World world = proxy(World.class);
        Player viewer = proxy(Player.class);
        ObserverPlacement placement = ObserverPlacement.automatic(world);

        PlaybackRequest request = PlaybackRequest.builder()
                .replay(ReplayId.random())
                .viewer(viewer)
                .observerPlacement(placement)
                .build();

        assertSame(placement, request.observerPlacement().orElseThrow());
    }

    @Test
    void compatibilityConstructorUsesRuntimeAutomaticPlacement() {
        PlaybackRequest request = new PlaybackRequest(
                ReplayId.random(),
                proxy(Player.class),
                PlaybackBufferOptions.builder().build());

        assertTrue(request.observerPlacement().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (instance, method, arguments) -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }
}
