package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Public CI regression for the ledger envelope, using only a temp synthetic file. */
class EvaluationSyntheticLedgerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TOTAL = 100_000, PAUSE = 10_000;

    private static ObjectNode row(String id, String status, int reserved, Integer actual) {
        ObjectNode row = JSON.createObjectNode();
        row.put("reservation_id", id);
        row.put("exit", "chat:127.0.0.1:synthetic");
        row.put("model", "synthetic");
        row.put("status", status);
        row.put("reserved_fen", reserved);
        row.put("usage_tokens", actual == null ? null : 10);
        row.put("actual_fen", actual);
        row.put("price_identity", "a".repeat(64));
        row.put("price_input_fen_per_million", 200);
        row.put("price_output_fen_per_million", 800);
        row.put("attribution", "direct");
        row.put("admitted_at", Instant.parse("2026-11-01T00:00:00Z").toString());
        row.put("request_body_bytes", 10);
        row.put("request_body_sha256", "b".repeat(64));
        if (actual == null) row.put("fail_reason", "SYNTHETIC_RETAINED");
        return row;
    }

    private static Path ledger(Path dir) throws Exception {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("schema", "fee-ledger-v2");
        doc.put("total_fen", TOTAL);
        doc.put("pause_fen", PAUSE);
        ObjectNode carried = doc.putObject("carried_in");
        carried.put("source", "synthetic public fixture");
        carried.put("settled_fen", 560);
        carried.put("retained_fen", 78);
        ArrayNode rows = doc.putArray("reservations");
        rows.add(row("fee-synthetic-1", "SETTLED", 5, 113));
        rows.add(row("fee-synthetic-2", "FAILED_RETAINED", 19, null));
        var list = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        rows.forEach(list::add);
        carried.put("rows_sha256", EvaluationFeeLedger.rowsDigest(list));
        carried.put("rows_count", list.size());
        Path file = dir.resolve("synthetic-ledger.json");
        Files.writeString(file, JSON.writeValueAsString(doc), StandardCharsets.UTF_8);
        return file;
    }

    @Test void syntheticEnvelopeCarriesOnceAndBindsTheRows(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        try (var opened = new EvaluationFeeLedger(file, TOTAL, PAUSE, Map.of())) {
            assertEquals(638, opened.carriedIn().settledFen() + opened.carriedIn().retainedFen());
            assertEquals(770, opened.conservativeUsedFen());
            assertEquals(132, opened.batchNewSpendFen());
            assertEquals(673, opened.knownSettledFen());
            assertEquals(0, opened.openReservations().size());
        }
        try (var reopened = new EvaluationFeeLedger(file, TOTAL, PAUSE, Map.of())) {
            assertEquals(770, reopened.conservativeUsedFen());
            assertEquals(132, reopened.batchNewSpendFen());
        }
    }

    @Test void sameSumSyntheticRowRewriteIsRefused(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        var doc = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        var rows = (ArrayNode) doc.path("reservations");
        ((ObjectNode) rows.get(0)).put("actual_fen", 114);
        ((ObjectNode) rows.get(1)).put("reserved_fen", 18);
        Files.writeString(file, JSON.writeValueAsString(doc), StandardCharsets.UTF_8);
        assertThrows(EvaluationFeeLedger.Corrupted.class,
                () -> new EvaluationFeeLedger(file, TOTAL, PAUSE, Map.of()).close());
    }

    @Test void tenThousandFenLineRefusesBeforeDispatch(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("line.json");
        ObjectNode doc = JSON.createObjectNode();
        doc.put("schema", "fee-ledger-v2");
        doc.put("total_fen", TOTAL);
        doc.put("pause_fen", PAUSE);
        ObjectNode carried = doc.putObject("carried_in");
        carried.put("source", "synthetic line fixture");
        carried.put("settled_fen", 9_999);
        carried.put("retained_fen", 0);
        doc.putArray("reservations");
        Files.writeString(file, JSON.writeValueAsString(doc), StandardCharsets.UTF_8);
        try (var opened = new EvaluationFeeLedger(file, TOTAL, PAUSE,
                Map.of("chat:127.0.0.1:probe", new EvaluationFeeLedger.Price(1_000_000, 1_000_000)))) {
            var blocked = assertThrows(EvaluationFeeLedger.Blocked.class,
                    () -> opened.admitChat("probe", "127.0.0.1", 1, 1));
            assertTrue(blocked.getMessage().contains("FEE_ADMISSION_PAUSED"));
        }
    }
}
