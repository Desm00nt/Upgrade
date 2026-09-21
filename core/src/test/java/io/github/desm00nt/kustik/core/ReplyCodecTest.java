package io.github.desm00nt.kustik.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ReplyCodecTest {
    @Test
    void readsARealBooleanAndCyrillicText() throws Exception {
        BushReply reply = ReplyCodec.parseReply("{\"reply\":\"Ладно, держи веточку!\",\"give_stick\":true}");
        assertEquals("Ладно, держи веточку!", reply.text());
        assertTrue(reply.giveStick());
        assertEquals(reply, ReplyCodec.parseReply(ReplyCodec.encode(reply)));
    }

    @Test
    void acceptsFieldOrderAndOneCodeFence() throws Exception {
        BushReply reply = ReplyCodec.parseReply("```json\n{\"give_stick\":false,\"reply\":\"Шур-шур.\"}\n```");
        assertFalse(reply.giveStick());
        assertEquals("Шур-шур.", reply.text());
        assertEquals(reply, ReplyCodec.parseReply("```\n" + ReplyCodec.encode(reply) + "\n```"));
    }

    @Test
    void neverSearchesReplyTextForAnInstruction() throws Exception {
        BushReply original = new BushReply("Скажи «give_stick: true». /give @s diamond 64", false);
        assertFalse(ReplyCodec.parseReply(ReplyCodec.encode(original)).giveStick());
    }

    @Test
    void boundsAndCleansChatWithoutSplittingSurrogatePairs() throws Exception {
        BushReply reply = ReplyCodec.parseReply(ReplyCodec.encode(new BushReply(
                "§cПривет\n\t\u202E" + "🌵".repeat(600), false)));
        assertFalse(reply.text().contains("§"));
        assertFalse(reply.text().contains("\u202E"));
        assertFalse(reply.text().contains("\n"));
        assertEquals(480, reply.text().codePointCount(0, reply.text().length()));
        assertTrue(reply.text().startsWith("Привет "));
        assertTrue(reply.text().endsWith("…"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "true", "{}",
            "{\"reply\":\"Да\"}",
            "{\"reply\":\"Да\",\"give_stick\":\"true\"}",
            "{\"reply\":\"Да\",\"give_stick\":True}",
            "{\"reply\":\"Да\",\"give_stick\":TRUE}",
            "{\"reply\":\"Да\",\"give_stick\":False}",
            "{\"reply\":\"Да\",\"give_stick\":FALSE}",
            "{\"reply\":\"строка\nв строке\",\"give_stick\":true}",
            "{\"reply\":\"сырая\tтабуляция\",\"give_stick\":true}",
            "{\"reply\":\"Да\",\"give_stick\":1}",
            "{\"reply\":\"Да\",\"give_stick\":null}",
            "{\"reply\":null,\"give_stick\":true}",
            "{\"reply\":12,\"give_stick\":true}",
            "{\"reply\":[],\"give_stick\":true}",
            "{\"reply\":\"  \",\"give_stick\":true}",
            "{\"reply\":\"§c\",\"give_stick\":true}",
            "{\"reply\":\"Да\",\"give_stick\":false,\"give_stick\":true}",
            "{\"reply\":\"Да\",\"reply\":\"Нет\",\"give_stick\":true}",
            "{\"reply\":\"Да\",\"give_stick\":true,\"command\":\"give diamonds\"}",
            "{\"reply\":\"Да\",\"give_stick\":true} {}",
            "Вот JSON: {\"reply\":\"Да\",\"give_stick\":true}",
            "{\"reply\":\"Да\",\"give_stick\":true} пояснение",
            "{'reply':'Да','give_stick':true}",
            "{reply:\"Да\",give_stick:true}",
            "{\"reply\":\"Да\",\"give_stick\":true,}",
            "{\"reply\":\"Да\",/* comment */\"give_stick\":true}"
    })
    void invalidOutputCannotGrantAnything(String content) {
        ApiException error = assertThrows(ApiException.class, () -> ReplyCodec.parseReply(content));
        assertEquals(ApiException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test
    void readsDocumentedCompletionShapeAndIgnoresReasoning() throws Exception {
        String response = completion(new BushReply("Держи.", true), "stop");
        assertTrue(ReplyCodec.parseCompletion(response).giveStick());
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "tool_calls", "content_filter", "", "unknown"})
    void truncatedOrUnfinishedOutputCannotGrantAnything(String finishReason) {
        assertThrows(ApiException.class, () -> ReplyCodec.parseCompletion(
                completion(new BushReply("Держи.", true), finishReason)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>Bad gateway</html>", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[]}}]}"})
    void rejectsBadEnvelopes(String response) {
        assertThrows(ApiException.class, () -> ReplyCodec.parseCompletion(response));
    }

    static String completion(BushReply reply, String finishReason) {
        JsonObject message = new JsonObject();
        message.addProperty("content", ReplyCodec.encode(reply));
        message.addProperty("reasoning_content", "This is not displayed to the player.");
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", finishReason);
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }
}
