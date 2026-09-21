package io.github.desm00nt.kustik.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class AtriaClientTest {
    private HttpServer server;
    private ExecutorService serverWorkers;
    private static final String FAKE_KEY = "test-key-not-a-real-secret";
    private final List<ChatMessage> messages = List.of(new ChatMessage("user", "Привет, кустик!"));

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (serverWorkers != null) {
            serverWorkers.shutdownNow();
        }
    }

    @Test
    void postsUtf8BearerAuthorizationAndTheDocumentedModel() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<JsonObject> body = new AtomicReference<>();
        ApiSettings settings = start(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, success());
        }, Duration.ofSeconds(3));
        BushReply reply = new AtriaClient().chat(settings, messages);
        assertEquals("Ладно, держи веточку!", reply.text());
        assertTrue(reply.giveStick());
        assertEquals("POST", method.get());
        assertEquals("Bearer " + FAKE_KEY, auth.get());
        assertEquals("application/json; charset=utf-8", contentType.get());
        assertEquals("Atria-Dawn-Preview", body.get().get("model").getAsString());
        assertFalse(body.get().get("stream").getAsBoolean());
        assertEquals(2048, body.get().get("max_tokens").getAsInt());
        assertEquals("none", body.get().get("reasoning_effort").getAsString());
        var user = body.get().getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("user", user.get("role").getAsString());
        assertEquals("Привет, кустик!", user.get("content").getAsString());
        assertFalse(body.get().toString().contains(FAKE_KEY));
        assertFalse(body.get().has("tools"));
        assertFalse(body.get().has("response_format")); // No undocumented structured-output API dependency.
    }

    @ParameterizedTest
    @CsvSource({"401, AUTH", "403, AUTH", "429, RATE_LIMIT", "500, UNAVAILABLE", "503, UNAVAILABLE", "400, BAD_REQUEST"})
    void handlesUpstreamErrorsWithoutLeakingBodiesOrRetrying(int status, ApiException.Kind expected) throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ApiSettings settings = start(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, status, "Private body with " + FAKE_KEY);
        }, Duration.ofSeconds(3));
        ApiException error = assertThrows(ApiException.class, () -> new AtriaClient().chat(settings, messages));
        assertEquals(expected, error.kind());
        assertFalse(error.toString().contains(FAKE_KEY));
        assertFalse(error.playerMessage().contains(FAKE_KEY));
        assertNull(error.getCause());
        assertEquals(1, attempts.get());
    }

    @Test
    void refusesRedirectsRatherThanForwardingCredentials() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        ApiSettings settings = start(exchange -> {
            exchange.getResponseHeaders().add("Location", "/other");
            respond(exchange, 302, "redirect");
        }, Duration.ofSeconds(3));
        server.createContext("/other", exchange -> {
            redirected.incrementAndGet();
            respond(exchange, 200, success());
        });
        ApiException error = assertThrows(ApiException.class, () -> new AtriaClient().chat(settings, messages));
        assertEquals(ApiException.Kind.BAD_REQUEST, error.kind());
        assertEquals(0, redirected.get());
    }

    @Test
    void malformedResponseFailsClosed() throws Exception {
        ApiSettings settings = start(exchange -> respond(exchange, 200, "{\"choices\":[]}"), Duration.ofSeconds(3));
        assertEquals(ApiException.Kind.INVALID_RESPONSE,
                assertThrows(ApiException.class, () -> new AtriaClient().chat(settings, messages)).kind());
    }

    @Test
    void capsActualBytesEvenWithoutContentLength() throws Exception {
        ApiSettings settings = start(exchange -> {
            exchange.sendResponseHeaders(200, 0); // Chunked, no Content-Length.
            try (var body = exchange.getResponseBody()) {
                body.write(new byte[AtriaClient.MAX_RESPONSE_BYTES + 1]);
            }
        }, Duration.ofSeconds(3));
        ApiException error = assertThrows(ApiException.class, () -> new AtriaClient().chat(settings, messages));
        assertEquals(ApiException.Kind.TOO_LARGE, error.kind());
    }

    @Test
    void deadlineIncludesSlowResponseBodyNotJustHeaders() throws Exception {
        ApiSettings settings = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(' ');
                body.flush();
                Thread.sleep(2000);
                body.write(success().getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofMillis(400));
        long start = System.nanoTime();
        ApiException error = assertThrows(ApiException.class, () -> new AtriaClient().chat(settings, messages));
        assertEquals(ApiException.Kind.TIMEOUT, error.kind());
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
    }

    @Test
    void interruptCancelsInFlightRequestSoAWorkerCanBeReused() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        ApiSettings settings = start(exchange -> {
            received.countDown();
            try {
                Thread.sleep(4000);
                respond(exchange, 200, success());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Duration.ofSeconds(5));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                new AtriaClient().chat(settings, messages);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(1500);
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
        } finally {
            worker.interrupt();
        }
    }

    @Test
    void theSameClientUsesTheRotatedKeyInTheNextAuthorizationHeader() throws Exception {
        var authorization = new java.util.concurrent.CopyOnWriteArrayList<String>();
        ApiSettings before = start(exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, success());
        }, Duration.ofSeconds(3));
        AtriaClient client = new AtriaClient();
        client.chat(before, messages);
        client.chat(before.withApiKey("rotated-test-key"), messages);
        assertEquals(List.of("Bearer " + FAKE_KEY, "Bearer rotated-test-key"), authorization);
        assertEquals(FAKE_KEY, before.apiKey());
    }

    @Test
    void omittedReasoningEffortIsNotSent() {
        var settings = new ApiSettings(URI.create("https://example.org/chat"), FAKE_KEY, "Atria-Dawn-Preview",
                2048, Duration.ofSeconds(3), "");
        assertFalse(JsonParser.parseString(AtriaClient.requestBody(settings, messages)).getAsJsonObject()
                .has("reasoning_effort"));
    }

    @Test
    void missingKeyNeverContactsTheEndpoint() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ApiSettings configured = start(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, success());
        }, Duration.ofSeconds(3));
        var empty = new ApiSettings(configured.endpoint(), "", configured.model(), 2048, configured.timeout(), "none");
        assertEquals(ApiException.Kind.AUTH,
                assertThrows(ApiException.class, () -> new AtriaClient().chat(empty, messages)).kind());
        assertEquals(0, requests.get());
    }

    private ApiSettings start(HttpHandler handler, Duration timeout) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "kustik-test-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverWorkers);
        server.createContext("/v1/chat/completions", handler);
        server.start();
        return new ApiSettings(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
                FAKE_KEY, "Atria-Dawn-Preview", 2048, timeout, "none");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String success() {
        return ReplyCodecTest.completion(new BushReply("Ладно, держи веточку!", true), "stop");
    }
}
