package io.github.desm00nt.kustik.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

/** A reward is read only from a strict schema, never from a substring or natural-language reply. */
public final class ReplyCodec {
    public static final int MAX_REPLY_CODE_POINTS = 480;

    private ReplyCodec() {}

    public static BushReply parseCompletion(String response) throws ApiException {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (!"stop".equals(choice.get("finish_reason").getAsString())) {
                throw invalid(); // A truncated answer must not grant an item.
            }
            JsonElement content = choice.getAsJsonObject("message").get("content");
            if (content == null || !content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
                throw invalid();
            }
            return parseReply(content.getAsString());
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    public static BushReply parseReply(String content) throws ApiException {
        String json = content.strip();
        // Accept a single fenced JSON object, but never extract JSON out of arbitrary prose.
        if (json.startsWith("```json\n") && json.endsWith("```")) {
            json = json.substring(8, json.length() - 3).strip();
        } else if (json.startsWith("```\n") && json.endsWith("```")) {
            json = json.substring(4, json.length() - 3).strip();
        }
        validateTokens(json);
        String text = null;
        Boolean giveStick = null;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "reply" -> {
                        if (text != null || reader.peek() != JsonToken.STRING) {
                            throw invalid();
                        }
                        text = reader.nextString();
                    }
                    case "give_stick" -> {
                        if (giveStick != null || reader.peek() != JsonToken.BOOLEAN) {
                            throw invalid();
                        }
                        giveStick = reader.nextBoolean();
                    }
                    default -> throw invalid();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || text == null || giveStick == null) {
                throw invalid();
            }
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
        String clean = ChatText.clean(text, MAX_REPLY_CODE_POINTS);
        if (clean.isBlank()) {
            throw invalid();
        }
        return new BushReply(clean, giveStick);
    }

    /** Gson 2.10 accepts uppercase booleans and raw controls even with lenient=false. Reject those first. */
    private static void validateTokens(String json) throws ApiException {
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c < 0x20) {
                    throw invalid();
                }
                if (c == '"') {
                    inString = false;
                } else if (c == '\\') {
                    if (++i >= json.length()) {
                        throw invalid();
                    }
                    char escape = json.charAt(i);
                    if (escape == 'u') {
                        for (int digit = 0; digit < 4; digit++) {
                            if (++i >= json.length() || "0123456789abcdefABCDEF".indexOf(json.charAt(i)) < 0) {
                                throw invalid();
                            }
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) {
                        throw invalid();
                    }
                }
            } else if (c == '"') {
                inString = true;
            } else if ("{}:, \t\r\n".indexOf(c) >= 0) {
                // Structural correctness, member names and duplicate fields are checked by JsonReader below.
            } else if (json.startsWith("true", i)) {
                i += 3;
            } else if (json.startsWith("false", i)) {
                i += 4;
            } else {
                // This schema allows only string and boolean values, no arrays/numbers/null/nested objects.
                throw invalid();
            }
        }
        if (inString) {
            throw invalid();
        }
    }

    public static String encode(BushReply reply) {
        JsonObject json = new JsonObject();
        json.addProperty("reply", reply.text());
        json.addProperty("give_stick", reply.giveStick());
        return json.toString();
    }

    private static ApiException invalid() {
        return new ApiException(ApiException.Kind.INVALID_RESPONSE);
    }
}
