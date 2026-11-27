package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.2 serverbound definitions owned by the chat protocol domain. */
public final class ChatServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id006ChatAckPacketDefinition(),
                    new Id007ChatCommandPacketDefinition(),
                    new Id008ChatCommandSignedPacketDefinition(),
                    new Id009ChatPacketDefinition(),
                    new Id010ChatSessionUpdatePacketDefinition(),
                    new Id015CommandSuggestionPacketDefinition());

    private ChatServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
