package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B must validate A's terminal state, ledger prefix, report bytes and identity before dispatch. */
class EvaluationVideoCanonContinuationGuardTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONFIG = Path.of("eval/report-trial-synthetic.example.json");
    private static final Path MANIFEST = Path.of("eval/fixtures/synthetic/frames/frames-manifest.json");
    private static final Path LEDGER_FIXTURE = Path.of("eval/fixtures/synthetic/batch-cumulative.json");
    private static final String BASELINE = "synthetic-baseline";
    private static final String GROUP = "synthetic-pair";
    private static final String SOURCE_SHA = "a".repeat(64);

    @Test
    void acceptsAVerifiedAPrefix(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir)) {
            assertDoesNotThrow(() -> EvaluationVideoCanonEntry.verifyCompletion(
                    EvaluationVideoCanonEntry.MODEL_A, f.record(), f.ledger(), f.context()));
        }
    }

    @Test
    void refusesAnACompletionThatIsStillRunning(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir)) {
            f.recordDoc().put("status", "RUNNING");
            f.writeRecord();
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.verifyCompletion(
                            EvaluationVideoCanonEntry.MODEL_A, f.record(), f.ledger(), f.context()));
            assertTrue(error.getMessage().contains("COMPLETION_NOT_TERMINAL"), error.getMessage());
        }
    }

    @Test
    void refusesAReportWhoseBytesChanged(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir)) {
            Files.writeString(f.report(), "tampered", StandardCharsets.UTF_8);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.verifyCompletion(
                            EvaluationVideoCanonEntry.MODEL_A, f.record(), f.ledger(), f.context()));
            assertTrue(error.getMessage().contains("COMPLETION_REPORT_MISMATCH"), error.getMessage());
        }
    }

    @Test
    void refusesALedgerPrefixThatWasRewritten(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir)) {
            f.recordDoc().put("ledger_rows_sha256", "0".repeat(64));
            f.writeRecord();
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.verifyCompletion(
                            EvaluationVideoCanonEntry.MODEL_A, f.record(), f.ledger(), f.context()));
            assertTrue(error.getMessage().contains("COMPLETION_LEDGER_PREFIX_MISMATCH"), error.getMessage());
        }
    }

    @Test
    void refusesASameSumRewriteOfTheLedgerPrefix(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir)) {
            var doc = JSON.readTree(Files.readString(f.context().ledgerFile(), StandardCharsets.UTF_8));
            var row = (ObjectNode) doc.path("reservations").get(0);
            row.put("reserved_fen", 18);
            row.put("actual_fen", 100);
            Files.writeString(f.context().ledgerFile(), doc.toPrettyString(), StandardCharsets.UTF_8);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.verifyCompletion(
                            EvaluationVideoCanonEntry.MODEL_A, f.record(), f.ledger(), f.context()));
            assertTrue(error.getMessage().contains("COMPLETION_LEDGER_PREFIX_MISMATCH"), error.getMessage());
        }
    }

    @Test
    void refusesACompletionWithAnotherIdentity(@TempDir Path dir) throws Exception {
        try (Fixture f = fixture(dir)) {
            f.recordDoc().put("pair_id", "another-pair");
            f.writeRecord();
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.verifyCompletion(
                            EvaluationVideoCanonEntry.MODEL_A, f.record(), f.ledger(), f.context()));
            assertTrue(error.getMessage().contains("COMPLETION_IDENTITY_MISMATCH"), error.getMessage());
        }
    }

    private static Fixture fixture(Path dir) throws Exception {
        Path ledgerFile = dir.resolve("batch-cumulative.json");
        Files.copy(LEDGER_FIXTURE, ledgerFile);
        JsonNode doc = JSON.readTree(Files.readString(ledgerFile, StandardCharsets.UTF_8));
        List<JsonNode> rows = new ArrayList<>();
        doc.path("reservations").forEach(rows::add);
        Path report = dir.resolve("a-report.html");
        Files.writeString(report, "<!doctype html><p>synthetic A report</p>", StandardCharsets.UTF_8);
        Path record = dir.resolve("controller-a-repeat-1.json");
        ObjectNode recordDoc = JSON.createObjectNode();
        recordDoc.put("run_id", "synthetic-a-run");
        recordDoc.put("controller", EvaluationVideoCanonEntry.MODEL_A);
        recordDoc.put("status", "SUCCEEDED");
        recordDoc.put("pid", 1);
        recordDoc.put("report_file", "a-report.html");
        recordDoc.put("report_bytes", Files.size(report));
        recordDoc.put("report_sha256", EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(report)));
        recordDoc.put("ledger_rows", rows.size());
        recordDoc.put("ledger_rows_sha256", EvaluationFeeLedger.rowsDigest(rows));
        recordDoc.put("ledger_source_sha256", SOURCE_SHA);
        recordDoc.put("pair_id", GROUP);
        recordDoc.put("baseline", BASELINE);
        recordDoc.put("contract_sha256", "");
        recordDoc.put("material_config_sha256", EvaluationVideoCanonContract.sha256(CONFIG));
        recordDoc.put("frames_manifest_sha256", EvaluationVideoCanonContract.sha256(MANIFEST));
        Files.writeString(record, recordDoc.toPrettyString(), StandardCharsets.UTF_8);
        EvaluationVideoCanonEntry.CompletionContext context = new EvaluationVideoCanonEntry.CompletionContext(
                dir, ledgerFile, MANIFEST, CONFIG, BASELINE, GROUP, SOURCE_SHA, "");
        EvaluationFeeLedger ledger = new EvaluationFeeLedger(ledgerFile, 100000, 10000, Map.of());
        return new Fixture(dir, record, report, recordDoc, ledger, context);
    }

    private record Fixture(Path dir, Path record, Path report, ObjectNode recordDoc,
                           EvaluationFeeLedger ledger, EvaluationVideoCanonEntry.CompletionContext context)
            implements AutoCloseable {
        void writeRecord() throws Exception {
            Files.writeString(record, recordDoc.toPrettyString(), StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            ledger.close();
        }
    }
}
