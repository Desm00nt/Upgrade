package io.github.desm00nt.kustik.core;

import java.util.Objects;
import java.util.Set;

public record ChatMessage(String role, String content) {
    public ChatMessage {
        Objects.requireNonNull(content);
        if (!Set.of("system", "user", "assistant").contains(role)) {
            throw new IllegalArgumentException("Invalid message role");
        }
    }
}
