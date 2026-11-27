package com.tianmu.agent.evaluation;

import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.feedback.FeedbackPreparationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Process 1 of the batch-4 pair: an EMPTY batch, only controller A. This class runs in its own
 * Surefire JVM; the continuation is a second, independent process in
 * {@link EvaluationVideoCanonBProcessTest}. The frozen Canon material is the same in both.
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
        "harness.trace.directory=target/evaluation/traces/canon-a"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.Timeout(value = 600, unit = java.util.concurrent.TimeUnit.SECONDS)
@Import(EvaluationVideoCanonFixture.class)
@EnabledIfSystemProperty(named = "eval.canon.enabled", matches = "true")
class EvaluationVideoCanonAProcessTest {
    @DynamicPropertySource
    static void loopback(DynamicPropertyRegistry registry) {
        registry.add("agent.video.base-url", () -> EvaluationVideoCanonLoopback.BASE_URL);
    }

    @Autowired
    EvaluationVideoCanonEntry entry;

    @Autowired
    FeedbackAnalysisService analysis;

    @Autowired
    Environment environment;

    @BeforeEach
    void pinClock() {
        analysis.clock(EvaluationVideoCanonFixture.TASK_CLOCK);
        EvaluationVideoCanonEntry.ACTIVE_CONTROLLER.set(EvaluationVideoCanonEntry.MODEL_A);
    }

    @AfterEach
    void gateMustAlreadyBeUnloaded() {
        assertFalse(EvaluationFeeGate.armed(),
                "no test cleanup may unload the gate; only the entry's confirmed stop can");
    }

    @Test
    void emptyBatchRunsOnlyAAndDeliversTheVideoGroundedCanonReport() throws Exception {
        var proof = EvaluationVideoCanonEntry.initializeEmptyBatch();
        assertEquals(Integer.getInteger("eval.opening.rows", 24), ((Number) proof.get("ledger_rows")).intValue(),
                "the copied batch carries the 24 historical B rows; no member has run in this batch");
        assertEquals(0, ((Number) proof.get("member_records")).intValue(),
                "an empty batch has no completion record for any member");
        assertEquals(770, ((Number) proof.get("conservative_fen")).intValue(),
                "7.70 CNY must be brought in before any dispatch");
        EvaluationVideoCanonEntry.requireClosedPaidExits(environment);

        EvaluationVideoCanonEntry.RunResult result;
        try (var ledger = new EvaluationFeeLedger(EvaluationVideoCanonEvidence.LEDGER,
                EvaluationVideoCanonEntry.TOTAL_FEN, EvaluationVideoCanonEntry.PAUSE_FEN,
                EvaluationVideoCanonEntry.prices())) {
            result = entry.runMember(ledger, EvaluationVideoCanonEntry.MODEL_A);
            entry.archive(EvaluationVideoCanonEntry.MODEL_A, result);

            assertEquals("SUCCEEDED", result.status(), () -> "run failed: " + result.error());
            assertEquals(FeedbackPreparationService.VERSION, result.rulesVersion(),
                    "the default entry must run feedback-pipeline-v2");
            assertEquals(List.of("read_skill", "analyze_video_content", "analyze_comments",
                            "analyze_danmaku", "create_feedback_report"), result.tools(),
                    "the video-grounded default tool sequence");
            assertEquals(Integer.getInteger("eval.expected.comments", 58), result.counts().path("comments").asInt(), "expected comments");
            assertEquals(Integer.getInteger("eval.expected.danmaku", 137), result.counts().path("danmaku").asInt(), "expected danmaku");
            assertEquals(Integer.getInteger("eval.expected.total", 195), result.counts().path("total").asInt(), "expected total");

            assertTrue(result.html().startsWith("<!doctype html>"), "the product must emit HTML");
            assertTrue(result.html().contains("观众反馈报告"), "the delivered report carries its title");
            assertTrue(result.html().contains(EvaluationVideoCanonLoopback.MARKER),
                    "the frozen-frame summary must reach the report body");
            assertTrue(result.html().contains("依据 12 个采样位置"),
                    "the report must state how many sampled positions the frame evidence used");

            var video = result.video();
            assertTrue(video.path("summary").asText("").contains(EvaluationVideoCanonLoopback.MARKER),
                    "the frozen job must carry the vision summary");
            assertEquals(12, video.path("sampledAtMs").size(), "all twelve frozen frames are anchored");
            assertEquals(EvaluationVideoCanonEvidence.sourceVersion(), video.path("sourceVersion").asText(),
                    "the report video is the frozen Canon source, not a re-encode");

            assertOnlyControllerSwapped(result);
            assertPinnedRolesByShape(result);
            assertDispatchRoles(result);
            assertVisionFrames(result);
            assertAnalysisVideoSplit(result);
            assertLedgerReconciles(result);

            assertEquals("ENTRY_CONFIRMED_STOP", result.finalizeProof().get("gate_unloaded_by"),
                    "the A process must unload the gate through the shared stop sequence");
            assertEquals(Boolean.FALSE, result.finalizeProof().get("armed_after"),
                    "the entry reports the gate as unloaded only after the confirmed stop");

            var doc = EvaluationVideoCanonEntry.ledgerDocument();
            long newSpend = 0;
            for (var row : result.ledgerRows()) newSpend += row.path("actual_fen").asLong();
            assertEquals(770 + newSpend, EvaluationVideoCanonEntry.sumConservative(doc),
                    "the opening 7.70 CNY plus A's settled spend");
            assertEquals(638, doc.path("carried_in").path("settled_fen").asLong()
                    + doc.path("carried_in").path("retained_fen").asLong(),
                    "the historical settled/retained rows are never folded into carried-in");
        }
    }

    private static void assertOnlyControllerSwapped(EvaluationVideoCanonEntry.RunResult result) {
        for (var row : result.requests()) {
            String role = String.valueOf(row.get("role"));
            String model = String.valueOf(row.get("model"));
            if (role.equals("controller")) assertEquals(EvaluationVideoCanonEntry.MODEL_A, model, "run A dispatches controller A only");
            if (role.equals("analysis")) assertEquals(EvaluationVideoCanonEntry.ANALYSIS_MODEL, model, "the analysis role is pinned");
            if (role.equals("vision")) assertEquals(EvaluationVideoCanonEntry.VISION_MODEL, model, "the vision role is pinned");
        }
    }

    /**
     * Roles are proven from what the double RECEIVED, not from a config string: a tool-carrying
     * body is the controller, a tool-free body is the analysis leg — even when both carry the same
     * V4.1 Flash request name (the batch-5 analysis binding).
     */
    private static void assertPinnedRolesByShape(EvaluationVideoCanonEntry.RunResult result) {
        var controller = result.requests().stream().filter(r -> "controller".equals(r.get("role"))).toList();
        var analysisRows = result.requests().stream().filter(r -> "analysis".equals(r.get("role"))).toList();
        assertEquals(List.of(EvaluationVideoCanonEntry.MODEL_A),
                controller.stream().map(r -> r.get("model")).distinct().toList(),
                "every controller dispatch names model A");
        assertEquals(List.of(true), controller.stream().map(r -> r.get("has_tools")).distinct().toList(),
                "every controller dispatch carries the tool catalogue");
        assertEquals(List.of(EvaluationVideoCanonEntry.ANALYSIS_MODEL),
                analysisRows.stream().map(r -> r.get("model")).distinct().toList(),
                "the analysis leg stays on the pinned analysis model");
        assertEquals(List.of(false), analysisRows.stream().map(r -> r.get("has_tools")).distinct().toList(),
                "no analysis dispatch carries a tool catalogue");
        assertEquals(List.of(EvaluationVideoCanonEntry.VISION_MODEL),
                result.requests().stream().filter(r -> "vision".equals(r.get("role")))
                        .map(r -> r.get("model")).distinct().toList(),
                "the vision leg stays on the registered image model");
    }

    private static void assertDispatchRoles(EvaluationVideoCanonEntry.RunResult result) {
        assertEquals(5, result.requests().stream().filter(r -> "controller".equals(r.get("role"))).count(),
                "read_skill, video, comments, danmaku, report");
        long analysis = result.requests().stream().filter(r -> "analysis".equals(r.get("role"))).count();
        assertTrue(analysis >= 2, "at least one classify and one merge dispatch");
        assertEquals(1, result.requests().stream().filter(r -> "analysis".equals(r.get("role"))
                && "MERGE".equals(r.get("analysis_kind"))).count(), "exactly one merge dispatch");
        assertEquals(1, result.requests().stream().filter(r -> "vision".equals(r.get("role"))).count(),
                "one frozen-frame vision call");
        assertTrue(result.requests().stream().noneMatch(r -> "unexpected".equals(r.get("role"))));
    }

    private static void assertVisionFrames(EvaluationVideoCanonEntry.RunResult result) {
        var vision = result.requests().stream().filter(r -> "vision".equals(r.get("role"))).findFirst().orElseThrow();
        assertEquals(12, ((Number) vision.get("images")).intValue(), "twelve frozen frames on the wire");
        var sent = (List<?>) vision.get("image_sha256");
        var expected = EvaluationVideoCanonEvidence.frames().stream()
                .map(EvaluationVideoCanonEvidence.Frame::sentSha).toList();
        assertEquals(expected, sent, "every sent frame is the documented derivative of the frozen JPEG");
        var atMs = (List<?>) vision.get("sampled_at_ms");
        assertEquals(EvaluationVideoCanonEvidence.frames().stream()
                        .map(EvaluationVideoCanonEvidence.Frame::atMs).toList(), atMs,
                "the vision request cites the manifest timestamps");
    }

    private static void assertAnalysisVideoSplit(EvaluationVideoCanonEntry.RunResult result) {
        var analysis = result.requests().stream().filter(r -> "analysis".equals(r.get("role"))).toList();
        long classifyWithVideo = analysis.stream().filter(r -> "CLASSIFY".equals(r.get("analysis_kind")))
                .filter(r -> Boolean.TRUE.equals(r.get("has_video"))).count();
        long classifyWithout = analysis.stream().filter(r -> "CLASSIFY".equals(r.get("analysis_kind")))
                .filter(r -> !Boolean.TRUE.equals(r.get("has_video"))).count();
        assertTrue(classifyWithVideo >= 1,
                "the danmaku classify prompt must carry the frozen video facts");
        assertEquals(1, classifyWithout,
                "the comment-only batch carries no video (the product's own condition)");
        var merge = analysis.stream().filter(r -> "MERGE".equals(r.get("analysis_kind"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, merge.get("has_video"),
                "the single-batch merge prompt does NOT carry video: recorded as a finding, not hidden");
    }

    private static void assertLedgerReconciles(EvaluationVideoCanonEntry.RunResult result) {
        assertEquals(result.requests().size(), result.ledgerRows().size(), "one ledger row per dispatch");
        for (int i = 0; i < result.ledgerRows().size(); i++) {
            var ledger = result.ledgerRows().get(i);
            var request = result.requests().get(i);
            assertEquals("SETTLED", ledger.path("status").asText(), "every dispatch settles once");
            assertEquals(request.get("body_sha256"), ledger.path("request_body_sha256").asText(),
                    "reservation digest == bytes the double received");
            assertEquals(((Number) request.get("body_bytes")).longValue(),
                    ledger.path("request_body_bytes").asLong(), "byte count matches");
        }
    }
}
