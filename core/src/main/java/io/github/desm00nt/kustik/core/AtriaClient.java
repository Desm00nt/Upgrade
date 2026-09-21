package io.github.desm00nt.kustik.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Called only on a bounded worker pool, never on Minecraft's server thread. */
public final class AtriaClient {
    public static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private final HttpClient http;

    public AtriaClient() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER) // Never forward a Bearer key to a redirect target.
                .build();
    }

    public BushReply chat(ApiSettings settings, List<ChatMessage> messages)
            throws ApiException, InterruptedException {
        if (!settings.configured()) {
            throw new ApiException(ApiException.Kind.AUTH);
        }
        HttpRequest request = HttpRequest.newBuilder(settings.endpoint())
                .timeout(settings.timeout())
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(settings, messages), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request,
                ignored -> new LimitedBodySubscriber(MAX_RESPONSE_BYTES));
        try {
            // Includes receiving the complete body, not just headers. No automatic retries / double billing.
            HttpResponse<byte[]> response = future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new ApiException(ApiException.Kind.AUTH);
            }
            if (status == 429) {
                throw new ApiException(ApiException.Kind.RATE_LIMIT);
            }
            if (status >= 500) {
                throw new ApiException(ApiException.Kind.UNAVAILABLE);
            }
            if (status < 200 || status >= 300) {
                throw new ApiException(ApiException.Kind.BAD_REQUEST);
            }
            return ReplyCodec.parseCompletion(new String(response.body(), StandardCharsets.UTF_8));
        } catch (TimeoutException e) {
            throw new ApiException(ApiException.Kind.TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof ApiException api) {
                    throw api;
                }
                if (cause instanceof HttpTimeoutException) {
                    throw new ApiException(ApiException.Kind.TIMEOUT);
                }
                cause = cause.getCause();
            }
            throw new ApiException(ApiException.Kind.NETWORK);
        } finally {
            // Also aborts the exchange when a player leaves or the server shuts down (thread interrupt).
            future.cancel(true);
        }
    }

    static String requestBody(ApiSettings settings, List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("max_tokens", settings.maxTokens());
        body.addProperty("stream", false);
        if (!settings.reasoningEffort().isEmpty()) {
            body.addProperty("reasoning_effort", settings.reasoningEffort());
        }
        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.role());
            entry.addProperty("content", message.content());
            array.add(entry);
        }
        body.add("messages", array);
        return body.toString();
    }
}
