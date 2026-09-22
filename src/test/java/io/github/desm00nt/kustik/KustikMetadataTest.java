package io.github.desm00nt.kustik;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KustikMetadataTest {
    @Test
    void processedDescriptorAcceptsForge4740AndHasNoUnexpandedBuildProperties() throws IOException {
        var descriptors = getClass().getClassLoader().getResources("META-INF/mods.toml");
        int found = 0;
        while (descriptors.hasMoreElements()) {
            String text;
            try (var stream = descriptors.nextElement().openStream()) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            UnmodifiableConfig descriptor = new TomlParser().parse(text);
            List<UnmodifiableConfig> mods = descriptor.getOrElse("mods", List.of());
            if (mods.stream().noneMatch(mod -> "kustik".equals(mod.get("modId")))) {
                continue;
            }
            found++;
            assertFalse(text.contains("${"), "Build properties must be expanded in the distributed descriptor");
            List<UnmodifiableConfig> dependencies = descriptor.get("dependencies.kustik");
            UnmodifiableConfig forge = dependencies.stream()
                    .filter(dep -> "forge".equals(dep.get("modId"))).findFirst().orElseThrow();
            UnmodifiableConfig minecraft = dependencies.stream()
                    .filter(dep -> "minecraft".equals(dep.get("modId"))).findFirst().orElseThrow();
            assertEquals("[47.4.0,48)", forge.<String>get("versionRange"));
            assertEquals("[1.20.1,1.20.2)", minecraft.<String>get("versionRange"));
        }
        assertEquals(1, found, "Expected exactly one processed Kustik descriptor on the test classpath");
    }
}
