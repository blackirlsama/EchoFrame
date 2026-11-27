package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.DanmakuTypes;
import com.tianmu.agent.core.FeedbackAnalysis.Counts;
import com.tianmu.agent.core.FeedbackAnalysis.Grounding;
import com.tianmu.agent.core.FeedbackAnalysis.Phase;
import com.tianmu.agent.core.FeedbackAnalysis.Request;
import com.tianmu.agent.core.FeedbackAnalysis.Source;
import com.tianmu.agent.core.FeedbackAnalysis.Unit;
import com.tianmu.agent.core.FeedbackAnalysis.WorkKind;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.evaluation.admission.ProviderHttpTransport;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.feedback.FeedbackJobStore;
import com.tianmu.agent.feedback.FeedbackModels;
import com.tianmu.agent.feedback.FeedbackModels.Group;
import com.tianmu.agent.feedback.FeedbackModels.Input;
import com.tianmu.agent.feedback.FeedbackModels.Proposal;
import com.tianmu.agent.feedback.FeedbackObjects;
import com.tianmu.agent.feedback.FeedbackPolicy;
import com.tianmu.agent.feedback.FeedbackSnapshotPort;
import com.tianmu.agent.feedback.FeedbackSnapshotPort.Receipt;
import com.tianmu.agent.feedback.MemoryFeedbackJobStore;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.model.ModelRecovery;
import com.tianmu.agent.model.TransportFault;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.store.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evaluation-mode analysis: the real client defers a recoverable fault to the real service that
 * owns the durable fence; the service's one plan call decides the wait, ceiling, count and row.
 *
 * <p>The previous acceptance's counterexample was a client decision of
 * {@code RETRY_AFTER_BEYOND_WAIT_BUDGET} followed by an owner ceiling of 3 with one counted
 * compensation. These cases assert the fence owner's record, the wire count and the reservation
 * IDs together, so a client refusal cannot be silently overruled by the queue.
 */
class EvaluationAnalysisRetryDecisionTest {
    private static final String MODEL = "deepseek-test";
    private static final String OK_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Clock TASK_CLOCK = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    @TempDir Path directory;
    private HttpServer server;
    private int port;
    private final Deque<Consumer<HttpExchange>> script = new ConcurrentLinkedDeque<>();
    private final AtomicInteger received = new AtomicInteger();
    private final List<String> reservations = new CopyOnWriteArrayList<>();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final List<Boolean> terminalFlags = new CopyOnWriteArrayList<>();
    private Admission admission;
    private EvaluationRecovery recovery;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                received.incrementAndGet();
                Consumer<HttpExchange> next = script.poll();
                if (next == null) reply(exchange, 200, OK_BODY, null);
                else next.accept(exchange);
            } catch (IOException ignored) {
                // A scripted truncation is the read break under test.
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach void stop() throws Exception {
        if (admission != null) EvaluationFeeGate.clear();
        server.stop(0);
        script.clear();
    }

    @Test void threeSecondWaitIsHonouredAndTheRetryUsesAFreshReservation() throws Exception {
        script.add(rateLimited("3"));
        var fixture = fixture(20_000);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        var first = fixture.service.advance(job.id(), null, () -> {}).orElseThrow();
        assertEquals("MODEL_UNAVAILABLE", first.code());
        assertEquals(1, received.get());
        assertEquals(1, reservations.size());
        assertEquals(1, recovery.compensations(), "the owner granted one compensation");
        assertEquals(1, recovery.rows());

        assertEquals(Optional.empty(), fixture.service.advance(job.id(), null, () -> {}));
        Thread.sleep(2_800);
        assertEquals(Optional.empty(), fixture.service.advance(job.id(), null, () -> {}));
        int at2800 = received.get();
        assertEquals(1, at2800, "no second send before the provider's three seconds");
        Thread.sleep(500);
        assertTrue(fixture.service.advance(job.id(), null, () -> {}).isEmpty());
        assertEquals(2, received.get());
        assertEquals(2, reservations.size());
        assertNotEquals(reservations.get(0), reservations.get(1), "a compensation gets a fresh reservation");
        assertEquals(List.of(false), terminalFlags, "the deferred attempt is not sealed");
        assertEquals(Phase.READY, fixture.finish(job.id()).phase());
        probe("evaluation-3s", "provider_wait_ms", 3000, "received_before", 1,
                "received_at_2800ms", at2800, "received_after_wait", 2,
                "reservations", List.copyOf(reservations), "compensations", recovery.compensations(),
                "phase", "READY");
    }

    @Test void sixtySecondWaitIsRefusedByTheFenceOwnerBeforeAnySecondSend() throws Exception {
        script.add(rateLimited("60"));
        var fixture = fixture(20_000);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        var stored = fixture.store.get(job.id());
        assertEquals(Phase.FAILED, stored.phase());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", stored.errorCode());
        assertEquals(1, received.get());
        assertEquals(1, reservations.size());
        assertEquals(0, recovery.compensations(), "an unpayable wait is never counted");
        assertEquals(1, recovery.rows());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", lastRow().path("decision").asText());
        assertEquals(false, lastRow().path("compensate").asBoolean());
        probe("evaluation-60s", "provider_wait_ms", 60000, "received", received.get(),
                "reservations", List.copyOf(reservations), "compensations", recovery.compensations(),
                "phase", stored.phase().name(), "error", stored.errorCode(),
                "row_decision", lastRow().path("decision").asText());
    }

    @Test void remainingBudgetShorterThanTheWaitStopsWithoutEnqueueing() throws Exception {
        script.add(rateLimited("3"));
        var fixture = fixture(1_500);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        var stored = fixture.store.get(job.id());
        assertEquals(Phase.FAILED, stored.phase());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", stored.errorCode());
        assertEquals(1, received.get());
        assertEquals(1, reservations.size());
        assertEquals(0, recovery.compensations());
        assertEquals("RETRY_AFTER_BEYOND_WAIT_BUDGET", lastRow().path("decision").asText());
        probe("evaluation-budget-1500ms-vs-3000ms", "job_budget_ms", 1500,
                "provider_wait_ms", 3000, "received", received.get(),
                "reservations", List.copyOf(reservations), "compensations", recovery.compensations(),
                "phase", stored.phase().name(), "error", stored.errorCode(),
                "row_decision", lastRow().path("decision").asText());
    }

    @Test void temporaryServerErrorRecoversWithAFreshReservation() throws Exception {
        script.add(exchange -> reply(exchange, 503, "{\"error\":\"busy\"}", null));
        var fixture = fixture(20_000);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        assertEquals(1, received.get());
        Thread.sleep(700);
        assertTrue(fixture.service.advance(job.id(), null, () -> {}).isEmpty());
        assertEquals(2, received.get());
        assertEquals(2, reservations.size());
        assertNotEquals(reservations.get(0), reservations.get(1));
        assertEquals(Phase.READY, fixture.finish(job.id()).phase());
    }

    @Test void aConnectionThatNeverOpenedRecoversWithANewReservation() throws Exception {
        var flaky = new FlakyTransport(ProviderHttpTransport.using(HttpClient.newHttpClient()));
        var fixture = fixture(20_000, flaky);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        assertEquals(1, flaky.attempts.get());
        assertEquals(0, received.get());
        assertEquals(1, reservations.size());
        Thread.sleep(700);
        assertTrue(fixture.service.advance(job.id(), null, () -> {}).isEmpty());
        assertEquals(2, flaky.attempts.get());
        assertEquals(1, received.get());
        assertEquals(2, reservations.size());
        assertNotEquals(reservations.get(0), reservations.get(1));
        assertEquals(Phase.READY, fixture.finish(job.id()).phase());
    }

    @Test void aBrokenBodyIsTerminalAndIsNeverResent() throws Exception {
        var broken = new FailingTransport(new IOException("broken stream"));
        var fixture = fixture(20_000, broken);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        var stored = fixture.store.get(job.id());
        assertEquals(Phase.FAILED, stored.phase());
        assertEquals("NOT_RECOVERABLE_READ_BROKEN", stored.errorCode());
        assertEquals(1, broken.attempts.get());
        assertEquals(0, received.get());
        assertEquals(1, reservations.size());
        assertEquals("NOT_RECOVERABLE_READ_BROKEN", lastRow().path("decision").asText());
        probe("evaluation-broken-body", "transport_attempts", broken.attempts.get(),
                "received", received.get(), "reservations", List.copyOf(reservations),
                "phase", stored.phase().name(), "error", stored.errorCode(),
                "row_decision", lastRow().path("decision").asText());
    }

    @Test void aReadTimeoutIsTerminalAndIsNeverResent() throws Exception {
        var silent = new SilentTransport();
        var fixture = fixture(1_500, silent);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        var stored = fixture.store.get(job.id());
        assertEquals(Phase.FAILED, stored.phase());
        assertEquals("NOT_RECOVERABLE_WAIT_TIMEOUT", stored.errorCode());
        assertEquals(1, silent.attempts.get());
        assertEquals(0, received.get());
        probe("evaluation-read-timeout", "transport_attempts", silent.attempts.get(),
                "received", received.get(), "reservations", List.copyOf(reservations),
                "phase", stored.phase().name(), "error", stored.errorCode());
    }

    @Test void cancelDuringTheWaitSendsNothingElse() throws Exception {
        script.add(rateLimited("3"));
        var fixture = fixture(20_000);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        assertEquals(1, received.get());
        fixture.service.cancel(job.id());
        Thread.sleep(3_200);
        fixture.service.advance(job.id());
        assertEquals(1, received.get());
        assertEquals(Phase.CANCELLED, fixture.store.get(job.id()).phase());
    }

    @Test void aFeeRefusalStopsTheAlreadyScheduledCompensation() throws Exception {
        script.add(rateLimited("0"));
        var fixture = fixture(20_000);
        admission.refuseAfter = 1;
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        fixture.service.advance(job.id(), null, () -> {});
        assertEquals(1, received.get());
        // The retry is scheduled at once (Retry-After: 0); the second admission is refused.
        fixture.service.advance(job.id(), null, () -> {});
        assertEquals(1, received.get(), "a refused fee admission must never reach the double");
        assertEquals(1, reservations.size());
        assertFalse(MemoryFeedbackJobStore.active(fixture.store.get(job.id()).phase())
                && fixture.store.get(job.id()).phase() == Phase.ANALYZING,
                "a fee refusal is not a healthy retry state: " + fixture.store.get(job.id()).phase());
    }

    @Test void aFailedDiagnosticRowStopsTheCompensation() throws Exception {
        Path blocked = Files.createTempFile("analysis-diagnostic-", ".jsonl");
        Files.writeString(blocked, "not a directory\n");
        try {
            script.add(rateLimited("0"));
            var fixture = fixture(20_000, null, blocked);
            var job = fixture.start(2);
            fixture.service.advance(job.id());
            fixture.service.advance(job.id(), null, () -> {});
            var stored = fixture.store.get(job.id());
            assertEquals(Phase.FAILED, stored.phase());
            assertEquals("DIAGNOSTIC_WRITE_FAILED", stored.errorCode());
            assertEquals(1, received.get(), "no resend when the decision cannot be recorded");
            assertEquals(0, recovery.compensations());
            assertEquals(0, recovery.rows());
            assertEquals(1, recovery.diagnosticFailures());
            probe("evaluation-diagnostic-unwritable", "received", received.get(),
                    "reservations", List.copyOf(reservations), "compensations", recovery.compensations(),
                    "rows", recovery.rows(), "diagnostic_failures", recovery.diagnosticFailures(),
                    "phase", stored.phase().name(), "error", stored.errorCode());
        } finally {
            Files.deleteIfExists(blocked);
        }
    }

    @Test void persistentRateLimitStopsAtTwoCompensationsForOneLogicalCall() throws Exception {
        for (int index = 0; index < 3; index++) script.add(rateLimited("0"));
        var fixture = fixture(20_000);
        var job = fixture.start(2);
        fixture.service.advance(job.id());
        for (int attempt = 1; attempt <= 3; attempt++)
            fixture.service.advance(job.id(), null, () -> {});
        assertEquals(3, received.get(), "the first send plus exactly two compensations");
        assertEquals(3, reservations.size());
        assertEquals(2, recovery.compensations(), "the per-call ceiling is two");
        assertEquals(3, recovery.rows());
        assertEquals("COMPENSATION_ALREADY_USED", lastRow().path("decision").asText());
        assertEquals(Phase.FAILED, fixture.store.get(job.id()).phase());
        probe("evaluation-per-call-cap", "received", received.get(),
                "reservations", List.copyOf(reservations), "compensations", recovery.compensations(),
                "rows", recovery.rows(), "last_decision", lastRow().path("decision").asText(),
                "phase", fixture.store.get(job.id()).phase().name());
    }

    // ------------------------------------------------------------------------------- fixture

    private static void probe(String label, Object... pairs) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("probe", label);
        for (int index = 0; index + 1 < pairs.length; index += 2)
            row.put(String.valueOf(pairs[index]), pairs[index + 1]);
        try {
            System.out.println("RETRY_PROBE " + JSON.writeValueAsString(row));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Fixture fixture(long budgetMillis) throws Exception {
        return fixture(budgetMillis, null);
    }

    private Fixture fixture(long budgetMillis, ProviderHttpTransport transport) throws Exception {
        return fixture(budgetMillis, transport, directory.resolve("transport"));
    }

    private Fixture fixture(long budgetMillis, ProviderHttpTransport transport, Path diagnosticsRoot)
            throws Exception {
        Files.createDirectories(directory);
        if (!diagnosticsRoot.equals(directory.resolve("transport"))) {
            // The blocked cell is a FILE path; let EvaluationRecovery fail on it.
        } else {
            Files.createDirectories(diagnosticsRoot);
        }
        recovery = new EvaluationRecovery(diagnosticsRoot);
        admission = new Admission(recovery);
        EvaluationFeeGate.install(admission);
        return new Fixture(budgetMillis, transport);
    }

    private DeepSeekProperties properties() {
        return new DeepSeekProperties(URI.create("http://127.0.0.1:" + port),
                "synthetic-local-only", MODEL, MODEL, Duration.ofSeconds(2), 384, 1024);
    }

    private JsonNode lastRow() throws IOException {
        var file = recovery == null ? null : directory.resolve("transport").resolve("transport-diagnostic.jsonl");
        // The blocked cell never writes a row; the real cells use the fixture path.
        if (file == null || !Files.exists(file)) return JSON.createObjectNode();
        var lines = Files.readAllLines(file);
        return lines.isEmpty() ? JSON.createObjectNode() : JSON.readTree(lines.get(lines.size() - 1));
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

    final class Fixture {
        final MemoryFeedbackJobStore store = new MemoryFeedbackJobStore();
        final AccessScope owner = new AccessScope("tenant", "881", Set.of(1001L), "opaque-ref");
        final Request request = new Request(new DanmakuTypes.Query(1001,
                LocalDateTime.of(2026, 10, 20, 0, 0), LocalDateTime.of(2026, 10, 21, 0, 0)),
                Set.of(Source.COMMENT, Source.DANMAKU), Grounding.FEEDBACK_ONLY, null, null, "feedback-v1");
        final FeedbackObjects objects;
        final FeedbackSnapshotPort source;
        final FeedbackAnalysisService service;
        final DeepSeekClient client;
        int total;
        final Instant deadline;

        Fixture(long budgetMillis, ProviderHttpTransport transport) {
            deadline = Instant.now().plusMillis(budgetMillis);
            objects = new FeedbackObjects(new ObjectStore() {
                final FileObjectStore delegate = new FileObjectStore(directory.resolve("objects"));
                public void put(String key, byte[] bytes) { delegate.put(key, bytes); }
                public byte[] get(String key, long size, String hash) { return delegate.get(key, size, hash); }
                public void delete(String key) { delegate.delete(key); }
            }, JSON);
            client = new DeepSeekClient(properties(), JSON, ContextPolicy.defaults(), null, transport, recovery);
            source = new FeedbackSnapshotPort() {
                public void authorize(AccessScope scope, long id) { }
                public Receipt export(Request r, AccessScope o, CallControl c, Consumer<List<Unit>> sink) {
                    var units = new ArrayList<Unit>();
                    for (int i = 1; i <= total; i++) {
                        Source kind = i % 2 == 0 ? Source.COMMENT : Source.DANMAKU;
                        String original = "" + ((i + 1) / 2);
                        units.add(new Unit((kind == Source.COMMENT ? "C:" : "D:") + original, kind, original,
                                "观众反馈" + i, "", false, kind == Source.DANMAKU ? 1000L : null, "" + i));
                    }
                    if (!units.isEmpty()) sink.accept(units);
                    return new Receipt(Instant.parse("2026-10-21T00:00:00Z"),
                            new Counts(total / 2, total - total / 2, 0, 0, total));
                }
            };
            FeedbackModels models = new FeedbackModels() {
                public String modelName() { return MODEL; }
                public String policyHash() { return "fixture-v1"; }
                public List<Proposal> analyze(Input input, CallControl control) {
                    client.complete(MODEL, List.of(Map.of("role", "user", "content", "synthetic")),
                            256, control, true);
                    return List.of(new Proposal("T1", "问题与反馈", "保留输入的讨论依据及不同意见。", "",
                            input.groups().stream().map(Group::id).toList()));
                }
            };
            service = new FeedbackAnalysisService(store, objects, source, models,
                    new FeedbackPolicy(67108864, 24000, 40, 2000, 16000000,
                            Duration.ofHours(1), Duration.ofSeconds(30)));
            service.clock(TASK_CLOCK);
        }

        FeedbackJobStore.Job start(int count) {
            total = count;
            return service.submit(UUID.randomUUID(), owner, request, null, deadline);
        }

        FeedbackJobStore.Job finish(UUID id) {
            for (int i = 0; i < 200; i++) {
                var job = store.get(id);
                if (!MemoryFeedbackJobStore.active(job.phase())) return job;
                service.advance(id);
            }
            return fail("job did not converge");
        }
    }

    final class Admission implements EvaluationFeeGate.Admission {
        final EvaluationRecovery policy;
        final AtomicInteger sequence = new AtomicInteger();
        volatile int refuseAfter = -1;
        Admission(EvaluationRecovery policy) { this.policy = policy; }

        @Override public void beforeFeedbackAttempt(int attempt) { }
        @Override public void feedbackFailed(String code) { }
        @Override public void feedbackFailed(String code, String reason, String stage) { }
        @Override public void feedbackFailed(String code, String reason, String stage, boolean compensated) { }
        @Override public void toolRejected(EvaluationFeeGate.ToolRejection rejection) { }
        @Override public void toolCorrected(String runId, String tool, String phase, int correction) { }
        @Override public void toolStopped(String runId, String tool, String phase, String code, int corrections) { }

        @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long bytes, int max) {
            return admitChat(model, host, bytes, max, "analysis");
        }

        @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long bytes,
                int max, String role) {
            int index = sequence.incrementAndGet();
            if (refuseAfter >= 0 && index > refuseAfter)
                throw new EvaluationFeeLedger.LedgerBusy("synthetic fee refusal");
            String id = "fee-analysis-" + index;
            reservations.add(id);
            return new EvaluationFeeGate.Reservation() {
                public String reservationId() { return id; }
                public void settle(JsonNode response) { }
                public void fail(String reason) { fail(reason, true); }
                public void fail(String reason, boolean terminal) {
                    failures.add(id);
                    terminalFlags.add(terminal);
                }
                public void bindRequestDigest(long bodyBytes, String bodySha256) { }
            };
        }

        @Override public EvaluationFeeGate.Reservation admitVideo(String provider, String path) {
            throw new AgentException("EXIT_REFUSED", "No video exit in this analysis fixture");
        }

        @Override public int analysisRetryCeiling(TransportFault fault, ModelRecovery.CallAttempt attempt) {
            return policy.analysisRetryCeiling(fault, attempt);
        }

        @Override public EvaluationFeeGate.AnalysisRetry planAnalysisRetry(TransportFault fault,
                ModelRecovery.CallAttempt attempt, int durableAttempt, int productionMaxAttempts,
                long remainingMillis) {
            return policy.planAnalysisRetry(fault, attempt, durableAttempt, productionMaxAttempts,
                    remainingMillis);
        }
    }

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

    static final class FailingTransport implements ProviderHttpTransport {
        final AtomicInteger attempts = new AtomicInteger();
        final IOException failure;
        FailingTransport(IOException failure) { this.failure = failure; }
        @Override public <T> CompletableFuture<HttpResponse<T>> send(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, byte[] payload, EvaluationFeeGate.Reservation reservation) {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(failure);
        }
    }

    static final class SilentTransport implements ProviderHttpTransport {
        final AtomicInteger attempts = new AtomicInteger();
        @Override public <T> CompletableFuture<HttpResponse<T>> send(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, byte[] payload, EvaluationFeeGate.Reservation reservation) {
            attempts.incrementAndGet();
            return new CompletableFuture<>();
        }
    }
}
