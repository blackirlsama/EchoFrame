package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The Canon contract must bind the real inputs, never a placeholder approval string. */
class EvaluationVideoCanonContractTest {
    private static final Path CONFIG = Path.of("eval/report-trial-synthetic.example.json");
    private static final Path FRAMES = Path.of("eval/fixtures/synthetic/frames");
    private static final Path LEDGER = Path.of("eval/fixtures/synthetic/batch-cumulative.json");
    private static final String BASELINE = "synthetic-baseline";
    private static final String GROUP = "synthetic-pair";

    @Test
    void bindsAndVerifiesTheActualInputs(@TempDir Path dir) throws Exception {
        String ledgerSha = EvaluationVideoCanonContract.sha256(LEDGER);
        ObjectNode contract = EvaluationVideoCanonContract.build(CONFIG, FRAMES, LEDGER, ledgerSha,
                BASELINE, GROUP);
        Path file = dir.resolve("contract.json");
        Files.writeString(file, contract.toPrettyString(), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> EvaluationVideoCanonContract.verify(file,
                EvaluationVideoCanonContract.sha256(file), BASELINE, GROUP, CONFIG, FRAMES, LEDGER, ledgerSha));
    }

    @Test
    void rejectsAChangedMaterialConfig(@TempDir Path dir) throws Exception {
        String ledgerSha = EvaluationVideoCanonContract.sha256(LEDGER);
        ObjectNode contract = EvaluationVideoCanonContract.build(CONFIG, FRAMES, LEDGER, ledgerSha,
                BASELINE, GROUP);
        Path file = dir.resolve("contract.json");
        Files.writeString(file, contract.toPrettyString(), StandardCharsets.UTF_8);
        Path changed = dir.resolve("material.json");
        Files.writeString(changed, Files.readString(CONFIG, StandardCharsets.UTF_8) + "\n", StandardCharsets.UTF_8);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> EvaluationVideoCanonContract.verify(file, EvaluationVideoCanonContract.sha256(file),
                        BASELINE, GROUP, changed, FRAMES, LEDGER, ledgerSha));
        assertTrue(error.getMessage().contains("does not bind"), error.getMessage());
    }

    @Test
    void rejectsAChangedLedgerSource(@TempDir Path dir) throws Exception {
        String ledgerSha = EvaluationVideoCanonContract.sha256(LEDGER);
        ObjectNode contract = EvaluationVideoCanonContract.build(CONFIG, FRAMES, LEDGER, ledgerSha,
                BASELINE, GROUP);
        Path file = dir.resolve("contract.json");
        Files.writeString(file, contract.toPrettyString(), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> EvaluationVideoCanonContract.verify(file,
                EvaluationVideoCanonContract.sha256(file), BASELINE, GROUP, CONFIG, FRAMES, LEDGER,
                "0".repeat(64)));
    }

    @Test
    void rejectsAChangedBaseline(@TempDir Path dir) throws Exception {
        String ledgerSha = EvaluationVideoCanonContract.sha256(LEDGER);
        ObjectNode contract = EvaluationVideoCanonContract.build(CONFIG, FRAMES, LEDGER, ledgerSha,
                BASELINE, GROUP);
        Path file = dir.resolve("contract.json");
        Files.writeString(file, contract.toPrettyString(), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> EvaluationVideoCanonContract.verify(file,
                EvaluationVideoCanonContract.sha256(file), "another-baseline", GROUP, CONFIG, FRAMES,
                LEDGER, ledgerSha));
    }

    @Test
    void rejectsChangedFramesOrRoles(@TempDir Path dir) throws Exception {
        String ledgerSha = EvaluationVideoCanonContract.sha256(LEDGER);
        for (String field : java.util.List.of("frames", "roles")) {
            ObjectNode contract = EvaluationVideoCanonContract.build(CONFIG, FRAMES, LEDGER, ledgerSha,
                    BASELINE, GROUP);
            if (field.equals("frames")) contract.put("frames_manifest_sha256", "0".repeat(64));
            else ((ObjectNode) contract.path("roles")).put("vision", "another-vision-model");
            Path file = dir.resolve(field + "-contract.json");
            Files.writeString(file, contract.toPrettyString(), StandardCharsets.UTF_8);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonContract.verify(file,
                            EvaluationVideoCanonContract.sha256(file), BASELINE, GROUP, CONFIG, FRAMES,
                            LEDGER, ledgerSha));
            assertTrue(error.getMessage().contains("does not bind"), error.getMessage());
        }
    }

    @Test
    void rejectsAReceiptThatNamesAnotherContract(@TempDir Path dir) throws Exception {
        String ledgerSha = EvaluationVideoCanonContract.sha256(LEDGER);
        ObjectNode contract = EvaluationVideoCanonContract.build(CONFIG, FRAMES, LEDGER, ledgerSha,
                BASELINE, GROUP);
        Path file = dir.resolve("contract.json");
        Files.writeString(file, contract.toPrettyString(), StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> EvaluationVideoCanonContract.verify(file,
                "f".repeat(64), BASELINE, GROUP, CONFIG, FRAMES, LEDGER, ledgerSha));
    }
}
