package dev.voldechse.replayframework.core.metadata;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataRegistryTest {

    @Test
    void registersSameDefinitionIdempotentlyAndResolvesByCanonicalKey() {
        TypeAdapter<String> codec = new Gson().getAdapter(String.class);
        ReplayMetadataKey<String> key = key(codec);
        MetadataRegistry registry = new MetadataRegistry();

        registry.register(key);
        registry.register(key);

        MetadataRegistry.RegisteredKey<?> registered = registry.require(Key.key("example:arena"));
        assertSame(key, registered.definition());
    }

    @Test
    void rejectsConflictingCodecWithoutReplacingExistingDefinition() {
        ReplayMetadataKey<String> original = key(new Gson().getAdapter(String.class).nullSafe());
        ReplayMetadataKey<String> conflicting = key(new Gson().getAdapter(String.class).nullSafe());
        MetadataRegistry registry = new MetadataRegistry();
        registry.register(original);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> registry.register(conflicting));

        assertEquals("metadata key conflicts with existing definition: example:arena",
                exception.getMessage());
        assertSame(original, registry.require(Key.key("example:arena")).definition());
    }

    private static ReplayMetadataKey<String> key(TypeAdapter<String> codec) {
        return ReplayMetadataKey.of(
                Key.key("example:arena"),
                TypeToken.get(String.class),
                codec,
                QueryCapabilities.of(
                        QueryCapabilities.Operation.EQUALITY,
                        new QueryCapabilities.Operation[]{
                                QueryCapabilities.Operation.EXISTENCE}));
    }
}
