package dev.voldechse.replayframework.example.resourcepack;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleResourcePackServiceTest {

    @Test
    void sendsOneStackableRequestAndCompletesOnlyAfterSuccessfulLoad() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<ResourcePackRequest> sentRequest = new AtomicReference<>();
        Player player = fakePlayer(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        ExampleResourcePackService service = newServiceWithReference(
                sentRequest,
                scheduler,
                Duration.ofSeconds(10));
        try {
            CompletableFuture<ExampleResourcePackService.HandshakeResult> stage =
                    service.request(player).toCompletableFuture();

            ResourcePackRequest request = sentRequest.get();
            assertNotNull(request);
            assertTrue(request.required());
            assertFalse(request.replace());
            UUID packId = request.packs().get(0).id();

            request.callback().packEventReceived(packId, ResourcePackStatus.ACCEPTED, player);
            assertFalse(stage.isDone());
            request.callback().packEventReceived(packId, ResourcePackStatus.DOWNLOADED, player);
            assertFalse(stage.isDone());
            request.callback().packEventReceived(packId, ResourcePackStatus.SUCCESSFULLY_LOADED, player);

            ExampleResourcePackService.HandshakeResult result = stage.get(1, TimeUnit.SECONDS);
            assertEquals(player.getUniqueId(), result.viewerId());
            assertEquals(packId, result.packId());
            assertEquals(ResourcePackStatus.SUCCESSFULLY_LOADED, result.status());
            assertTrue(service.isLoaded(player.getUniqueId()));
        } finally {
            service.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void returnsTheSameStageForDuplicateRequestsAndIgnoresLateCallbacks() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<ResourcePackRequest> sentRequest = new AtomicReference<>();
        AtomicInteger sendCount = new AtomicInteger();
        Player player = fakePlayer(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        ExampleResourcePackService service = newService(
                (viewer, request) -> {
                    sendCount.incrementAndGet();
                    sentRequest.set(request);
                },
                scheduler,
                Duration.ofSeconds(10));
        try {
            CompletableFuture<ExampleResourcePackService.HandshakeResult> first =
                    service.request(player).toCompletableFuture();
            CompletableFuture<ExampleResourcePackService.HandshakeResult> second =
                    service.request(player).toCompletableFuture();

            assertSame(first, second);
            assertEquals(1, sendCount.get());

            ResourcePackRequest request = sentRequest.get();
            UUID packId = request.packs().get(0).id();
            request.callback().packEventReceived(packId, ResourcePackStatus.DECLINED, player);
            assertThrows(CompletionException.class, first::join);
            request.callback().packEventReceived(packId, ResourcePackStatus.SUCCESSFULLY_LOADED, player);
            assertThrows(CompletionException.class, first::join);
            assertFalse(service.isLoaded(player.getUniqueId()));
        } finally {
            service.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void rejectsUnsafeConfigurationBeforeSending() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExampleResourcePackService.Configuration(
                        URI.create("http://example.invalid/replay.zip"),
                        "0123456789abcdef0123456789ABCDEF01234567",
                        Duration.ofSeconds(1),
                        net.kyori.adventure.text.Component.text("Load pack")));
    }

    private static ExampleResourcePackService newServiceWithReference(
            AtomicReference<ResourcePackRequest> sentRequest,
            ScheduledExecutorService scheduler,
            Duration timeout) {
        return newService(
                (viewer, request) -> sentRequest.set(request),
                scheduler,
                timeout);
    }

    private static ExampleResourcePackService newService(
            ExampleResourcePackService.PackRequestSender sender,
            ScheduledExecutorService scheduler,
            Duration timeout) {
        ExampleResourcePackService.Configuration configuration =
                new ExampleResourcePackService.Configuration(
                        URI.create("https://example.invalid/replay.zip"),
                        "0123456789abcdef0123456789abcdef01234567",
                        timeout,
                        net.kyori.adventure.text.Component.text("Load pack"));
        return new ExampleResourcePackService(configuration, sender, Runnable::run, scheduler);
    }

    private static Player fakePlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> true;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    case "toString" -> "FakePlayer[" + id + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }
}
