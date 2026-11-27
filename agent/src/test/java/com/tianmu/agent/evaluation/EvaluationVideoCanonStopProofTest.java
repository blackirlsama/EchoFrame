package com.tianmu.agent.evaluation;

import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Script-side gate: a failed A may be continued only when its confirmed-stop proof and exact
 *  final ledger close are both valid. This class writes nothing. */
@EnabledIfSystemProperty(named = "eval.canon.stop-proof", matches = "true")
class EvaluationVideoCanonStopProofTest {
    @Test
    void confirmedStopAtTheExactACloseAllowsBStart() throws Exception {
        Path a = EvaluationVideoCanonEvidence.COMPLETED.resolve("controller-a-repeat-1.json");
        assertTrue(Files.isRegularFile(a), "A completion record missing: " + a);
        try (var ledger = new EvaluationFeeLedger(EvaluationVideoCanonEvidence.LEDGER,
                EvaluationVideoCanonEntry.TOTAL_FEN, EvaluationVideoCanonEntry.PAUSE_FEN,
                EvaluationVideoCanonEntry.prices())) {
            EvaluationVideoCanonEntry.verifyBStart(EvaluationVideoCanonEntry.MODEL_A, a, ledger);
        }
    }
}
