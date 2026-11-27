package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4B-1 fee admission at the real dispatch exits (Repair-01 semantics). Every client-loop
 * test drives the REAL clients against localhost doubles; the dispatch settles its own
 * reservation from the raw provider response and no test ever settles manually to close a client
 * loop. Ledger-only tests exercise the persistence API directly. Semantics under test:
 * reservation before dispatch; missing usage turns USAGE_UNKNOWN after exactly one dispatch and
 * blocks the next; failures keep the reservation (FAILED_RETAINED); retries are fresh
 * admissions; pause at 2000 fen refuses before crossing; hard limit at 5000 fen; the frozen
 * price snapshot re-prices nothing after a reopen; a second writer on the same ledger file is
 * refused (LedgerBusy); the armed gate refuses vision/audio exits outright (no verified billing
 * unit); the disarmed gate leaves production byte-identical.
 *
 * Repair-01 change notes (test-strength accounting, targets preserved): the previous tests
 * manually settled open reservations to simulate outcomes — that masked the missing R1 loop and
 * is now forbidden; the pause/hard-limit scenarios drive real dispatches (stronger); the restart
 * test closes the owner first (new single-writer lifecycle) and asserts the same persistence
 * target plus the frozen-price guarantee.
 */
class EvaluationFeeGateTest {
    private static final String KEY = "synthetic-test-key";
    private static final String MODEL = "deepseek-test";
    private static final String HOST = "127.0.0.1";
    private static final long PAUSE_FEN = 2000;
    private static final long TOTAL_FEN = 5000;
    private static final Map<String, EvaluationFeeLedger.Price> PRICES = Map.of(
            "chat:" + HOST + ":" + MODEL, new EvaluationFeeLedger.Price(210, 840));

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger dispatched = new AtomicInteger();
    /** Per-dispatch usage the double will report; ("promptTokens", "completionTokens") or null for none. */
    final AtomicReference<long[]> stubUsage = new AtomicReference<>(new long[]{10, 5});
    /** 4B-2A: the raw request bytes the double actually received, one entry per dispatch. */
    final CopyOnWriteArrayList<byte[]> receivedBodies = new CopyOnWriteArrayList<>();
    private int port;
    private Path ledgerFile;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            receivedBodies.add(exchange.getRequestBody().readAllBytes());
            dispatched.incrementAndGet();
            long[] usage = stubUsage.get();
            String usagePart = usage == null ? "" :
                    ",\"usage\":{\"prompt_tokens\":" + usage[0] + ",\"completion_tokens\":" + usage[1] + "}";
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},"
                    + "\"finish_reason\":\"stop\"}]" + usagePart + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        port = server.getAddress().getPort();
        ledgerFile = Files.createTempFile("admission-fee-", ".json");
        Files.deleteIfExists(ledgerFile);
    }

    @AfterEach void stop() {
        EvaluationFeeGate.clear();
        server.stop(0);
    }

    DeepSeekClient client() {
        return new DeepSeekClient(new DeepSeekProperties(URI.create("http://127.0.0.1:" + port), KEY,
                MODEL, MODEL, Duration.ofSeconds(1), 384, 1024), json);
    }

    CallControl control() {
        return new CallControl(Instant.now().plusSeconds(10), new CancellationToken());
    }

    void admit(DeepSeekClient client, CallControl control) {
        client.complete(MODEL, List.of(Map.of("role", "user", "content", "x")), 256, control, true);
    }

    JsonNode usage(long promptTokens, long completionTokens) {
        var usage = json.createObjectNode();
        usage.putObject("usage").put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens);
        return usage;
    }

    /** Test-only counting Admission: records admit/settle/fail per exit without ledger pricing.
     * Lets the client-side guarantee (exactly one admission per real dispatch) be observed
     * directly; the ledger's refusal for unverified vision billing units is tested separately. */
    static final class CountingAdmission implements EvaluationFeeGate.Admission {
        final java.util.List<String> admits = new java.util.ArrayList<>();
        final java.util.List<String> settles = new java.util.ArrayList<>();
        final java.util.List<String> fails = new java.util.ArrayList<>();
        @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes, int maxTokens) {
            admits.add("chat:" + model);
            return reservation("chat:" + model);
        }
        @Override public EvaluationFeeGate.Reservation admitVideo(String provider, String path) {
            admits.add(path);
            return reservation(path);
        }
        private EvaluationFeeGate.Reservation reservation(String label) {
            return new EvaluationFeeGate.Reservation() {
                public String reservationId() { return label + "#" + admits.size(); }
                public void settle(com.fasterxml.jackson.databind.JsonNode response) { settles.add(label); }
                public void fail(String reason) { fails.add(label + ":" + reason); }
            };
        }
    }

    @Test void eachVisionDispatchAdmitsAndSettlesExactlyOnce() throws Exception {
        // Acceptance counterexample: pre-fix the stale hook in sendUnbudgeted admitted a second
        // time per dispatch (two admits, one settle, one dangling reservation per HTTP send).
        HttpServer visionServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger visionSends = new AtomicInteger();
        visionServer.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            visionSends.incrementAndGet();
            byte[] body = "{\"base_resp\":{\"status_code\":0},\"content\":\"{}\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        visionServer.start();
        try {
            var counting = new CountingAdmission();
            EvaluationFeeGate.install(counting);
            var visionClient = new com.tianmu.agent.video.VideoModelClient(
                    new com.tianmu.agent.video.VideoModelProperties(false, "minimax",
                            URI.create("http://127.0.0.1:" + visionServer.getAddress().getPort()),
                            KEY, "placeholder-model", "placeholder-audio"), json);
            var media = new com.tianmu.agent.core.VideoContent.Media(
                    new com.tianmu.agent.core.VideoContent.Source(1001L, "v1", 10_000L), 0, 5_000,
                    List.of(new com.tianmu.agent.core.VideoContent.Frame(100, jpeg()),
                            new com.tianmu.agent.core.VideoContent.Frame(400, jpeg())), "");
            visionClient.understand(media, "外观", "", control());
            visionClient.understand(media, "外观", "", control());
            assertEquals(2, visionSends.get());
            assertEquals(visionSends.get(), counting.admits.size(),
                    "every real vision dispatch must admit exactly once");
            assertEquals(visionSends.get(), counting.settles.size(),
                    "every completed vision dispatch must settle exactly once");
            assertEquals(0, counting.fails.size());
        } finally {
            EvaluationFeeGate.clear();
            visionServer.stop(0);
        }
    }

    @Test void eachChatDispatchAdmitsAndSettlesExactlyOnce() {
        var counting = new CountingAdmission();
        EvaluationFeeGate.install(counting);
        stubUsage.set(new long[]{10, 5});
        admit(client(), control());
        admit(client(), control());
        assertEquals(2, dispatched.get());
        assertEquals(dispatched.get(), counting.admits.size(),
                "every real chat dispatch must admit exactly once");
        assertEquals(dispatched.get(), counting.settles.size(),
                "every completed chat dispatch must settle exactly once");
        assertEquals(0, counting.fails.size());
    }

    @Test void disarmedGateLeavesProductionBehaviorUnchanged() {
        EvaluationFeeGate.clear();
        admit(client(), control());
        assertEquals(1, dispatched.get(), "disarmed gate must not block any dispatch");
    }

    @Test void missingPriceBlocksBeforeNetworkDispatch() {
        EvaluationFeeGate.install(new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, Map.of()));
        var failure = assertThrows(EvaluationFeeLedger.Blocked.class, () -> admit(client(), control()));
        assertTrue(failure.getMessage().contains("NEEDS_PRICING"));
        assertEquals(0, dispatched.get(), "refusal must happen before the request leaves");
    }

    @Test void missingUsageTurnsUnknownAfterOneDispatchAndBlocksTheNext() {
        stubUsage.set(null); // the double answers 200 without usage, twice if allowed
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        EvaluationFeeGate.install(ledger);
        admit(client(), control()); // first dispatch completes; its own response settles as unknown
        assertEquals(1, dispatched.get());
        // USAGE_UNKNOWN reservations are not "open"; the retained reserve shows in the exposure.
        var exposed = ledger.conservativeUsedFen();
        assertTrue(exposed >= 2, "the full reservation is retained: " + exposed);
        var failure = assertThrows(EvaluationFeeLedger.Blocked.class, () -> admit(client(), control()));
        assertTrue(failure.getMessage().contains("BLOCKED") || failure.getMessage().contains("PAUSED"),
                "the second dispatch must be refused, saw: " + failure.getMessage());
        assertEquals(1, dispatched.get(), "no second HTTP dispatch after unknown usage");
        ledger.close();
    }

    @Test void streamingDispatchSettlesFromItsOwnStreamResult() throws Exception {
        HttpServer sse = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger sseCount = new AtomicInteger();
        sse.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sseCount.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":20}}\n\n".getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally { exchange.close(); }
        });
        sse.start();
        try {
            var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
            EvaluationFeeGate.install(ledger);
            var streamingClient = new DeepSeekClient(new DeepSeekProperties(
                    URI.create("http://127.0.0.1:" + sse.getAddress().getPort()), KEY,
                    MODEL, MODEL, Duration.ofSeconds(1), 384, 1024), json);
            var chunks = new CopyOnWriteArrayList<String>();
            var streaming = control().withTextOutput(chunks::add);
            streamingClient.completeHarness(MODEL, List.of(Map.of("role", "user", "content", "x")),
                    List.of(new com.tianmu.agent.core.AgentTypes.ToolDefinition("analyze_comments", "d", Map.of())),
                    256, streaming);
            assertEquals(1, sseCount.get());
            // The stream settled its own reservation: no OPEN remains, exposure is the real usage cost.
            assertEquals(0, ledger.openReservations().size());
            // 30*210/1M ceiling = 1 fen; 20*840/1M ceiling = 1 fen
            assertEquals(2, ledger.conservativeUsedFen(), "the stream dispatch settles from its own result");
            ledger.close();
        } finally {
            sse.stop(0);
        }
    }

    @Test void dispatchFailureKeepsReservationRetainedAndRetryIsFresh() {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        EvaluationFeeGate.install(ledger);
        stubUsage.set(null);
        admit(client(), control()); // dispatch 1: succeeds but usage missing -> USAGE_UNKNOWN blocks
        var exposed = ledger.conservativeUsedFen();
        assertThrows(EvaluationFeeLedger.Blocked.class, () -> admit(client(), control()));
        assertEquals(exposed, ledger.conservativeUsedFen(), "unknown usage keeps the whole reservation");
        ledger.close();
    }

    @Test void timeoutFailureMarksRetainedThroughTheClientLoop() throws Exception {
        // The double answers 500: the dispatch fails after admission, the client's failure path
        // marks FAILED_RETAINED, and the retained cost keeps counting toward the pause line.
        HttpServer failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        failing.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            dispatched.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        failing.start();
        try {
            var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
            EvaluationFeeGate.install(ledger);
            var failingClient = new DeepSeekClient(new DeepSeekProperties(
                    URI.create("http://127.0.0.1:" + failing.getAddress().getPort()), KEY,
                    MODEL, MODEL, Duration.ofSeconds(1), 384, 1024), json);
            assertThrows(com.tianmu.agent.core.AgentException.class,
                    () -> admit(failingClient, control()));
            assertEquals(0, ledger.openReservations().size(), "failed reservation is no longer open");
            var exposed = ledger.conservativeUsedFen();
            assertTrue(exposed >= 2, "FAILED_RETAINED keeps its reserve in the conservative total: " + exposed);
            // A retry is a fresh admission with its own reservation, never a reuse of the failed one.
            admit(client(), control());
            assertEquals(2, dispatched.get());
            assertTrue(ledger.conservativeUsedFen() > exposed, "retry adds its own reservation");
            assertTrue(EvaluationFeeGate.armed(), "a failed dispatch must never unload the gate");
            ledger.close();
        } finally {
            failing.stop(0);
        }
    }

    @Test void socketTimeoutKeepsGateArmedAndReservationRetained() throws Exception {
        // Batch-2 port supplement: a REAL request timeout (not a 500) must end as FAILED_RETAINED,
        // never as a released reservation, and the gate must stay armed — nothing in the client
        // release path is allowed to unload it early.
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.setExecutor(Executors.newCachedThreadPool());
        slow.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            dispatched.incrementAndGet();
            try { Thread.sleep(3_000); } catch (InterruptedException ignored) { }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slow.start();
        try {
            var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
            EvaluationFeeGate.install(ledger);
            var slowClient = new DeepSeekClient(new DeepSeekProperties(
                    URI.create("http://127.0.0.1:" + slow.getAddress().getPort()), KEY,
                    MODEL, MODEL, Duration.ofSeconds(1), 384, 1024), json);
            var expired = new CallControl(Instant.now().plusMillis(400), new CancellationToken());
            assertThrows(RuntimeException.class, () -> admit(slowClient, expired));
            assertEquals(1, dispatched.get(), "the timed-out dispatch really left the process");
            assertEquals(0, ledger.openReservations().size(), "a timed-out reservation is never left OPEN");
            var row = ledgerDoc(ledgerFile).path("reservations").path(0);
            assertEquals("FAILED_RETAINED", row.path("status").asText(),
                    "a timeout keeps the reservation counted instead of releasing it");
            assertFalse(row.path("fail_reason").asText("").isEmpty(), "the failure reason is recorded");
            assertTrue(ledger.conservativeUsedFen() >= row.path("reserved_fen").asLong(),
                    "the retained reserve still counts toward the envelope");
            assertTrue(EvaluationFeeGate.armed(), "a timeout must never unload the gate");
            ledger.close();
        } finally {
            slow.stop(0);
        }
    }

    @Test void reachingPauseLineThroughSettledUsageBlocksNewDispatch() {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        EvaluationFeeGate.install(ledger);
        stubUsage.set(new long[]{2_000_000, 2_000_000}); // the double reports 2100 fen of usage
        admit(client(), control()); // dispatch happens, settles at the frozen prices
        assertEquals(1, dispatched.get());
        assertTrue(ledger.conservativeUsedFen() >= PAUSE_FEN);
        var failure = assertThrows(EvaluationFeeLedger.Blocked.class, () -> admit(client(), control()));
        assertTrue(failure.getMessage().contains("PAUSED"));
        assertEquals(1, dispatched.get(), "in-flight work finished before the line; only new dispatch stops");
        ledger.close();
    }

    @Test void hardLimitRefusesDispatchThatWouldCrossFifty() {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        var first = ledger.admitChat(MODEL, HOST, 1_000_000, 1_000_000); // 1050 fen reserved
        ledger.settle(first.reservationId(), usage(5_000_000, 1_000_000)); // 1890 fen known
        assertEquals(1890, ledger.knownSettledFen());
        var failure = assertThrows(EvaluationFeeLedger.Blocked.class,
                () -> ledger.admitChat(MODEL, HOST, 4_000_000, 4_000_000)); // would push past 5000
        assertTrue(failure.getMessage().contains("EXHAUSTED"), failure.getMessage());
        ledger.close();
    }

    @Test void frozenPriceSnapshotSurvivesReopenWithDifferentPrices() {
        var first = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        var reservation = first.admitChat(MODEL, HOST, 1_000_000, 1_000_000); // 1050 fen at 210/840
        first.close();
        var reopened = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN,
                Map.of("chat:" + HOST + ":" + MODEL, new EvaluationFeeLedger.Price(0, 0)));
        reopened.settle(reservation.reservationId(), usage(1_000_000, 1_000_000));
        var settled = reopened.reservation(reservation.reservationId());
        assertEquals("SETTLED", settled.path("status").asText());
        assertEquals(1050, settled.path("actual_fen").asLong(),
                "settlement must use the record's frozen price snapshot, never the reopened table");
        assertEquals(1050, reopened.knownSettledFen());
        reopened.close();
    }

    @Test void duplicateSettlementRefused() {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        var first = ledger.admitChat(MODEL, HOST, 100, 100);
        ledger.settle(first.reservationId(), usage(10, 5));
        assertThrows(EvaluationFeeLedger.Blocked.class, () -> ledger.settle(first.reservationId(), usage(10, 5)));
        ledger.close();
    }

    @Test void secondWriterOnTheSameLedgerFileIsRefused() {
        var first = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        try {
            var second = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
            second.close();
            fail("a second writer on the same ledger file must be refused");
        } catch (EvaluationFeeLedger.LedgerBusy expected) {
            assertTrue(expected.getMessage().contains("another writer"));
        }
        first.admitChat(MODEL, HOST, 1_000_000, 1_000_000); // 1050 fen
        first.close();
        // After the owner closes, the next opener sees the latest state and enforces the pause line.
        var reopened = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        assertEquals(1, reopened.openReservations().size(), "open reservations survive the restart");
        var refusal = assertThrows(EvaluationFeeLedger.Blocked.class,
                () -> reopened.admitChat(MODEL, HOST, 1_000_000, 1_000_000));
        assertTrue(refusal.getMessage().contains("PAUSED"), refusal.getMessage());
        reopened.close();
    }

    @Test void sameInstanceConcurrentAdmissionsAreSerializedWithinThePauseLine() throws Exception {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var successes = new AtomicInteger();
            var refusals = new AtomicInteger();
            List<Future<?>> jobs = List.of(
                pool.submit(() -> { try { start.await(); ledger.admitChat(MODEL, HOST, 1_000_000, 1_000_000); successes.incrementAndGet(); } catch (EvaluationFeeLedger.Blocked e) { refusals.incrementAndGet(); } catch (Exception e) { throw new RuntimeException(e); } }),
                pool.submit(() -> { try { start.await(); ledger.admitChat(MODEL, HOST, 1_000_000, 1_000_000); successes.incrementAndGet(); } catch (EvaluationFeeLedger.Blocked e) { refusals.incrementAndGet(); } catch (Exception e) { throw new RuntimeException(e); } }));
            start.countDown();
            for (Future<?> job : jobs) job.get(5, TimeUnit.SECONDS);
            assertEquals(1, successes.get(), "one admission of 1200+1200 crosses the pause line, so exactly one passes");
            assertEquals(1, refusals.get());
            assertTrue(ledger.conservativeUsedFen() < PAUSE_FEN + 100);
        } finally {
            pool.shutdownNow();
            ledger.close();
        }
    }

    @Test void armedGateRefusesVideoAndAudioExitsWithoutVerifiedBillingUnit() throws Exception {
        HttpServer visionServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger visionDispatched = new AtomicInteger();
        visionServer.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            visionDispatched.incrementAndGet();
            byte[] body = "{\"base_resp\":{\"status_code\":0},\"content\":\"{}\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        visionServer.start();
        try {
            var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
            EvaluationFeeGate.install(ledger);
            var media = new com.tianmu.agent.core.VideoContent.Media(
                    new com.tianmu.agent.core.VideoContent.Source(1001L, "v1", 10_000L), 0, 5_000,
                    List.of(new com.tianmu.agent.core.VideoContent.Frame(100, jpeg()),
                            new com.tianmu.agent.core.VideoContent.Frame(400, jpeg())), "");
            var visionClient = new com.tianmu.agent.video.VideoModelClient(
                    new com.tianmu.agent.video.VideoModelProperties(false, "minimax",
                            URI.create("http://127.0.0.1:" + visionServer.getAddress().getPort()),
                            KEY, "placeholder-model", "placeholder-audio"), json);
            var failure = assertThrows(com.tianmu.agent.core.AgentException.class,
                    () -> visionClient.understand(media, "外观", "", control()));
            assertTrue(failure.getMessage().contains("NEEDS_PRICING"),
                    "video exit must be refused without a verified billing unit");
            assertEquals(0, visionDispatched.get(), "the refused exit must not reach the network");
            ledger.close();
        } finally {
            visionServer.stop(0);
        }
    }

    @Test void oversizedEstimateIsAControlledRefusal() {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        var failure = assertThrows(EvaluationFeeLedger.Blocked.class,
                () -> ledger.admitChat(MODEL, HOST, Long.MAX_VALUE / 2, 1));
        assertTrue(failure.getMessage().contains("REFUSED") || failure.getMessage().contains("EXHAUSTED")
                || failure.getMessage().contains("PAUSED"), failure.getMessage());
        ledger.close();
    }

    @Test void corruptedLedgerFileIsRefusedWithoutRewrite() throws Exception {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        ledger.admitChat(MODEL, HOST, 100, 100);
        ledger.close();
        byte[] broken = "{\"schema\":\"fee-ledger-v1\",\"total_fen\":5000,\"pause_fen\":2000,\"reservations\":[{}]}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(ledgerFile, broken);
        assertThrows(EvaluationFeeLedger.Corrupted.class,
                () -> new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES));
        assertArrayEquals(broken, Files.readAllBytes(ledgerFile),
                "the refusing loader must leave the corrupt bytes untouched");
    }

    @Test void corruptRecordsOfEveryShapeAreRefused() throws Exception {
        record Case(String name, String doc) {}
        String open = "{\"reservation_id\":\"fee-x1\",\"exit\":\"chat:u\",\"status\":\"OPEN\",\"reserved_fen\":1200,"
                + "\"usage_tokens\":null,\"actual_fen\":null,\"price_identity\":\"" + "a".repeat(64) + "\","
                + "\"price_input_fen_per_million\":600,\"price_output_fen_per_million\":600,"
                + "\"attribution\":\"direct\",\"admitted_at\":\"2026-11-16T00:00:00Z\"}";
        List<Case> cases = List.of(
            new Case("empty_record", "{\"schema\":\"fee-ledger-v2\",\"total_fen\":5000,\"pause_fen\":2000,\"reservations\":[{}]}"),
            new Case("negative_reserved", "{\"schema\":\"fee-ledger-v2\",\"total_fen\":5000,\"pause_fen\":2000,\"reservations\":[" +
                    open.replace("\"reserved_fen\":1200", "\"reserved_fen\":-10000") + "]}"),
            new Case("settled_missing_actual", "{\"schema\":\"fee-ledger-v2\",\"total_fen\":5000,\"pause_fen\":2000,\"reservations\":[" +
                    open.replace("\"status\":\"OPEN\"", "\"status\":\"SETTLED\"").replace("\"usage_tokens\":null", "\"usage_tokens\":10") + "]}"),
            new Case("bool_amount", "{\"schema\":\"fee-ledger-v2\",\"total_fen\":5000,\"pause_fen\":2000,\"reservations\":[" +
                    open.replace("\"reserved_fen\":1200", "\"reserved_fen\":true") + "]}"),
            new Case("duplicate_id", "{\"schema\":\"fee-ledger-v2\",\"total_fen\":5000,\"pause_fen\":2000,\"reservations\":[" + open + "," + open + "]}"),
            new Case("old_schema_v1", "{\"schema\":\"fee-ledger-v1\",\"total_fen\":5000,\"pause_fen\":2000,\"reservations\":[]}"));
        for (Case c : cases) {
            Path file = Files.createTempFile("admission-corrupt-", ".json");
            Files.deleteIfExists(file);
            Files.writeString(file, c.doc(), StandardCharsets.UTF_8);
            assertThrows(EvaluationFeeLedger.Corrupted.class,
                    () -> new EvaluationFeeLedger(file, TOTAL_FEN, PAUSE_FEN, PRICES).close(),
                    c.name());
            Files.deleteIfExists(file);
        }
    }

    /** A real 8x8 JPEG so the contact-sheet composer accepts the sampled frames. */
    static String jpeg() throws Exception {
        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(0xFF0000));
        graphics.fillRect(0, 0, 8, 8);
        graphics.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // ------------------------------------------------------------------ 4B-2A content repair

    @Test void clientBindsTheSerializedRequestDigestAtTheSendBoundary() throws Exception {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        EvaluationFeeGate.install(ledger);
        admit(client(), control());
        assertEquals(1, dispatched.get());
        var reservation = ledgerDoc(ledgerFile).path("reservations").path(0);
        String digest = reservation.path("request_body_sha256").asText("");
        long bytes = reservation.path("request_body_bytes").asLong(-1);
        assertTrue(bytes > 0, "the send boundary must record a positive byte count");
        assertTrue(digest.matches("[0-9a-f]{64}"), "the send boundary must record a 64-hex sha256");
        byte[] sent = receivedBodies.get(0);
        assertEquals(sent.length, bytes, "the bound byte count must equal what the double received");
        assertEquals(EvaluationRunManifest.sha256(sent), digest,
                "the bound digest must equal the digest of the bytes the double received");
        ledger.close();
    }

    @Test void disarmedGateNeverComputesOrBindsADigest() throws Exception {
        admit(client(), control());
        assertEquals(1, dispatched.get(), "the disarmed path dispatches exactly as production does");
    }

    @Test void requestDigestBindRefusesBadShapesAndDifferingRebind() {
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
        var reservation = ledger.admitChat(MODEL, HOST, 100, 100);
        String id = reservation.reservationId();
        assertThrows(EvaluationFeeLedger.Blocked.class, () -> ledger.bindRequestDigest(id, 0, "a".repeat(64)),
                "a zero byte count is refused");
        assertThrows(EvaluationFeeLedger.Blocked.class, () -> ledger.bindRequestDigest(id, 10, "nothex"),
                "a non-hex digest is refused");
        assertThrows(EvaluationFeeLedger.Blocked.class, () -> ledger.bindRequestDigest(id, 10, null),
                "a missing digest is refused");
        assertThrows(EvaluationFeeLedger.Blocked.class, () -> ledger.bindRequestDigest(id, 10, "ABC".repeat(21) + "A"),
                "an uppercase digest is refused (the clients write lowercase hex)");
        ledger.bindRequestDigest(id, 1024, "a".repeat(64));
        ledger.bindRequestDigest(id, 1024, "a".repeat(64)); // identical re-bind is idempotent
        assertThrows(EvaluationFeeLedger.Blocked.class, () -> ledger.bindRequestDigest(id, 2048, "a".repeat(64)),
                "a differing re-bind is corruption and refused");
        var bound = ledger.reservation(id);
        assertEquals(1024, bound.path("request_body_bytes").asLong());
        assertEquals("a".repeat(64), bound.path("request_body_sha256").asText());
        ledger.close();
    }

    @Test void carriedInCountsTowardTheEnvelopeAndSurvivesReopen() {
        var carried = new EvaluationFeeLedger.CarriedIn(
                "4b2a-first-pair-release-20261119 + price-refresh probes (file-verified, provider reconciliation pending)",
                280, 24);
        var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES, carried);
        assertEquals(304, ledger.conservativeUsedFen(), "the envelope opens with the carried spend counted");
        // 304 + 1050 = 1354: fine; a second admission would reach 2404 >= the 2000 pause line.
        ledger.admitChat(MODEL, HOST, 1_000_000, 1_000_000);
        var refusal = assertThrows(EvaluationFeeLedger.Blocked.class,
                () -> ledger.admitChat(MODEL, HOST, 1_000_000, 1_000_000));
        assertTrue(refusal.getMessage().contains("PAUSED"), refusal.getMessage());
        ledger.close();
        // restart: the carried block AND the open reservation persist; the pause line still holds
        var reopened = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES, carried);
        assertEquals(1354, reopened.conservativeUsedFen(),
                "carried spend + the persisted reservation survive the restart");
        var afterRestart = assertThrows(EvaluationFeeLedger.Blocked.class,
                () -> reopened.admitChat(MODEL, HOST, 1_000_000, 1_000_000));
        assertTrue(afterRestart.getMessage().contains("PAUSED"), afterRestart.getMessage());
        reopened.close();
        // a mismatched re-declaration of the carried identity is refused in full
        var mismatched = new EvaluationFeeLedger.CarriedIn("some other source", 1, 0);
        assertThrows(EvaluationFeeLedger.Corrupted.class,
                () -> new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES, mismatched).close());
    }

    @Test void halfBoundRequestDigestRecordIsCorruption() throws Exception {
        String open = "{\"reservation_id\":\"fee-x1\",\"exit\":\"chat:u\",\"status\":\"OPEN\",\"reserved_fen\":1200,"
                + "\"usage_tokens\":null,\"actual_fen\":null,\"price_identity\":\"" + "a".repeat(64) + "\","
                + "\"price_input_fen_per_million\":600,\"price_output_fen_per_million\":600,"
                + "\"attribution\":\"direct\",\"admitted_at\":\"2026-11-16T00:00:00Z\","
                + "\"request_body_bytes\":512}";
        Files.writeString(ledgerFile, "{\"schema\":\"fee-ledger-v2\",\"total_fen\":5000,\"pause_fen\":2000,"
                + "\"reservations\":[" + open + "]}", StandardCharsets.UTF_8);
        assertThrows(EvaluationFeeLedger.Corrupted.class,
                () -> new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES).close(),
                "bytes without the sha256 half is corruption, not partial evidence");
    }

    // -------------------------------------------------- stop-protection (deferred)

    /** Batch-2 port note: the historical stopProtectionKeepsGateArmedUntilConfirmed case drives
     *  EvaluationRealFirstPairRunner's RunLedgerBridge and confirmStopBeforeGateUnload. That
     *  runner is a 1881-line live launcher that also needs the report-entry tests, so it moves in
     *  the report-integration batch. The property it pins — a failed dispatch never releases the
     *  gate early — is covered here by socketTimeoutKeepsGateArmedAndReservationRetained on the
     *  real client and ledger, without the launcher. */

    // -------------------------------------------------- live-readiness dual lines (2026-11-19)

    /** The POST-FIX flip of first-fail probe B: the launch path now opens its batch ledger with
     *  pause=1000 + the batch cap, and an admission crossing the cumulative line is refused
     *  BEFORE any dispatch (the probe crossed 304 -> 1106 unimpeded pre-fix). */
    @Test void launchPathThousandFenLineRefusesBeforeDispatch() {
        var carried = new EvaluationFeeLedger.CarriedIn("probe: launch-path parameters (carried history)", 280, 24);
        var ledger = new EvaluationFeeLedger(ledgerFile, 5000, 1000, PRICES, carried,
                new EvaluationFeeLedger.BatchCap("aligned batch ceiling", 1444));
        assertEquals(304, ledger.conservativeUsedFen());
        // 421-fen admissions (420 input + 1 output at the class price table 210/840):
        // 304 + 421 = 725 admitted; the next would reach 1146 >= the 1000-fen line
        ledger.admitChat(MODEL, HOST, 2_000_000, 1);
        var refusal = assertThrows(EvaluationFeeLedger.Blocked.class,
                () -> ledger.admitChat(MODEL, HOST, 2_000_000, 1));
        assertTrue(refusal.getMessage().contains("PAUSED"), refusal.getMessage());
        assertEquals(725, ledger.conservativeUsedFen(), "the refused admission must not count");
        ledger.close();
    }

    /** The batch's OWN new-spend cap fires while the cumulative total is still under the pause
     *  line — the two lines are enforced separately. */
    @Test void batchNewSpendCapFiresBeforeTheCumulativePauseLine() {
        var carried = new EvaluationFeeLedger.CarriedIn("probe carried", 280, 24);
        var ledger = new EvaluationFeeLedger(ledgerFile, 5000, 1000, PRICES, carried,
                new EvaluationFeeLedger.BatchCap("probe batch cap", 500));
        assertEquals(304, ledger.conservativeUsedFen());
        // 211-fen admissions (210 input + 1 output): new spend 211 -> 422 both fit under the
        // 500-fen batch cap, and the cumulative total stays under the 1000-fen pause line
        ledger.admitChat(MODEL, HOST, 1_000_000, 1);
        ledger.admitChat(MODEL, HOST, 1_000_000, 1);
        assertEquals(726, ledger.conservativeUsedFen());
        // the third would push the batch's new spend to 633 > 500 while the cumulative total
        // (937) is still under the pause line: the BATCH cap is what refuses, named as such
        var refusal = assertThrows(EvaluationFeeLedger.Blocked.class,
                () -> ledger.admitChat(MODEL, HOST, 1_000_000, 1));
        assertTrue(refusal.getMessage().contains("BATCH_CAPPED"), refusal.getMessage());
        assertEquals(726, ledger.conservativeUsedFen(), "the refused admission must not count");
        ledger.close();
        // a restart cannot lift the batch cap: the reopened ledger still refuses
        try (var reopened = new EvaluationFeeLedger(ledgerFile, 5000, 1000, PRICES, carried,
                new EvaluationFeeLedger.BatchCap("probe batch cap", 500))) {
            assertEquals(726, reopened.conservativeUsedFen());
            assertEquals(422, reopened.batchNewSpendFen());
            var afterRestart = assertThrows(EvaluationFeeLedger.Blocked.class,
                    () -> reopened.admitChat(MODEL, HOST, 1_000_000, 1));
            assertTrue(afterRestart.getMessage().contains("BATCH_CAPPED"), afterRestart.getMessage());
        }
    }

    @Test void cancellationDuringDispatchKeepsGateArmedAndReservationRetained() throws Exception {
        // The other half of "never unload early": a user cancellation lands while the request is in
        // flight, the client's registered onCancel aborts it, and the reservation stays counted.
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.setExecutor(Executors.newCachedThreadPool());
        slow.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            dispatched.incrementAndGet();
            try { Thread.sleep(3_000); } catch (InterruptedException ignored) { }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slow.start();
        try {
            var ledger = new EvaluationFeeLedger(ledgerFile, TOTAL_FEN, PAUSE_FEN, PRICES);
            EvaluationFeeGate.install(ledger);
            var slowClient = new DeepSeekClient(new DeepSeekProperties(
                    URI.create("http://127.0.0.1:" + slow.getAddress().getPort()), KEY,
                    MODEL, MODEL, Duration.ofSeconds(1), 384, 1024), json);
            var token = new CancellationToken();
            var canceller = new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                token.cancel(com.tianmu.agent.core.StopReason.USER_CANCELLED);
            });
            canceller.setDaemon(true);
            canceller.start();
            assertThrows(RuntimeException.class,
                    () -> admit(slowClient, new CallControl(Instant.now().plusSeconds(10), token)));
            canceller.join(5_000);
            assertEquals(1, dispatched.get(), "the cancelled dispatch really left the process");
            assertEquals(0, ledger.openReservations().size(), "a cancelled dispatch is never left OPEN");
            var row = ledgerDoc(ledgerFile).path("reservations").path(0);
            assertEquals("FAILED_RETAINED", row.path("status").asText(),
                    "cancellation keeps the reservation counted instead of releasing it");
            assertTrue(EvaluationFeeGate.armed(), "a cancellation must never unload the gate");
            ledger.close();
        } finally {
            slow.stop(0);
        }
    }

    private static JsonNode ledgerDoc(Path file) throws Exception {
        return new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
    }
}
