package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Batch-4 loopback double for the three paid exits of the video-grounded entry. Roles are decided
 * by the REQUEST SHAPE, never by the model name: the registered vision contract has its own path,
 * the controller always carries a tool catalogue, and the analysis call never does. That keeps a
 * controller dispatch from being counted as an independent analysis.
 *
 * <p>Supplier responses only: every client, fee reservation and parser is the real class.
 */
final class EvaluationVideoCanonLoopback {
    static final String MARKER = "CANON-FRAME-SUMMARY";
    static final String CONTROLLER_A = "deepseek-ai/DeepSeek-V4.1-Flash";
    /** The registered B request name is V4 **Pro** — never `V4.1-Pro`. Batch 4/5 labelled the
     *  double with a name the provider does not document; batch 6 pins the real one so an
     *  accidental "V4.1 Pro" can only ever be a refused decoy. */
    static final String CONTROLLER_B = "deepseek-ai/DeepSeek-V4-Pro";
    /** Pinned topic-analysis role. Default keeps batch 4 reproducible; batch 5 runs it as the
     *  V4.1 Flash model, i.e. the SAME request name as controller A — which is exactly why the
     *  role must be proven from the request shape and never from the model string. */
    static final String ANALYSIS = System.getProperty("eval.analysis.model",
            "deepseek-ai/DeepSeek-V3.2-Exp");
    static final String VISION = "MiniMax-M3";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer SERVER;
    static final String BASE_URL;

    private static final List<Map<String, Object>> WINDOW = new CopyOnWriteArrayList<>();
    private static volatile boolean recording;
    private static final java.util.concurrent.atomic.AtomicInteger RECEIVED =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Test-only latency knob: the live-cancel case must have an in-flight supplier call to
     *  interrupt, otherwise "cancel" would race a run that already finished. Zero by default. */
    private static volatile long responseDelayMs;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        SERVER.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] raw = exchange.getRequestBody().readAllBytes();
            RECEIVED.incrementAndGet();
            String body;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", WINDOW.size());
            if (!path.equals("/chat/completions") && !path.equals("/v1/chat/completions")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            try {
                body = EvaluationCanonResponseScript.respond(path, raw, entry, ANALYSIS);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            long delay = responseDelayMs;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            if (recording) WINDOW.add(entry);
        });
        SERVER.start();
        BASE_URL = "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    private EvaluationVideoCanonLoopback() {}

    static void beginWindow() {
        WINDOW.clear();
        RECEIVED.set(0);
        recording = true;
    }

    static List<Map<String, Object>> endWindow() {
        recording = false;
        return List.copyOf(WINDOW);
    }

    /** Requests the double has STARTED to read (increments before the response delay). */
    static int receivedCount() {
        return RECEIVED.get();
    }

    /** Test-only: hold every response for {@code millis} so a live cancellation has something
     *  in flight to interrupt. Restore to 0 after the case. */
    static void responseDelay(long millis) {
        responseDelayMs = millis;
    }

    /** Every recorded dispatch of one role, in arrival order. */
    static List<Map<String, Object>> ofRole(List<Map<String, Object>> window, String role) {
        return window.stream().filter(row -> role.equals(row.get("role"))).toList();
    }
}
