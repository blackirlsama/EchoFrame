package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Process 2 of the batch-4 pair: a NEW JVM on the SAME batch. A has a completion record, so the
 * launcher rule skips it and runs only B. A's ledger rows and completion record must be unchanged.
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
        "harness.trace.directory=target/evaluation/traces/canon-b"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.Timeout(value = 600, unit = java.util.concurrent.TimeUnit.SECONDS)
@Import(EvaluationVideoCanonFixture.class)
@EnabledIfSystemProperty(named = "eval.canon.enabled", matches = "true")
class EvaluationVideoCanonBProcessTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void loopback(DynamicPropertyRegistry registry) {
        registry.add("agent.video.base-url", () -> EvaluationVideoCanonLoopback.BASE_URL);
        registry.add("harness.trace.directory",
                () -> System.getProperty("eval.trace.dir", "target/evaluation/traces/canon-b"));
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
        EvaluationVideoCanonEntry.ACTIVE_CONTROLLER.set(EvaluationVideoCanonEntry.MODEL_B);
    }

    @AfterEach
    void gateMustAlreadyBeUnloaded() {
        assertFalse(EvaluationFeeGate.armed(),
                "no test cleanup may unload the gate; only the entry's confirmed stop can");
    }

    @Test
    void newProcessOnTheSameBatchRunsOnlyBAndKeepsAIntact() throws Exception {
        Path completedA = EvaluationVideoCanonEvidence.COMPLETED.resolve(
                entry.recordName(EvaluationVideoCanonEntry.MODEL_A));
        assertTrue(Files.isRegularFile(completedA), "process 1 must have completed A before this continuation");
        JsonNode recordA = JSON.readTree(Files.readString(completedA, StandardCharsets.UTF_8));
        assertNotEquals(recordA.path("pid").asLong(), ProcessHandle.current().pid(),
                "the continuation must be a different process from the A run");
        EvaluationVideoCanonEntry.requireApprovalIfConfigured();

        JsonNode before = EvaluationVideoCanonEntry.ledgerDocument();
        int rowsBefore = before.path("reservations").size();
        long usedBefore = EvaluationVideoCanonEntry.sumConservative(before);
        String digestA = EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(completedA));
        EvaluationVideoCanonEntry.requireClosedPaidExits(environment);

        EvaluationVideoCanonEntry.Continuation continuation;
        try (var ledger = new EvaluationFeeLedger(EvaluationVideoCanonEvidence.LEDGER,
                EvaluationVideoCanonEntry.TOTAL_FEN, EvaluationVideoCanonEntry.PAUSE_FEN,
                EvaluationVideoCanonEntry.prices())) {
            assertEquals(rowsBefore, EvaluationVideoCanonEntry.reservationCount(EvaluationVideoCanonEvidence.LEDGER),
                    "the batch ledger is the same file, not a new one");
            continuation = entry.continueBatch(ledger);
            IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                    () -> entry.continueBatch(ledger));
            assertTrue(duplicate.getMessage().contains("DUPLICATE_MEMBER_RUN"), duplicate.getMessage());
        }

        assertEquals(2, continuation.timeline().size());
        assertEquals("SKIPPED_COMPLETED", continuation.timeline().get(0).get("action"), "A is never rerun");
        assertEquals("RAN", continuation.timeline().get(1).get("action"), "B is the only continued member");
        var b = continuation.last();
        assertNotNull(b, "the continuation must have run B");
        assertEquals(EvaluationVideoCanonEntry.MODEL_B, b.controller());
        assertEquals("SUCCEEDED", b.status(), () -> "run failed: " + b.error());
        assertEquals(List.of("read_skill", "analyze_video_content", "analyze_comments",
                "analyze_danmaku", "create_feedback_report"), b.tools());
        assertEquals(Integer.getInteger("eval.expected.comments", 58), b.counts().path("comments").asInt());
        assertEquals(Integer.getInteger("eval.expected.danmaku", 137), b.counts().path("danmaku").asInt());
        assertEquals(Integer.getInteger("eval.expected.total", 195), b.counts().path("total").asInt());
        assertTrue(b.html().contains(EvaluationVideoCanonLoopback.MARKER));
        assertTrue(b.html().contains("依据 12 个采样位置"));
        assertOnlyControllerB(b);

        JsonNode after = EvaluationVideoCanonEntry.ledgerDocument();
        assertTrue(after.path("reservations").size() > rowsBefore, "B added its own rows");
        for (int i = 0; i < rowsBefore; i++) {
            assertEquals(before.path("reservations").path(i).path("reservation_id").asText(),
                    after.path("reservations").path(i).path("reservation_id").asText(),
                    "A's rows stay byte-identical in order");
            assertEquals(before.path("reservations").path(i).path("actual_fen").asLong(),
                    after.path("reservations").path(i).path("actual_fen").asLong(),
                    "A's settled amounts are not rewritten");
        }
        assertEquals(digestA, EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(completedA)),
                "A's completion record is unchanged by the continuation");
        assertEquals(638, after.path("carried_in").path("settled_fen").asLong()
                + after.path("carried_in").path("retained_fen").asLong(),
                "the historical rows stay counted once; carried_in is unchanged");

        long historicalRows = before.path("carried_in").path("rows_count").asInt(24);
        long aNewSpend = 0;
        for (int i = (int) historicalRows; i < rowsBefore; i++) {
            var row = before.path("reservations").path(i);
            aNewSpend += "SETTLED".equals(row.path("status").asText())
                    ? row.path("actual_fen").asLong() : row.path("reserved_fen").asLong();
        }
        long bSpend = 0;
        for (var row : b.ledgerRows()) {
            bSpend += "SETTLED".equals(row.path("status").asText())
                    ? row.path("actual_fen").asLong() : row.path("reserved_fen").asLong();
        }
        assertEquals(usedBefore + bSpend, EvaluationVideoCanonEntry.sumConservative(after),
                "B adds real spend on top of A");
        assertEquals(770 + aNewSpend + bSpend, EvaluationVideoCanonEntry.sumConservative(after),
                "7.70 CNY opening + A's new rows + B; the 24 historical rows are counted once");
        assertEquals(770 + aNewSpend, usedBefore,
                "the continuation opens at 7.70 CNY plus A's new rows, counted once");

        if ("SUCCEEDED".equals(recordA.path("status").asText())) {
            assertSamePinnedRoles(b);
            assertRoleShapeAcrossThePair(b);
        } else {
            assertEquals("STOPPED_CONFIRMED", recordA.path("status").asText(),
                    "only a verified confirmed stop may skip the normal-A pair comparison");
        }
        assertEquals("ENTRY_CONFIRMED_STOP", b.finalizeProof().get("gate_unloaded_by"),
                "the B process must unload the gate through the shared stop sequence");
        writeRestartEvidence(continuation, rowsBefore, usedBefore, after);
    }

    private static void assertOnlyControllerB(EvaluationVideoCanonEntry.RunResult result) {
        for (var row : result.requests()) {
            String role = String.valueOf(row.get("role"));
            String model = String.valueOf(row.get("model"));
            if (role.equals("controller")) assertEquals(EvaluationVideoCanonEntry.MODEL_B, model, "run B dispatches controller B only");
            if (role.equals("analysis")) assertEquals(EvaluationVideoCanonEntry.ANALYSIS_MODEL, model, "the analysis role stays pinned");
            if (role.equals("vision")) assertEquals(EvaluationVideoCanonEntry.VISION_MODEL, model, "the vision role stays pinned");
        }
    }

    /** Only the controller may differ across the pair. The vision request is byte-identical and the
     *  analysis prompts carry the same frozen material; parallel classification seeds prior topic
     *  titles, so whole-body byte equality is deliberately NOT claimed for the analysis role. */
    private static void assertSamePinnedRoles(EvaluationVideoCanonEntry.RunResult b) throws Exception {
        JsonNode aRequests = JSON.readTree(Files.readString(
                EvaluationVideoCanonEvidence.ROOT.resolve("a-dispatches.json"), StandardCharsets.UTF_8));
        var aVisionFrames = new java.util.ArrayList<Object>();
        var aVisionAt = new java.util.ArrayList<Object>();
        var aKinds = new java.util.ArrayList<String>();
        var aMembers = new java.util.ArrayList<Object>();
        var aSummary = new java.util.ArrayList<String>();
        for (JsonNode row : aRequests) {
            if ("vision".equals(row.path("role").asText())) {
                aVisionFrames.add(JSON.convertValue(row.path("image_sha256"), java.util.List.class));
                aVisionAt.add(numbers(JSON.convertValue(row.path("sampled_at_ms"), java.util.List.class)));
            }
            if ("analysis".equals(row.path("role").asText())) {
                aKinds.add(row.path("analysis_kind").asText());
                aMembers.add(row.path("member_count").asInt());
                aSummary.add(row.path("video_summary_sha256").asText());
            }
        }
        var bAnalysis = b.requests().stream().filter(r -> "analysis".equals(r.get("role"))).toList();
        var bVision = b.requests().stream().filter(r -> "vision".equals(r.get("role"))).toList();
        assertEquals(aVisionFrames, bVision.stream().map(r -> r.get("image_sha256")).toList(),
                "the frozen frames sent to vision are identical across the pair");
        assertEquals(aVisionAt, bVision.stream().map(r -> numbers((java.util.List<?>) r.get("sampled_at_ms"))).toList(),
                "the sampled timestamps sent to vision are identical across the pair");
        assertEquals(aKinds, bAnalysis.stream().map(r -> String.valueOf(r.get("analysis_kind"))).toList(),
                "the analysis phase shape is identical across the pair");
        assertEquals(aMembers, bAnalysis.stream().map(r -> r.get("member_count")).toList(),
                "the analysis batches cover the same members");
        assertEquals(aSummary, bAnalysis.stream().map(r -> String.valueOf(r.get("video_summary_sha256"))).toList(),
                "the frozen frame summary reaching analysis is identical across the pair");
    }

    /**
     * The pair-level proof, taken from what the double RECEIVED in each process: the controller is
     * the only role whose request name changes; the analysis leg keeps the identical request name
     * and stays tool-free on both sides (batch 5 pins it to the same V4.1 Flash name as controller
     * A, so a name-only check could not tell the two roles apart at all).
     */
    private static void assertRoleShapeAcrossThePair(EvaluationVideoCanonEntry.RunResult b) throws Exception {
        var aRows = rows(JSON.readTree(Files.readString(
                EvaluationVideoCanonEvidence.ROOT.resolve("a-dispatches.json"), StandardCharsets.UTF_8)));
        var bRows = b.requests();
        assertEquals(List.of(EvaluationVideoCanonEntry.MODEL_A), models(aRows, "controller"),
                "process 1 dispatched controller A only");
        assertEquals(List.of(EvaluationVideoCanonEntry.MODEL_B), models(bRows, "controller"),
                "process 2 dispatched controller B only");
        assertNotEquals(models(aRows, "controller"), models(bRows, "controller"),
                "the controller must be the role that changed");
        assertEquals(models(aRows, "analysis"), models(bRows, "analysis"),
                "the analysis role keeps the same request name across the pair");
        assertEquals(List.of(EvaluationVideoCanonEntry.ANALYSIS_MODEL), models(bRows, "analysis"));
        assertEquals(List.of(true), tools(aRows, "controller"), "controller bodies carry tools on both sides");
        assertEquals(List.of(true), tools(bRows, "controller"));
        assertEquals(List.of(false), tools(aRows, "analysis"), "analysis bodies never carry tools");
        assertEquals(List.of(false), tools(bRows, "analysis"));
        assertEquals(models(aRows, "vision"), models(bRows, "vision"),
                "the vision role keeps the registered image model");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(JsonNode array) {
        return JSON.convertValue(array, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    }

    private static List<Object> models(List<Map<String, Object>> rows, String role) {
        return rows.stream().filter(r -> role.equals(r.get("role"))).map(r -> r.get("model")).distinct().toList();
    }

    private static List<Object> tools(List<Map<String, Object>> rows, String role) {
        return rows.stream().filter(r -> role.equals(r.get("role"))).map(r -> r.get("has_tools")).distinct().toList();
    }

    private static java.util.List<Long> numbers(java.util.List<?> values) {
        return values.stream().map(value -> ((Number) value).longValue()).toList();
    }

    private static void writeRestartEvidence(EvaluationVideoCanonEntry.Continuation continuation,
                                             int rowsBefore, long usedBefore, JsonNode after) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("verdict", "RESTART_RAN_ONLY_B_ON_THE_SAME_LEDGER");
        doc.put("pid", ProcessHandle.current().pid());
        doc.put("timeline", continuation.timeline());
        doc.put("rows_before", rowsBefore);
        doc.put("rows_after", after.path("reservations").size());
        doc.put("conservative_before_fen", usedBefore);
        doc.put("conservative_after_fen", EvaluationVideoCanonEntry.sumConservative(after));
        Files.writeString(EvaluationVideoCanonEvidence.ROOT.resolve("restart-only-b.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
    }
}
