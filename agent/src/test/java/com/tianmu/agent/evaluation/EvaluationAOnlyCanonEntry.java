package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AgentTypes.Status;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.RunService;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Batch-6 A-only single-member entry. There is no B branch: the entry can only launch controller
 * A, and the admission refuses every other chat model before the ledger is touched. That is a
 * structural property, not a flag — a caller cannot "also run B" through this class.
 *
 * <p>The real launch contract (SiliconFlow chat + MiniMax vision) is declared here and checked by
 * {@link EvaluationAOnlyConfigTest}; the runtime transport is forced to the loopback double by the
 * fixture, and {@link #requireLoopback(URI)} refuses a non-local endpoint before any client is
 * built. A real request is therefore impossible from this entry's test wiring, not merely unused.
 */
final class EvaluationAOnlyCanonEntry {
    // ---------------------------------------------------------------- declared launch contract

    /** controller A and the fixed topic-analysis role are the SAME model by design. */
    static final String MODEL_A = "deepseek-ai/DeepSeek-V4.1-Flash";
    /** Registered B name: V4 **Pro**. `deepseek-ai/DeepSeek-V4.1-Pro` does not exist in the
     *  provider contract and is a decoy this entry must refuse. */
    static final String MODEL_B = "deepseek-ai/DeepSeek-V4-Pro";
    static final String DECOY_MODEL = "deepseek-ai/DeepSeek-V4.1-Pro";
    static final String VISION_MODEL = "MiniMax-M3";
    static final String PROVIDER = "siliconflow";
    static final String REAL_BASE_URL = "https://api.siliconflow.cn/v1";
    static final String CHAT_PATH = "/chat/completions";
    static final String VISION_PROVIDER = "minimax";
    static final String VISION_TRANSPORT = "openai_chat_vision";
    static final String REAL_VISION_BASE_URL = "https://api.minimax.cn";
    static final String VISION_PATH = "/v1/chat/completions";
    static final int PRICE_IN_FEN = 200;
    static final int PRICE_OUT_FEN = 800;
    static final int VISION_RESERVED_IN_FEN = 420;
    static final int VISION_RESERVED_OUT_FEN = 1680;
    static final int VISION_APPLICABLE_IN_FEN = 210;
    static final int VISION_APPLICABLE_OUT_FEN = 840;
    static final int VISION_MAX_OUTPUT_TOKENS = 2048;
    static final int VISION_PROMPT_CHARS = 1000;
    static final int VISION_PER_IMAGE_TOKENS = 600;
    static final int VISION_REQUEST_FLOOR = 202;

    static final long CANON_VIDEO = EvaluationVideoCanonEvidence.CANON_VIDEO;
    static final Instant TASK_NOW = Instant.parse("2026-11-16T00:00:00Z");
    static final String PROMPT = "分析视频" + CANON_VIDEO + "从2026-11-03到2026-11-10的评论和弹幕，"
            + "结合视频画面，生成观众反馈HTML报告";
    static final String CREDENTIAL = "demo-alice";
    static final AccessScope OWNER = new AccessScope("demo", "alice", Set.of(CANON_VIDEO));

    static final int TOTAL_FEN = 5000;
    static final int PAUSE_FEN = 1000;
    static final int EXPECTED_BATCH_CAP_FEN = 2348;
    static final int CARRIED_SETTLED_FEN = 280;
    static final int CARRIED_RETAINED_FEN = 24;
    static final int OPENING_CONSERVATIVE_FEN = 322;
    static final int OLD_A_NEW_SPEND_FEN = 18;

    static final String KEY = "synthetic-loopback-only-not-a-credential";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final RunService runs;
    private final FeedbackAnalysisService analysis;
    private final MockMvc mvc;

    EvaluationAOnlyCanonEntry(RunService runs, FeedbackAnalysisService analysis, MockMvc mvc) {
        requireEvidenceRoot();
        this.runs = runs;
        this.analysis = analysis;
        this.mvc = mvc;
    }

    // ---------------------------------------------------------------- evidence root

    /**
     * The evidence root has to be stated, never inferred. A missing property is a startup error,
     * so a stop probe can never silently write into another run's evidence directory.
     */
    static Path requireEvidenceRoot(String raw) {
        if (raw == null || raw.isBlank())
            throw new IllegalStateException("A_ONLY_ENTRY_EVIDENCE_ROOT_REQUIRED: pass "
                    + "-Deval.evidence.root=<dir>; the shared default (target/evaluation/offline-canon) "
                    + "belongs to batch 4 and must not receive this batch's evidence");
        return Path.of(raw).toAbsolutePath().normalize();
    }

    static Path requireEvidenceRoot() {
        return requireEvidenceRoot(System.getProperty("eval.evidence.root"));
    }

    // ---------------------------------------------------------------- declared vs effective

    /** The contract a FUTURE real run would use. No credential is read: the value is a fixed
     *  placeholder so the properties object can be validated without touching any key file. */
    static DeepSeekProperties realProperties() {
        return new DeepSeekProperties(URI.create(REAL_BASE_URL), KEY,
                MODEL_A, MODEL_A, Duration.ofSeconds(3), 384, 3072, MODEL_A,
                true, "high", "low", 16384, "siliconflow");
    }

    static Map<String, Object> declaredConfig() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("entry", "A_ONLY_SINGLE_MEMBER");
        Map<String, Object> controller = new LinkedHashMap<>();
        controller.put("role", "controller");
        controller.put("model_request_name", MODEL_A);
        controller.put("provider", PROVIDER);
        controller.put("base_url", REAL_BASE_URL);
        controller.put("endpoint", CHAT_PATH);
        controller.put("protocol_family", "openai_chat_completions");
        controller.put("protocol_field", "enable_thinking=false");
        controller.put("thinking", "disabled");
        controller.put("max_output_tokens", 3072);
        controller.put("price_input_fen_per_million", PRICE_IN_FEN);
        controller.put("price_output_fen_per_million", PRICE_OUT_FEN);
        controller.put("price_window", "0-2/8-24 Asia/Shanghai; 2-8 window cheaper and unused");
        controller.put("version_state", "REQUESTED_ONLY");
        controller.put("alias_of_upstream_build", true);
        Map<String, Object> analysisRole = new LinkedHashMap<>();
        analysisRole.put("role", "analysis");
        analysisRole.put("model_request_name", MODEL_A);
        analysisRole.put("same_name_as_controller", true);
        analysisRole.put("discriminator", "request shape: tools present=controller, absent=analysis");
        analysisRole.put("max_output_tokens", 16384);
        analysisRole.put("thinking", "disabled");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("role", "controller-b");
        b.put("model_request_name", MODEL_B);
        b.put("launched_by_this_entry", false);
        b.put("refused_at_admission", true);
        b.put("decoy_rejected", DECOY_MODEL);
        b.put("price_input_fen_per_million", 1200);
        b.put("price_output_fen_per_million", 2400);
        Map<String, Object> vision = new LinkedHashMap<>();
        vision.put("provider", VISION_PROVIDER);
        vision.put("transport", VISION_TRANSPORT);
        vision.put("base_url", REAL_VISION_BASE_URL);
        vision.put("endpoint", VISION_PATH);
        vision.put("model_request_name", VISION_MODEL);
        vision.put("thinking", "disabled");
        vision.put("image_detail", "low");
        vision.put("images_per_request", 12);
        vision.put("input_bound_tokens",
                VISION_REQUEST_FLOOR + VISION_PROMPT_CHARS + 12 * VISION_PER_IMAGE_TOKENS);
        vision.put("output_cap_tokens", VISION_MAX_OUTPUT_TOKENS);
        vision.put("price_reservation_fen_per_million", List.of(VISION_RESERVED_IN_FEN, VISION_RESERVED_OUT_FEN));
        vision.put("price_applicable_fen_per_million", List.of(VISION_APPLICABLE_IN_FEN, VISION_APPLICABLE_OUT_FEN));
        Map<String, Object> lines = new LinkedHashMap<>();
        lines.put("total_fen", TOTAL_FEN);
        lines.put("pause_fen", PAUSE_FEN);
        lines.put("batch_cap_fen", EXPECTED_BATCH_CAP_FEN);
        doc.put("controller", controller);
        doc.put("analysis", analysisRole);
        doc.put("controller_b", b);
        doc.put("vision", vision);
        doc.put("lines", lines);
        doc.put("credentials", Map.of("read", false, "placeholder_only", true,
                "placeholder", KEY));
        doc.put("effective_transport", Map.of("declared", REAL_BASE_URL, "effective",
                EvaluationVideoCanonLoopback.BASE_URL, "guard", "LOOPBACK_ONLY"));
        return doc;
    }

    static void writeDeclaredConfig() throws IOException {
        Path root = EvaluationVideoCanonEvidence.requireOwned(EvaluationVideoCanonEvidence.ROOT);
        Path target = root.resolve("config");
        Files.createDirectories(target);
        Files.writeString(target.resolve("declared-config.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(declaredConfig()),
                StandardCharsets.UTF_8);
        Files.writeString(target.resolve("price-parity.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(priceParity()),
                StandardCharsets.UTF_8);
    }

    /** The only transport this entry may build. A public host is refused before a client exists,
     *  which is what makes "no real request" a property of the wiring rather than a promise. */
    static URI requireLoopback(URI candidate) {
        if (candidate == null || candidate.getHost() == null
                || !Set.of("127.0.0.1", "localhost", "[::1]").contains(candidate.getHost())
                || !"http".equals(candidate.getScheme())) {
            throw new IllegalStateException("A_ONLY_ENTRY_REMOTE_REFUSED: the A-only entry may only "
                    + "send to its loopback double; would_send_to=" + candidate
                    + (candidate == null ? "" : candidate.getPath()));
        }
        return candidate;
    }

    // ---------------------------------------------------------------- prices

    /** One price table for both hosts: the loopback key is what this run uses, and the declared
     *  SiliconFlow key is what a future real run would use. They are the same Price objects, and
     *  the parity file records that equality instead of assuming it. */
    static Map<String, EvaluationFeeLedger.Price> prices() {
        var price = new EvaluationFeeLedger.Price(PRICE_IN_FEN, PRICE_OUT_FEN);
        Map<String, EvaluationFeeLedger.Price> prices = new LinkedHashMap<>();
        for (String host : List.of("127.0.0.1", "api.siliconflow.cn"))
            prices.put("chat:" + host + ":" + MODEL_A, price);
        prices.put("vision:" + VISION_PROVIDER + ":" + VISION_MODEL + ":" + VISION_TRANSPORT,
                new EvaluationFeeLedger.Price(VISION_RESERVED_IN_FEN, VISION_RESERVED_OUT_FEN));
        return prices;
    }

    static Map<String, Object> priceParity() {
        var table = prices();
        var loopback = table.get("chat:127.0.0.1:" + MODEL_A);
        var real = table.get("chat:api.siliconflow.cn:" + MODEL_A);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("loopback_key", "chat:127.0.0.1:" + MODEL_A);
        doc.put("declared_key", "chat:api.siliconflow.cn:" + MODEL_A);
        doc.put("loopback_price_fen_per_million", List.of(loopback.inputFenPerMillion(), loopback.outputFenPerMillion()));
        doc.put("declared_price_fen_per_million", List.of(real.inputFenPerMillion(), real.outputFenPerMillion()));
        doc.put("same_price_object", loopback == real);
        doc.put("vision_key", "vision:" + VISION_PROVIDER + ":" + VISION_MODEL + ":" + VISION_TRANSPORT);
        var vision = table.get("vision:" + VISION_PROVIDER + ":" + VISION_MODEL + ":" + VISION_TRANSPORT);
        doc.put("vision_reservation_fen_per_million",
                List.of(vision.inputFenPerMillion(), vision.outputFenPerMillion()));
        doc.put("exit_key_uses_actual_host", true);
        return doc;
    }

    // ---------------------------------------------------------------- A-only admission

    /**
     * The send boundary's A-only + stop guard. It runs before {@code ledger.admit}, so a refused
     * dispatch neither reserves nor writes a row. Ordering is the point: cancellation and the
     * stop flag cover different failure modes, and the stop flag must survive a cancel that did
     * not take effect.
     */
    static final class AOnlyAdmission implements EvaluationFeeGate.Admission {
        static final String REFUSED = "A_ONLY_ENTRY_REFUSED";
        static final String STOPPED = "A_ONLY_ENTRY_STOPPED";
        private final EvaluationFeeLedger ledger;
        private final Path refusals;
        private volatile boolean stopped;
        private final List<Map<String, Object>> recorded = new CopyOnWriteArrayList<>();

        AOnlyAdmission(EvaluationFeeLedger ledger) {
            this.ledger = ledger;
            this.refusals = EvaluationVideoCanonEvidence.requireOwned(
                    EvaluationVideoCanonEvidence.ROOT.resolve("config").resolve("refusals.jsonl"));
            try {
                Files.createDirectories(refusals.getParent());
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }

        void stop() {
            stopped = true;
        }

        boolean stopped() {
            return stopped;
        }

        int refusalCount() {
            return recorded.size();
        }

        private AgentException refuse(String code, String model, String role, long bytes, String why) {
            return refuse(code, model, role, bytes, why, "");
        }

        private AgentException refuse(String code, String model, String role, long bytes, String why,
                                      String host) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", Instant.now().toString());
            row.put("pid", ProcessHandle.current().pid());
            row.put("code", code);
            row.put("model", model);
            row.put("role", role);
            row.put("payload_bytes", bytes);
            row.put("host", host);
            row.put("reason", why);
            recorded.add(row);
            try {
                Files.writeString(refusals, JSON.writeValueAsString(row) + System.lineSeparator(),
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException error) {
                throw new IllegalStateException("cannot record the refusal", error);
            }
            return new AgentException(code, why);
        }

        @Override
        public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes, int maxTokens) {
            return admitChat(model, host, payloadBytes, maxTokens, null);
        }

        @Override
        public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes,
                                                       int maxTokens, String clientRole) {
            String role = clientRole == null ? "chat" : clientRole;
            if (stopped)
                throw refuse(STOPPED, model, role, payloadBytes,
                        "the A-only entry is stopped; the dispatch never reaches the ledger or the wire");
            if (!MODEL_A.equals(model))
                throw refuse(REFUSED, model, role, payloadBytes,
                        "the A-only entry may dispatch only " + MODEL_A + "; saw " + model
                                + " (B is " + MODEL_B + "; " + DECOY_MODEL + " does not exist)");
            if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(host))
                throw refuse(REFUSED, model, role, payloadBytes,
                        "the A-only entry may only price a loopback host; saw " + host, host);
            return ledger.admitChat(model, host, payloadBytes, maxTokens, clientRole);
        }

        @Override
        public EvaluationFeeGate.Reservation admitVideo(String provider, String path) {
            throw refuse(REFUSED, "n/a", "vision", 0,
                    "the legacy video exit is not the registered image contract (" + provider + " " + path + ")");
        }

        @Override
        public EvaluationFeeGate.Reservation admitVision(String provider, String transport, String path,
                                                         String model, int imageCount, long payloadBytes,
                                                         int maxOutputTokens) {
            if (stopped)
                throw refuse(STOPPED, model, "vision", payloadBytes,
                        "the A-only entry is stopped; the vision dispatch never reaches the ledger or the wire");
            if (!VISION_PROVIDER.equals(provider) || !VISION_TRANSPORT.equals(transport)
                    || !VISION_MODEL.equals(model))
                throw refuse(REFUSED, model, "vision", payloadBytes,
                        "the registered image exit is " + VISION_PROVIDER + "/" + VISION_TRANSPORT + "/"
                                + VISION_MODEL + "; saw " + provider + "/" + transport + "/" + model);
            return ledger.admitVision(provider, transport, path, model, imageCount, payloadBytes, maxOutputTokens);
        }
    }

    // ---------------------------------------------------------------- batch start

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

    /** Read-only copy of the historical ledger into THIS batch's evidence root, then assert the
     *  opening envelope before any dispatch. Batch 3/4/5 are never opened for writing. */
    static Map<String, Object> initializeEmptyBatch() throws IOException {
        Path root = EvaluationVideoCanonEvidence.requireOwned(EvaluationVideoCanonEvidence.ROOT);
        Files.createDirectories(root);
        Files.createDirectories(EvaluationVideoCanonEvidence.COMPLETED);
        Files.createDirectories(EvaluationVideoCanonEvidence.STOP);
        Path ledger = EvaluationVideoCanonEvidence.LEDGER;
        Files.deleteIfExists(ledger);
        Files.deleteIfExists(ledger.resolveSibling(ledger.getFileName() + ".lock"));
        try (var files = Files.list(EvaluationVideoCanonEvidence.COMPLETED)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
        Path historical = historicalLedger();
        Files.copy(historical, ledger, StandardCopyOption.REPLACE_EXISTING);
        try (var opened = new EvaluationFeeLedger(ledger, TOTAL_FEN, PAUSE_FEN, prices())) {
            assertEquals(CARRIED_SETTLED_FEN + CARRIED_RETAINED_FEN,
                    opened.carriedIn().settledFen() + opened.carriedIn().retainedFen(),
                    "the carried-in envelope must be the file-verified 3.04 CNY");
            assertEquals(OPENING_CONSERVATIVE_FEN, opened.conservativeUsedFen(),
                    "3.22 CNY must be the opening conservative total: 3.04 carried + 0.18 old A");
            assertEquals(OLD_A_NEW_SPEND_FEN, opened.batchNewSpendFen(),
                    "the old A 0.18 CNY is counted exactly once");
            assertEquals(0, opened.openReservations().size(), "the copied historical batch has no open reservation");
            assertEquals(EXPECTED_BATCH_CAP_FEN, opened.batchCap().newSpendCapFen(),
                    "the inherited pair cap stays 23.48 CNY; A-only never reaches it before the pause line");
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("verdict", "A_ONLY_BATCH_OPENED_WITH_CARRIED_IN");
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
            proof.put("batch_cap_fen", opened.batchCap().newSpendCapFen());
            proof.put("pause_fen", PAUSE_FEN);
            proof.put("total_fen", TOTAL_FEN);
            proof.put("historical_source_sha256_16",
                    EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(historical)).substring(0, 16));
            Files.writeString(root.resolve("empty-batch.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof), StandardCharsets.UTF_8);
            return proof;
        }
    }

    // ---------------------------------------------------------------- the single member

    record RunResult(String pid, String runId, String status, String rulesVersion,
                     List<String> tools, JsonNode counts, String html,
                     List<Map<String, Object>> requests, List<JsonNode> ledgerRows,
                     JsonNode video, String error, Map<String, Object> finalizeProof) {}

    RunResult runSingle(EvaluationFeeLedger ledger) throws Exception {
        var admission = new AOnlyAdmission(ledger);
        EvaluationFeeGate.install(admission);
        int rowsBefore = reservationCount(EvaluationVideoCanonEvidence.LEDGER);
        EvaluationVideoCanonLoopback.beginWindow();
        var run = runs.create(PROMPT, OWNER);
        var reached = awaitLaunch(() -> run.snapshot().status(), 90);
        var window = EvaluationVideoCanonLoopback.endWindow();
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
        List<JsonNode> rows = new ArrayList<>();
        JsonNode doc = JSON.readTree(Files.readString(EvaluationVideoCanonEvidence.LEDGER, StandardCharsets.UTF_8));
        for (int i = rowsBefore; i < doc.path("reservations").size(); i++) rows.add(doc.path("reservations").path(i));
        assertEquals(window.size(), rows.size(), "this member's ledger rows == its dispatches");

        var bridge = new Bridge(ledger);
        for (var row : rows) bridge.record(row.path("reservation_id").asText());
        int openBefore = ledger.openReservations().size();
        // The member is terminal; declaring the stop here closes the admission before the
        // release sequence runs, so no queued dispatch can slip into the confirmation window.
        declareStop(admission, null);
        var confirmed = confirmStopBeforeGateUnload(null, () -> run.snapshot().status(), bridge, 5);
        Map<String, Object> finalizeProof = new LinkedHashMap<>();
        finalizeProof.put("gate_unloaded_by", "ENTRY_CONFIRMED_STOP");
        finalizeProof.put("confirmed_status", confirmed.status().name());
        finalizeProof.put("open_before_release", openBefore);
        finalizeProof.put("open_after_release", ledger.openReservations().size());
        finalizeProof.put("armed_after", EvaluationFeeGate.armed());
        finalizeProof.put("dispatches_checked", confirmed.dispatches().size());
        finalizeProof.put("refusals", admission.refusalCount());
        assertFalse(EvaluationFeeGate.armed(),
                "the confirmed stop is the only path that may unload the gate");

        return new RunResult(String.valueOf(ProcessHandle.current().pid()), run.id.toString(), reached.name(),
                job.request().rulesVersion(),
                run.execution().observations().stream().map(row -> row.tool()).toList(),
                JSON.valueToTree(Map.of("comments", job.snapshot().counts().comments(),
                        "danmaku", job.snapshot().counts().danmaku(),
                        "total", job.snapshot().counts().total())),
                html, List.copyOf(window), rows, JSON.valueToTree(job.video()), error, finalizeProof);
    }

    // ---------------------------------------------------------------- evidence

    void archive(RunResult result) throws IOException {
        Path root = EvaluationVideoCanonEvidence.requireOwned(EvaluationVideoCanonEvidence.ROOT);
        Files.writeString(root.resolve("a-report.html"), result.html(), StandardCharsets.UTF_8);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("verdict", "A_ONLY_SINGLE_MEMBER_VIDEO_GROUNDED_REPORT_DELIVERED");
        doc.put("pid", result.pid());
        doc.put("controller", MODEL_A);
        doc.put("analysis_model", MODEL_A);
        doc.put("vision_model", VISION_MODEL);
        doc.put("run_id", result.runId());
        doc.put("status", result.status());
        doc.put("rules_version", result.rulesVersion());
        doc.put("tools", result.tools());
        doc.put("counts", result.counts());
        doc.put("report_sha256", EvaluationVideoCanonEvidence.sha256(
                result.html().getBytes(StandardCharsets.UTF_8)));
        doc.put("report_bytes", result.html().getBytes(StandardCharsets.UTF_8).length);
        doc.put("report_file", "a-report.html");
        doc.put("video", videoFacts(result.video()));
        doc.put("requests", result.requests().size());
        doc.put("ledger_rows", result.ledgerRows().size());
        doc.put("role_binding", roleBinding(result.requests()));
        doc.put("finalize", result.finalizeProof());
        doc.put("reconciliation", reconciliation(result));
        Files.writeString(root.resolve("a-report.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("a-dispatches.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result.requests()),
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("reconciliation.tsv"), reconciliationTsv(result),
                StandardCharsets.UTF_8);
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("run_id", result.runId());
        completed.put("controller", MODEL_A);
        completed.put("status", result.status());
        completed.put("pid", result.pid());
        completed.put("report_sha256", doc.get("report_sha256"));
        completed.put("completed_at", Instant.now().toString());
        completed.put("ledger_rows", result.ledgerRows().size());
        Files.writeString(EvaluationVideoCanonEvidence.COMPLETED.resolve("controller-a-single.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(completed), StandardCharsets.UTF_8);
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
        facts.put("sampled_at_ms", video.path("sampledAtMs"));
        return facts;
    }

    /** Roles come from the wire shape the double saw, never from the model string: controller A
     *  and the fixed analysis role share one request name, so only `tools` separates them. */
    static Map<String, Object> roleBinding(List<Map<String, Object>> requests) {
        Map<String, Object> binding = new LinkedHashMap<>();
        for (String role : List.of("controller", "analysis", "vision")) {
            var rows = requests.stream().filter(r -> role.equals(r.get("role"))).toList();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("count", rows.size());
            entry.put("models", rows.stream().map(r -> r.get("model")).distinct().toList());
            entry.put("has_tools", rows.stream().map(r -> r.get("has_tools")).distinct().toList());
            entry.put("paths", rows.stream().map(r -> r.get("path")).distinct().toList());
            binding.put(role, entry);
        }
        binding.put("model_a", MODEL_A);
        binding.put("analysis_model", MODEL_A);
        binding.put("analysis_model_equals_a_controller", true);
        binding.put("controller_b_dispatched", requests.stream().anyMatch(r -> MODEL_B.equals(r.get("model"))));
        binding.put("decoy_dispatched", requests.stream().anyMatch(r -> DECOY_MODEL.equals(r.get("model"))));
        return binding;
    }

    private static List<Map<String, Object>> reconciliation(RunResult result) {
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

    private static String reconciliationTsv(RunResult result) {
        var rows = reconciliation(result);
        StringBuilder out = new StringBuilder();
        out.append("index\trole\tmodel\ttool\tanalysis_kind\tbody_bytes\treserved_fen\tactual_fen\tstatus\tsha_match\n");
        for (var row : rows) {
            out.append(row.get("index")).append('\t').append(row.get("role")).append('\t')
                    .append(row.get("model")).append('\t').append(row.get("tool")).append('\t')
                    .append(row.get("analysis_kind")).append('\t').append(row.get("body_bytes")).append('\t')
                    .append(row.get("reserved_fen")).append('\t').append(row.get("actual_fen")).append('\t')
                    .append(row.get("status")).append('\t')
                    .append(String.valueOf(row.get("body_sha256")).equals(String.valueOf(row.get("ledger_body_sha256"))))
                    .append('\n');
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- stop sequence

    interface StopBridge {
        EvaluationFeeLedger ledger();

        List<Map<String, Object>> finish();
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

    /** One call for the launcher's stop: close the admission, then issue the real cancel. The
     *  flag is set BEFORE the cancel so a dispatch that escapes the cancellation chain still has
     *  to pass the stopped admission. */
    static void declareStop(AOnlyAdmission admission, Runnable issueCancel) {
        admission.stop();
        if (issueCancel != null) issueCancel.run();
    }

    /** Same shape as the old launcher's release path: cancel, bounded confirm, terminal check,
     *  per-dispatch reservation check, OPEN refusal, interrupt check, and ONLY THEN clear(). */
    static ConfirmedStop confirmStopBeforeGateUnload(Runnable issueCancel, Supplier<Status> statusView,
                                                     StopBridge bridge, long confirmSeconds)
            throws InterruptedException {
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

    static Status awaitLaunch(Supplier<Status> statusView, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!com.tianmu.agent.core.AgentTypes.terminal(statusView.get())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return statusView.get();
    }

    // ---------------------------------------------------------------- late dispatch / clients

    /** A real {@link DeepSeekClient} over the loopback double. The model is explicit so the same
     *  helper can prove A is admitted and B/decoy are refused at the send boundary. */
    static DeepSeekClient client() {
        var properties = new DeepSeekProperties(URI.create(EvaluationVideoCanonLoopback.BASE_URL), KEY,
                MODEL_A, MODEL_A, Duration.ofSeconds(2), 384, 1024, MODEL_A,
                true, "high", "low", 16384, "siliconflow");
        return new DeepSeekClient(properties, JSON);
    }

    /** One unexpired late request. Returns the refusal instead of throwing so the caller can
     *  archive the exact code with the pre/post counters. */
    static AgentException lateDispatch(DeepSeekClient client, String model, CallControl control) {
        try {
            client.complete(model, List.of(Map.of("role", "user", "content", "late")),
                    256, control, true);
            return null;
        } catch (AgentException refused) {
            return refused;
        }
    }

    // ---------------------------------------------------------------- small helpers

    static int reservationCount(Path ledger) throws IOException {
        if (!Files.isRegularFile(ledger)) return 0;
        JsonNode doc = JSON.readTree(Files.readString(ledger, StandardCharsets.UTF_8));
        return doc.path("reservations").size();
    }

    static long sumConservative(JsonNode doc) {
        long total = 0;
        for (JsonNode row : doc.path("reservations")) {
            total += "SETTLED".equals(row.path("status").asText())
                    ? row.path("actual_fen").asLong(0) : row.path("reserved_fen").asLong(0);
        }
        if (doc.hasNonNull("carried_in"))
            total += doc.path("carried_in").path("settled_fen").asLong(0)
                    + doc.path("carried_in").path("retained_fen").asLong(0);
        return total;
    }

    /** For tests that need a control without a running job: unexpired by construction. */
    static CallControl liveControl(CancellationToken token) {
        return new CallControl(Instant.now().plusSeconds(30), token);
    }

    static JsonNode ledgerDocument() throws IOException {
        return JSON.readTree(Files.readString(EvaluationVideoCanonEvidence.LEDGER, StandardCharsets.UTF_8));
    }

    /** Paid exits the fee gate does NOT cover must be off BEFORE the context serves a request. */
    static void requireClosedPaidExits(org.springframework.core.env.Environment environment) {
        assertTrue(!environment.getProperty("agent.video.asr.enabled", Boolean.class, false),
                "independent ASR is a paid exit without a fee gate and must stay off");
        assertTrue(!environment.getProperty("agent.feedback.embedding.enabled", Boolean.class, false),
                "embedding is a paid exit without a fee gate and must stay off");
        assertTrue(!environment.getProperty("agent.feedback.rerank.enabled", Boolean.class, false),
                "rerank is a paid exit without a fee gate and must stay off");
    }
}
