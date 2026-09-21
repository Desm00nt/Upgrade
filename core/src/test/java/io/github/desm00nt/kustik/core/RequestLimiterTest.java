package io.github.desm00nt.kustik.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.github.desm00nt.kustik.core.RequestLimiter.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestLimiterTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void onePendingRequestPerPlayerEvenAfterCooldown() {
        var limiter = new RequestLimiter(Duration.ofSeconds(4), 4, 30);
        assertEquals(ALLOWED, limiter.acquire(a, 0));
        assertEquals(ALREADY_PENDING, limiter.acquire(a, seconds(10)));
        assertEquals(1, limiter.activeCount());
        limiter.release(a);
        assertEquals(ALLOWED, limiter.acquire(a, seconds(10)));
    }

    @Test
    void cooldownSurvivesReleaseAndExpiresExactlyAtBoundary() {
        var limiter = new RequestLimiter(Duration.ofSeconds(4), 4, 30);
        limiter.acquire(a, 0);
        limiter.release(a);
        assertEquals(PLAYER_COOLDOWN, limiter.acquire(a, seconds(4) - 1));
        assertEquals(ALLOWED, limiter.acquire(a, seconds(4)));
    }

    @Test
    void globalConcurrencyIsBoundedAndRejectedAttemptsAreNotCharged() {
        var limiter = new RequestLimiter(Duration.ZERO, 1, 2);
        assertEquals(ALLOWED, limiter.acquire(a, 0));
        for (int i = 1; i <= 10; i++) {
            assertEquals(SERVER_BUSY, limiter.acquire(b, i));
        }
        limiter.release(a);
        assertEquals(ALLOWED, limiter.acquire(b, 11));
        assertEquals(1, limiter.activeCount());
    }

    @Test
    void globalRollingWindowCannotBeBypassedWithAnotherPlayer() {
        var limiter = new RequestLimiter(Duration.ZERO, 4, 2);
        assertEquals(ALLOWED, limiter.acquire(a, 0));
        limiter.release(a);
        assertEquals(ALLOWED, limiter.acquire(b, seconds(1)));
        limiter.release(b);
        assertEquals(GLOBAL_RATE_LIMIT, limiter.acquire(UUID.randomUUID(), seconds(59)));
        assertEquals(ALLOWED, limiter.acquire(a, seconds(60)));
        assertEquals(GLOBAL_RATE_LIMIT, limiter.acquire(b, seconds(60)));
        assertEquals(ALLOWED, limiter.acquire(b, seconds(61)));
    }

    @Test
    void doubleReleaseCannotCreateExtraCapacity() {
        var limiter = new RequestLimiter(Duration.ZERO, 1, 30);
        limiter.acquire(a, 0);
        limiter.release(a);
        limiter.release(a);
        assertEquals(0, limiter.activeCount());
        assertEquals(ALLOWED, limiter.acquire(b, 1));
        assertEquals(SERVER_BUSY, limiter.acquire(a, 2));
    }

    private static long seconds(long seconds) {
        return Duration.ofSeconds(seconds).toNanos();
    }
}
