package com.tianmu.agent.evaluation;

import com.tianmu.agent.core.AgentException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The two-process entry refuses missing/draft/old/mismatched approval receipts before ledger copy. */
class EvaluationApprovalEntryTest {
    private static final Map<String, String> PREVIOUS_PROPERTIES = new HashMap<>();

    @BeforeAll
    static void pinApprovalIdentity() {
        Map.of("eval.approval.baseline", "f7527962e635210f0fb02a759d39c47dbdaa8805",
                "eval.approval.group", "canon-730013-pair").forEach((key, value) ->
                PREVIOUS_PROPERTIES.put(key, System.setProperty(key, value)));
    }

    @AfterAll
    static void restoreApprovalIdentity() {
        PREVIOUS_PROPERTIES.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    private static Path receipt(Path dir, String state, boolean approved, String baseline,
                                int maxRuns, boolean auto, String root) throws Exception {
        Path file = dir.resolve("approval-" + state + "-" + baseline + "-" + maxRuns + ".json");
        Files.writeString(file, """
                {"state":"%s","approved":%s,"scope":"canon-730013-pair","baseline":"%s",
                 "contract_sha256":"synthetic-contract","evidence_root":"%s",
                 "max_runs":%d,"automatic_retry":%s,"budget_profile":"headroom-770",
                 "pair_group":"canon-730013-pair"}
                """.formatted(state, approved, baseline, root.replace('\\', '/'), maxRuns, auto),
                StandardCharsets.UTF_8);
        return file;
    }

    @Test void validReceiptIsAccepted(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("evidence");
        assertDoesNotThrow(() -> EvaluationVideoCanonEntry.requireApproval(root,
                receipt(dir, "APPROVED", true, "f7527962e635210f0fb02a759d39c47dbdaa8805", 1, false,
                        root.toString())));
    }

    @Test void missingReceiptIsRefused() {
        assertEquals("APPROVAL_REQUIRED", assertThrows(AgentException.class,
                () -> EvaluationVideoCanonEntry.requireApproval(Path.of("evidence"), null)).code());
    }

    @Test void draftReceiptIsRefused(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("evidence");
        assertEquals("APPROVAL_DRAFT_REFUSED", assertThrows(AgentException.class,
                () -> EvaluationVideoCanonEntry.requireApproval(root,
                        receipt(dir, "DRAFT_NOT_APPROVED", true,
                                "f7527962e635210f0fb02a759d39c47dbdaa8805", 1, false, root.toString()))).code());
    }

    @Test void oldBaselineIsRefused(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("evidence");
        assertEquals("APPROVAL_MISMATCH", assertThrows(AgentException.class,
                () -> EvaluationVideoCanonEntry.requireApproval(root,
                        receipt(dir, "APPROVED", true,
                                "baseline-old", 1, false, root.toString()))).code());
    }

    @Test void multiRunOrAutomaticRetryIsRefused(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("evidence");
        for (Path bad : java.util.List.of(
                receipt(dir, "APPROVED", true, "f7527962e635210f0fb02a759d39c47dbdaa8805", 2, false, root.toString()),
                receipt(dir, "APPROVED", true, "f7527962e635210f0fb02a759d39c47dbdaa8805", 1, true, root.toString()))) {
            assertEquals("APPROVAL_MISMATCH", assertThrows(AgentException.class,
                    () -> EvaluationVideoCanonEntry.requireApproval(root, bad)).code());
        }
    }
}
