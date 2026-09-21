package io.github.desm00nt.kustik.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ApiSettingsTest {
    @ParameterizedTest
    @ValueSource(strings = {"https://api.atria-asi.ai/v1/chat/completions", "https://example.org/v1/chat/completions",
            "http://localhost:8123/v1/chat/completions", "http://127.0.0.1:8123/v1/chat/completions",
            "http://[::1]:8123/v1/chat/completions"})
    void supportsHttpsAndExplicitLocalTesting(String uri) {
        assertDoesNotThrow(() -> settings(uri, "test-key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.org/chat", "file:///etc/passwd", "ftp://example.org/chat", "/relative/path",
            "https://user:secret@example.org/chat", "https://example.org/chat?key=secret",
            "https://example.org/chat#secret", "http://localhost.evil.example/chat", "http://127.1.2.3/chat"})
    void neverSendsCredentialsOverPlaintextOrThroughAnAmbiguousUri(String uri) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> settings(uri, "test-key"));
        assertFalse(error.toString().contains("secret"));
    }

    @Test
    void emptyKeyDisablesRequestsAndDiagnosticsRedactTheKey() {
        assertFalse(settings("https://example.org/chat", " ").configured());
        ApiSettings config = settings("https://example.org/chat", "  never-log-this  ");
        assertTrue(config.configured());
        assertEquals("never-log-this", config.apiKey());
        assertFalse(config.toString().contains("never-log-this"));
    }

    @Test
    void rejectsHeaderInjectionWithoutEchoingIt() {
        String key = "secret\r\nX-Inject: yes";
        var error = assertThrows(IllegalArgumentException.class, () -> settings("https://example.org/chat", key));
        assertFalse(error.toString().contains("secret"));
    }

    @Test
    void rotatingOnlyTheKeyPreservesTheRestAndDoesNotMutateInFlightSnapshots() {
        ApiSettings before = settings("https://api.atria-asi.ai/v1/chat/completions", "old-test-key");
        ApiSettings after = before.withApiKey("new-test-key");
        assertEquals("old-test-key", before.apiKey());
        assertEquals("new-test-key", after.apiKey());
        assertEquals(before.endpoint(), after.endpoint());
        assertEquals(before.model(), after.model());
        assertEquals(before.maxTokens(), after.maxTokens());
        assertEquals(before.timeout(), after.timeout());
        assertEquals(before.reasoningEffort(), after.reasoningEffort());
        assertFalse(after.toString().contains(after.apiKey()));
        assertFalse(after.withApiKey("").configured());
    }

    private static ApiSettings settings(String endpoint, String key) {
        return new ApiSettings(URI.create(endpoint), key, "Atria-Dawn-Preview", 2048,
                Duration.ofSeconds(90), "none");
    }
}
