package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.evaluation.admission.ProviderHttpTransport;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "eval.canon.slow-controller-smoke", matches = "true")
class EvaluationSlowControllerReplyTest {
    private static final String MODEL = "deepseek-ai/DeepSeek-V4.1-Flash";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] REPLY = (
            "data: {\"choices\":[{\"delta\":{\"content\":\"loopback reply\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);

    @Test
    void delayOfThirtyFiveSecondsFitsTheWiderControllerDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicInteger();
        var listeners = Executors.newCachedThreadPool();
        var callers = Executors.newFixedThreadPool(2);
        try {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).build();
            server.setExecutor(listeners);
            server.createContext("/chat/completions", exchange -> {
                try {
                    var request = JSON.readTree(exchange.getRequestBody());
                    if (!request.path("stream").asBoolean()) throw new IOException("Expected streaming request");
                    received.incrementAndGet();
                    TimeUnit.SECONDS.sleep(35);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, REPLY.length);
                    exchange.getResponseBody().write(REPLY);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // The short-deadline client may close the socket before this reply.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var settings = new DeepSeekProperties(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "loopback-smoke-only", MODEL, MODEL, Duration.ofSeconds(3),
                    384, 3072, MODEL, false, "high", "low", 16384, "siliconflow");
            var client = new DeepSeekClient(settings, JSON, ContextPolicy.defaults(), null,
                    ProviderHttpTransport.using(http));
            var shortCall = callers.submit(() -> call(client, 30));
            var longCall = callers.submit(() -> call(client, 90));
            var shortResult = shortCall.get(40, TimeUnit.SECONDS);
            var longResult = longCall.get(10, TimeUnit.SECONDS);
            System.out.println("30 second limit: " + shortResult);
            System.out.println("90 second limit: " + longResult);
            assertEquals("MODEL_CALL_TIMEOUT", shortResult.value());
            assertEquals("loopback reply", longResult.value());
            assertTrue(shortResult.elapsedMillis() >= 29_000 && shortResult.elapsedMillis() < 34_000);
            assertTrue(longResult.elapsedMillis() >= 34_000 && longResult.elapsedMillis() < 45_000);
            assertEquals(2, received.get());
        } finally {
            server.stop(0);
            callers.shutdownNow();
            listeners.shutdownNow();
        }
    }

    private static Observation call(DeepSeekClient client, int seconds) {
        var control = new CallControl(Instant.now().plusSeconds(seconds), new CancellationToken())
                .withTextOutput(delta -> {});
        long started = System.nanoTime();
        String value;
        try {
            value = client.completeHarness(MODEL,
                    List.of(Map.of("role", "user", "content", "local timeout fixture")),
                    List.of(), 3072, control).path("content").asText();
        } catch (AgentException failure) {
            value = failure.code();
        }
        return new Observation(value, (System.nanoTime() - started) / 1_000_000);
    }

    private record Observation(String value, long elapsedMillis) {}
}
