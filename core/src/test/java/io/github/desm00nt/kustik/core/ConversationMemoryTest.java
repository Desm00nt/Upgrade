package io.github.desm00nt.kustik.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {
    private final UUID player = UUID.randomUUID();
    private final ConversationKey key = new ConversationKey(player, "minecraft:overworld", 1L);

    @Test
    void retainsCompletePairsAndKeepsTurnCountWhenOldHistoryIsTrimmed() {
        var memory = memory(10, 2);
        var session = memory.get(key, 0);
        assertEquals(1, session.nextTurn());
        for (int i = 1; i <= 8; i++) {
            session.accept("Реплика " + i, new BushReply("Ответ " + i, false), i);
        }
        assertEquals(9, session.nextTurn());
        assertEquals(4, session.history().size());
        assertEquals(new ChatMessage("user", "Реплика 7"), session.history().get(0));
        assertEquals("assistant", session.history().get(1).role());
        assertEquals("Реплика 8", session.history().get(2).content());
        assertThrows(UnsupportedOperationException.class, () -> session.history().clear());
    }

    @Test
    void separatesPlayersBlocksAndDimensions() {
        var memory = memory(10, 6);
        var original = memory.get(key, 0);
        original.accept("Только мой секрет", new BushReply("Шур-шур", false), 1);
        assertTrue(memory.get(new ConversationKey(UUID.randomUUID(), key.dimension(), 1), 2).history().isEmpty());
        assertTrue(memory.get(new ConversationKey(player, key.dimension(), 2), 2).history().isEmpty());
        assertTrue(memory.get(new ConversationKey(player, "minecraft:the_nether", 1), 2).history().isEmpty());
        assertSame(original, memory.get(key, 2));
    }

    @Test
    void expiresIdleSessionsAtTheBoundary() {
        var memory = memory(10, 6);
        var original = memory.get(key, 0);
        original.accept("Привет", new BushReply("Привет", false), 0);
        var next = memory.get(key, Duration.ofMinutes(15).toNanos());
        assertNotSame(original, next);
        assertTrue(next.history().isEmpty());
        assertEquals(1, next.nextTurn());
    }

    @Test
    void usesBoundedLeastRecentlyUsedStorage() {
        var memory = memory(2, 6);
        var second = new ConversationKey(player, key.dimension(), 2);
        var third = new ConversationKey(player, key.dimension(), 3);
        var one = memory.get(key, 0);
        var two = memory.get(second, 1);
        assertSame(one, memory.get(key, 2)); // Refresh first; second becomes oldest.
        memory.get(third, 3);
        assertEquals(2, memory.size());
        assertSame(one, memory.get(key, 4));
        assertNotSame(two, memory.get(second, 5));
        assertEquals(2, memory.size());
    }

    @Test
    void logoutAndBrokenBushClearOnlyRelevantSessions() {
        var memory = memory(10, 6);
        var other = UUID.randomUUID();
        memory.get(key, 0);
        memory.get(new ConversationKey(other, key.dimension(), 1), 0);
        memory.get(new ConversationKey(other, key.dimension(), 2), 0);
        memory.forgetPlayer(player);
        assertEquals(2, memory.size());
        memory.forgetBush(key.dimension(), 1);
        assertEquals(1, memory.size());
        memory.clear();
        assertEquals(0, memory.size());
    }

    @Test
    void aPendingOrFailedAttemptDoesNotCountAsACompletedTurn() {
        var session = memory(10, 6).get(key, 0);
        BushPrompt.messages(session, "hi", 2, false);
        assertEquals(1, session.nextTurn());
        assertTrue(session.history().isEmpty());
    }

    private static ConversationMemory memory(int sessions, int turns) {
        return new ConversationMemory(sessions, turns, Duration.ofMinutes(15));
    }
}
