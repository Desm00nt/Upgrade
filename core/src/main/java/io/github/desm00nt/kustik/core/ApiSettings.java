package io.github.desm00nt.kustik.core;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable request settings. Never expose the API key through toString(). */
public record ApiSettings(URI endpoint, String apiKey, String model,
                          int maxTokens, Duration timeout, String reasoningEffort) {
    public ApiSettings {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(apiKey);
        Objects.requireNonNull(model);
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(reasoningEffort);
        validateEndpoint(endpoint);
        apiKey = apiKey.strip();
        model = model.strip();
        if (apiKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid characters in API key");
        }
        if (model.isEmpty() || model.length() > 128 || maxTokens < 1 || maxTokens > 65536
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(3)) > 0) {
            throw new IllegalArgumentException("Invalid API settings");
        }
        if (!Set.of("", "none", "low", "medium", "high").contains(reasoningEffort)) {
            throw new IllegalArgumentException("Invalid reasoning effort");
        }
    }

    /** New immutable snapshot: in-flight callers never read a half-updated credential. */
    public ApiSettings withApiKey(String key) {
        return new ApiSettings(endpoint, key, model, maxTokens, timeout, reasoningEffort);
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    public static void validateEndpoint(URI uri) {
        String host = uri.getHost();
        String scheme = uri.getScheme();
        boolean local = host != null && Set.of("localhost", "127.0.0.1", "[::1]", "::1")
                .contains(host.toLowerCase(Locale.ROOT));
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || !("https".equalsIgnoreCase(scheme) || (local && "http".equalsIgnoreCase(scheme)))) {
            // Do not include the URI: a misconfigured URI might contain credentials.
            throw new IllegalArgumentException("API endpoint must use HTTPS (HTTP allowed only on loopback)");
        }
    }

    @Override
    public String toString() {
        return "ApiSettings[credentials=REDACTED]";
    }
}
