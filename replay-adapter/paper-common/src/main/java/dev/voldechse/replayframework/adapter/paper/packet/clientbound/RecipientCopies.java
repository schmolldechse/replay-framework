package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * How copies of a player-only packet that several players received are
 * recognized.
 *
 * <p>The server sends sounds, particles, chat and similar events to every
 * player in range. A recording observes such a packet once per recipient and
 * must keep one event. Every definition of a recorded player-only packet
 * names one of these kinds; there is no default, because only the packet's
 * server code tells which one applies.</p>
 */
public abstract sealed class RecipientCopies {

    private static final RecipientCopies UNDECLARED = new Unique(false);
    private static final RecipientCopies UNIQUE = new Unique(true);
    private static final RecipientCopies IDENTICAL_PAYLOAD = new IdenticalPayload();
    private static final RecipientCopies DELIVERED_BY_SCENE = new DeliveredByScene();

    private RecipientCopies() {
    }

    /** Every observation is an event of its own; the server never sends it to several players. */
    public static RecipientCopies unique() {
        return UNIQUE;
    }

    /** The server sends equal bytes to every recipient, often the same packet object. */
    public static RecipientCopies identicalPayload() {
        return IDENTICAL_PAYLOAD;
    }

    /**
     * The server builds the packet per recipient with recipient-specific
     * fields. The identity holds only the fields that describe the event and
     * must implement value equality.
     *
     * @param packetType the native packet class
     * @param identity the event's identity
     */
    public static <P> RecipientCopies keyedBy(Class<P> packetType, Function<? super P, ?> identity) {
        return new Keyed<>(packetType, identity);
    }

    /**
     * The scene delivers this packet itself, for example time and weather
     * sampled by the virtual observers. Observations through player
     * connections are dropped.
     */
    public static RecipientCopies deliveredByScene() {
        return DELIVERED_BY_SCENE;
    }

    /** The default of a definition that has not declared its copies. */
    static RecipientCopies undeclared() {
        return UNDECLARED;
    }

    /** Whether the definition declared how its copies are recognized. */
    public boolean declared() {
        return this != UNDECLARED;
    }

    /** Whether observations through player connections are dropped. */
    public boolean fromSceneOnly() {
        return false;
    }

    /**
     * Returns what all copies of the observed event share; empty when the
     * observation is an event of its own.
     *
     * @param decodedPacket the native packet
     * @param payload the packet's wire payload
     */
    public abstract Optional<Object> identity(Object decodedPacket, byte[] payload);

    private static final class Unique extends RecipientCopies {
        private final boolean declared;

        private Unique(boolean declared) {
            this.declared = declared;
        }

        @Override
        public Optional<Object> identity(Object decodedPacket, byte[] payload) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return declared ? "unique" : "undeclared";
        }
    }

    private static final class IdenticalPayload extends RecipientCopies {
        @Override
        public Optional<Object> identity(Object decodedPacket, byte[] payload) {
            return Optional.of(new PayloadIdentity(payload));
        }

        @Override
        public String toString() {
            return "identical payload";
        }
    }

    private static final class DeliveredByScene extends RecipientCopies {
        @Override
        public boolean fromSceneOnly() {
            return true;
        }

        @Override
        public Optional<Object> identity(Object decodedPacket, byte[] payload) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "delivered by the scene";
        }
    }

    private static final class Keyed<P> extends RecipientCopies {
        private final Class<P> packetType;
        private final Function<? super P, ?> identity;

        private Keyed(Class<P> packetType, Function<? super P, ?> identity) {
            this.packetType = Objects.requireNonNull(packetType, "packetType");
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        @Override
        public Optional<Object> identity(Object decodedPacket, byte[] payload) {
            if (!packetType.isInstance(decodedPacket)) {
                throw new IncompatibleAdapterException(
                        "recipient copy identity expects " + packetType.getName()
                                + " but got " + decodedPacket.getClass().getName());
            }
            return Optional.of(Objects.requireNonNull(
                    identity.apply(packetType.cast(decodedPacket)), "recipient copy identity"));
        }

        @Override
        public String toString() {
            return "keyed by " + packetType.getSimpleName();
        }
    }

    /** Wire bytes with value equality. */
    private static final class PayloadIdentity {
        private final byte[] payload;
        private final int hashCode;

        private PayloadIdentity(byte[] payload) {
            this.payload = payload.clone();
            this.hashCode = Arrays.hashCode(this.payload);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof PayloadIdentity that && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
