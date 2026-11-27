package com.tianmu.agent.evaluation.admission;

import com.tianmu.agent.runtime.spi.ToolArgumentObserver;

/**
 * Bridges the kernel's optional observation point to the explicitly armed evaluation gate.
 * Disarmed production has no observer bean, so this class is never called there.
 */
public final class EvaluationToolArgumentObserver implements ToolArgumentObserver {
    @Override public void rejected(Rejection rejection) {
        EvaluationFeeGate.toolRejected(new EvaluationFeeGate.ToolRejection(
                rejection.runId(), rejection.callIndex(), rejection.tool(), rejection.phase(),
                rejection.code(), rejection.paths(), rejection.problems(),
                rejection.issueCount(), rejection.issuesTruncated(),
                rejection.failureClass(), rejection.handlerExecuted()));
    }

    @Override public void corrected(String runId, String tool, String phase, int correction) {
        EvaluationFeeGate.toolCorrected(runId, tool, phase, correction);
    }

    @Override public void stopped(String runId, String tool, String phase, String code, int corrections) {
        EvaluationFeeGate.toolStopped(runId, tool, phase, code, corrections);
    }
}
