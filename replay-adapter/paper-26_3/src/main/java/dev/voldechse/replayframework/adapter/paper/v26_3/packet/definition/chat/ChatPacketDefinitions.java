package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.chat;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the chat protocol domain. */
public final class ChatPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(new Id023CustomChatCompletionsPacketDefinition());

    private ChatPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
