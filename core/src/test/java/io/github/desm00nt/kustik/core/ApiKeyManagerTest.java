package io.github.desm00nt.kustik.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.github.desm00nt.kustik.core.ApiKeyManager.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyManagerTest {
    private static final String KEY = "atr_unit-test-not-a-real-key";
    private static final Consumer<String> MUST_NOT_RUN = ignored -> fail("Unexpected credential access");

    @Test
    void settingKeySavesBeforeActivationAndReturnsNoSecret() {
        List<String> order = new ArrayList<>();
        AtomicReference<String> saved = new AtomicReference<>();
        AtomicReference<String> active = new AtomicReference<>();
        var result = ApiKeyManager.set("  " + KEY + "  ", true, false,
                key -> { order.add("save"); saved.set(key); },
                key -> { order.add("activate"); active.set(key); });
        assertEquals(UPDATED, result);
        assertEquals(List.of("save", "activate"), order);
        assertEquals(KEY, saved.get());
        assertEquals(KEY, active.get());
        assertFalse(result.toString().contains(KEY));
    }

    @Test
    void unauthorizedRequestsCannotChangeOrReadSettings() {
        assertEquals(DENIED, ApiKeyManager.set(KEY, false, false, MUST_NOT_RUN, MUST_NOT_RUN));
        assertEquals(DENIED, ApiKeyManager.clear(false, false, MUST_NOT_RUN, MUST_NOT_RUN));
        // Do not disclose even the presence of an environment override to an unauthorized sender.
        assertEquals(DENIED, ApiKeyManager.set(KEY, false, true, MUST_NOT_RUN, MUST_NOT_RUN));
    }

    @Test
    void anEnvironmentKeyCannotBeSilentlyOverriddenOrReportedAsCleared() {
        assertEquals(ENVIRONMENT_OVERRIDE, ApiKeyManager.set(KEY, true, true, MUST_NOT_RUN, MUST_NOT_RUN));
        assertEquals(ENVIRONMENT_OVERRIDE, ApiKeyManager.clear(true, true, MUST_NOT_RUN, MUST_NOT_RUN));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", "atr_abc def", "\"atr_abc\"", "'atr_abc'", "`atr_abc`",
            "atr_abc\r\nInjected: true", "atr_abc\n", "\tatr_abc", "atr_abc\u0000",
            "atr_abc\u202E", "atr_abc\u200B", "atr_КЛЮЧ", "atr_a;other-command", "atr_a\\escaped"})
    void rejectsMalformedKeysWithoutPersistingOrActivating(String input) {
        assertEquals(INVALID_KEY, ApiKeyManager.set(input, true, false, MUST_NOT_RUN, MUST_NOT_RUN));
    }

    @Test
    void limitsLengthButDoesNotAssumeAProviderPrefix() {
        assertEquals(INVALID_KEY, ApiKeyManager.set("a".repeat(ApiKeyManager.MAX_KEY_LENGTH + 1), true, false,
                MUST_NOT_RUN, MUST_NOT_RUN));
        assertEquals(UPDATED, ApiKeyManager.set("a".repeat(ApiKeyManager.MAX_KEY_LENGTH), true, false, k -> {}, k -> {}));
        assertEquals(UPDATED, ApiKeyManager.set("other-provider_AZaz09._~+/=-", true, false, k -> {}, k -> {}));
    }

    @Test
    void clearingDisablesFutureCallsAndPersistsAnEmptyKey() {
        var original = settings(KEY);
        AtomicReference<ApiSettings> active = new AtomicReference<>(original);
        AtomicReference<String> stored = new AtomicReference<>(KEY);
        var result = ApiKeyManager.clear(true, false, stored::set, key -> active.set(active.get().withApiKey(key)));
        assertEquals(CLEARED, result);
        assertEquals("", stored.get());
        assertFalse(active.get().configured());
        assertTrue(original.configured()); // Immutable request snapshots are not modified in place.
    }

    @Test
    void saveFailureDoesNotReplaceLiveCredentialsOrLeakTheException() {
        AtomicReference<ApiSettings> active = new AtomicReference<>(settings("previous-test-key"));
        var result = ApiKeyManager.set(KEY, true, false, key -> {
            throw new IllegalStateException("Disk rejected " + key);
        }, key -> active.set(active.get().withApiKey(key)));
        assertEquals(SAVE_FAILED, result);
        assertEquals("previous-test-key", active.get().apiKey());
        assertFalse(result.toString().contains(KEY));
    }

    @Test
    void failedClearLeavesLiveCredentialsAlone() {
        assertEquals(SAVE_FAILED, ApiKeyManager.clear(true, false,
                key -> { throw new IllegalStateException("File is read-only"); }, MUST_NOT_RUN));
    }

    @Test
    void activationFailureIsNotReportedAsACompletedChangeOrFailedSave() {
        AtomicReference<String> stored = new AtomicReference<>();
        var result = ApiKeyManager.set(KEY, true, false, stored::set,
                key -> { throw new IllegalStateException("Activation failed for " + key); });
        assertEquals(APPLY_FAILED, result);
        assertEquals(KEY, stored.get());
        assertFalse(result.toString().contains(KEY));
    }

    private static ApiSettings settings(String key) {
        return new ApiSettings(URI.create("https://api.atria-asi.ai/v1/chat/completions"), key,
                "Atria-Dawn-Preview", 2048, Duration.ofSeconds(90), "none");
    }
}
