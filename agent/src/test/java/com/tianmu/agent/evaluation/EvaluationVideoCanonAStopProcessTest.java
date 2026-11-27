package com.tianmu.agent.evaluation;

import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Produces a real A failure through the product run, cancelled while a supplier call is in flight.
 * The test intentionally fails after the entry writes its confirmed-stop proof and completion; the
 * script then runs {@link EvaluationVideoCanonStopProofTest} and starts B only if that proof is
 * valid. Default CI skips this class.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "agent.preparation.deterministic-routing=true",
        "agent.policy-snapshot.enabled=true",
        "agent.evaluation.enabled=true",
        "agent.feedback.compression.enabled=false",
        "agent.feedback.embedding.enabled=false",
        "agent.feedback.milvus.enabled=false",
        "agent.feedback.rerank.enabled=false",
        "agent.feedback.model-timeout=120s",
        "agent.video.asr.enabled=false",
        "agent.video.enabled=true",
        "agent.video.provider=minimax",
        "agent.video.vision-transport=openai_chat_vision",
        "agent.video.model=MiniMax-M3",
        "agent.video.api-key=synthetic-loopback-only-not-a-credential",
        "agent.video.transcription-model=",
        "agent.video.runtime.model-timeout-millis=100000",
        "harness.loop.max-turns=32",
        "harness.loop.max-model-calls=32",
        "harness.loop.max-tokens=192000",
        "harness.loop.model-timeout=120s",
        "harness.loop.request-timeout=540s",
        "harness.trace.directory=target/evaluation/traces/canon-a-stop"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.Timeout(value = 600, unit = java.util.concurrent.TimeUnit.SECONDS)
@Import(EvaluationVideoCanonFixture.class)
@EnabledIfSystemProperty(named = "eval.canon.a-stop", matches = "true")
class EvaluationVideoCanonAStopProcessTest {
    @DynamicPropertySource
    static void loopback(DynamicPropertyRegistry registry) {
        registry.add("agent.video.base-url", () -> EvaluationVideoCanonLoopback.BASE_URL);
    }

    @Autowired
    EvaluationVideoCanonEntry entry;

    @Autowired
    FeedbackAnalysisService analysis;

    @BeforeEach
    void pinClock() throws Exception {
        analysis.clock(EvaluationVideoCanonFixture.TASK_CLOCK);
        EvaluationVideoCanonEntry.ACTIVE_CONTROLLER.set(EvaluationVideoCanonEntry.MODEL_A);
        EvaluationVideoCanonLoopback.responseDelay(3000);
        System.setProperty("eval.a.cancel.after.millis", "1500");
    }

    @AfterEach
    void gateMustBeUnloadedByTheConfirmedStop() {
        EvaluationVideoCanonLoopback.responseDelay(0);
        assertFalse(EvaluationFeeGate.armed(),
                "a confirmed stop is the only path that may unload the gate");
    }

    @Test
    void aLiveFailureWritesAConfirmedStopForBToVerify() throws Exception {
        entry.initializeEmptyBatch();
        try (var ledger = new EvaluationFeeLedger(EvaluationVideoCanonEvidence.LEDGER,
                EvaluationVideoCanonEntry.TOTAL_FEN, EvaluationVideoCanonEntry.PAUSE_FEN,
                EvaluationVideoCanonEntry.prices())) {
            // The cancel hook makes this fail after the shared cleanup has confirmed the stop.
            entry.runMember(ledger, EvaluationVideoCanonEntry.MODEL_A);
        }
    }
}
