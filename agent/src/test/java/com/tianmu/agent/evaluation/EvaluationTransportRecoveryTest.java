package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.evaluation.admission.ProviderHttpTransport;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.model.ModelRecovery;
import com.tianmu.agent.model.TransportFault;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import com.tianmu.agent.runtime.control.ModelBudget;

import com.tianmu.agent.video.VideoModelClient;
import com.tianmu.agent.video.VideoModelProperties;
import com.tianmu.agent.video.VideoRuntimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice 2 checks: one typed classification per failure, up to two controlled compensations for
 * each logical call, one fresh reservation per attempt, and a durable row that names the reason.
 *
 * <p>Everything runs against a localhost double with a synthetic credential. The double is
 * scripted per request, so a 429→200 pair, a persistent 429, a truncated body, a malformed body,
 * an oversize body, a 401/402/403 and a stream that dies after its first delta are all exercised
 * through the real client and the real fee-gate seam rather than a bespoke harness.
 *
 * <p>The two invariants that must never regress: a second attempt only happens when the fault is
 * one of the three declared recoverable kinds, the response never started, and the gate admits a
 * fresh reservation; and the diagnostic row carries only fixed fields — never prompt, response,
 * header or exception text.
 */
class EvaluationTransportRecoveryTest {
    private static final String KEY = "synthetic-test-key";
    private static final String MODEL = "deepseek-test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GENERIC_VISION_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"summary\\\":\\\"通用\\\",\\\"moments\\\":[]}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3}}";
    private static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

    private HttpServer server;
    private int port;
    private final Deque<Consumer<HttpExchange>> script = new ConcurrentLinkedDeque<>();
    private final AtomicInteger dispatched = new AtomicInteger();
    private Path diagnostics;
    private Recorder gate;
    private CallControl control;
    private int overridePort = -1;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            dispatched.incrementAndGet();
            Consumer<HttpExchange> next = script.poll();
            try {
                if (next == null) ok(exchange, OK_BODY); else next.accept(exchange);
            } catch (RuntimeException broken) {
                // A scripted reply that fails mid-write is exactly the truncation under test.
            } finally {
                exchange.close();
            }
        });
        server.start();
        port = server.getAddress().getPort();
        diagnostics = Files.createTempDirectory("canon-transport-");
        gate = new Recorder();
        EvaluationFeeGate.install(gate);
        control = new CallControl(Instant.now().plusSeconds(30), new CancellationToken(),
                new ModelBudget(0, 8192), () -> {});
        overridePort = -1;
    }

    @AfterEach void stop() throws Exception {
        EvaluationFeeGate.clear();
        server.stop(0);
        script.clear();
        try (var files = Files.walk(diagnostics)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }

    // ---------------------------------------------------------------- the three roles, one rule

    @Test void harnessRateLimitRecoversOnceWithAFreshReservation() throws Exception {
        script.add(rateLimited("0"));
        var client = client(new EvaluationRecovery(diagnostics));
        var result = harness(client);
        assertEquals("{\"ok\":true}", result.path("content").asText());
        assertEquals(2, dispatched.get(), "one compensation, then the retry must be the last attempt");
        assertEquals(2, gate.reservations.size(), "each attempt needs its own reservation");
        assertNotEquals(gate.reservations.get(0), gate.reservations.get(1), "a retry may not reuse the id");
        assertEquals(List.of(gate.reservations.get(0)), gate.failedIds);
        assertEquals(List.of(false), gate.terminalFlags, "a recoverable failure must not seal the envelope");
        assertEquals(List.of(gate.reservations.get(1)), gate.settledIds);
        assertEquals(2, gate.bound.size(), "both attempts bind their own request digest before sending");
        var rows = rows();
        assertEquals(1, rows.size());
        assertEquals("harness", rows.get(0).path("role").asText());
        assertEquals("HTTP_429", rows.get(0).path("kind").asText());
        assertEquals(429, rows.get(0).path("http_status").asInt());
        assertEquals(0, rows.get(0).path("retry_after_ms").asLong(), "Retry-After: 0 is honoured as a zero wait");
        assertEquals(true, rows.get(0).path("compensate").asBoolean());
        assertEquals("RECOVERABLE_HTTP_429", rows.get(0).path("decision").asText());
        assertEquals(1, rows.get(0).path("attempt").asInt());
    }

    @Test void analysisRoleDefersItsCompensationsToTheDurableFence() throws Exception {
        // The analysis role's job store admits exactly one dispatch per attempt, so a client-side
        // resend would be refused as a lost lease. The client therefore refuses to compensate for
        // that role and the service that owns the fence asks the same policy instead.
        script.add(rateLimited("0"));
        script.add(okText(OK_BODY));
        var recovery = new EvaluationRecovery(diagnostics);
        var client = client(recovery);
        assertEquals("MODEL_UNAVAILABLE", assertThrows(AgentException.class, () -> analysis(client)).code());
        assertEquals(1, dispatched.get(), "the client may not resend under the analysis fence");
        assertEquals(List.of(false), gate.terminalFlags,
                "the failed attempt is retained but not sealed: the fence owner may still retry");
        assertEquals(0, rows().size(), "the deferral writes no row; the fence owner writes the one row");

        // Each of the first two attempts earns one more durable attempt, so the ceiling is 3 total.
        assertEquals(3, recovery.analysisRetryCeiling(
                TransportFault.http(TransportFault.Kind.HTTP_429, 429, 0, 12), 1));
        assertEquals(3, recovery.analysisRetryCeiling(
                TransportFault.http(TransportFault.Kind.HTTP_429, 429, 0, 12), 2));
        assertEquals(2, recovery.compensations());
        // At the last allowed attempt the same fault is terminal: the ceiling cannot be raised.
        assertEquals(1, recovery.analysisRetryCeiling(
                TransportFault.http(TransportFault.Kind.HTTP_429, 429, 0, 12), 3));
        // A non-recoverable kind never extends the ceiling, and a non-transport failure keeps the
        // production ceiling of three untouched.
        assertEquals(1, recovery.analysisRetryCeiling(
                TransportFault.of(TransportFault.Kind.READ_BROKEN, 9), 1));
        assertEquals(3, recovery.analysisRetryCeiling(null, 1));
        var rows = rows();
        assertEquals(4, rows.size(), "one row per analysis decision the fence owner asked for");
        assertEquals("analysis", rows.get(0).path("role").asText());
        assertEquals("HTTP_429", rows.get(0).path("kind").asText());
        assertEquals("RECOVERABLE_HTTP_429", rows.get(0).path("decision").asText());
        assertEquals("RECOVERABLE_HTTP_429", rows.get(1).path("decision").asText(),
                "the second attempt may still buy one more");
        assertEquals("COMPENSATION_ALREADY_USED", rows.get(2).path("decision").asText(),
                "the last allowed attempt may not buy another one");
        assertEquals("NOT_RECOVERABLE_READ_BROKEN", rows.get(3).path("decision").asText());
    }

    @Test void harnessTemporaryServerErrorRecoversOnce() throws Exception {
        script.add(status(503, "{\"error\":\"busy\"}"));
        var client = client(new EvaluationRecovery(diagnostics));
        var result = harness(client);
        assertEquals("{\"ok\":true}", result.path("content").asText());
        assertEquals(2, dispatched.get());
        assertEquals(1, gate.terminalFlags.size());
        assertEquals(false, gate.terminalFlags.get(0));
        var row = rows().get(0);
        assertEquals("harness", row.path("role").asText());
        assertEquals("HTTP_5XX", row.path("kind").asText());
        assertEquals(503, row.path("http_status").asInt());
    }

    @Test void visionRateLimitRecoversOnce() throws Exception {
        script.add(rateLimited("0"));
        script.add(okText(GENERIC_VISION_BODY));
        var client = vision(new EvaluationRecovery(diagnostics));
        var result = client.understand(media(), "外观", "", control);
        assertEquals("通用", result.path("summary").asText());
        assertEquals(2, dispatched.get());
        assertEquals(2, gate.reservations.size());
        assertEquals(List.of(false), gate.terminalFlags);
        var row = rows().get(0);
        assertEquals("vision", row.path("role").asText());
        assertEquals("HTTP_429", row.path("kind").asText());
    }

    @Test void aConnectionThatNeverOpenedRecoversOnce() throws Exception {
        var transport = new FlakyTransport(ProviderHttpTransport.using(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()));
        var client = new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null,
                transport, new EvaluationRecovery(diagnostics));
        var result = harness(client);
        assertEquals("{\"ok\":true}", result.path("content").asText());
        assertEquals(2, transport.attempts.get(), "the transport saw two dispatches");
        assertEquals(1, dispatched.get(), "the first one never reached the provider");
        var row = rows().get(0);
        assertEquals("CONNECT_FAILED", row.path("kind").asText());
        assertEquals(0, row.path("http_status").asInt(), "no status exists when nothing was sent");
        assertEquals(false, gate.terminalFlags.get(0));
    }

    // ------------------------------------------------------------------- terminal classifications

    @Test void persistentRateLimitStopsAfterTwoCompensations() throws Exception {
        script.add(rateLimited("0"));
        script.add(rateLimited("0"));
        script.add(rateLimited("0"));
        script.add(okText(OK_BODY)); // must never be used
        var client = client(new EvaluationRecovery(diagnostics));
        var failure = assertThrows(AgentException.class, () -> harness(client));
        assertEquals("MODEL_UNAVAILABLE", failure.code());
        assertEquals(3, dispatched.get(), "two compensations, then the third failure is terminal");
        assertEquals(3, gate.reservations.size(), "every send opened its own reservation");
        assertEquals(List.of(false, false, true), gate.terminalFlags,
                "only the last failure ends the logical call and seals the envelope");
        var rows = rows();
        assertEquals(3, rows.size());
        assertEquals("RECOVERABLE_HTTP_429", rows.get(1).path("decision").asText());
        assertEquals("COMPENSATION_ALREADY_USED", rows.get(2).path("decision").asText());
        assertEquals(false, rows.get(2).path("compensate").asBoolean());
    }

    @Test void partialBodyIsTerminalAndNeverResent() throws Exception {
        try (var raw = new RawServer(RawServer.truncated(4096))) {
            overridePort = raw.port();
            var client = client(new EvaluationRecovery(diagnostics));
            var failure = assertThrows(AgentException.class, () -> harness(client));
            assertEquals("MODEL_UNAVAILABLE", failure.code());
            assertEquals(1, raw.connections.get(), "a broken body may not be re-sent");
        }
        var row = rows().get(0);
        assertEquals("READ_BROKEN", row.path("kind").asText());
        assertEquals(false, row.path("compensate").asBoolean());
        assertEquals("NOT_RECOVERABLE_READ_BROKEN", row.path("decision").asText());
        assertEquals(List.of(true), gate.terminalFlags);
    }

    @Test void partialStreamIsNeverResentEvenWhenThePolicyWouldAllowIt() throws Exception {
        // A permissive policy proves the client's own guard, not the policy: once a 200 opened the
        // stream (and any delta may already be billed) nothing may be re-sent under this call.
        ModelRecovery permissive = (fault, attempt) -> ModelRecovery.Decision.compensateAfter(0, "TEST_PERMISSIVE");
        var broken = new BrokenStreamTransport();
        var client = new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null, broken, permissive);
        var deltas = new CopyOnWriteArrayList<String>();
        var streaming = control.withTextOutput(deltas::add);
        var failure = assertThrows(AgentException.class, () -> client.completeHarness(MODEL,
                List.of(Map.of("role", "user", "content", "x")), List.of(tool()), 256, streaming));
        assertEquals("MODEL_UNAVAILABLE", failure.code());
        assertEquals(1, broken.attempts.get(), "a stream that already started must never be re-sent");
        assertEquals(List.of("部分"), List.copyOf(deltas), "the delta really reached the consumer first");

        // The same policy and the same truncation WITHOUT a response head does retry once, which is
        // what isolates the guard to the streamed case.
        try (var raw = new RawServer(RawServer.truncated(4096), RawServer.ok())) {
            overridePort = raw.port();
            var second = new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null, null, permissive);
            assertEquals("{\"ok\":true}", harness(second).path("content").asText());
            assertEquals(2, raw.connections.get());
        } finally {
            overridePort = -1;
        }
    }

    @Test void malformedJsonIsTerminal() throws Exception {
        script.add(okText("{not json"));
        var client = client(new EvaluationRecovery(diagnostics));
        assertEquals("MODEL_UNAVAILABLE", assertThrows(AgentException.class, () -> harness(client)).code());
        assertEquals(1, dispatched.get());
        assertEquals("PARSE_FAILED", rows().get(0).path("kind").asText());
    }

    @Test void oversizeBodyIsTerminal() throws Exception {
        script.add(okText("x".repeat(140_000)));
        var client = client(new EvaluationRecovery(diagnostics));
        assertEquals("MODEL_UNAVAILABLE", assertThrows(AgentException.class, () -> harness(client)).code());
        assertEquals(1, dispatched.get());
        assertEquals("OVERSIZE", rows().get(0).path("kind").asText());
    }

    @Test void authQuotaAndRejectionKeepTheirHistoricalCodes() throws Exception {
        record Case(int status, String code, String kind) { }
        for (var c : new Case[]{
                new Case(401, "MODEL_AUTH_FAILED", "AUTH"),
                new Case(403, "MODEL_AUTH_FAILED", "AUTH"),
                new Case(400, "MODEL_REQUEST_REJECTED", "REJECTED"),
                // 402 arms a five-minute quota cooldown, so it must be the last case of its client.
                new Case(402, "MODEL_QUOTA_EXHAUSTED", "QUOTA")}) {
            int before = dispatched.get();
            int rowsBefore = rows().size();
            script.add(status(c.status(), "{\"error\":\"x\"}"));
            var client = client(new EvaluationRecovery(diagnostics));
            assertEquals(c.code(), assertThrows(AgentException.class, () -> harness(client)).code(),
                    "status " + c.status());
            assertEquals(before + 1, dispatched.get(), "status " + c.status() + " must not be retried");
            assertEquals(List.of(true), gate.terminalFlags.subList(gate.terminalFlags.size() - 1,
                    gate.terminalFlags.size()));
            var row = rows().get(rowsBefore);
            assertEquals(c.kind(), row.path("kind").asText());
            assertEquals(c.status(), row.path("http_status").asInt());
            assertEquals(false, row.path("compensate").asBoolean());
        }
    }

    // ------------------------------------------------------------------- who may not send again

    @Test void cancelDuringBackoffSendsNothingMore() throws Exception {
        var token = new CancellationToken();
        control = new CallControl(Instant.now().plusSeconds(30), token,
                new ModelBudget(0, 8192), () -> {});
        ModelRecovery cancelling = (fault, attempt) -> {
            token.cancel(StopReason.USER_CANCELLED); // the user stops while the backoff is running
            return ModelRecovery.Decision.compensateAfter(400, "TEST_CANCEL");
        };
        script.add(rateLimited("0"));
        script.add(okText(OK_BODY));
        var client = new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null, null, cancelling);
        var failure = assertThrows(AgentException.class, () -> harness(client));
        assertEquals("CANCELLED", failure.code());
        assertEquals(1, dispatched.get(), "a cancel during the backoff must send nothing else");
        assertEquals(1, gate.reservations.size(), "no second reservation may be opened after the stop");
    }

    @Test void gateRefusalOnTheCompensationAttemptSendsNothingMore() throws Exception {
        gate.refuseAfter = 1; // the second admission is refused (ledger busy / cap reached)
        script.add(rateLimited("0"));
        script.add(okText(OK_BODY));
        var client = client(new EvaluationRecovery(diagnostics));
        var failure = assertThrows(AgentException.class, () -> harness(client));
        assertEquals("FEE_ADMISSION_BLOCKED", failure.code());
        assertEquals(1, dispatched.get(), "a refused admission must never reach the provider");
    }

    @Test void observationFailureCannotBreakTheCall() throws Exception {
        ModelRecovery noisy = (fault, attempt) -> { throw new IllegalStateException("observer down"); };
        script.add(rateLimited("0"));
        var client = new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null, null, noisy);
        var failure = assertThrows(AgentException.class, () -> harness(client));
        assertEquals("MODEL_UNAVAILABLE", failure.code(), "the dispatch error must survive a broken observer");
        assertEquals(1, dispatched.get(), "an observer that throws can only refuse the compensation");
    }

    // ------------------------------------------------------------------ waiting and its evidence

    /**
     * The whole wait decision on one page: every role against every stated interval. Each line is
     * printed as {@code WAIT_PROBE <json>} so the matrix can be captured as evidence, and every
     * cell is asserted here so the printed table cannot drift from what the policy really does.
     */
    @Test void waitProbeMatrix() throws Exception {
        for (String role : new String[]{"harness", "vision", "analysis"}) {
            for (long stated : new long[]{-1, 0, 1500, 3000, 6000, 60_000}) {
                Path cell = diagnostics.resolve("cells/" + role + "-" + (stated < 0 ? "none" : stated));
                Files.createDirectories(cell);
                var recovery = new EvaluationRecovery(cell);
                TransportFault fault = stated < 0
                        ? TransportFault.of(TransportFault.Kind.CONNECT_FAILED, 7)
                        : TransportFault.http(TransportFault.Kind.HTTP_429, 429, stated, 7);
                var attempt = new ModelRecovery.CallAttempt(role, MODEL, 1, "fee-probe-1", 100,
                        "c".repeat(64), 7);
                ModelRecovery.Decision decision = recovery.onFailure(fault, attempt);
                int ceiling = role.equals("analysis") ? recovery.analysisRetryCeiling(fault, attempt) : -1;
                Path file = cell.resolve("transport-diagnostic.jsonl");
                var lines = Files.exists(file) ? Files.readAllLines(file) : List.<String>of();
                JsonNode row = lines.isEmpty() ? JSON.createObjectNode() : JSON.readTree(lines.get(lines.size() - 1));
                boolean unpayable = stated >= 6000;
                boolean analysis = role.equals("analysis");
                if (unpayable) {
                    assertFalse(decision.compensate(), role + "@" + stated);
                    assertEquals(0, decision.delayMillis(), role + "@" + stated);
                    if (analysis) {
                        // The fence owner, not the client, names the unpayable wait: the client only
                        // says the fault is recoverable enough to hand to the owner.
                        assertEquals("DEFERRED_TO_DURABLE_FENCE", decision.reason(), role + "@" + stated);
                        assertEquals(1, ceiling, "no durable retry may be granted");
                    } else {
                        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", decision.reason(), role + "@" + stated);
                    }
                    assertEquals(false, row.path("compensate").asBoolean(), role + "@" + stated);
                    assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", row.path("decision").asText());
                } else if (analysis) {
                    assertEquals("DEFERRED_TO_DURABLE_FENCE", decision.reason());
                    assertEquals(3, ceiling, "a payable wait still buys the whole ceiling");
                    assertEquals(true, row.path("compensate").asBoolean());
                } else {
                    long expected = stated < 0 ? 500 : stated;
                    assertTrue(decision.compensate(), role + "@" + stated);
                    assertEquals(expected, decision.delayMillis(), role + "@" + stated);
                    assertEquals(expected, row.path("delay_ms").asLong(), "the row names the wait used");
                }
                System.out.println("WAIT_PROBE " + JSON.writeValueAsString(probeCell(
                        "role", role, "stated_ms", stated, "compensate", decision.compensate(),
                        "delay_ms", decision.delayMillis(), "decision", decision.reason(),
                        "terminal", decision.terminal(), "ceiling", ceiling,
                        "row_decision", row.path("decision").asText("NONE"),
                        "row_delay_ms", row.path("delay_ms").asLong(-1),
                        "row_compensate", row.path("compensate").asBoolean(false),
                        "run_compensations", recovery.compensations())));
            }
        }
        // And the cell the acceptance cared about: an unwritable row buys no resend.
        Path blocked = Files.createTempFile("canon-probe-", ".jsonl");
        Files.writeString(blocked, "not a directory\n");
        try {
            var broken = new EvaluationRecovery(blocked);
            var refused = broken.onFailure(TransportFault.http(TransportFault.Kind.HTTP_429, 429, 0, 7),
                    new ModelRecovery.CallAttempt("harness", MODEL, 1, "fee-probe-2", 100,
                            "c".repeat(64), 7));
            assertEquals("DIAGNOSTIC_WRITE_FAILED", refused.reason());
            assertFalse(refused.compensate());
            assertEquals(0, broken.compensations());
            assertEquals(0, broken.rows());
            System.out.println("WAIT_PROBE " + JSON.writeValueAsString(probeCell(
                    "role", "harness", "stated_ms", 0, "compensate", refused.compensate(),
                    "delay_ms", refused.delayMillis(), "decision", refused.reason(),
                    "terminal", refused.terminal(), "ceiling", -1,
                    "row_decision", "NONE", "row_delay_ms", -1, "row_compensate", false,
                    "run_compensations", broken.compensations(),
                    "diagnostic_saved", broken.rows() > 0, "retry_allowed", refused.compensate())));
        } finally {
            Files.deleteIfExists(blocked);
        }
    }

    @Test void retryAfterIsWaitedOutExactly() throws Exception {
        // The provider says "come back in one second". The client must actually spend that second,
        // not a shorter internal backoff that only pretends the interval was honoured.
        script.add(rateLimited("1"));
        var client = client(new EvaluationRecovery(diagnostics));
        long started = System.nanoTime();
        var result = harness(client);
        long waited = (System.nanoTime() - started) / 1_000_000;
        assertEquals("{\"ok\":true}", result.path("content").asText());
        assertEquals(2, dispatched.get());
        assertTrue(waited >= 900, "the stated second must really elapse, waited=" + waited + "ms");
        var row = rows().get(0);
        assertEquals(1000, row.path("retry_after_ms").asLong());
        assertEquals(1000, row.path("delay_ms").asLong(), "the recorded wait is the one that was used");
        assertEquals("RECOVERABLE_HTTP_429", row.path("decision").asText());
    }

    @Test void unpayableRetryAfterStopsWithoutResending() throws Exception {
        script.add(rateLimited("60"));
        script.add(okText(OK_BODY)); // must never be used
        var recovery = new EvaluationRecovery(diagnostics);
        var client = client(recovery);
        var failure = assertThrows(AgentException.class, () -> harness(client));
        assertEquals("MODEL_UNAVAILABLE", failure.code());
        assertEquals(1, dispatched.get(), "a stated minute may not be shortened into a resend");
        assertEquals(1, gate.reservations.size(), "no second reservation may be opened");
        assertEquals(List.of(true), gate.terminalFlags, "the refusal ends the logical call");
        assertEquals(0, recovery.compensations());
        var row = rows().get(0);
        assertEquals(60_000, row.path("retry_after_ms").asLong());
        assertEquals(false, row.path("compensate").asBoolean());
        assertEquals(0, row.path("delay_ms").asLong());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", row.path("decision").asText());
    }

    @Test void aWaitThatWouldOutliveTheCallIsRefused() throws Exception {
        // 600ms of budget left, the provider asks for one second: the honest answer is to stop,
        // because waiting would either overrun the deadline or be a truncated interval.
        control = new CallControl(Instant.now().plusMillis(600), new CancellationToken(),
                new ModelBudget(0, 8192), () -> {});
        script.add(rateLimited("1"));
        script.add(okText(OK_BODY));
        var client = client(new EvaluationRecovery(diagnostics));
        assertEquals("MODEL_UNAVAILABLE", assertThrows(AgentException.class, () -> harness(client)).code());
        assertEquals(1, dispatched.get());
        var row = rows().get(0);
        assertEquals(false, row.path("compensate").asBoolean());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", row.path("decision").asText());
    }

    @Test void visionRoleRefusesAnUnpayableWaitToo() throws Exception {
        script.add(rateLimited("60"));
        script.add(okText(GENERIC_VISION_BODY));
        var client = vision(new EvaluationRecovery(diagnostics));
        assertThrows(AgentException.class, () -> client.understand(media(), "外观", "", control));
        assertEquals(1, dispatched.get(), "the image role reads the same rule");
        var row = rows().get(0);
        assertEquals("vision", row.path("role").asText());
        assertEquals(false, row.path("compensate").asBoolean());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", row.path("decision").asText());
    }

    @Test void analysisRoleStopsOnAnUnpayableWait() throws Exception {
        // The fence owner asks the same policy; a stated minute may not become an immediate retry.
        var recovery = new EvaluationRecovery(diagnostics);
        var fault = TransportFault.http(TransportFault.Kind.HTTP_429, 429, 60_000, 12);
        assertEquals(1, recovery.analysisRetryCeiling(fault, analysisAttempt(1)));
        assertEquals(0, recovery.compensations());
        var row = rows().get(0);
        assertEquals("analysis", row.path("role").asText());
        assertEquals(false, row.path("compensate").asBoolean());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", row.path("decision").asText());
        // A wait inside the bound still buys the whole ceiling, so the stop is specific to the wait.
        assertEquals(3, recovery.analysisRetryCeiling(
                TransportFault.http(TransportFault.Kind.HTTP_429, 429, 3000, 12), analysisAttempt(1)));
        assertEquals(1, recovery.compensations());
    }

    @Test void unrecordableEvidenceMeansNoResend() throws Exception {
        // A file where the row's directory should be: the diagnostic cannot be written, so the
        // compensation it would justify must not happen either.
        Path blocked = Files.createTempFile("canon-blocked-", ".jsonl");
        Files.writeString(blocked, "not a directory\n");
        try {
            var recovery = new EvaluationRecovery(blocked);
            var decision = recovery.onFailure(
                    TransportFault.http(TransportFault.Kind.HTTP_429, 429, 0, 5), attempt(1, "harness"));
            assertFalse(decision.compensate());
            assertTrue(decision.terminal());
            assertEquals("DIAGNOSTIC_WRITE_FAILED", decision.reason());
            assertEquals(0, recovery.compensations(), "the counter is rolled back, so nothing was spent");
            assertEquals(0, recovery.rows());
            assertEquals(1, recovery.diagnosticFailures());

            script.add(rateLimited("0"));
            script.add(okText(OK_BODY));
            var client = new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null, null, recovery);
            assertEquals("MODEL_UNAVAILABLE", assertThrows(AgentException.class, () -> harness(client)).code());
            assertEquals(1, dispatched.get(), "the stub proves nothing was sent again");
            assertEquals(1, gate.reservations.size(), "and no second reservation was opened");
        } finally {
            Files.deleteIfExists(blocked);
        }
    }

    @Test void readTimeoutIsTerminalAndNeverResent() throws Exception {
        // The double accepts and then says nothing past the call's own deadline.
        control = new CallControl(Instant.now().plusMillis(500), new CancellationToken(),
                new ModelBudget(0, 8192), () -> {});
        script.add(exchange -> {
            try { Thread.sleep(1_500); } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            ok(exchange, OK_BODY);
        });
        var client = client(new EvaluationRecovery(diagnostics));
        assertEquals("MODEL_UNAVAILABLE", assertThrows(AgentException.class, () -> harness(client)).code());
        assertEquals(1, dispatched.get(), "a timed-out call may not be re-sent");
        var row = rows().get(0);
        assertEquals("WAIT_TIMEOUT", row.path("kind").asText());
        assertEquals(false, row.path("compensate").asBoolean());
        assertEquals("NOT_RECOVERABLE_WAIT_TIMEOUT", row.path("decision").asText());
        assertEquals(List.of(true), gate.terminalFlags);
    }

    // ---------------------------------------------------------------------------- policy bounds

    @Test void perRunCapStopsFurtherCompensations() throws IOException {
        var recovery = new EvaluationRecovery(diagnostics);
        for (int call = 1; call <= EvaluationRecovery.MAX_PER_RUN; call++)
            assertTrue(recovery.onFailure(TransportFault.http(TransportFault.Kind.HTTP_429, 429, 0, 5),
                    attemptOf(call, 1, "harness")).compensate(), "logical call " + call);
        var refused = recovery.onFailure(TransportFault.http(TransportFault.Kind.HTTP_429, 429, 0, 5),
                attemptOf(99, 1, "harness"));
        assertFalse(refused.compensate());
        assertEquals("RUN_COMPENSATION_CAP", refused.reason());
        assertEquals(EvaluationRecovery.MAX_PER_RUN, recovery.compensations());
        assertEquals(EvaluationRecovery.MAX_PER_RUN + 1, recovery.rows());
        // The two counters are independent: every one of those calls was on its own first attempt,
        // so the per-call column never moves while the per-run column climbs to its ceiling.
        var written = rows();
        for (int row = 0; row < EvaluationRecovery.MAX_PER_RUN; row++) {
            assertEquals(0, written.get(row).path("call_compensations").asInt(), "row " + row);
            assertEquals(row + 1, written.get(row).path("run_compensations").asInt(), "row " + row);
        }
        assertEquals(EvaluationRecovery.MAX_PER_RUN,
                written.get(EvaluationRecovery.MAX_PER_RUN).path("run_compensations").asInt());
    }

    @Test void twoCompensationsPerLogicalCallAndNoneAfterThem() {
        var recovery = new EvaluationRecovery(diagnostics);
        assertEquals(2, EvaluationRecovery.MAX_PER_LOGICAL_CALL);
        assertTrue(recovery.onFailure(TransportFault.http(TransportFault.Kind.HTTP_5XX, 503, -1, 10),
                attempt(1, "harness")).compensate(), "the first re-offer");
        assertTrue(recovery.onFailure(TransportFault.http(TransportFault.Kind.HTTP_5XX, 503, -1, 10),
                attempt(2, "harness")).compensate(), "the second re-offer");
        var third = recovery.onFailure(TransportFault.http(TransportFault.Kind.HTTP_5XX, 503, -1, 10),
                attempt(3, "harness"));
        assertFalse(third.compensate());
        assertEquals("COMPENSATION_ALREADY_USED", third.reason());
        for (var kind : new TransportFault.Kind[]{TransportFault.Kind.WAIT_TIMEOUT,
                TransportFault.Kind.READ_BROKEN, TransportFault.Kind.PARSE_FAILED,
                TransportFault.Kind.OVERSIZE, TransportFault.Kind.AUTH, TransportFault.Kind.QUOTA,
                TransportFault.Kind.REJECTED, TransportFault.Kind.CANCELLED, TransportFault.Kind.UNKNOWN}) {
            var decision = recovery.onFailure(TransportFault.of(kind, 7), attempt(1, "vision"));
            assertFalse(decision.compensate(), kind.name());
            assertEquals("NOT_RECOVERABLE_" + kind, decision.reason());
        }
        // Backoff: a declared Retry-After is honoured exactly, an absent one gets a short wait, and
        // an interval that does not fit stops the call instead of being silently shortened.
        assertEquals(1500, recovery.onFailure(
                TransportFault.http(TransportFault.Kind.HTTP_429, 429, 1500, 3), attempt(1, "harness")).delayMillis());
        assertEquals(500, recovery.onFailure(TransportFault.of(TransportFault.Kind.CONNECT_FAILED, 4),
                attempt(1, "vision")).delayMillis());
        var unpayable = recovery.onFailure(
                TransportFault.http(TransportFault.Kind.HTTP_429, 429, 60_000, 3), attempt(1, "harness"));
        assertFalse(unpayable.compensate(), "a 60s interval may not be truncated to the 5s bound");
        assertEquals(0, unpayable.delayMillis());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", unpayable.reason());
        // A wait that would outlive the logical call is refused for the same reason, at policy level.
        var expiring = new EvaluationRecovery(diagnostics).onFailure(
                TransportFault.http(TransportFault.Kind.HTTP_429, 429, 3000, 3), attempt(1, "harness"), 2000);
        assertFalse(expiring.compensate());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", expiring.reason());
    }

    @Test void diagnosticRowsCarryOnlyFixedFields() throws Exception {
        var recovery = new EvaluationRecovery(diagnostics);
        recovery.onFailure(TransportFault.http(TransportFault.Kind.HTTP_429, 429, 1500, 1234),
                new ModelRecovery.CallAttempt("harness", MODEL, 1, "fee-abc123", 4242,
                        "a".repeat(64), 1234));
        assertEquals(1, recovery.rows());
        var line = Files.readAllLines(diagnostics.resolve("transport-diagnostic.jsonl")).get(0);
        var row = JSON.readTree(line);
        List<String> names = new ArrayList<>();
        row.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("schema", "at", "role", "model", "attempt", "reservation_id", "body_bytes",
                        "body_sha256", "kind", "http_status", "retry_after_ms", "elapsed_ms", "compensate",
                        "delay_ms", "call_compensations", "run_compensations", "decision"),
                Set.copyOf(names), "the row's field set is the contract");
        assertEquals("canon-transport-diagnostic-v1", row.path("schema").asText());
        assertEquals("harness", row.path("role").asText());
        assertEquals(MODEL, row.path("model").asText());
        assertEquals(1, row.path("attempt").asInt());
        assertEquals("fee-abc123", row.path("reservation_id").asText());
        assertEquals(4242, row.path("body_bytes").asLong());
        assertEquals("a".repeat(64), row.path("body_sha256").asText());
        assertEquals(429, row.path("http_status").asInt());
        assertEquals(1500, row.path("retry_after_ms").asLong());
        assertEquals(1234, row.path("elapsed_ms").asLong());
        assertEquals(true, row.path("compensate").asBoolean());
        assertEquals(1500, row.path("delay_ms").asLong());
        assertEquals(0, row.path("call_compensations").asInt(),
                "the counter is the number already spent on THIS logical call, not the one being granted");
        assertEquals(1, row.path("run_compensations").asInt(), "the run counter is incremented before the row");
        assertFalse(line.contains(KEY), "no credential may reach the diagnostic file");
        assertFalse(line.contains("prompt"), "no prompt text may reach the diagnostic file");
    }

    // ---------------------------------------------------------------------------------- fixtures

    private DeepSeekClient client(ModelRecovery recovery) {
        return new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null, null, recovery);
    }

    private DeepSeekProperties properties() {
        return new DeepSeekProperties(URI.create("http://127.0.0.1:" + (overridePort > 0 ? overridePort : port)),
                KEY, MODEL, MODEL,
                Duration.ofSeconds(2), 384, 1024);
    }

    private JsonNode analysis(DeepSeekClient client) {
        return client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true);
    }

    private JsonNode harness(DeepSeekClient client) {
        return client.completeHarness(MODEL, List.of(Map.of("role", "user", "content", "x")),
                List.of(tool()), 256, control);
    }

    private static AgentTypes.ToolDefinition tool() {
        return new AgentTypes.ToolDefinition("analyze_comments", "read comments",
                Map.of("type", "object", "properties", Map.of()));
    }

    private VideoModelClient vision(ModelRecovery recovery) {
        return new VideoModelClient(new VideoModelProperties(false, "compatible",
                URI.create("http://127.0.0.1:" + port), KEY, "placeholder-model", ""), JSON,
                VideoRuntimeProperties.defaults(), ProviderHttpTransport.using(HttpClient.newHttpClient()),
                recovery);
    }

    private VideoContent.Media media() {
        return new VideoContent.Media(new VideoContent.Source(1001L, "v1", 10_000L), 0, 5_000,
                List.of(new VideoContent.Frame(100, jpegBase64(0xFF0000)),
                        new VideoContent.Frame(400, jpegBase64(0x00FF00))), "");
    }

    static String jpegBase64(int rgb) {
        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(rgb));
        graphics.fillRect(0, 0, 8, 8);
        graphics.dispose();
        var out = new java.io.ByteArrayOutputStream();
        try { javax.imageio.ImageIO.write(image, "jpeg", out); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private List<JsonNode> rows() throws IOException {
        Path file = diagnostics.resolve("transport-diagnostic.jsonl");
        List<JsonNode> parsed = new ArrayList<>();
        if (!Files.exists(file)) return parsed;
        for (String line : Files.readAllLines(file)) if (!line.isBlank()) parsed.add(JSON.readTree(line));
        return parsed;
    }

    private static ModelRecovery.CallAttempt attempt(int attempt, String role) {
        return attemptOf(1, attempt, role);
    }

    private static Map<String, Object> probeCell(Object... pairs) {
        Map<String, Object> cell = new java.util.LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) cell.put((String) pairs[index], pairs[index + 1]);
        return cell;
    }

    private static ModelRecovery.CallAttempt analysisAttempt(int attempt) {
        return new ModelRecovery.CallAttempt("analysis", MODEL, attempt, "fee-analysis-" + attempt,
                0, "", 12);
    }

    private static ModelRecovery.CallAttempt attemptOf(int call, int attempt, String role) {
        return new ModelRecovery.CallAttempt(role, MODEL, attempt, "fee-" + call + "-" + attempt,
                100, "b".repeat(64), 5);
    }

    // ---------------------------------------------------------------------------- scripted replies

    private static void ok(HttpExchange exchange, String body) { reply(exchange, 200, body, null); }

    private static Consumer<HttpExchange> okText(String body) { return exchange -> ok(exchange, body); }

    private static Consumer<HttpExchange> status(int status, String body) {
        return exchange -> reply(exchange, status, body, null);
    }

    private static Consumer<HttpExchange> rateLimited(String retryAfter) {
        return exchange -> reply(exchange, 429, "{\"error\":\"rate limited\"}", retryAfter);
    }

    private static void reply(HttpExchange exchange, int status, String body, String retryAfter) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        } catch (IOException gone) {
            throw new java.io.UncheckedIOException(gone);
        }
    }

    // ------------------------------------------------------------------------------- fee recorder

    /** Records every admission outcome so the test can see the per-attempt accounting. */
    static final class Recorder implements EvaluationFeeGate.Admission {
        final List<String> reservations = new CopyOnWriteArrayList<>();
        final List<String> settledIds = new CopyOnWriteArrayList<>();
        final List<String> failedIds = new CopyOnWriteArrayList<>();
        final List<Boolean> terminalFlags = new CopyOnWriteArrayList<>();
        final List<String> bound = new CopyOnWriteArrayList<>();
        final AtomicInteger sequence = new AtomicInteger();
        volatile int refuseAfter = -1;

        @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes,
                int maxTokens) {
            return next();
        }

        @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes,
                int maxTokens, String clientRole) {
            return next();
        }

        @Override public EvaluationFeeGate.Reservation admitVideo(String provider, String path) {
            return next();
        }

        private EvaluationFeeGate.Reservation next() {
            int index = sequence.incrementAndGet();
            if (refuseAfter >= 0 && index > refuseAfter)
                throw new EvaluationFeeLedger.LedgerBusy("synthetic refusal at admission " + index);
            String id = "fee-synthetic-" + index;
            reservations.add(id);
            return new EvaluationFeeGate.Reservation() {
                @Override public String reservationId() { return id; }
                @Override public void settle(JsonNode response) { settledIds.add(id); }
                @Override public void fail(String reason) { fail(reason, true); }
                @Override public void fail(String reason, boolean terminal) {
                    failedIds.add(id);
                    terminalFlags.add(terminal);
                }
                @Override public void bindRequestDigest(long bodyBytes, String bodySha256) {
                    bound.add(id + ":" + bodyBytes + ":" + bodySha256.substring(0, 8));
                }
            };
        }
    }

    /**
     * One-shot raw responder. A body that truly stops mid-flight cannot be produced by the JDK's
     * HttpServer without either completing it or leaving the socket open, so the truncation cases
     * speak HTTP/1.1 directly: a declared length larger than the bytes written, then close.
     */
    static final class RawServer implements AutoCloseable {
        interface Reply { void write(java.io.OutputStream out) throws IOException; }

        private final java.net.ServerSocket server;
        private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
        final AtomicInteger connections = new AtomicInteger();
        private final Thread worker;

        RawServer(Reply... script) throws IOException {
            for (Reply reply : script) replies.add(reply);
            server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "raw-double");
            worker.setDaemon(true);
            worker.start();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (var client = server.accept()) {
                    connections.incrementAndGet();
                    readRequest(client.getInputStream());
                    var out = client.getOutputStream();
                    Reply next = replies.poll();
                    (next == null ? ok() : next).write(out);
                    out.flush();
                } catch (IOException finished) {
                    // A closed server socket ends the loop; a dropped client ends one attempt.
                }
            }
        }

        private static void readRequest(java.io.InputStream in) throws IOException {
            var head = new StringBuilder();
            int run = 0;
            while (run < 4) {
                int next = in.read();
                if (next < 0) return;
                head.append((char) next);
                run = (next == '\r' || next == '\n') ? run + 1 : 0;
            }
            int length = 0;
            for (String line : head.toString().split("\r\n"))
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                    length = Integer.parseInt(line.substring(15).trim());
            if (length > 0) in.readNBytes(length);
        }

        private static byte[] head(int status, String contentType, long length) {
            return ("HTTP/1.1 " + status + " OK\r\nContent-Type: " + contentType
                    + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
        }

        static Reply ok() {
            return out -> {
                byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
                out.write(head(200, "application/json", body.length));
                out.write(body);
            };
        }

        static Reply truncated(int declared) {
            return out -> {
                out.write(head(200, "application/json", declared));
                out.write("{\"choices\":[".getBytes(StandardCharsets.UTF_8));
            };
        }

        int port() { return server.getLocalPort(); }

        @Override public void close() throws IOException { server.close(); }
    }

    /**
     * Delivers a real response head and one real delta, then breaks the read. The head is a proper
     * 200 with an event-stream content type, so the client has already accepted (and may already
     * have been billed for) the call when the break arrives — which is exactly the state in which
     * no compensation may be sent.
     */
    static final class BrokenStreamTransport implements ProviderHttpTransport {
        final AtomicInteger attempts = new AtomicInteger();

        @Override public <T> CompletableFuture<HttpResponse<T>> send(HttpRequest sent,
                HttpResponse.BodyHandler<T> handler, byte[] payload, EvaluationFeeGate.Reservation reservation) {
            attempts.incrementAndGet();
            // One stable body instance: the client reads it repeatedly, and a fresh stream per call
            // would replay the same bytes instead of advancing.
            InputStream body = headThenBreak();
            @SuppressWarnings("unchecked")
            HttpResponse<T> response = (HttpResponse<T>) new HttpResponse<InputStream>() {
                @Override public int statusCode() { return 200; }
                @Override public HttpRequest request() { return sent; }
                @Override public java.util.Optional<HttpResponse<InputStream>> previousResponse() {
                    return java.util.Optional.empty();
                }
                @Override public java.net.http.HttpHeaders headers() {
                    return java.net.http.HttpHeaders.of(
                            Map.of("Content-Type", List.of("text/event-stream; charset=utf-8")), (a, b) -> true);
                }
                @Override public InputStream body() { return body; }
                @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                    return java.util.Optional.empty();
                }
                @Override public URI uri() { return sent.uri(); }
                @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
            };
            return CompletableFuture.completedFuture(response);
        }

        private static InputStream headThenBreak() {
            byte[] event = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"部分\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            return new InputStream() {
                private int index;
                @Override public int read() throws IOException {
                    if (index < event.length) return event[index++] & 0xff;
                    throw new IOException("connection reset by peer");
                }
            };
        }
    }

    /** Fails the first dispatch at the connect boundary, then delegates to the real transport. */
    static final class FlakyTransport implements ProviderHttpTransport {
        final AtomicInteger attempts = new AtomicInteger();
        final ProviderHttpTransport real;

        FlakyTransport(ProviderHttpTransport real) { this.real = real; }

        @Override public <T> CompletableFuture<HttpResponse<T>> send(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, byte[] payload, EvaluationFeeGate.Reservation reservation) {
            if (attempts.incrementAndGet() == 1)
                return CompletableFuture.failedFuture(new ConnectException("Connection refused"));
            return real.send(request, handler, payload, reservation);
        }
    }
}
