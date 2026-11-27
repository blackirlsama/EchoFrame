package com.tianmu.agent.evaluation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch-3 evaluation-startup guard: the fee gate covers the controller/analysis chat exit and the
 * video client's vision/legacy-audio exit, but it does NOT cover the independent Bailian ASR client,
 * the Qwen feedback embedding client, or the rerank client. Those are out of scope for this round,
 * so an evaluation configuration that enables any of them is refused BEFORE the context serves a
 * request instead of being silently treated as "protected by the vision gate".
 *
 * Active only when {@code agent.evaluation.enabled=true}; production defaults are untouched.
 */
@Component
@ConditionalOnProperty(name = "agent.evaluation.enabled", havingValue = "true")
public final class EvaluationPaidExitGuard {
    public EvaluationPaidExitGuard(Environment environment) {
        requireGatedExits(
                environment.getProperty("agent.video.asr.enabled", Boolean.class, false),
                environment.getProperty("agent.feedback.embedding.enabled", Boolean.class, false),
                environment.getProperty("agent.feedback.rerank.enabled", Boolean.class, false),
                environment.getProperty("agent.feedback.milvus.enabled", Boolean.class, false));
    }

    /** Refuses every paid exit that has no fee-gate coverage in this slice. Each switch is named
     *  in the message so the operator sees exactly which exit must be turned off. */
    public static void requireGatedExits(boolean independentAsr, boolean embedding, boolean rerank) {
        requireGatedExits(independentAsr, embedding, rerank, false);
    }

    public static void requireGatedExits(boolean independentAsr, boolean embedding, boolean rerank,
                                         boolean milvus) {
        List<String> open = new ArrayList<>();
        if (independentAsr) open.add("agent.video.asr.enabled=true (independent Bailian transcription)");
        if (embedding) open.add("agent.feedback.embedding.enabled=true (Qwen feedback embedding)");
        if (rerank) open.add("agent.feedback.rerank.enabled=true (feedback rerank)");
        if (milvus) open.add("agent.feedback.milvus.enabled=true (vector store)");
        if (!open.isEmpty()) {
            throw new IllegalStateException("evaluation paid exits are enabled without fee-gate "
                    + "coverage: " + String.join("; ", open)
                    + "; refuse to start the evaluation wiring. The vision client's gate does not "
                    + "protect these exits.");
        }
    }
}
