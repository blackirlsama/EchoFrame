package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.FeedbackAnalysis;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.feedback.FeedbackEmbedding;
import com.tianmu.agent.feedback.FeedbackSnapshotPort;
import com.tianmu.agent.feedback.FeedbackVectorIndex;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.runtime.RunService;
import com.tianmu.agent.runtime.RunState;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actual-entry regression for the report argument fix. The controller is scripted, but the tool
 * catalogue, preflight, dispatcher, correction loop, snapshot binding, analysis service and report
 * store are the product's own classes.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "agent.preparation.deterministic-routing=true",
        "agent.feedback.compression.enabled=false",
        "agent.feedback.embedding.enabled=false",
        "agent.feedback.milvus.enabled=false",
        "agent.feedback.rerank.enabled=false",
        "harness.trace.directory=target/evaluation/traces/report-arguments"})
@AutoConfigureMockMvc
class EvaluationReportArgumentsTest {
    private static final String PROMPT = "只根据视频1001的评论和弹幕生成反馈报告，不读取视频内容";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EVIDENCE = Path.of(System.getProperty("eval.evidence.root",
            "target/evaluation/report-arguments")).toAbsolutePath().normalize();

    @Autowired RunService runs;
    @Autowired FeedbackAnalysisService analysis;
    @Autowired MockMvc mvc;
    @MockBean ModelPort model;
    @MockBean FeedbackSnapshotPort source;
    @MockBean FeedbackEmbedding embedding;
    @MockBean FeedbackVectorIndex vectors;

    private final AtomicInteger exports = new AtomicInteger();

    @BeforeEach
    void script() throws Exception {
        Files.createDirectories(EVIDENCE);
        try (var files = Files.list(EVIDENCE)) {
            for (Path stale : files.filter(p -> p.getFileName().toString().startsWith("tool-diagnostic-"))
                    .toList()) Files.deleteIfExists(stale);
        }
        exports.set(0);
        when(embedding.version()).thenReturn("test-embedding:dim3:framing-v1");
        when(embedding.embed(anyList(), any())).thenAnswer(inv -> {
            List<String> inputs = inv.getArgument(0);
            return inputs.stream().map(text -> List.of(1f, 0f, 0f)).toList();
        });
        when(vectors.search(any(), anyList(), anyInt(), any())).thenReturn(List.of());
        when(source.export(any(), any(), any(), any())).thenAnswer(inv -> {
            exports.incrementAndGet();
            FeedbackAnalysis.Request request = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<List<FeedbackAnalysis.Unit>> sink = inv.getArgument(3);
            var rows = new ArrayList<FeedbackAnalysis.Unit>();
            var at = request.query().end().minusSeconds(1).atZone(com.tianmu.agent.core.DanmakuTypes.ZONE).toInstant();
            for (var selected : request.sources()) for (int i = 1; i <= 6; i++)
                rows.add(new FeedbackAnalysis.Unit((selected == FeedbackAnalysis.Source.COMMENT ? "C:" : "D:") + i,
                        selected, "" + i, i % 2 == 0 ? "没声音" : "😀", "", i == 1,
                        selected == FeedbackAnalysis.Source.DANMAKU ? 1000L : null,
                        "viewer" + (i % 3), at));
            sink.accept(rows);
            return new FeedbackSnapshotPort.Receipt(Instant.now(),
                    new FeedbackAnalysis.Counts(6, 6, 1, 1, 3));
        });
    }

    @AfterEach
    void disarm() {
        EvaluationFeeGate.clear();
        assertFalse(EvaluationFeeGate.armed());
    }

    @Test
    void mixedEvidenceIsRejectedBeforeTheHandlerThenCorrected() throws Exception {
        var admission = new DiagnosticAdmission(EVIDENCE.resolve("tool-diagnostic-mixed.jsonl"));
        EvaluationFeeGate.install(admission);
        var observations = new ArrayList<Map<String, Object>>();
        scriptModel("mixed", observations);
        RunState run = run();
        assertSucceeded(run);
        assertFalse(admission.sealed, "diagnostics were writable");
        assertEquals(1, exports.get(), "the source snapshot is exported once; no report side effect on rejection");
        var rejection = firstCreateReport(observations);
        assertEquals("INVALID_ARGUMENTS", rejection.get("error_code"));
        assertEquals(Boolean.TRUE, rejection.get("retryable"));
        assertFalse(String.valueOf(rejection).contains("forged-legacy"),
                "no argument value may reach the model-visible rejection");
        var rows = rows(admission.file);
        assertEquals("REJECTED", rows.get(0).path("kind").asText());
        assertEquals("create_feedback_report", rows.get(0).path("tool").asText());
        assertEquals("PREFLIGHT", rows.get(0).path("phase").asText());
        assertEquals("INVALID_ARGUMENTS", rows.get(0).path("code").asText());
        assertEquals(Boolean.FALSE, rows.get(0).path("handler_executed").asBoolean());
        assertTrue(rows.get(0).path("problems").toString().contains("EVIDENCE_MIXED"));
        assertTrue(rows.get(0).path("paths").toString().contains("comment_evidence_id"));
        assertFalse(Files.readString(admission.file, StandardCharsets.UTF_8).contains("forged-legacy"),
                "diagnostics record only codes and declared paths, never argument values");
        assertEquals("CORRECTED", rows.get(1).path("kind").asText());
        assertTrue(rows.stream().noneMatch(r -> "STOPPED".equals(r.path("kind").asText())));
    }

    @Test
    void blankSourceIdIsRejectedBeforeTheHandlerThenCorrected() throws Exception {
        var admission = new DiagnosticAdmission(EVIDENCE.resolve("tool-diagnostic-blank.jsonl"));
        EvaluationFeeGate.install(admission);
        var observations = new ArrayList<Map<String, Object>>();
        scriptModel("blank", observations);
        RunState run = run();
        assertSucceeded(run);
        var rejection = firstCreateReport(observations);
        assertEquals("INVALID_ARGUMENTS", rejection.get("error_code"));
        var rows = rows(admission.file);
        assertEquals("PREFLIGHT", rows.get(0).path("phase").asText());
        assertTrue(rows.get(0).path("problems").toString().contains("STRING"));
        assertTrue(rows.get(0).path("paths").toString().contains("comment_evidence_id"));
        assertEquals(1, exports.get(), "no extra snapshot export before the corrected report");
    }

    @Test
    void sourceSelectionMismatchIsRejectedBeforeTheHandlerThenCorrected() throws Exception {
        var admission = new DiagnosticAdmission(EVIDENCE.resolve("tool-diagnostic-selection.jsonl"));
        EvaluationFeeGate.install(admission);
        var observations = new ArrayList<Map<String, Object>>();
        scriptModel("selection", observations);
        RunState run = run();
        assertSucceeded(run);
        var rows = rows(admission.file);
        assertTrue(rows.get(0).path("problems").toString().contains("EVIDENCE_SELECTION"));
        assertTrue(rows.get(0).path("paths").toString().contains("danmaku_evidence_id"));
    }

    @Test
    void repeatedBadArgumentsExhaustTheExistingCorrectionLimit() throws Exception {
        var admission = new DiagnosticAdmission(EVIDENCE.resolve("tool-diagnostic-repeat.jsonl"));
        EvaluationFeeGate.install(admission);
        var observations = new ArrayList<Map<String, Object>>();
        scriptModel("repeat", observations);
        RunState run = run();
        assertEquals(Status.FAILED, run.snapshot().status());
        assertEquals("TOOL_FAILURE_LIMIT", run.snapshot().errorCode());
        assertEquals(0, reportArtifacts(run), "no report may be written by repeated bad arguments");
        var rows = rows(admission.file);
        assertEquals(3, rows.stream().filter(r -> "REJECTED".equals(r.path("kind").asText())).count());
        assertEquals(1, rows.stream().filter(r -> "STOPPED".equals(r.path("kind").asText())).count());
        assertTrue(rows.get(rows.size() - 1).path("code").asText().equals("TOOL_FAILURE_LIMIT"));
    }

    @Test
    void unknownEvidenceStillFailsAfterAPreflightPass() throws Exception {
        var admission = new DiagnosticAdmission(EVIDENCE.resolve("tool-diagnostic-unknown.jsonl"));
        EvaluationFeeGate.install(admission);
        var observations = new ArrayList<Map<String, Object>>();
        scriptModel("unknown", observations);
        RunState run = run();
        assertEquals(Status.FAILED, run.snapshot().status());
        assertEquals("RESULT_NOT_FOUND", run.snapshot().errorCode());
        assertEquals(0, reportArtifacts(run));
        if (Files.exists(admission.file)) {
            var rows = rows(admission.file);
            assertTrue(rows.stream().noneMatch(r -> "create_feedback_report".equals(r.path("tool").asText())),
                    "an evidence-code failure is not rewritten as an argument preflight rejection");
        }
    }

    @Test
    void aDiagnosticWriteFailureSealsTheGate() throws Exception {
        Path directory = EVIDENCE.resolve("not-a-file");
        Files.createDirectories(directory);
        var admission = new DiagnosticAdmission(directory);
        EvaluationFeeGate.install(admission);
        var observations = new ArrayList<Map<String, Object>>();
        scriptModel("mixed", observations);
        RunState run = run();
        assertTrue(admission.sealed, "a failed diagnostic write must seal the admission");
        assertEquals(Status.SUCCEEDED, run.snapshot().status(),
                "the scripted model may still finish through the corrected path; the seal blocks the next paid dispatch");
        assertThrows(AgentException.class, () -> admission.admitChat("model", "127.0.0.1", 1, 1, "harness"),
                "no further paid dispatch may be admitted after the diagnostic failure");
    }

    private RunState run() throws Exception {
        RunState run = runs.create(PROMPT, new AccessScope("demo", "alice", Set.of(1001L)));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!com.tianmu.agent.core.AgentTypes.terminal(run.snapshot().status()) && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(com.tianmu.agent.core.AgentTypes.terminal(run.snapshot().status()), "run did not terminate");
        return run;
    }

    private void scriptModel(String mode, List<Map<String, Object>> observed) {
        when(model.decide(any(), any())).thenAnswer(inv -> {
            ModelContext context = inv.getArgument(0);
            if (!context.activeSkills().contains("audience-report"))
                return Decision.tool("read_skill", Map.of("name", "audience-report"));
            ToolResult comments = context.observations().stream()
                    .filter(r -> r.tool().equals("analyze_comments")).findFirst().orElse(null);
            ToolResult danmaku = context.observations().stream()
                    .filter(r -> r.tool().equals("analyze_danmaku")).findFirst().orElse(null);
            if (comments == null && danmaku == null)
                return new Decision(Kind.TOOL, new ToolCall("analyze_comments", Map.of()), null, null,
                        List.of(new ToolCall("analyze_danmaku", Map.of())));
            assertNotNull(comments);
            assertNotNull(danmaku);
            String comment = comments.evidenceId();
            String dan = danmaku.evidenceId();
            ToolResult last = context.observations().stream()
                    .filter(r -> r.tool().equals("create_feedback_report"))
                    .reduce((a, b) -> b).orElse(null);
            if (last != null && last.data().containsKey("error_code") && !"repeat".equals(mode)) {
                observed.add(last.data());
                return Decision.tool("create_feedback_report",
                        Map.of("comment_evidence_id", comment, "danmaku_evidence_id", dan));
            }
            if ("mixed".equals(mode) && !seenRejection(context))
                return Decision.tool("create_feedback_report", Map.of(
                        "evidence_id", "forged-legacy", "comment_evidence_id", comment,
                        "danmaku_evidence_id", dan));
            if ("blank".equals(mode) && !seenRejection(context))
                return Decision.tool("create_feedback_report", Map.of(
                        "comment_evidence_id", "   ", "danmaku_evidence_id", dan));
            if ("selection".equals(mode) && !seenRejection(context))
                return Decision.tool("create_feedback_report", Map.of("comment_evidence_id", comment));
            if ("repeat".equals(mode))
                return Decision.tool("create_feedback_report", Map.of(
                        "evidence_id", "forged-legacy", "comment_evidence_id", comment,
                        "danmaku_evidence_id", dan));
            if ("unknown".equals(mode))
                return Decision.tool("create_feedback_report",
                        Map.of("comment_evidence_id", "ev_unknown", "danmaku_evidence_id", "ev_unknown"));
            return Decision.tool("create_feedback_report",
                    Map.of("comment_evidence_id", comment, "danmaku_evidence_id", dan));
        });
    }

    private static boolean seenRejection(ModelContext context) {
        return context.observations().stream().anyMatch(r -> r.tool().equals("create_feedback_report")
                && "INVALID_ARGUMENTS".equals(r.data().get("error_code")));
    }

    private static void assertSucceeded(RunState run) throws Exception {
        assertEquals(Status.SUCCEEDED, run.snapshot().status(), run.snapshot().errorCode());
        assertEquals(1, reportArtifacts(run));
    }

    private static long reportArtifacts(RunState run) {
        return run.execution().artifactEvidence().size();
    }

    private static Map<String, Object> firstCreateReport(List<Map<String, Object>> observed) {
        return observed.stream().filter(m -> m.containsKey("error_code")).findFirst().orElseThrow();
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> rows(Path file) throws Exception {
        var rows = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (var line : lines.toList()) if (!line.isBlank()) rows.add(JSON.readTree(line));
        }
        assertFalse(rows.isEmpty());
        return rows;
    }

    /** Writes fixed enums and declared paths only; a write failure seals further dispatch. */
    static final class DiagnosticAdmission implements EvaluationFeeGate.Admission {
        private final Path file;
        private boolean sealed;
        DiagnosticAdmission(Path file) { this.file = file; }
        @Override public synchronized void toolRejected(EvaluationFeeGate.ToolRejection rejection) {
            append("REJECTED", rejection);
        }
        @Override public synchronized void toolCorrected(String runId, String tool, String phase, int correction) {
            append("CORRECTED", runId, tool, phase, null, correction);
        }
        @Override public synchronized void toolStopped(String runId, String tool, String phase, String code, int corrections) {
            append("STOPPED", runId, tool, phase, code, corrections);
        }
        private void append(String kind, EvaluationFeeGate.ToolRejection rejection) {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", kind); row.put("tool", rejection.tool()); row.put("phase", rejection.phase());
            row.put("code", rejection.code()); row.put("handler_executed", rejection.handlerExecuted());
            row.put("failure_class", rejection.failureClass()); row.put("issue_count", rejection.issueCount());
            row.put("truncated", rejection.issuesTruncated());
            row.put("paths", rejection.paths()); row.put("problems", rejection.problems());
            write(row);
        }
        private void append(String kind, String runId, String tool, String phase, String code, int value) {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", kind); row.put("run_id", runId); row.put("tool", tool); row.put("phase", phase);
            row.put("code", code); row.put("value", value);
            write(row);
        }
        private void write(Map<String, Object> row) {
            if (sealed) return;
            try {
                Files.writeString(file, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception failure) {
                sealed = true;
            }
        }
        @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes, int maxTokens) {
            return admitChat(model, host, payloadBytes, maxTokens, null);
        }
        @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes, int maxTokens, String role) {
            if (sealed) throw new AgentException("TOOL_DIAGNOSTIC_WRITE_FAILED", "diagnostic write failed");
            return null;
        }
        @Override public EvaluationFeeGate.Reservation admitVideo(String provider, String path) {
            if (sealed) throw new AgentException("TOOL_DIAGNOSTIC_WRITE_FAILED", "diagnostic write failed");
            return null;
        }
    }
}
