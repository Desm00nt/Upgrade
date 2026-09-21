package io.github.desm00nt.kustik.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread-confined, bounded LRU memory. Only successful exchanges are retained. */
public final class ConversationMemory {
    private final int maxSessions;
    private final int maxTurns;
    private final long ttlNanos;
    private final Map<ConversationKey, Session> sessions = new LinkedHashMap<>(16, 0.75f, true);

    public ConversationMemory(int maxSessions, int maxTurns, Duration ttl) {
        if (maxSessions < 1 || maxTurns < 1 || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Invalid conversation limits");
        }
        this.maxSessions = maxSessions;
        this.maxTurns = maxTurns;
        this.ttlNanos = ttl.toNanos();
    }

    public Session get(ConversationKey key, long now) {
        prune(now);
        Session session = sessions.get(key);
        if (session == null) {
            if (sessions.size() >= maxSessions) {
                Iterator<ConversationKey> keys = sessions.keySet().iterator();
                keys.next();
                keys.remove();
            }
            session = new Session(maxTurns, now);
            sessions.put(key, session);
        }
        session.lastTouched = now;
        return session;
    }

    public void prune(long now) {
        sessions.values().removeIf(session -> now - session.lastTouched >= ttlNanos);
    }

    public void forgetPlayer(UUID playerId) {
        sessions.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    public void forgetBush(String dimension, long blockPosition) {
        sessions.keySet().removeIf(key -> key.dimension().equals(dimension) && key.blockPosition() == blockPosition);
    }

    public void clear() {
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }

    public static final class Session {
        private final int maxTurns;
        private final ArrayDeque<Turn> turns = new ArrayDeque<>();
        private long lastTouched;
        private int completedTurns;

        private Session(int maxTurns, long now) {
            this.maxTurns = maxTurns;
            this.lastTouched = now;
        }

        public int nextTurn() {
            return completedTurns == Integer.MAX_VALUE ? completedTurns : completedTurns + 1;
        }

        public List<ChatMessage> history() {
            List<ChatMessage> history = new ArrayList<>(turns.size() * 2);
            for (Turn turn : turns) {
                history.add(new ChatMessage("user", turn.message));
                history.add(new ChatMessage("assistant", ReplyCodec.encode(turn.reply)));
            }
            return List.copyOf(history);
        }

        public void accept(String message, BushReply reply, long now) {
            turns.addLast(new Turn(message, reply));
            while (turns.size() > maxTurns) {
                turns.removeFirst();
            }
            completedTurns = nextTurn();
            lastTouched = now;
        }

        private record Turn(String message, BushReply reply) {}
    }
}
