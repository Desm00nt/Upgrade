package io.github.desm00nt.kustik;

import io.github.desm00nt.kustik.core.ApiSettings;
import net.minecraftforge.common.ForgeConfigSpec;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** COMMON is intentional: Forge SERVER configs are synced to clients and must never contain secrets. */
public final class KustikConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    private static final ForgeConfigSpec.ConfigValue<String> ENDPOINT;
    private static final ForgeConfigSpec.ConfigValue<String> MODEL;
    private static final ForgeConfigSpec.ConfigValue<String> REASONING;
    private static final ForgeConfigSpec.IntValue MAX_TOKENS;
    private static final ForgeConfigSpec.IntValue TIMEOUT_SECONDS;
    private static final ForgeConfigSpec.IntValue RADIUS;
    private static final ForgeConfigSpec.IntValue MINIMUM_TURNS;
    private static final ForgeConfigSpec.IntValue HISTORY_TURNS;
    private static final ForgeConfigSpec.IntValue MEMORY_MINUTES;
    private static final ForgeConfigSpec.IntValue MAX_SESSIONS;
    private static final ForgeConfigSpec.IntValue COOLDOWN_SECONDS;
    private static final ForgeConfigSpec.IntValue MAX_CONCURRENT;
    private static final ForgeConfigSpec.IntValue REQUESTS_PER_MINUTE;
    private static final ForgeConfigSpec.IntValue MAX_MESSAGE_LENGTH;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Server-side Atria settings. File edits require a world/server restart; the key command applies immediately.",
                "Never share this file if it contains a real key. ATRIA_API_KEY takes precedence.").push("atria");
        API_KEY = builder.comment("Prefer ATRIA_API_KEY or editing this file. /kustikadmin key also saves here; command history may retain it.")
                .define("apiKey", "");
        ENDPOINT = builder.comment("Full Chat Completions URL. Only HTTPS, except HTTP on loopback for local testing.",
                        "Only use an endpoint you trust: it receives the Bearer key and conversation.")
                .define("endpoint", "https://api.atria-asi.ai/v1/chat/completions", value -> {
                    if (!(value instanceof String text)) {
                        return false;
                    }
                    try {
                        ApiSettings.validateEndpoint(URI.create(text));
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                });
        MODEL = builder.comment("Case-sensitive Atria model ID.").define("model", "Atria-Dawn-Preview");
        REASONING = builder.comment("none avoids long reasoning for short roleplay replies. Empty string omits the field.")
                .defineInList("reasoningEffort", "none", List.of("", "none", "low", "medium", "high"));
        MAX_TOKENS = builder.defineInRange("maxTokens", 2048, 128, 8192);
        TIMEOUT_SECONDS = builder.defineInRange("timeoutSeconds", 90, 5, 180);
        builder.pop().push("gameplay");
        RADIUS = builder.comment("Search radius in blocks; a clear line of sight is also required.")
                .defineInRange("radius", 4, 1, 8);
        MINIMUM_TURNS = builder.comment("Earliest successful reply that can grant a stick. Persuasion is still required.")
                .defineInRange("minimumTurns", 2, 1, 10);
        HISTORY_TURNS = builder.defineInRange("historyTurns", 6, 1, 12);
        MEMORY_MINUTES = builder.comment("Conversation memory is temporary; reward claims are saved with the world.")
                .defineInRange("memoryMinutes", 15, 1, 60);
        MAX_SESSIONS = builder.defineInRange("maxSessions", 512, 16, 4096);
        MAX_MESSAGE_LENGTH = builder.defineInRange("maxMessageLength", 240, 16, 1024);
        builder.pop().push("limits");
        COOLDOWN_SECONDS = builder.defineInRange("playerCooldownSeconds", 4, 1, 60);
        MAX_CONCURRENT = builder.defineInRange("maxConcurrentRequests", 4, 1, 8);
        REQUESTS_PER_MINUTE = builder.comment("Rolling global limit, shared by all players. Protects API quota.")
                .defineInRange("requestsPerMinute", 30, 1, 120);
        builder.pop();
        SPEC = builder.build();
    }

    private KustikConfig() {}

    public static boolean hasEnvironmentKey() {
        String key = System.getenv("ATRIA_API_KEY");
        return key != null && !key.isBlank();
    }

    /** Save on the server thread. Forge uses a synchronous, autosaving COMMON config (never synced to clients). */
    static void saveApiKey(String key) {
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("Config is not loaded");
        }
        String previous = API_KEY.get();
        try {
            API_KEY.set(key);
            API_KEY.save();
        } catch (RuntimeException ignored) {
            // Best-effort rollback of the file and Forge's cached value. Active requests are not changed on failure.
            try {
                API_KEY.set(previous);
                API_KEY.save();
            } catch (RuntimeException rollbackFailed) {
                API_KEY.clearCache();
            }
            throw new IllegalStateException("Could not save API key");
        }
    }

    public static Settings snapshot() {
        String environmentKey = System.getenv("ATRIA_API_KEY");
        String key = environmentKey != null && !environmentKey.isBlank() ? environmentKey : API_KEY.get();
        ApiSettings api = new ApiSettings(URI.create(ENDPOINT.get()), key, MODEL.get(), MAX_TOKENS.get(),
                Duration.ofSeconds(TIMEOUT_SECONDS.get()), REASONING.get());
        return new Settings(api, RADIUS.get(), MINIMUM_TURNS.get(), HISTORY_TURNS.get(),
                Duration.ofMinutes(MEMORY_MINUTES.get()), MAX_SESSIONS.get(), MAX_MESSAGE_LENGTH.get(),
                Duration.ofSeconds(COOLDOWN_SECONDS.get()), MAX_CONCURRENT.get(), REQUESTS_PER_MINUTE.get());
    }

    public record Settings(ApiSettings api, int radius, int minimumTurns, int historyTurns,
                           Duration memoryDuration, int maxSessions, int maxMessageLength,
                           Duration cooldown, int maxConcurrent, int requestsPerMinute) {
        Settings withApiKey(String key) {
            return new Settings(api.withApiKey(key), radius, minimumTurns, historyTurns, memoryDuration,
                    maxSessions, maxMessageLength, cooldown, maxConcurrent, requestsPerMinute);
        }
    }
}
