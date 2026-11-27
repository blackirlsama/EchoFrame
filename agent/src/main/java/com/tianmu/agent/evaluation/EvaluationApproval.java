package com.tianmu.agent.evaluation;

import com.tianmu.agent.core.AgentException;

/**
 * Evaluation-only approval receipt gate. The full real first-pair launcher stays archived; this
 * compact contract keeps the refusal semantics testable: missing, draft, mismatched (including a
 * reused old receipt), multi-run or automatic-retry receipts are refused before any dispatch.
 */
public final class EvaluationApproval {
    public record Receipt(String state, boolean approved, String scope, String baseline,
                          String contractSha256, String evidenceRoot, int maxRuns,
                          boolean automaticRetry, String budgetProfile, String pairGroup) {}

    public static Receipt require(Receipt receipt, String scope, String baseline, String contractSha256,
                                  String evidenceRoot, String budgetProfile, String pairGroup) {
        if (receipt == null) throw new AgentException("APPROVAL_REQUIRED", "A new approval receipt is required");
        if ("DRAFT_NOT_APPROVED".equals(receipt.state()) || !receipt.approved())
            throw new AgentException("APPROVAL_DRAFT_REFUSED", "A draft receipt cannot authorize a run");
        boolean matches = "APPROVED".equals(receipt.state())
                && scope.equals(receipt.scope())
                && baseline.equals(receipt.baseline())
                && contractSha256.equals(receipt.contractSha256())
                && evidenceRoot.equals(receipt.evidenceRoot())
                && receipt.maxRuns() == 1
                && !receipt.automaticRetry()
                && budgetProfile.equals(receipt.budgetProfile())
                && pairGroup.equals(receipt.pairGroup());
        if (!matches) throw new AgentException("APPROVAL_MISMATCH", "Approval receipt does not match this run");
        return receipt;
    }

    private EvaluationApproval() {}
}
