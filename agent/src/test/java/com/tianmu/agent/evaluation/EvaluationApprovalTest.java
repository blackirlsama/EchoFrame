package com.tianmu.agent.evaluation;

import com.tianmu.agent.core.AgentException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Missing, draft, mismatched and reused-old approvals are refused before any dispatch. */
class EvaluationApprovalTest {
    private static EvaluationApproval.Receipt receipt(String state, boolean approved, String baseline,
                                                      String contract, String root, int maxRuns,
                                                      boolean auto, String budget, String group) {
        return new EvaluationApproval.Receipt(state, approved, "canon-730013-pair", baseline, contract,
                root, maxRuns, auto, budget, group);
    }

    private static EvaluationApproval.Receipt valid() {
        return receipt("APPROVED", true, "baseline-old", "contract-1", "var/evidence", 1, false,
                "headroom-770", "group-1");
    }

    @Test void matchingReceiptIsAccepted() {
        var receipt = valid();
        assertSame(receipt, EvaluationApproval.require(receipt, "canon-730013-pair", "baseline-old",
                "contract-1", "var/evidence", "headroom-770", "group-1"));
    }

    @Test void missingReceiptIsRefused() {
        assertEquals("APPROVAL_REQUIRED", assertThrows(AgentException.class,
                () -> EvaluationApproval.require(null, "canon-730013-pair", "baseline-old", "contract-1",
                        "var/evidence", "headroom-770", "group-1")).code());
    }

    @Test void draftIsRefused() {
        var draft = receipt("DRAFT_NOT_APPROVED", true, "baseline-old", "contract-1", "var/evidence",
                1, false, "headroom-770", "group-1");
        assertEquals("APPROVAL_DRAFT_REFUSED", assertThrows(AgentException.class,
                () -> EvaluationApproval.require(draft, "canon-730013-pair", "baseline-old", "contract-1",
                        "var/evidence", "headroom-770", "group-1")).code());
    }

    @Test void oldOrForeignReceiptIsRefused() {
        var old = receipt("APPROVED", true, "baseline-older", "contract-1", "var/evidence", 1, false,
                "headroom-770", "group-1");
        assertEquals("APPROVAL_MISMATCH", assertThrows(AgentException.class,
                () -> EvaluationApproval.require(old, "canon-730013-pair", "baseline-old", "contract-1",
                        "var/evidence", "headroom-770", "group-1")).code());
        var oldBudget = receipt("APPROVED", true, "baseline-old", "contract-1", "var/evidence", 1, false,
                "legacy-322", "group-1");
        assertEquals("APPROVAL_MISMATCH", assertThrows(AgentException.class,
                () -> EvaluationApproval.require(oldBudget, "canon-730013-pair", "baseline-old", "contract-1",
                        "var/evidence", "headroom-770", "group-1")).code());
    }

    @Test void multiRunOrAutomaticRetryIsRefused() {
        var twice = receipt("APPROVED", true, "baseline-old", "contract-1", "var/evidence", 2, false,
                "headroom-770", "group-1");
        var auto = receipt("APPROVED", true, "baseline-old", "contract-1", "var/evidence", 1, true,
                "headroom-770", "group-1");
        for (var bad : java.util.List.of(twice, auto))
            assertEquals("APPROVAL_MISMATCH", assertThrows(AgentException.class,
                    () -> EvaluationApproval.require(bad, "canon-730013-pair", "baseline-old", "contract-1",
                            "var/evidence", "headroom-770", "group-1")).code());
    }
}
