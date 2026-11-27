package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import com.tianmu.agent.runtime.control.ModelBudget;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4B-1 provider protocol contract checks for the controller client against a localhost HTTP
 * double (siliconflow-shaped endpoint). Synthetic credential only; no real provider, no auth.
 *
 * Contract facts pinned here: native tool pairing across turns, required tool_choice, streaming
 * termination, invalid structured JSON refusal, missing usage keeps the token reservation, and the
 * thinking field follows the declared protocol (auto classifies only siliconflow.cn / deepseek.com)
 * instead of the model name — the host mappings themselves are pinned in
 * EvaluationPreflightDriverTest, the live-compat risk stays reported by the offline admission check
 * as PROTOCOL_FIELD_UNVERIFIED.
 */
class ProviderProtocolContractTest {
    private static final String KEY = "synthetic-test-key";
    private static final String MODEL = "deepseek-test";
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private int port;
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    final AtomicReference<String> mode = new AtomicReference<>("final");
    DeepSeekClient client;
    CallControl control;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            received.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            String body;
            int status = 200;
            switch (mode.get()) {
                case "tool_call" -> body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null," +
                        "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"analyze_comments\"," +
                        "\"arguments\":\"{\\\"dataset_key\\\":\\\"ysjf-canon\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]," +
                        "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4},\"model\":\"DeepSeek-V4.1-Flash-1017\"}";
                case "wrong_tool" -> body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null," +
                        "\"tool_calls\":[{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"other_tool\"," +
                        "\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
                case "stream" -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.sendResponseHeaders(200, 0);
                    try (var out = exchange.getResponseBody()) {
                        out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"部分\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                        out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回答\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                        out.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":6}}\n\n".getBytes(StandardCharsets.UTF_8));
                        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } finally { exchange.close(); }
                    return;
                }
                case "bad_json" -> body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{bad json\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
                case "no_usage" -> body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}";
                case "echo_credential" -> body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}],\"echo_authorization\":\"Bearer " + KEY + "\"}";
                case "401" -> { status = 401; body = "{\"error\":\"unauthorized\"}"; }
                case "402" -> { status = 402; body = "{\"error\":\"quota\"}"; }
                case "429" -> { status = 429; body = "{\"error\":\"rate\"}"; }
                case "500" -> { status = 500; body = "{\"error\":\"boom\"}"; }
                case "400" -> { status = 400; body = "{\"error\":\"bad request\"}"; }
                default -> body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5},\"model\":\"DeepSeek-V4.1-Flash-1017\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        port = server.getAddress().getPort();
        client = new DeepSeekClient(new DeepSeekProperties(URI.create("http://127.0.0.1:" + port), KEY,
                MODEL, MODEL, Duration.ofSeconds(1), 384, 1024), json);
        control = new CallControl(Instant.now().plusSeconds(10), new CancellationToken(),
                new ModelBudget(0, 8192), () -> {});
    }

    @AfterEach void stop() { server.stop(0); mode.set("final"); received.clear(); authorizationHeaders.clear(); }

    List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools() {
        return List.of(new com.tianmu.agent.core.AgentTypes.ToolDefinition("analyze_comments", "read comments",
                Map.of("type", "object", "properties", Map.of())));
    }

    @Test void nativeToolPairingSurvivesMultiTurnAndReturnsToolMessage() {
        mode.set("tool_call");
        var message = client.completeHarness(MODEL, List.of(Map.of("role", "user", "content", "分析评论")),
                tools(), 1024, control);
        assertEquals("analyze_comments", message.path("tool_calls").path(0).path("function").path("name").asText());
        var followUp = List.of(
                Map.<String, Object>of("role", "user", "content", "分析评论"),
                Map.<String, Object>of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                        "id", "c1", "type", "function",
                        "function", Map.of("name", "analyze_comments", "arguments", "{\"dataset_key\":\"ysjf-canon\"}")))),
                Map.<String, Object>of("role", "tool", "tool_call_id", "c1", "content", "{\"rows\":58}"));
        mode.set("final");
        var result = client.completeHarness(MODEL, followUp, tools(), 1024, control);
        // With native tools the client returns the raw assistant message; the content string is the JSON payload.
        assertEquals("{\"ok\":true}", result.path("content").asText());
        JsonNode second = received.get(1);
        assertTrue(received.size() >= 2, () -> "expected two dispatches, got " + received.size());
        assertEquals("tool", second.path("messages").path(2).path("role").asText(),
                () -> "second request body: " + second);
        assertEquals("assistant", second.path("messages").path(1).path("role").asText(),
                () -> "second request body: " + second);
        assertTrue(second.path("messages").path(1).path("tool_calls").isArray(),
                () -> "assistant tool_calls message must be replayed verbatim; body: " + second);
    }

    @Test void requiredToolChoiceSentAndWrongToolRefused() {
        // A named required tool is sent as an object choice naming that tool, and a provider that
        // picks a different tool is refused.
        mode.set("wrong_tool");
        var failure = assertThrows(AgentException.class, () ->
                client.completeHarness(MODEL, List.of(Map.of("role", "user", "content", "x")), tools(), 1024, control, "analyze_comments"));
        assertEquals("INVALID_DECISION", failure.code());
        var choice = received.get(0).path("tool_choice");
        assertEquals("analyze_comments", choice.path("function").path("name").asText(),
                () -> "tool_choice body: " + received.get(0));
        // The bare "required" string names no tool and is accepted when the provider calls any tool.
        mode.set("tool_call");
        var message = client.completeHarness(MODEL, List.of(Map.of("role", "user", "content", "x")), tools(), 1024, control, "required");
        assertEquals("required", received.get(1).path("tool_choice").asText());
        assertTrue(message.path("tool_calls").isArray());
    }

    @Test void emptyToolsWithRequiredResourceRefusedBeforeDispatch() {
        int before = received.size();
        assertThrows(AgentException.class, () ->
                client.completeHarness(MODEL, List.of(Map.of("role", "user", "content", "x")), List.of(), 1024, control, "analyze_comments"));
        assertEquals(before, received.size(), "refusal must happen before any network dispatch");
    }

    @Test void streamingTerminatesWithFinishReasonAndUsage() {
        mode.set("stream");
        var chunks = new CopyOnWriteArrayList<String>();
        var streaming = control.withTextOutput(chunks::add);
        var result = client.completeHarness(MODEL, List.of(Map.of("role", "user", "content", "x")), tools(), 1024, streaming);
        assertEquals("部分回答", result.path("content").asText());
        assertEquals(List.of("部分", "回答"), List.copyOf(chunks));
        assertEquals(true, received.get(0).path("stream").asBoolean());
        assertEquals(true, received.get(0).path("stream_options").path("include_usage").asBoolean());
    }

    @Test void invalidStructuredJsonRefused() {
        mode.set("bad_json");
        var failure = assertThrows(AgentException.class, () ->
                client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true));
        assertEquals("MODEL_ERROR", failure.code());
    }

    @Test void missingUsageKeepsTokenReservationUnsettled() {
        mode.set("no_usage");
        var result = client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true);
        assertEquals(true, result.path("ok").asBoolean());
        assertEquals(1L, control.budget().attributes().get("budget.unsettled_calls"),
                "usage must not be counted as zero when the provider omits it");
    }

    @Test void credentialIsSentOnWireButNeverSurfacesInResultsOrErrors() {
        mode.set("final");
        client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true);
        assertEquals("Bearer " + KEY, authorizationHeaders.get(0));
        mode.set("echo_credential");
        var result = client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true);
        assertFalse(result.toString().contains(KEY), "provider echo of the credential must not surface in the result");
        mode.set("500");
        var failure = assertThrows(AgentException.class, () ->
                client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true));
        assertFalse(failure.getMessage().contains(KEY), "error messages must not carry the credential");
    }

    @Test void providerErrorStatusesMapToDistinctControlledFailures() {
        record Case(String mode, String code) {}
        // The 402 case runs last: it arms a five-minute quota cooldown, which is itself real client
        // behavior and must not pollute the other status mappings.
        for (var c : new Case[]{new Case("401", "MODEL_AUTH_FAILED"),
                new Case("429", "MODEL_UNAVAILABLE"), new Case("500", "MODEL_UNAVAILABLE"),
                new Case("400", "MODEL_REQUEST_REJECTED"), new Case("402", "MODEL_QUOTA_EXHAUSTED")}) {
            mode.set(c.mode());
            var failure = assertThrows(AgentException.class, () ->
                    client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true), c.mode());
            assertEquals(c.code(), failure.code(), c.mode());
        }
    }

    @Test void anUndeclaredHostSendsNeitherThinkingField() {
        // Stage 4B-2A: the thinking field follows the declared protocol (auto classifies only the two
        // public hosts). An undeclared loopback double sends neither family's field, so a rehearsal
        // can never quietly exercise a different wire format than the one it declares. The two host
        // mappings are pinned in EvaluationPreflightDriverTest against an explicit selection.
        mode.set("final");
        client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true);
        var body = received.get(0);
        assertFalse(body.has("thinking"), "an undeclared host must not receive the thinking object");
        assertFalse(body.has("enable_thinking"), "an undeclared host must not receive enable_thinking");
        assertEquals(0, body.path("temperature").asInt());
        assertTrue(body.has("max_tokens"));

        var silent = new DeepSeekClient(new DeepSeekProperties(URI.create("http://127.0.0.1:" + port), KEY,
                MODEL, MODEL, Duration.ofSeconds(1), 384, 1024, MODEL, false, "high", "low", 16384, "none"), json);
        silent.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true);
        var silentBody = received.get(1);
        assertFalse(silentBody.has("thinking"));
        assertFalse(silentBody.has("enable_thinking"));
    }
}
