package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The current stage ledger: 24 historical rows, 770 fen opening total, 638 fen carried block,
 * 132 fen historical rows. The source is read-only; the copy binds the historical rows with a
 * canonical digest, so a rewrite that keeps the sum is still refused by both Java and Python.
 */
class EvaluationCanonLedgerCarryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TOTAL = 100_000, PAUSE = 10_000;
    private static final String A = "deepseek-ai/DeepSeek-V4.1-Flash";
    private static final String B = "deepseek-ai/DeepSeek-V4-Pro";
    private static final String ANALYSIS = "deepseek-ai/DeepSeek-V4.1-Flash";
    private static final String VISION = "MiniMax-M3";
    private static final String HOST = "127.0.0.1";

    private static Path source() {
        String raw = System.getProperty("eval.historical.batch.ledger");
        if (raw == null || raw.isBlank()) raw = System.getenv("EVAL_HISTORICAL_BATCH_LEDGER");
        Assumptions.assumeTrue(raw != null && !raw.isBlank(),
                "inject -Deval.historical.batch.ledger=... to run the 770-fen carry test");
        Path path = Path.of(raw).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(path), "injected ledger missing: " + path);
        return path;
    }

    private static Path boundCopy(Path source, Path dir) throws Exception {
        byte[] raw = Files.readAllBytes(source);
        String expected = System.getProperty("eval.historical.ledger.sha256", "").strip();
        if (!expected.isEmpty()) assertEquals(expected, EvaluationVideoCanonEvidence.sha256(raw));
        ObjectNode doc = (ObjectNode) JSON.readTree(raw);
        var rows = new ArrayList<JsonNode>();
        doc.path("reservations").forEach(rows::add);
        ObjectNode carried = (ObjectNode) doc.path("carried_in");
        carried.put("rows_sha256", EvaluationFeeLedger.rowsDigest(rows));
        carried.put("rows_count", rows.size());
        Path copy = dir.resolve("batch-cumulative.json");
        Files.writeString(copy, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
        return copy;
    }

    private static Map<String, EvaluationFeeLedger.Price> prices() {
        var prices = new java.util.LinkedHashMap<String, EvaluationFeeLedger.Price>();
        prices.put("chat:" + HOST + ":" + A, new EvaluationFeeLedger.Price(200, 800));
        prices.put("chat:" + HOST + ":" + B, new EvaluationFeeLedger.Price(1200, 2400));
        prices.put("vision:minimax:" + VISION + ":openai_chat_vision",
                new EvaluationFeeLedger.Price(420, 1680));
        return Map.copyOf(prices);
    }

    @Test
    void theTwentyFourRowCloseoutOpensAtSevenSeventyAndNeverDoubleCounts(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path source = source();
        long sourceMtime = Files.getLastModifiedTime(source).toMillis();
        byte[] sourceBytes = Files.readAllBytes(source);
        Path copy = boundCopy(source, dir);
        try (var ledger = new EvaluationFeeLedger(copy, TOTAL, PAUSE, prices())) {
            assertEquals(24, JSON.readTree(Files.readString(copy, StandardCharsets.UTF_8))
                    .path("reservations").size(), "the shipped file carries 24 rows");
            assertEquals(638, ledger.carriedIn().settledFen() + ledger.carriedIn().retainedFen(),
                    "560 settled + 78 retained are the carried block");
            assertEquals(770, ledger.conservativeUsedFen(),
                    "7.70 CNY is the cumulative conservative total");
            assertEquals(132, ledger.batchNewSpendFen(),
                    "113 settled + 19 retained historical rows, counted once");
            assertEquals(113, ledger.knownSettledFen() - 560,
                    "the 24 rows' settled part is 113 fen");
            assertEquals(0, ledger.openReservations().size());
        }
        try (var reopened = new EvaluationFeeLedger(copy, TOTAL, PAUSE, prices())) {
            assertEquals(770, reopened.conservativeUsedFen(), "a restart cannot re-add the carried block");
            assertEquals(132, reopened.batchNewSpendFen());
        }
        assertArrayEquals(sourceBytes, Files.readAllBytes(source), "the source ledger is never written");
        assertEquals(sourceMtime, Files.getLastModifiedTime(source).toMillis());
    }

    @Test
    void rewritingHistoricalRowsIsRefusedEvenWhenTheSumIsUnchanged(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path copy = boundCopy(source(), dir);
        JsonNode doc = JSON.readTree(Files.readString(copy, StandardCharsets.UTF_8));
        ArrayNode rows = (ArrayNode) doc.path("reservations");
        ((ObjectNode) rows.get(0)).put("actual_fen", rows.get(0).path("actual_fen").asLong() + 1);
        ((ObjectNode) rows.get(1)).put("actual_fen", rows.get(1).path("actual_fen").asLong() - 1);
        Files.writeString(copy, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
        assertThrows(EvaluationFeeLedger.Corrupted.class,
                () -> new EvaluationFeeLedger(copy, TOTAL, PAUSE, prices()).close(),
                "same-sum rewrites must still be refused by the row digest");
    }

    @Test
    void realClientDispatchesContinueTheLedgerFromAToB(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path copy = boundCopy(source(), dir);
        var server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var ledger = new EvaluationFeeLedger(copy, TOTAL, PAUSE, prices())) {
            assertEquals(770, ledger.conservativeUsedFen());
            EvaluationFeeGate.install(ledger);
            int port = server.getAddress().getPort();
            var client = new com.tianmu.agent.model.deepseek.DeepSeekClient(
                    new com.tianmu.agent.model.deepseek.DeepSeekProperties(
                            URI.create("http://127.0.0.1:" + port), "synthetic-test-key", A, A,
                            Duration.ofSeconds(2), 384, 1024, ANALYSIS, true, "high", "low", 16384, "siliconflow"),
                    JSON);
            dispatch(client, A);
            assertEquals(772, ledger.conservativeUsedFen(), "A settles its own usage on top of 770");
            dispatch(client, B);
            assertEquals(774, ledger.conservativeUsedFen(), "B continues the same ledger");
            assertEquals(136, ledger.batchNewSpendFen(), "132 historical + A 2 + B 2");
            assertEquals(0, ledger.openReservations().size(), "both dispatches closed cleanly");
        } finally {
            EvaluationFeeGate.clear();
            server.stop(0);
        }
        try (var reopened = new EvaluationFeeLedger(copy, TOTAL, PAUSE, prices())) {
            assertEquals(774, reopened.conservativeUsedFen(), "the A+B continuation survives a reopen");
            assertEquals(136, reopened.batchNewSpendFen());
        }
    }

    private static void dispatch(com.tianmu.agent.model.deepseek.DeepSeekClient client, String model) {
        client.complete(model, List.of(Map.of("role", "user", "content", "ledger carry probe")), 256,
                new com.tianmu.agent.runtime.control.CallControl(Instant.now().plusSeconds(10),
                        new com.tianmu.agent.runtime.control.CancellationToken()), true);
    }

    @Test
    void theNextReservationAtTheTenThousandLineIsRefusedBeforeDispatch(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("synthetic-line.json");
        ObjectNode doc = JSON.createObjectNode();
        doc.put("schema", "fee-ledger-v2"); doc.put("total_fen", TOTAL); doc.put("pause_fen", PAUSE);
        ObjectNode carried = doc.putObject("carried_in");
        carried.put("source", "synthetic line fixture"); carried.put("settled_fen", 9_999);
        carried.put("retained_fen", 0); doc.putArray("reservations");
        Files.writeString(file, JSON.writeValueAsString(doc), StandardCharsets.UTF_8);
        try (var ledger = new EvaluationFeeLedger(file, TOTAL, PAUSE,
                Map.of("chat:" + HOST + ":probe", new EvaluationFeeLedger.Price(1_000_000, 1_000_000)))) {
            var blocked = assertThrows(EvaluationFeeLedger.Blocked.class,
                    () -> ledger.admitChat("probe", HOST, 1, 1));
            assertTrue(blocked.getMessage().contains("FEE_ADMISSION_PAUSED"),
                    "the next reservation would reach the 10,000-fen pause line");
        }
    }
}
