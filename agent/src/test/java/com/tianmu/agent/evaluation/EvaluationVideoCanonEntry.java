package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AgentTypes.Status;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.runtime.RunService;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch 4 shared launch entry. Both independent processes (A alone / continuation B) and the stop
 * cases call the SAME methods here: one prompt, one material boundary, one gate/ledger pair, one
 * release sequence. The launcher itself is not migrated; only its release semantics are reused,
 * exactly as recorded in the batch-3 report.
 */
final class EvaluationVideoCanonEntry {
    static final String MODEL_A = EvaluationVideoCanonLoopback.CONTROLLER_A;
    static final String MODEL_B = EvaluationVideoCanonLoopback.CONTROLLER_B;
    static final String ANALYSIS_MODEL = EvaluationVideoCanonLoopback.ANALYSIS;
    static final String VISION_MODEL = EvaluationVideoCanonLoopback.VISION;
    static final long CANON_VIDEO = EvaluationVideoCanonEvidence.CANON_VIDEO;
    static final Instant TASK_NOW = Instant.parse("2026-11-16T00:00:00Z");
    static final String PROMPT = "分析视频" + CANON_VIDEO + "从2026-11-03到2026-11-10的评论和弹幕，"
            + "结合视频画面，生成观众反馈HTML报告";
    static final String CREDENTIAL = "demo-alice";
    static final int TOTAL_FEN = 100000;
    static final int PAUSE_FEN = 10000;
    static final AtomicReference<String> ACTIVE_CONTROLLER = new AtomicReference<>(MODEL_A);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final RunService runs;
    private final FeedbackAnalysisService analysis;
    private final MockMvc mvc;

    EvaluationVideoCanonEntry(RunService runs, FeedbackAnalysisService analysis, MockMvc mvc) {
        this.runs = runs;
        this.analysis = analysis;
        this.mvc = mvc;
    }

    // ---------------------------------------------------------------- batch start

    /** Empty batch: the historical ledger is copied read-only, and the carried-in envelope is
     *  asserted BEFORE any dispatch. A fresh directory is required; an occupied batch root is
     *  refused rather than silently overwritten. */
    static Map<String, Object> initializeEmptyBatch() throws IOException {
        Path root = EvaluationVideoCanonEvidence.requireOwned(EvaluationVideoCanonEvidence.ROOT);
        EvaluationVideoCanonEvidence.requireFreshBatchRoot(root);
        requireApprovalIfConfigured();
        Path historical = historicalLedger();
        byte[] historicalBytes = Files.readAllBytes(historical);
        String expected = System.getProperty("eval.historical.ledger.sha256", "").strip();
        if (expected.isEmpty())
            throw new IllegalStateException("LEDGER_SOURCE_SHA_REQUIRED: pass "
                    + "-Deval.historical.ledger.sha256=<trusted digest>");
        assertEquals(expected, EvaluationVideoCanonEvidence.sha256(historicalBytes),
                "the injected historical ledger must be the verified source");
        JsonNode historicalDoc = JSON.readTree(historicalBytes);
        Files.createDirectories(root);
        EvaluationVideoCanonEvidence.prepareFrames();
        Files.createDirectories(EvaluationVideoCanonEvidence.COMPLETED);
        Files.createDirectories(EvaluationVideoCanonEvidence.STOP);
        Path ledger = EvaluationVideoCanonEvidence.LEDGER;
        var historicalRows = new ArrayList<JsonNode>();
        historicalDoc.path("reservations").forEach(historicalRows::add);
        ObjectNode carried = (ObjectNode) historicalDoc.path("carried_in");
        carried.put("rows_sha256", EvaluationFeeLedger.rowsDigest(historicalRows));
        carried.put("rows_count", historicalRows.size());
        Files.writeString(ledger, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(historicalDoc),
                StandardCharsets.UTF_8);
        try (var opened = new EvaluationFeeLedger(ledger, TOTAL_FEN, PAUSE_FEN, prices())) {
            assertEquals(Integer.getInteger("eval.opening.carried", 638).longValue(),
                    opened.carriedIn().settledFen() + opened.carriedIn().retainedFen(),
                    "the carried-in envelope must be the file-verified source");
            assertEquals(Integer.getInteger("eval.opening.conservative", 770).longValue(), opened.conservativeUsedFen(),
                    "opening conservative total");
            assertEquals(Integer.getInteger("eval.opening.batchNew", 132).longValue(), opened.batchNewSpendFen(),
                    "the historical rows are counted exactly once");
            assertEquals(0, opened.openReservations().size(), "the copied historical batch has no open reservation");
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("verdict", "EMPTY_BATCH_OPENED_WITH_CARRIED_IN");
            proof.put("pid", ProcessHandle.current().pid());
            proof.put("opened_at", Instant.now().toString());
            proof.put("ledger_rows", reservationCount(ledger));
            try (var records = Files.list(EvaluationVideoCanonEvidence.COMPLETED)) {
                proof.put("member_records", records.count());
            }
            proof.put("carried_settled_fen", opened.carriedIn().settledFen());
            proof.put("carried_retained_fen", opened.carriedIn().retainedFen());
            proof.put("conservative_fen", opened.conservativeUsedFen());
            proof.put("batch_new_fen", opened.batchNewSpendFen());
            proof.put("historical_source_sha256_16", EvaluationVideoCanonEvidence.sha256(
                    Files.readAllBytes(historical)).substring(0, 16));
            Files.writeString(EvaluationVideoCanonEvidence.ROOT.resolve("empty-batch.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof), StandardCharsets.UTF_8);
            return proof;
        }
    }

    /** Approval is opt-in for synthetic runs and mandatory when {@code eval.approval.required=true}. */
    static void requireApprovalIfConfigured() throws IOException {
        String approvalFile = System.getProperty("eval.approval.file", "").strip();
        if (!approvalFile.isEmpty() || Boolean.getBoolean("eval.approval.required"))
            requireApproval(EvaluationVideoCanonEvidence.ROOT,
                    approvalFile.isEmpty() ? null : Path.of(approvalFile));
    }

    /**
     * Approval receipt gate for the two-process entry. Missing, draft, mismatched, old-baseline
     * and multi-run receipts are refused before the ledger is copied. When
     * {@code eval.contract.required=true}, the receipt MUST carry the SHA-256 of a contract file
     * that binds the actual baseline, materials, frames, ledger, roles and conditions.
     */
    static EvaluationApproval.Receipt requireApproval(Path root, Path receiptFile) throws IOException {
        String baseline = System.getProperty("eval.approval.baseline", "").strip();
        if (baseline.isEmpty())
            throw new IllegalStateException("APPROVAL_BASELINE_REQUIRED: pass -Deval.approval.baseline=<commit>");
        String budget = EvaluationVideoCanonContract.budgetProfile();
        String group = System.getProperty("eval.approval.group", "canon-730013-pair");
        if (receiptFile == null) throw new AgentException("APPROVAL_REQUIRED", "approval receipt required");
        JsonNode node = JSON.readTree(Files.readString(receiptFile, StandardCharsets.UTF_8));
        var receipt = new EvaluationApproval.Receipt(
                node.path("state").asText(""), node.path("approved").asBoolean(false),
                node.path("scope").asText(""), node.path("baseline").asText(""),
                node.path("contract_sha256").asText(""),
                node.path("evidence_root").asText(""), node.path("max_runs").asInt(0),
                node.path("automatic_retry").asBoolean(false),
                node.path("budget_profile").asText(""), node.path("pair_group").asText(""));
        String contractFile = System.getProperty("eval.approval.contract.file", "").strip();
        boolean contractRequired = Boolean.getBoolean("eval.contract.required")
                || Boolean.getBoolean("eval.approval.required");
        if (contractRequired && contractFile.isEmpty())
            throw new AgentException("APPROVAL_CONTRACT_REQUIRED",
                    "pass -Deval.approval.contract.file=<contract.json> and put its SHA-256 in the receipt");
        if (!contractFile.isEmpty()) {
            Path materialConfig = Path.of(System.getProperty("eval.material.config",
                    "eval/report-trial-admission.example.json")).toAbsolutePath().normalize();
            String framesRaw = System.getProperty("eval.frames.source", "").strip();
            if (framesRaw.isEmpty())
                throw new IllegalStateException("EVAL_FRAMES_SOURCE_REQUIRED: pass -Deval.frames.source=...");
            Path framesSource = Path.of(framesRaw).toAbsolutePath().normalize();
            Path ledgerSource = historicalLedger();
            String ledgerSha = System.getProperty("eval.historical.ledger.sha256", "").strip();
            if (ledgerSha.isEmpty())
                throw new IllegalStateException("LEDGER_SOURCE_SHA_REQUIRED: pass "
                        + "-Deval.historical.ledger.sha256=<trusted digest>");
            EvaluationVideoCanonContract.verify(Path.of(contractFile), receipt.contractSha256(), baseline,
                    group, materialConfig, framesSource, ledgerSource, ledgerSha);
        }
        return EvaluationApproval.require(receipt, group, baseline, receipt.contractSha256(),
                root.toString().replace('\\', '/'), budget, group);
    }

    static Path historicalLedger() {
        String raw = System.getProperty("eval.historical.batch.ledger");
        if (raw == null || raw.isBlank()) raw = System.getenv("EVAL_HISTORICAL_BATCH_LEDGER");
        if (raw == null || raw.isBlank())
            throw new IllegalStateException("the historical batch ledger must be injected explicitly "
                    + "(-Deval.historical.batch.ledger=... or EVAL_HISTORICAL_BATCH_LEDGER)");
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path))
            throw new IllegalStateException("the injected historical ledger does not exist: " + path);
        return path;
    }

    // ---------------------------------------------------------------- one member

    record RunResult(String controller, long pid, String runId, String status, String rulesVersion,
                     List<String> tools, JsonNode counts, String html,
                     List<Map<String, Object>> requests, List<JsonNode> ledgerRows,
                     JsonNode video, String error, Map<String, Object> finalizeProof) {}

    RunResult runMember(EvaluationFeeLedger ledger, String controller) throws Exception {
        return runMember(ledger, controller, 570, new EvidencePaths(
                EvaluationVideoCanonEvidence.ROOT, EvaluationVideoCanonEvidence.LEDGER));
    }

    /** Same entry with an injectable launch wait and evidence paths so failure-cleanup regressions
     *  do not sleep 570s or share the static Canon root. */
    RunResult runMember(EvaluationFeeLedger ledger, String controller, long launchSeconds,
                        EvidencePaths paths) throws Exception {
        ACTIVE_CONTROLLER.set(controller);
        StopAdmission admission = new StopAdmission(ledger, paths.root());
        EvaluationFeeGate.install(admission);
        int rowsBefore = reservationCount(paths.ledger());
        var scope = new AccessScope("demo", "alice", Set.of(CANON_VIDEO));
        EvaluationVideoCanonLoopback.beginWindow();
        var run = runs.create(PROMPT, scope);
        boolean gateUnloaded = false;
        List<Map<String, Object>> window = List.of();
        if (Boolean.getBoolean("eval.a.cancel.after.launch")) {
            long delay = Long.getLong("eval.a.cancel.after.millis", 250);
            Thread.sleep(delay);
            try { runs.cancel(run.id, scope); } catch (RuntimeException ignored) { }
        }
        try {
            var reached = awaitLaunch(() -> run.snapshot().status(), launchSeconds);
            window = EvaluationVideoCanonLoopback.endWindow();
            String error = run.snapshot().errorCode();
            assertEquals(Status.SUCCEEDED, reached, () -> "run failed: " + error);

            var report = run.execution().observations().stream()
                    .filter(row -> row.tool().equals("create_feedback_report")).findFirst().orElseThrow();
            String html = mvc.perform(get(report.data().get("url").toString())
                            .header("Authorization", "Bearer " + CREDENTIAL))
                    .andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            mvc.perform(get(report.data().get("url").toString())).andExpect(status().isUnauthorized());

            var job = analysis.forRun(run.id);
            List<JsonNode> rows = rowsSince(rowsBefore, paths.ledger());
            assertEquals(window.size(), rows.size(), "this member's ledger rows == its dispatches");

            // The gate is unloaded ONLY after the admission is stopped and the shared stop sequence
            // confirms the terminal state, every dispatch reservation and zero OPEN.
            var bridge = new Bridge(ledger);
            for (var row : rows) bridge.record(row.path("reservation_id").asText());
            int openBefore = ledger.openReservations().size();
            admission.stop();
            var confirmed = confirmStopBeforeGateUnload(null, () -> run.snapshot().status(), bridge, 5);
            gateUnloaded = true;
            Map<String, Object> finalizeProof = new LinkedHashMap<>();
            finalizeProof.put("gate_unloaded_by", "ENTRY_CONFIRMED_STOP");
            finalizeProof.put("confirmed_status", confirmed.status().name());
            finalizeProof.put("open_before_release", openBefore);
            finalizeProof.put("open_after_release", ledger.openReservations().size());
            finalizeProof.put("armed_after", EvaluationFeeGate.armed());
            finalizeProof.put("dispatches_checked", confirmed.dispatches().size());
            assertFalse(EvaluationFeeGate.armed(),
                    "the confirmed stop is the only path that may unload the gate");

            return new RunResult(controller, ProcessHandle.current().pid(), run.id.toString(), reached.name(),
                    job.request().rulesVersion(),
                    run.execution().observations().stream().map(row -> row.tool()).toList(),
                    JSON.valueToTree(Map.of("comments", job.snapshot().counts().comments(),
                            "danmaku", job.snapshot().counts().danmaku(),
                            "total", job.snapshot().counts().total())),
                    html, List.copyOf(window), rows, JSON.valueToTree(job.video()), error, finalizeProof);
        } catch (Throwable failure) {
            try { window = EvaluationVideoCanonLoopback.endWindow(); } catch (RuntimeException ignored) { }
            if (!gateUnloaded) {
                Map<String, Object> evidence;
                try {
                    evidence = failureEvidence(controller, run, rowsBefore, paths);
                } catch (IOException error) {
                    evidence = new LinkedHashMap<>();
                    evidence.put("verdict", "PAIR_ENTRY_FAILED");
                    evidence.put("controller", controller);
                    evidence.put("evidence_error", error.getClass().getSimpleName());
                }
                evidence.put("received_requests", EvaluationVideoCanonLoopback.receivedCount());
                evidence.put("gate_armed_before_cleanup", EvaluationFeeGate.armed());
                String cleanupStatus = null;
                int dispatchesChecked = 0;
                try {
                    admission.stop();
                    var bridge = new Bridge(ledger);
                    for (var row : rowsSince(rowsBefore, paths.ledger())) bridge.record(row.path("reservation_id").asText());
                    var confirmed = confirmStopBeforeGateUnload(
                            () -> runs.cancel(run.id, scope), () -> run.snapshot().status(), bridge, 5);
                    gateUnloaded = true;
                    cleanupStatus = confirmed.status().name();
                    dispatchesChecked = confirmed.dispatches().size();
                    evidence.put("cleanup", "CONFIRMED_STOP");
                    evidence.put("cleanup_status", cleanupStatus);
                    evidence.put("dispatches_checked", dispatchesChecked);
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                    evidence.put("cleanup", "REFUSED");
                    evidence.put("cleanup_error", cleanup.getClass().getSimpleName() + ": " + cleanup.getMessage());
                }
                evidence.put("open_after_cleanup", ledger.openReservations().size());
                evidence.put("gate_armed_after_cleanup", EvaluationFeeGate.armed());
                Path failureFile = writeFailure(controller, evidence, failure, paths);
                if (controller.equals(MODEL_A) && gateUnloaded && failureFile != null) {
                    try {
                        writeConfirmedStop(run, ledger, failureFile, evidence, cleanupStatus,
                                dispatchesChecked, rowsBefore, paths);
                    } catch (Exception evidenceError) {
                        failure.addSuppressed(evidenceError);
                    }
                }
            }
            rethrow(failure);
            return null;
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception error) throw error;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException(failure);
    }

    record EvidencePaths(Path root, Path ledger) {}

    private static Map<String, Object> failureEvidence(String controller, com.tianmu.agent.runtime.RunState run,
                                                       int rowsBefore, EvidencePaths paths) throws IOException {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("verdict", "PAIR_ENTRY_FAILED");
        evidence.put("pid", ProcessHandle.current().pid());
        evidence.put("controller", controller);
        evidence.put("run_id", run.id.toString());
        evidence.put("status", run.snapshot().status().name());
        evidence.put("error_code", run.snapshot().errorCode());
        evidence.put("rows_before", rowsBefore);
        evidence.put("rows_after", reservationCount(paths.ledger()));
        return evidence;
    }

    private static Path writeFailure(String controller, Map<String, Object> evidence, Throwable failure,
                                     EvidencePaths paths) {
        try {
            String prefix = controller.equals(MODEL_A) ? "a" : "b";
            Path target = paths.root().resolve("failure-" + prefix + ".json").normalize();
            if (!target.startsWith(paths.root()))
                throw new IllegalStateException("failure evidence outside the root: " + target);
            evidence.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
                    StandardCharsets.UTF_8);
            return target;
        } catch (Exception ignored) {
            // A failed diagnostic write must not replace the dispatch failure; the gate remains armed.
            return null;
        }
    }

    /** A confirmed-stop proof is written only after the shared stop sequence succeeded: terminal,
     *  every dispatch checked, zero OPEN and the gate actually unloaded. */
    private static void writeConfirmedStop(com.tianmu.agent.runtime.RunState run, EvaluationFeeLedger ledger,
                                           Path failureFile, Map<String, Object> evidence, String confirmedStatus,
                                           int dispatchesChecked, int rowsBefore, EvidencePaths paths)
            throws IOException {
        JsonNode doc = ledgerDocument(paths.ledger());
        int rows = doc.path("reservations").size();
        String rowsDigest = EvaluationFeeLedger.rowsDigest(jsonRows(doc));
        String failureName = failureFile.getFileName().toString();
        String failureSha = EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(failureFile));
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("kind", "offline-stop-proof-v1");
        proof.put("state", "CONFIRMED_STOP");
        proof.put("controller", MODEL_A);
        proof.put("pair_id", System.getProperty("eval.approval.group", "canon-730013-pair"));
        proof.put("baseline", baselineValue());
        proof.put("contract_sha256", currentContractSha256());
        proof.put("material_config_sha256", materialConfigSha256());
        Path manifest = paths.root().resolve("frames").resolve("frames-manifest.json").normalize();
        if (!Files.isRegularFile(manifest)) manifest = EvaluationVideoCanonEvidence.MANIFEST;
        proof.put("frames_manifest_sha256", EvaluationVideoCanonContract.sha256(manifest));
        proof.put("ledger_source_sha256", ledgerSourceSha256());
        proof.put("run_id", run.id.toString());
        proof.put("pid", ProcessHandle.current().pid());
        proof.put("terminal_status", run.snapshot().status().name());
        proof.put("confirmed_status", confirmedStatus);
        proof.put("error_code", run.snapshot().errorCode());
        proof.put("ledger_rows", rows);
        proof.put("ledger_rows_sha256", rowsDigest);
        proof.put("member_ledger_rows", rows - rowsBefore);
        proof.put("open_reservations", ledger.openReservations().size());
        proof.put("dispatches_checked", dispatchesChecked);
        proof.put("received_requests", evidence.get("received_requests"));
        proof.put("gate_armed_after", EvaluationFeeGate.armed());
        proof.put("failure_file", failureName);
        proof.put("failure_sha256", failureSha);
        proof.put("confirmed_at", Instant.now().toString());
        Path proofFile = paths.root().resolve("a-stop-proof.json").normalize();
        if (!proofFile.startsWith(paths.root()))
            throw new IllegalStateException("stop proof outside the root: " + proofFile);
        Files.writeString(proofFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
        String proofSha = EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(proofFile));
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("run_id", run.id.toString());
        completed.put("controller", MODEL_A);
        completed.put("status", "STOPPED_CONFIRMED");
        completed.put("pid", ProcessHandle.current().pid());
        completed.put("stop_file", "a-stop-proof.json");
        completed.put("proof_sha256", proofSha);
        completed.put("pair_id", proof.get("pair_id"));
        completed.put("baseline", proof.get("baseline"));
        completed.put("contract_sha256", proof.get("contract_sha256"));
        completed.put("material_config_sha256", proof.get("material_config_sha256"));
        completed.put("frames_manifest_sha256", proof.get("frames_manifest_sha256"));
        completed.put("ledger_source_sha256", proof.get("ledger_source_sha256"));
        completed.put("ledger_rows", rows);
        completed.put("ledger_rows_sha256", rowsDigest);
        completed.put("member_ledger_rows", rows - rowsBefore);
        completed.put("report_file", "");
        completed.put("report_bytes", 0);
        completed.put("report_sha256", "");
        completed.put("failure_file", failureName);
        completed.put("failure_sha256", failureSha);
        completed.put("completed_at", Instant.now().toString());
        Path record = paths.root().resolve("completed").resolve(recordName(MODEL_A));
        Files.createDirectories(record.getParent());
        Files.writeString(record, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(completed),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
    }

    private static List<JsonNode> rowsSince(int rowsBefore, Path ledger) throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        JsonNode doc = ledgerDocument(ledger);
        for (int i = rowsBefore; i < doc.path("reservations").size(); i++)
            rows.add(doc.path("reservations").path(i));
        return rows;
    }

    record Continuation(List<Map<String, Object>> timeline, RunResult last) {}

    /** Continuation on the same batch: the ledger must be EXACTLY A's close, and B may declare
     *  one attempt only. The attempt file is created with CREATE_NEW before the first B dispatch. */
    Continuation continueBatch(EvaluationFeeLedger ledger) throws Exception {
        requireApprovalIfConfigured();
        EvaluationVideoCanonEvidence.verifyFrames();
        Path aRecord = EvaluationVideoCanonEvidence.COMPLETED.resolve(recordName(MODEL_A));
        if (!Files.isRegularFile(aRecord))
            throw new IllegalStateException("A_COMPLETION_REQUIRED: B cannot start before A has a "
                    + "verified completion record in " + EvaluationVideoCanonEvidence.COMPLETED);
        verifyCompletion(MODEL_A, aRecord, ledger);
        Path bRecord = EvaluationVideoCanonEvidence.COMPLETED.resolve(recordName(MODEL_B));
        if (Files.isRegularFile(bRecord))
            throw new IllegalStateException("DUPLICATE_MEMBER_RUN: " + bRecord
                    + " already exists; B cannot run twice on one batch");
        if (Files.exists(EvaluationVideoCanonEvidence.B_ATTEMPT))
            refuseExistingBAttempt(aRecord);
        verifyBStart(MODEL_A, aRecord, ledger);
        declareBAttempt(aRecord, ledger);
        List<Map<String, Object>> timeline = List.of(
                Map.of("member", MODEL_A, "action", "SKIPPED_COMPLETED"),
                Map.of("member", MODEL_B, "action", "RAN"));
        RunResult last = runMember(ledger, MODEL_B);
        archive(MODEL_B, last);
        return new Continuation(timeline, last);
    }

    /** Atomically records B's one attempt, bound to the approval, A's close and the pair identity. */
    static Path declareBAttempt(Path aRecord, EvaluationFeeLedger ledger) throws IOException {
        return declareBAttempt(aRecord, ledger, EvaluationVideoCanonEvidence.B_ATTEMPT);
    }

    static Path declareBAttempt(Path aRecord, EvaluationFeeLedger ledger, Path file) throws IOException {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("kind", "offline-b-attempt-v1");
        attempt.put("state", "DECLARED");
        attempt.put("pair_id", System.getProperty("eval.approval.group", "canon-730013-pair"));
        attempt.put("baseline", baselineValue());
        attempt.put("contract_sha256", currentContractSha256());
        attempt.put("approval_sha256", approvalFileSha256());
        attempt.put("a_completion_sha256", EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(aRecord)));
        JsonNode a = JSON.readTree(Files.readString(aRecord, StandardCharsets.UTF_8));
        attempt.put("a_run_id", a.path("run_id").asText(""));
        attempt.put("a_pid", a.path("pid").asLong());
        attempt.put("a_status", a.path("status").asText(""));
        attempt.put("a_ledger_rows", a.path("ledger_rows").asInt(-1));
        attempt.put("a_ledger_rows_sha256", a.path("ledger_rows_sha256").asText(""));
        attempt.put("ledger_source_sha256", ledgerSourceSha256());
        attempt.put("material_config_sha256", materialConfigSha256());
        Path manifest = file.getParent().resolve("frames").resolve("frames-manifest.json").normalize();
        if (!Files.isRegularFile(manifest)) manifest = EvaluationVideoCanonEvidence.MANIFEST;
        attempt.put("frames_manifest_sha256", EvaluationVideoCanonContract.sha256(manifest));
        attempt.put("pid", ProcessHandle.current().pid());
        attempt.put("declared_at", Instant.now().toString());
        try {
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(attempt),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            throw new IllegalStateException("B_ATTEMPT_ALREADY_DECLARED: " + file);
        } catch (IOException error) {
            throw new IllegalStateException("B_ATTEMPT_DECLARATION_FAILED: " + file, error);
        }
        return file;
    }

    private static void refuseExistingBAttempt(Path aRecord) throws IOException {
        Path file = EvaluationVideoCanonEvidence.B_ATTEMPT;
        JsonNode attempt;
        try {
            attempt = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception error) {
            recordUnknownAttempt(file, error);
            throw new IllegalStateException("B_ATTEMPT_STATE_UNKNOWN: " + file);
        }
        String state = attempt.path("state").asText("");
        if (!"DECLARED".equals(state) && !"COMPLETED".equals(state)) {
            recordUnknownAttempt(file, new IllegalStateException("state=" + state));
            throw new IllegalStateException("B_ATTEMPT_STATE_UNKNOWN: " + file);
        }
        if (!System.getProperty("eval.approval.group", "canon-730013-pair")
                        .equals(attempt.path("pair_id").asText(""))
                || !baselineValue().equals(attempt.path("baseline").asText(""))
                || !currentContractSha256().equals(attempt.path("contract_sha256").asText(""))
                || !approvalFileSha256().equals(attempt.path("approval_sha256").asText(""))
                || !EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(aRecord))
                        .equals(attempt.path("a_completion_sha256").asText(""))
                || !ledgerSourceSha256().equals(attempt.path("ledger_source_sha256").asText(""))) {
            recordUnknownAttempt(file, new IllegalStateException("identity mismatch"));
            throw new IllegalStateException("B_ATTEMPT_STATE_UNKNOWN: " + file);
        }
        throw new IllegalStateException("B_ATTEMPT_ALREADY_DECLARED: " + file + " state=" + state);
    }

    private static void recordUnknownAttempt(Path file, Exception error) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "offline-b-attempt-unknown-v1");
            row.put("at", Instant.now().toString());
            row.put("attempt_file", file.getFileName().toString());
            row.put("attempt_sha256", Files.isRegularFile(file)
                    ? EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(file)) : "");
            row.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            Path target = EvaluationVideoCanonEvidence.requireOwned(
                    EvaluationVideoCanonEvidence.ROOT.resolve("b-attempt-unknown.json"));
            if (!Files.exists(target))
                Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(row),
                        StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // The attempt file itself remains the authoritative refusal.
        }
    }

    static String approvalFileSha256() throws IOException {
        String raw = System.getProperty("eval.approval.file", "").strip();
        if (raw.isEmpty()) return "";
        return EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(Path.of(raw)));
    }

    record CompletionContext(Path root, Path ledgerFile, Path manifest, Path materialConfig, String baseline,
                             String group, String ledgerSourceSha256, String contractSha256) {}

    static CompletionContext completionContext() throws IOException {
        return new CompletionContext(EvaluationVideoCanonEvidence.ROOT,
                EvaluationVideoCanonEvidence.LEDGER,
                EvaluationVideoCanonEvidence.MANIFEST,
                Path.of(System.getProperty("eval.material.config",
                        "eval/report-trial-admission.example.json")).toAbsolutePath().normalize(),
                baselineValue(),
                System.getProperty("eval.approval.group", "canon-730013-pair"),
                required("eval.historical.ledger.sha256"),
                currentContractSha256());
    }

    /** B's gate: the completion record, the ledger prefix it claims, the report bytes and the
     *  approval/contract identity must all match BEFORE the first B dispatch. */
    static void verifyCompletion(String controller, Path recordFile, EvaluationFeeLedger ledger)
            throws IOException {
        verifyCompletion(controller, recordFile, ledger, completionContext());
    }

    static void verifyCompletion(String controller, Path recordFile, EvaluationFeeLedger ledger,
                                 CompletionContext context) throws IOException {
        verifyCompletion(controller, recordFile, ledger, context, false);
    }

    static void verifyCompletion(String controller, Path recordFile, EvaluationFeeLedger ledger,
                                 CompletionContext context, boolean allowSamePid) throws IOException {
        JsonNode record = JSON.readTree(Files.readString(recordFile, StandardCharsets.UTF_8));
        String status = record.path("status").asText("");
        if (!Set.of("SUCCEEDED", "STOPPED_CONFIRMED").contains(status))
            throw new IllegalStateException("COMPLETION_NOT_TERMINAL: " + controller + " record says " + status);
        if (!allowSamePid && record.path("pid").asLong() == ProcessHandle.current().pid())
            throw new IllegalStateException("COMPLETION_SAME_PROCESS: " + controller + " was not produced by A");
        if (!controller.equals(record.path("controller").asText("")))
            throw new IllegalStateException("COMPLETION_CONTROLLER_MISMATCH: " + recordFile);
        requireIdentity(record, "pair_id", context.group());
        requireIdentity(record, "baseline", context.baseline());
        requireIdentity(record, "ledger_source_sha256", context.ledgerSourceSha256());
        requireIdentity(record, "material_config_sha256",
                EvaluationVideoCanonContract.sha256(context.materialConfig()));
        requireIdentity(record, "frames_manifest_sha256",
                EvaluationVideoCanonContract.sha256(context.manifest()));
        requireIdentity(record, "contract_sha256", context.contractSha256());

        JsonNode doc = ledgerDocument(context.ledgerFile());
        int rows = doc.path("reservations").size();
        int expectedRows = record.path("ledger_rows").asInt(-1);
        if (expectedRows < 0 || expectedRows > rows)
            throw new IllegalStateException("COMPLETION_LEDGER_ROWS_MISMATCH: record says "
                    + expectedRows + ", ledger has " + rows);
        if (!EvaluationFeeLedger.rowsDigest(jsonRows(doc).subList(0, expectedRows))
                .equals(record.path("ledger_rows_sha256").asText("")))
            throw new IllegalStateException("COMPLETION_LEDGER_PREFIX_MISMATCH: the ledger prefix was rewritten");
        if (!ledger.openReservations().isEmpty())
            throw new IllegalStateException("COMPLETION_HAS_OPEN_RESERVATIONS: " + ledger.openReservations().size());

        if ("SUCCEEDED".equals(status)) {
            Path report = context.root().resolve(record.path("report_file").asText("")).normalize();
            if (!report.startsWith(context.root()))
                throw new IllegalStateException("COMPLETION_REPORT_OUTSIDE_ROOT: " + report);
            if (!Files.isRegularFile(report))
                throw new IllegalStateException("COMPLETION_REPORT_MISSING: " + report);
            byte[] bytes = Files.readAllBytes(report);
            if (bytes.length != record.path("report_bytes").asInt(-1)
                    || !EvaluationVideoCanonEvidence.sha256(bytes).equals(record.path("report_sha256").asText("")))
                throw new IllegalStateException("COMPLETION_REPORT_MISMATCH: " + report);
        } else {
            verifyStopProof(record, context, doc);
        }
    }

    private static void verifyStopProof(JsonNode record, CompletionContext context, JsonNode doc) throws IOException {
        Path proofFile = context.root().resolve(record.path("stop_file").asText("")).normalize();
        if (!proofFile.startsWith(context.root()) || !Files.isRegularFile(proofFile))
            throw new IllegalStateException("COMPLETION_STOP_EVIDENCE_MISSING: " + proofFile);
        byte[] proofBytes = Files.readAllBytes(proofFile);
        String proofSha = record.path("proof_sha256").asText("");
        if (proofSha.isEmpty() || !EvaluationVideoCanonEvidence.sha256(proofBytes).equals(proofSha))
            throw new IllegalStateException("COMPLETION_STOP_PROOF_MISMATCH: " + proofFile);
        JsonNode proof = JSON.readTree(proofBytes);
        if (!"offline-stop-proof-v1".equals(proof.path("kind").asText())
                || !"CONFIRMED_STOP".equals(proof.path("state").asText()))
            throw new IllegalStateException("COMPLETION_STOP_NOT_CONFIRMED: " + proofFile);
        requireIdentity(proof, "controller", MODEL_A);
        requireIdentity(proof, "pair_id", context.group());
        requireIdentity(proof, "baseline", context.baseline());
        requireIdentity(proof, "contract_sha256", context.contractSha256());
        requireIdentity(proof, "material_config_sha256",
                EvaluationVideoCanonContract.sha256(context.materialConfig()));
        requireIdentity(proof, "frames_manifest_sha256",
                EvaluationVideoCanonContract.sha256(context.manifest()));
        requireIdentity(proof, "ledger_source_sha256", context.ledgerSourceSha256());
        requireIdentity(proof, "run_id", record.path("run_id").asText(""));
        if (proof.path("pid").asLong(-1) != record.path("pid").asLong(-2))
            throw new IllegalStateException("COMPLETION_STOP_PID_MISMATCH: " + proofFile);
        String terminal = proof.path("terminal_status").asText("");
        if (!Set.of("FAILED", "CANCELLED", "NEEDS_INPUT").contains(terminal))
            throw new IllegalStateException("COMPLETION_STOP_TERMINAL_INVALID: " + terminal);
        if (!terminal.equals(proof.path("confirmed_status").asText("")))
            throw new IllegalStateException("COMPLETION_STOP_NOT_CONFIRMED: " + proofFile);
        if (proof.path("open_reservations").asInt(-1) != 0
                || proof.path("gate_armed_after").asBoolean(true))
            throw new IllegalStateException("COMPLETION_STOP_STILL_ARMED: " + proofFile);
        if (proof.path("ledger_rows").asInt(-1) != record.path("ledger_rows").asInt(-2)
                || !proof.path("ledger_rows_sha256").asText("")
                        .equals(record.path("ledger_rows_sha256").asText("")))
            throw new IllegalStateException("COMPLETION_STOP_LEDGER_MISMATCH: " + proofFile);
        if (!EvaluationFeeLedger.rowsDigest(jsonRows(doc).subList(0, record.path("ledger_rows").asInt()))
                .equals(proof.path("ledger_rows_sha256").asText("")))
            throw new IllegalStateException("COMPLETION_STOP_LEDGER_PREFIX_MISMATCH: " + proofFile);
        if (proof.path("dispatches_checked").asInt(-1) < 0)
            throw new IllegalStateException("COMPLETION_STOP_DISPATCHES_MISSING: " + proofFile);
        Path failure = context.root().resolve(proof.path("failure_file").asText("")).normalize();
        if (!failure.startsWith(context.root()) || !Files.isRegularFile(failure))
            throw new IllegalStateException("COMPLETION_STOP_FAILURE_MISSING: " + failure);
        if (!EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(failure))
                .equals(proof.path("failure_sha256").asText("")))
            throw new IllegalStateException("COMPLETION_STOP_FAILURE_MISMATCH: " + failure);
        JsonNode failureDoc = JSON.readTree(Files.readAllBytes(failure));
        if (!"CONFIRMED_STOP".equals(failureDoc.path("cleanup").asText())
                || failureDoc.path("open_after_cleanup").asInt(-1) != 0
                || failureDoc.path("gate_armed_after_cleanup").asBoolean(true)
                || !terminal.equals(failureDoc.path("cleanup_status").asText(""))
                || !record.path("run_id").asText("").equals(failureDoc.path("run_id").asText(""))
                || record.path("pid").asLong(-2) != failureDoc.path("pid").asLong(-3)
                || failureDoc.path("rows_after").asInt(-1) != record.path("ledger_rows").asInt(-2))
            throw new IllegalStateException("COMPLETION_STOP_FAILURE_NOT_CONFIRMED: " + failure);
    }

    /** B may start only when the current ledger is EXACTLY A's final close, not merely a prefix. */
    static void verifyBStart(String controller, Path recordFile, EvaluationFeeLedger ledger) throws IOException {
        verifyBStart(controller, recordFile, ledger, completionContext());
    }

    static void verifyBStart(String controller, Path recordFile, EvaluationFeeLedger ledger,
                             CompletionContext context) throws IOException {
        verifyBStart(controller, recordFile, ledger, context, false);
    }

    static void verifyBStart(String controller, Path recordFile, EvaluationFeeLedger ledger,
                             CompletionContext context, boolean allowSamePid) throws IOException {
        verifyCompletion(controller, recordFile, ledger, context, allowSamePid);
        JsonNode record = JSON.readTree(Files.readString(recordFile, StandardCharsets.UTF_8));
        JsonNode doc = ledgerDocument(context.ledgerFile());
        int rows = doc.path("reservations").size();
        if (record.path("ledger_rows").asInt(-1) != rows
                || !EvaluationFeeLedger.rowsDigest(jsonRows(doc))
                        .equals(record.path("ledger_rows_sha256").asText("")))
            throw new IllegalStateException("B_LEDGER_NOT_AT_A_CLOSE: record says "
                    + record.path("ledger_rows").asInt(-1) + ", ledger has " + rows);
        if (!ledger.openReservations().isEmpty())
            throw new IllegalStateException("B_LEDGER_HAS_OPEN_RESERVATIONS: "
                    + ledger.openReservations().size());
    }

    private static void requireIdentity(JsonNode record, String field, String expected) {
        String actual = record.path(field).asText("");
        if (expected == null || !expected.equals(actual))
            throw new IllegalStateException("COMPLETION_IDENTITY_MISMATCH: " + field
                    + " record=" + actual + " expected=" + expected);
    }

    private static String required(String property) {
        String value = System.getProperty(property, "").strip();
        if (value.isEmpty()) throw new IllegalStateException(property + " is required for a completion check");
        return value;
    }

    static String currentContractSha256() throws IOException {
        String file = System.getProperty("eval.approval.contract.file", "").strip();
        if (file.isEmpty()) return "";
        return EvaluationVideoCanonContract.sha256(Path.of(file));
    }

    private static List<JsonNode> jsonRows(JsonNode doc) {
        List<JsonNode> rows = new ArrayList<>();
        doc.path("reservations").forEach(rows::add);
        return rows;
    }

    static String recordName(String controller) {
        return (controller.equals(MODEL_A) ? "controller-a" : "controller-b") + "-repeat-1.json";
    }

    // ---------------------------------------------------------------- evidence

    void archive(String controller, RunResult result) throws IOException {
        String prefix = controller.equals(MODEL_A) ? "a" : "b";
        Path root = EvaluationVideoCanonEvidence.requireOwned(EvaluationVideoCanonEvidence.ROOT);
        Files.writeString(root.resolve(prefix + "-report.html"), result.html, StandardCharsets.UTF_8);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("verdict", controller.equals(MODEL_A)
                ? "A_ONLY_VIDEO_GROUNDED_REPORT_DELIVERED" : "B_CONTINUATION_VIDEO_GROUNDED_REPORT_DELIVERED");
        doc.put("pid", result.pid());
        doc.put("controller", controller);
        doc.put("run_id", result.runId());
        doc.put("status", result.status());
        doc.put("rules_version", result.rulesVersion());
        doc.put("tools", result.tools());
        doc.put("counts", result.counts());
        doc.put("report_sha256", EvaluationVideoCanonEvidence.sha256(result.html().getBytes(StandardCharsets.UTF_8)));
        doc.put("report_bytes", result.html().getBytes(StandardCharsets.UTF_8).length);
        doc.put("report_file", prefix + "-report.html");
        doc.put("video", videoFacts(result.video()));
        doc.put("requests", result.requests().size());
        doc.put("member_ledger_rows", result.ledgerRows().size());
        doc.put("ledger_rows", reservationCount(EvaluationVideoCanonEvidence.LEDGER));
        doc.put("ledger_rows_sha256", EvaluationFeeLedger.rowsDigest(jsonRows(ledgerDocument())));
        doc.put("ledger_source_sha256", ledgerSourceSha256());
        doc.put("pair_id", System.getProperty("eval.approval.group", "canon-730013-pair"));
        doc.put("baseline", baselineValue());
        doc.put("contract_sha256", currentContractSha256());
        doc.put("material_config_sha256", materialConfigSha256());
        doc.put("frames_manifest_sha256", EvaluationVideoCanonContract.sha256(
                EvaluationVideoCanonEvidence.MANIFEST));
        doc.put("role_binding", roleBinding(result.requests()));
        doc.put("finalize", result.finalizeProof());
        doc.put("reconciliation", reconciliation(result));
        Files.writeString(root.resolve(prefix + "-report.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
        Files.writeString(root.resolve(prefix + "-dispatches.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result.requests()),
                StandardCharsets.UTF_8);
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("run_id", result.runId());
        completed.put("controller", controller);
        completed.put("status", result.status());
        completed.put("pid", result.pid());
        completed.put("report_sha256", doc.get("report_sha256"));
        completed.put("report_bytes", doc.get("report_bytes"));
        completed.put("report_file", doc.get("report_file"));
        completed.put("completed_at", Instant.now().toString());
        completed.put("member_ledger_rows", result.ledgerRows().size());
        completed.put("ledger_rows", doc.get("ledger_rows"));
        completed.put("ledger_rows_sha256", doc.get("ledger_rows_sha256"));
        completed.put("ledger_source_sha256", doc.get("ledger_source_sha256"));
        completed.put("pair_id", doc.get("pair_id"));
        completed.put("baseline", doc.get("baseline"));
        completed.put("contract_sha256", doc.get("contract_sha256"));
        completed.put("material_config_sha256", doc.get("material_config_sha256"));
        completed.put("frames_manifest_sha256", doc.get("frames_manifest_sha256"));
        Files.writeString(EvaluationVideoCanonEvidence.COMPLETED.resolve(recordName(controller)),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(completed), StandardCharsets.UTF_8);
    }

    static String baselineValue() {
        return System.getProperty("eval.approval.baseline", "synthetic-baseline");
    }

    static String materialConfigSha256() throws IOException {
        return EvaluationVideoCanonContract.sha256(Path.of(System.getProperty("eval.material.config",
                "eval/report-trial-admission.example.json")).toAbsolutePath().normalize());
    }

    static String ledgerSourceSha256() {
        String value = System.getProperty("eval.historical.ledger.sha256", "").strip();
        if (value.isEmpty())
            throw new IllegalStateException("LEDGER_SOURCE_SHA_REQUIRED: pass -Deval.historical.ledger.sha256=...");
        return value;
    }

    private static Map<String, Object> videoFacts(JsonNode video) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (video == null || video.isNull()) {
            facts.put("present", false);
            return facts;
        }
        facts.put("present", true);
        facts.put("video_id", video.path("videoId").asLong());
        facts.put("source_version", video.path("sourceVersion").asText());
        facts.put("start_ms", video.path("startMs").asLong());
        facts.put("end_ms", video.path("endMs").asLong());
        facts.put("coverage", video.path("coverage").asText());
        facts.put("sampled_frames", video.path("sampledAtMs").size());
        facts.put("model", video.path("model").asText());
        facts.put("summary_sha256", EvaluationVideoCanonEvidence.sha256(
                video.path("summary").asText("").getBytes(StandardCharsets.UTF_8)));
        facts.put("summary_marker", video.path("summary").asText("").contains(EvaluationVideoCanonLoopback.MARKER));
        facts.put("moments", video.path("moments").size());
        return facts;
    }

    /** Per-role binding as the DOUBLE actually received it: role is decided by request shape
     *  (path + tool catalogue), so two roles may legitimately share one request name (batch 5's
     *  analysis role is the same V4.1 Flash name as controller A). */
    static Map<String, Object> roleBinding(List<Map<String, Object>> requests) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("judged_by", "REQUEST_SHAPE: path + tools, never the model name");
        out.put("analysis_model_equals_a_controller", ANALYSIS_MODEL.equals(MODEL_A));
        for (String role : List.of("controller", "analysis", "vision")) {
            var rows = requests.stream().filter(r -> role.equals(r.get("role"))).toList();
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("count", rows.size());
            block.put("models", rows.stream().map(r -> String.valueOf(r.get("model"))).distinct().toList());
            block.put("has_tools", rows.stream().map(r -> Boolean.TRUE.equals(r.get("has_tools"))).distinct().toList());
            block.put("paths", rows.stream().map(r -> String.valueOf(r.get("path"))).distinct().toList());
            out.put(role, block);
        }
        return out;
    }

    private List<Map<String, Object>> reconciliation(RunResult result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < result.ledgerRows().size(); i++) {
            JsonNode ledger = result.ledgerRows().get(i);
            Map<String, Object> request = result.requests().get(i);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("index", i);
            line.put("role", request.get("role"));
            line.put("model", request.get("model"));
            line.put("path", request.get("path"));
            line.put("tool", request.get("tool"));
            line.put("analysis_kind", request.get("analysis_kind"));
            line.put("has_video", request.get("has_video"));
            line.put("images", request.get("images"));
            line.put("body_bytes", request.get("body_bytes"));
            line.put("body_sha256", request.get("body_sha256"));
            line.put("reservation_id", ledger.path("reservation_id").asText());
            line.put("reserved_fen", ledger.path("reserved_fen").asLong());
            line.put("actual_fen", ledger.path("actual_fen").asLong());
            line.put("status", ledger.path("status").asText());
            line.put("ledger_body_bytes", ledger.path("request_body_bytes").asLong());
            line.put("ledger_body_sha256", ledger.path("request_body_sha256").asText());
            rows.add(line);
        }
        return rows;
    }

    // ---------------------------------------------------------------- stop sequence

    interface StopBridge {
        EvaluationFeeLedger ledger();

        List<Map<String, Object>> finish();
    }

    /**
     * The send-boundary stop flag for the three-role pairing entry. It is closed BEFORE the cancel
     * is issued, so an unexpired late request cannot slip through when the cancellation itself does
     * not take effect. A refused dispatch neither reserves nor reaches the wire.
     */
    static final class StopAdmission implements EvaluationFeeGate.Admission {
        static final String STOPPED = "PAIR_ENTRY_STOPPED";
        private final EvaluationFeeLedger ledger;
        private final Path refusals;
        private volatile boolean stopped;

        StopAdmission(EvaluationFeeLedger ledger) {
            this(ledger, EvaluationVideoCanonEvidence.ROOT);
        }

        StopAdmission(EvaluationFeeLedger ledger, Path root) {
            this.ledger = ledger;
            Path refusal = root.resolve("stop-refusals.jsonl").normalize();
            if (!refusal.startsWith(root))
                throw new IllegalStateException("stop refusal evidence outside the root: " + refusal);
            this.refusals = refusal;
        }

        void stop() {
            stopped = true;
        }

        boolean stopped() {
            return stopped;
        }

        private AgentException refuse(String role, String model, long bytes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", Instant.now().toString());
            row.put("pid", ProcessHandle.current().pid());
            row.put("code", STOPPED);
            row.put("role", role);
            row.put("model", model);
            row.put("payload_bytes", bytes);
            try {
                Files.writeString(refusals, JSON.writeValueAsString(row) + System.lineSeparator(),
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException error) {
                throw new AgentException("STOP_REFUSAL_WRITE_FAILED",
                        "the stopped dispatch could not be recorded; nothing was sent");
            }
            return new AgentException(STOPPED, "the pairing entry is stopped; the dispatch never reaches the ledger or the wire");
        }

        @Override
        public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes, int maxTokens) {
            return admitChat(model, host, payloadBytes, maxTokens, null);
        }

        @Override
        public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes,
                                                       int maxTokens, String clientRole) {
            if (stopped) throw refuse(clientRole == null ? "chat" : clientRole, model, payloadBytes);
            return ledger.admitChat(model, host, payloadBytes, maxTokens, clientRole);
        }

        @Override
        public EvaluationFeeGate.Reservation admitVideo(String provider, String path) {
            if (stopped) throw refuse("vision", "n/a", 0);
            return ledger.admitVideo(provider, path);
        }

        @Override
        public EvaluationFeeGate.Reservation admitVision(String provider, String transport, String path,
                                                         String model, int imageCount, long payloadBytes,
                                                         int maxOutputTokens) {
            if (stopped) throw refuse("vision", model, payloadBytes);
            return ledger.admitVision(provider, transport, path, model, imageCount, payloadBytes, maxOutputTokens);
        }
    }

    static final class Bridge implements StopBridge {
        private final EvaluationFeeLedger ledger;
        private final List<Map<String, Object>> rows = new ArrayList<>();

        Bridge(EvaluationFeeLedger ledger) {
            this.ledger = ledger;
        }

        void record(String reservationId) {
            rows.add(Map.of("reservation_id", reservationId));
        }

        @Override
        public EvaluationFeeLedger ledger() {
            return ledger;
        }

        @Override
        public List<Map<String, Object>> finish() {
            return List.copyOf(rows);
        }
    }

    record ConfirmedStop(Status status, List<Map<String, Object>> dispatches) {}

    /** Line-faithful extraction of the old launcher's release path (188-220): cancel, bounded
     *  confirm wait, terminal check, per-dispatch reservation check, OPEN refusal, interrupt check,
     *  and ONLY THEN {@code EvaluationFeeGate.clear()}. */
    static ConfirmedStop confirmStopBeforeGateUnload(Runnable issueCancel, Supplier<Status> statusView,
                                                     StopBridge bridge, long confirmSeconds) throws InterruptedException {
        var ledger = bridge.ledger();
        if (issueCancel != null) {
            issueCancel.run();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(confirmSeconds);
            while (System.nanoTime() < deadline
                    && (!com.tianmu.agent.core.AgentTypes.terminal(statusView.get())
                    || !ledger.openReservations().isEmpty())) {
                Thread.sleep(200);
            }
        }
        Status status = statusView.get();
        if (!com.tianmu.agent.core.AgentTypes.terminal(status))
            throw new IllegalStateException("launch deadline: task did not reach terminal "
                    + "— the fee gate stays armed; manual review required");
        var dispatches = bridge.finish();
        for (var dispatch : dispatches) ledger.reservation(dispatch.get("reservation_id").toString());
        if (!ledger.openReservations().isEmpty())
            throw new IllegalStateException("launch stop not confirmed: an OPEN reservation remains "
                    + "— the fee gate stays armed; manual review required");
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("interrupted before the fee gate could be released");
        EvaluationFeeGate.clear();
        return new ConfirmedStop(status, dispatches);
    }

    /** The launcher's launch-wait loop (1578-1590): bounded polling; an interrupt propagates and
     *  the caller must keep the gate armed. */
    static Status awaitLaunch(Supplier<Status> statusView, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!com.tianmu.agent.core.AgentTypes.terminal(statusView.get())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return statusView.get();
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, EvaluationFeeLedger.Price> prices() {
        String host = "127.0.0.1";
        // The analysis role may carry the SAME request name as controller A (batch 5: both are the
        // V4.1 Flash model). One exit key, one price: an ordered map keeps that collision explicit
        // instead of letting Map.of reject the duplicate key.
        Map<String, EvaluationFeeLedger.Price> prices = new LinkedHashMap<>();
        prices.put("chat:" + host + ":" + MODEL_A, new EvaluationFeeLedger.Price(200, 800));
        prices.put("chat:" + host + ":" + MODEL_B, new EvaluationFeeLedger.Price(1200, 2400));
        prices.put("chat:" + host + ":" + ANALYSIS_MODEL, new EvaluationFeeLedger.Price(200, 800));
        prices.put("vision:minimax:" + VISION_MODEL + ":openai_chat_vision",
                new EvaluationFeeLedger.Price(420, 1680));
        return Map.copyOf(prices);
    }

    static int reservationCount(Path ledger) throws IOException {
        if (!Files.isRegularFile(ledger)) return 0;
        return JSON.readTree(Files.readString(ledger, StandardCharsets.UTF_8)).path("reservations").size();
    }

    static long sumConservative(JsonNode doc) {
        long carried = doc.path("carried_in").path("settled_fen").asLong()
                + doc.path("carried_in").path("retained_fen").asLong();
        long used = 0;
        for (JsonNode row : doc.path("reservations")) {
            if ("SETTLED".equals(row.path("status").asText())) used += row.path("actual_fen").asLong(0);
            else used += row.path("reserved_fen").asLong(0);
        }
        return carried + used;
    }

    static JsonNode ledgerDocument() throws IOException {
        return ledgerDocument(EvaluationVideoCanonEvidence.LEDGER);
    }

    static JsonNode ledgerDocument(Path ledger) throws IOException {
        return JSON.readTree(Files.readString(ledger, StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        return EvaluationVideoCanonEvidence.sha256(bytes);
    }

    static void requireClosedPaidExits(org.springframework.core.env.Environment environment) {
        assertTrue(!environment.getProperty("agent.video.asr.enabled", Boolean.class, false),
                "independent ASR is a paid exit without a fee gate and must stay off");
        assertTrue(!environment.getProperty("agent.feedback.embedding.enabled", Boolean.class, false),
                "embedding is a paid exit without a fee gate and must stay off");
        assertTrue(!environment.getProperty("agent.feedback.rerank.enabled", Boolean.class, false),
                "rerank is a paid exit without a fee gate and must stay off");
        assertTrue(!environment.getProperty("agent.feedback.milvus.enabled", Boolean.class, false),
                "the vector store is an external exit and must stay off in the frozen-source pair");
    }
}
