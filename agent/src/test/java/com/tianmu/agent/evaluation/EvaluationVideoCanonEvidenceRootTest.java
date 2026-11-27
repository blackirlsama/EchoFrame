package com.tianmu.agent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A batch root is created once; an occupied root is never cleaned or overwritten. */
class EvaluationVideoCanonEvidenceRootTest {
    @Test
    void anOccupiedRootIsRefusedAndItsMarkerIsUntouched(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("evidence");
        Files.createDirectories(root);
        Path marker = root.resolve("marker.txt");
        Files.writeString(marker, "keep-me", StandardCharsets.UTF_8);
        withEvidenceRoot(root, () -> {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEvidence.requireFreshBatchRoot(root));
            assertTrue(error.getMessage().contains("EVIDENCE_ROOT_OCCUPIED"), error.getMessage());
        });
        assertEquals("keep-me", Files.readString(marker, StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyRootIsAcceptedAndNotDeleted(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("evidence");
        Files.createDirectories(root);
        withEvidenceRoot(root, () -> assertDoesNotThrow(
                () -> EvaluationVideoCanonEvidence.requireFreshBatchRoot(root)));
        assertTrue(Files.isDirectory(root), "the empty root is left in place");
    }

    private static void withEvidenceRoot(Path root, ThrowingRunnable action) throws Exception {
        String old = System.getProperty("eval.evidence.root");
        System.setProperty("eval.evidence.root", root.toString());
        try {
            action.run();
        } finally {
            if (old == null) System.clearProperty("eval.evidence.root");
            else System.setProperty("eval.evidence.root", old);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
