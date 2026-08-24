package dev.voldechse.replayframework.api.recording;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.key.Key;

/**
 * Immutable per-recording packet capture policy.
 *
 * <p>Connection-control and unsupported packets intentionally do not have a
 * public category here. The adapter must reject them independently of this
 * policy.</p>
 */
public final class CapturePolicy {
    /** Public semantic categories that a recording may exclude. */
    public enum PacketCategory {
        /** Long-lived client-visible state. */
        STATEFUL,
        /** Short-lived effects such as sounds or particles. */
        EPHEMERAL,
        /** User-configurable presentation packets such as chat or titles. */
        CONFIGURABLE
    }

    private static final Predicate<String> ALLOW_ALL_CHAT = message -> true;

    private final boolean includeChat;
    private final Predicate<String> chatFilter;
    private final Set<Key> allowedCustomPayloadKeys;
    private final Set<PacketCategory> excludedPacketCategories;

    private CapturePolicy(Builder builder) {
        this.includeChat = builder.includeChat;
        this.chatFilter = builder.chatFilter;
        this.allowedCustomPayloadKeys = Set.copyOf(builder.allowedCustomPayloadKeys);
        this.excludedPacketCategories = Set.copyOf(builder.excludedPacketCategories);
    }

    /**
     * Creates a builder with chat enabled, an allow-all chat filter and closed
     * custom-payload capture.
     *
     * @return a new capture-policy builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether chat packets are eligible for capture.
     *
     * @return {@code true} when chat capture is enabled
     */
    public boolean includeChat() {
        return includeChat;
    }

    /**
     * Returns the filter applied to normalized chat text when chat is enabled.
     *
     * @return non-null chat predicate
     */
    public Predicate<String> chatFilter() {
        return chatFilter;
    }

    /**
     * Returns the exact custom-payload channels allowed by this policy.
     *
     * @return immutable allowlist
     */
    public Set<Key> allowedCustomPayloadKeys() {
        return allowedCustomPayloadKeys;
    }

    /**
     * Returns the semantic packet categories excluded by this policy.
     *
     * @return immutable exclusion set
     */
    public Set<PacketCategory> excludedPacketCategories() {
        return excludedPacketCategories;
    }

    /** Builder for an immutable {@link CapturePolicy}. */
    public static final class Builder {
        private boolean includeChat = true;
        private Predicate<String> chatFilter = ALLOW_ALL_CHAT;
        private final Set<Key> allowedCustomPayloadKeys = new LinkedHashSet<>();
        private final Set<PacketCategory> excludedPacketCategories = new LinkedHashSet<>();

        private Builder() {
        }

        /**
         * Enables or disables chat eligibility.
         *
         * @param includeChat whether chat packets are eligible
         * @return this builder
         */
        public Builder includeChat(boolean includeChat) {
            this.includeChat = includeChat;
            return this;
        }

        /**
         * Sets the predicate applied to normalized chat text.
         *
         * @param chatFilter non-blocking chat predicate
         * @return this builder
         */
        public Builder chatFilter(Predicate<String> chatFilter) {
            this.chatFilter = Objects.requireNonNull(chatFilter, "chatFilter");
            return this;
        }

        /**
         * Adds an exact custom-payload channel to the allowlist.
         *
         * @param channel channel key
         * @return this builder
         */
        public Builder allowCustomPayload(Key channel) {
            allowedCustomPayloadKeys.add(Objects.requireNonNull(channel, "channel"));
            return this;
        }

        /**
         * Excludes a semantic packet category.
         *
         * @param category category to exclude
         * @return this builder
         */
        public Builder excludePacketCategory(PacketCategory category) {
            excludedPacketCategories.add(Objects.requireNonNull(category, "category"));
            return this;
        }

        /**
         * Builds an immutable policy snapshot.
         *
         * @return immutable capture policy
         */
        public CapturePolicy build() {
            return new CapturePolicy(this);
        }
    }
}
