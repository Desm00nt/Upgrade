package io.github.desm00nt.kustik.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatTextTest {
    @Test
    void stripsUnsafeControlsAndNormalizesWhitespace() {
        assertEquals("Привет кустик", ChatText.clean("  §cПривет\n\tкустик\u0000\u202e  ", 80));
        assertEquals("", ChatText.clean("§a\u200b\n ", 80));
        assertEquals("hi there", ChatText.clean("hi\u00a0there", 80));
    }

    @Test
    void preservesLiteralTextNotChatComponents() {
        String text = "{\"clickEvent\":\"run_command\"} /op Somebody";
        assertEquals(text, ChatText.clean(text, 100));
    }

    @Test
    void handlesCodePointBoundariesAndTinyLimits() {
        assertEquals("🌵…", ChatText.clean("🌵🌵🌵", 2));
        assertEquals("…", ChatText.clean("hello", 1));
        assertEquals("🌵", ChatText.clean("🌵", 1));
    }
}
