package com.tianmu.agent.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Batch-3 paid-exit audit: the evaluation wiring must refuse to start when an exit that has no
 * fee-gate coverage is enabled. This is deliberately separate from the vision/audio gate tests —
 * a vision refusal is NOT evidence that the independent ASR, embedding or rerank clients are safe.
 */
class EvaluationPaidExitGuardTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GuardConfiguration.class);

    @Test void evaluationStartupPassesWhenOnlyGatedExitsAreOn() {
        runner.withPropertyValues("agent.evaluation.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void independentAsrRefusesStartup() {
        runner.withPropertyValues("agent.evaluation.enabled=true", "agent.video.asr.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("agent.video.asr.enabled=true");
                });
    }

    @Test void embeddingRefusesStartup() {
        runner.withPropertyValues("agent.evaluation.enabled=true",
                        "agent.feedback.embedding.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("agent.feedback.embedding.enabled=true");
                });
    }

    @Test void rerankRefusesStartup() {
        runner.withPropertyValues("agent.evaluation.enabled=true",
                        "agent.feedback.rerank.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("agent.feedback.rerank.enabled=true");
                });
    }

    @Test void milvusRefusesStartup() {
        runner.withPropertyValues("agent.evaluation.enabled=true",
                        "agent.feedback.milvus.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("agent.feedback.milvus.enabled=true");
                });
    }

    @Test void theGuardIsInactiveOutsideTheEvaluationWiring() {
        runner.withPropertyValues("agent.video.asr.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void allUnprotectedExitsAreNamedInOneRefusal() {
        var failure = assertThrows(IllegalStateException.class,
                () -> EvaluationPaidExitGuard.requireGatedExits(true, true, true, true));
        assertThat(failure.getMessage())
                .contains("agent.video.asr.enabled=true")
                .contains("agent.feedback.embedding.enabled=true")
                .contains("agent.feedback.rerank.enabled=true")
                .contains("agent.feedback.milvus.enabled=true");
        assertDoesNotThrow(() -> EvaluationPaidExitGuard.requireGatedExits(false, false, false, false));
    }

    @Configuration
    @ConditionalOnProperty(name = "agent.evaluation.enabled", havingValue = "true")
    static class GuardConfiguration {
        @Bean EvaluationPaidExitGuard evaluationPaidExitGuard(Environment environment) {
            return new EvaluationPaidExitGuard(environment);
        }
    }
}
