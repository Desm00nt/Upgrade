package io.github.desm00nt.kustik;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import io.github.desm00nt.kustik.core.ApiSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Real Forge / NightConfig persistence checks; require the full Minecraft/Forge build classpath. */
class KustikConfigTest {
    @TempDir
    Path directory;
    private CommentedFileConfig config;

    @AfterEach
    void unloadConfig() {
        KustikConfig.SPEC.setConfig(null);
        if (config != null) {
            config.close();
        }
    }

    @Test
    void settingKeyPersistsItForTheNextStart() {
        Path path = loadConfig();
        KustikConfig.saveApiKey("atr_test-not-a-real-key");
        try (CommentedFileConfig reloaded = CommentedFileConfig.of(path)) {
            reloaded.load();
            assertEquals("atr_test-not-a-real-key", reloaded.<String>get("atria.apiKey"));
            assertEquals("Atria-Dawn-Preview", reloaded.<String>get("atria.model"));
        }
    }

    @Test
    void clearingOnlyChangesTheKey() {
        Path path = loadConfig();
        config.set("gameplay.radius", 7);
        KustikConfig.saveApiKey("atr_test-not-a-real-key");
        KustikConfig.saveApiKey("");
        try (CommentedFileConfig reloaded = CommentedFileConfig.of(path)) {
            reloaded.load();
            assertEquals("", reloaded.<String>get("atria.apiKey"));
            assertEquals(7, reloaded.getInt("gameplay.radius"));
        }
    }

    @Test
    void cannotClaimToSaveBeforeForgeLoadsTheConfig() {
        KustikConfig.SPEC.setConfig(null);
        var error = assertThrows(IllegalStateException.class,
                () -> KustikConfig.saveApiKey("test-secret-not-to-echo"));
        assertFalse(error.toString().contains("test-secret-not-to-echo"));
    }

    @Test
    void hotUpdatePreservesAllGameAndQuotaSettings() {
        var api = new ApiSettings(URI.create("https://api.atria-asi.ai/v1/chat/completions"), "old-test-key",
                "Atria-Dawn-Preview", 2048, Duration.ofSeconds(90), "none");
        var before = new KustikConfig.Settings(api, 4, 2, 6, Duration.ofMinutes(15), 512, 240,
                Duration.ofSeconds(4), 4, 30);
        var after = before.withApiKey("new-test-key");
        assertEquals("old-test-key", before.api().apiKey());
        assertEquals("new-test-key", after.api().apiKey());
        assertEquals(before, after.withApiKey("old-test-key"));
    }

    private Path loadConfig() {
        Path path = directory.resolve("kustik-common.toml");
        config = CommentedFileConfig.builder(path).sync().autosave().build();
        config.load();
        KustikConfig.SPEC.setConfig(config);
        return path;
    }
}
