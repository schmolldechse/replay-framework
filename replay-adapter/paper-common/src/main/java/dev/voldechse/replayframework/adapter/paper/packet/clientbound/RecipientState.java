package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Whether a packet describes the state of its recipient: its inventory,
 * selected slot, health or experience. A recording keeps such a packet in
 * the recipient's state track instead of the scene.
 */
public abstract sealed class RecipientState {

    private static final RecipientState NONE = new Fixed(false);
    private static final RecipientState ALWAYS = new Fixed(true);

    private RecipientState() {
    }

    /** The packet never describes its recipient's state. This is the default. */
    public static RecipientState none() {
        return NONE;
    }

    /** The packet always describes its recipient's state. */
    public static RecipientState always() {
        return ALWAYS;
    }

    /**
     * The packet describes its recipient's state when the condition holds,
     * for example only for the recipient's own inventory window.
     *
     * @param packetType the native packet class
     * @param condition the condition on the native packet
     */
    public static <P> RecipientState when(Class<P> packetType, Predicate<? super P> condition) {
        return new Conditional<>(packetType, condition);
    }

    /** Whether the observed packet belongs to its recipient's state. */
    public abstract boolean describesRecipient(Object decodedPacket);

    private static final class Fixed extends RecipientState {
        private final boolean value;

        private Fixed(boolean value) {
            this.value = value;
        }

        @Override
        public boolean describesRecipient(Object decodedPacket) {
            return value;
        }
    }

    private static final class Conditional<P> extends RecipientState {
        private final Class<P> packetType;
        private final Predicate<? super P> condition;

        private Conditional(Class<P> packetType, Predicate<? super P> condition) {
            this.packetType = Objects.requireNonNull(packetType, "packetType");
            this.condition = Objects.requireNonNull(condition, "condition");
        }

        @Override
        public boolean describesRecipient(Object decodedPacket) {
            return packetType.isInstance(decodedPacket) && condition.test(packetType.cast(decodedPacket));
        }
    }
}
