package dev.voldechse.replayframework.adapter.playback;

import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The viewer's clicks on what the replay shows. The bridge reads them from the
 * viewer's serverbound packets and keeps them from the server, which knows no
 * such entity or block.
 */
public interface ViewerInteractions {

    /** Receives the viewer's interactions with replay entities. */
    void setInteractionHandler(Consumer<EntityInteraction> handler);

    /** Receives the viewer's clicks on blocks with recorded state, such as a chest of the replay. */
    void setBlockInteractionHandler(Consumer<BlockInteraction> handler);

    /**
     * A viewer's interaction with a replay entity.
     *
     * @param entity what the scene shows of the entity
     * @param attack whether the viewer attacked instead of using it
     * @param hand the hand the viewer used
     */
    record EntityInteraction(RecordedEntity entity, boolean attack, EquipmentSlot hand) {
        public EntityInteraction {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(hand, "hand");
        }
    }

    /**
     * A viewer's click on a block of the replay with recorded state.
     *
     * @param world the recorded world of the block
     * @param position the block's position
     * @param block the block's type, such as {@code minecraft:chest}
     * @param hand the hand the viewer used
     */
    record BlockInteraction(Key world, BlockPosition position, Key block, EquipmentSlot hand) {
        public BlockInteraction {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(hand, "hand");
        }
    }
}
