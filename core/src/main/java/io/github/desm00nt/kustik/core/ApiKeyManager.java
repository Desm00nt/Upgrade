package io.github.desm00nt.kustik.core;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Administrative credential changes: no network calls, no secret-bearing results or exception messages. */
public final class ApiKeyManager {
    public enum Result { UPDATED, CLEARED, DENIED, ENVIRONMENT_OVERRIDE, INVALID_KEY, SAVE_FAILED, APPLY_FAILED }

    public static final int MAX_KEY_LENGTH = 512;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]+");

    private ApiKeyManager() {}

    public static Result set(String input, boolean authorized, boolean environmentOverride,
                             Consumer<String> save, Consumer<String> activate) {
        if (!authorized) {
            return Result.DENIED;
        }
        if (environmentOverride) {
            return Result.ENVIRONMENT_OVERRIDE;
        }
        // Reject controls before stripping outer spaces. Never repair a potentially injected header.
        if (input == null || input.codePoints().anyMatch(Character::isISOControl)) {
            return Result.INVALID_KEY;
        }
        String key = input.strip();
        // Structural validation only. Do not guess the provider's prefix/length or claim the key is valid remotely.
        if (key.length() > MAX_KEY_LENGTH || !TOKEN.matcher(key).matches()) {
            return Result.INVALID_KEY;
        }
        return persistAndActivate(key, Result.UPDATED, save, activate);
    }

    public static Result clear(boolean authorized, boolean environmentOverride,
                               Consumer<String> save, Consumer<String> activate) {
        if (!authorized) {
            return Result.DENIED;
        }
        if (environmentOverride) {
            return Result.ENVIRONMENT_OVERRIDE;
        }
        return persistAndActivate("", Result.CLEARED, save, activate);
    }

    private static Result persistAndActivate(String key, Result success,
                                             Consumer<String> save, Consumer<String> activate) {
        try {
            save.accept(key);
        } catch (RuntimeException ignored) {
            // An I/O library exception may include the key. Never let it reach Minecraft's command error logger.
            return Result.SAVE_FAILED;
        }
        try {
            activate.accept(key);
        } catch (RuntimeException ignored) {
            // Saved but not active is different from a failed save: report that explicitly, without the cause.
            return Result.APPLY_FAILED;
        }
        return success;
    }
}
