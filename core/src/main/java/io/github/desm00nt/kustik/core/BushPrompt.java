package io.github.desm00nt.kustik.core;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class BushPrompt {
    private static final String SYSTEM = load();

    private BushPrompt() {}

    public static List<ChatMessage> messages(ConversationMemory.Session session, String message,
                                             int minimumTurns, boolean alreadyRewarded) {
        JsonObject state = new JsonObject();
        state.addProperty("turn", session.nextTurn());
        state.addProperty("minimum_turns", minimumTurns);
        state.addProperty("already_rewarded", alreadyRewarded);
        state.addProperty("reward_available", !alreadyRewarded && session.nextTurn() >= minimumTurns);
        List<ChatMessage> result = new ArrayList<>();
        result.add(new ChatMessage("system", SYSTEM));
        result.add(new ChatMessage("system", "Достоверное состояние игры от сервера: " + state));
        result.addAll(session.history());
        result.add(new ChatMessage("user", message));
        return List.copyOf(result);
    }

    private static String load() {
        try (InputStream stream = BushPrompt.class.getResourceAsStream("/kustik/bush-system.txt")) {
            if (stream == null) {
                throw new IllegalStateException("Bush prompt resource missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
