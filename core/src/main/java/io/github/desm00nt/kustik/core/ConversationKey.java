package io.github.desm00nt.kustik.core;

import java.util.Objects;
import java.util.UUID;

/** A player's conversation / reward at one block in one dimension of the current save. */
public record ConversationKey(UUID playerId, String dimension, long blockPosition) {
    public ConversationKey {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(dimension);
    }
}
