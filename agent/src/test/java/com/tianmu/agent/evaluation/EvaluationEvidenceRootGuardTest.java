package com.tianmu.agent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A new batch root is explicit and empty; an occupied root is never overwritten. */
class EvaluationEvidenceRootGuardTest {
    @Test
    void emptyRootIsAccepted(@TempDir Path root) {
        String previous = System.getProperty("eval.evidence.root");
        System.setProperty("eval.evidence.root", root.toString());
        try {
            assertDoesNotThrow(() -> EvaluationVideoCanonEvidence.requireFreshBatchRoot(root));
        } finally {
            restore(previous);
        }
    }

    @Test
    void ledgerOccupiesTheRoot(@TempDir Path root) throws Exception {
        String previous = System.getProperty("eval.evidence.root");
        System.setProperty("eval.evidence.root", root.toString());
        try {
            Files.writeString(root.resolve("batch-cumulative.json"), "{}");
            var error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEvidence.requireFreshBatchRoot(root));
            assertTrue(error.getMessage().contains("EVIDENCE_ROOT_OCCUPIED"));
        } finally {
            restore(previous);
        }
    }

    @Test
    void memberRecordsOccupyTheRoot(@TempDir Path root) throws Exception {
        String previous = System.getProperty("eval.evidence.root");
        System.setProperty("eval.evidence.root", root.toString());
        try {
            Files.createDirectories(root.resolve("completed"));
            Files.writeString(root.resolve("completed").resolve("controller-a.json"), "{}");
            var error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEvidence.requireFreshBatchRoot(root));
            assertTrue(error.getMessage().contains("EVIDENCE_ROOT_OCCUPIED"));
        } finally {
            restore(previous);
        }
    }

    @Test
    void anUnsetRootIsRefused(@TempDir Path root) {
        String previous = System.getProperty("eval.evidence.root");
        System.clearProperty("eval.evidence.root");
        try {
            var error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEvidence.requireFreshBatchRoot(root));
            assertTrue(error.getMessage().contains("EVAL_EVIDENCE_ROOT_REQUIRED"));
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty("eval.evidence.root");
        else System.setProperty("eval.evidence.root", previous);
    }
}
