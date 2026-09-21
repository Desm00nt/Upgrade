package io.github.desm00nt.kustik.core;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Uses monotonic time. Rejected commands never consume the global API budget. Server thread only. */
public final class RequestLimiter {
    public enum Result { ALLOWED, ALREADY_PENDING, PLAYER_COOLDOWN, SERVER_BUSY, GLOBAL_RATE_LIMIT }
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private final long cooldownNanos;
    private final int maxConcurrent;
    private final int requestsPerMinute;
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

    public RequestLimiter(Duration cooldown, int maxConcurrent, int requestsPerMinute) {
        if (cooldown.isNegative() || maxConcurrent < 1 || requestsPerMinute < 1) {
            throw new IllegalArgumentException("Invalid request limits");
        }
        this.cooldownNanos = cooldown.toNanos();
        this.maxConcurrent = maxConcurrent;
        this.requestsPerMinute = requestsPerMinute;
    }

    public Result acquire(UUID player, long now) {
        prune(now);
        if (active.contains(player)) {
            return Result.ALREADY_PENDING;
        }
        if (lastRequest.containsKey(player)) {
            return Result.PLAYER_COOLDOWN;
        }
        if (active.size() >= maxConcurrent) {
            return Result.SERVER_BUSY;
        }
        if (recent.size() >= requestsPerMinute) {
            return Result.GLOBAL_RATE_LIMIT;
        }
        active.add(player);
        lastRequest.put(player, now);
        recent.addLast(now);
        return Result.ALLOWED;
    }

    public void release(UUID player) {
        active.remove(player);
    }

    public void prune(long now) {
        lastRequest.values().removeIf(time -> now - time >= cooldownNanos);
        while (!recent.isEmpty() && now - recent.peekFirst() >= WINDOW_NANOS) {
            recent.removeFirst();
        }
    }

    public int activeCount() {
        return active.size();
    }
}
