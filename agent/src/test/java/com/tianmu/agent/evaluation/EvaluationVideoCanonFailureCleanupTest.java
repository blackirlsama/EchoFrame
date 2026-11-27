package com.tianmu.agent.evaluation;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.runtime.RunService;
import com.tianmu.agent.runtime.RunState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pairing entry's failure cleanup must run through the same stop sequence as success. These
 * cases use a real RunState (terminal failure or a waiting run) and a mocked RunService, so no
 * model, supplier or Spring context is involved.
 */
class EvaluationVideoCanonFailureCleanupTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONFIG = Path.of("eval/report-trial-synthetic.example.json");
    private static final Path REPO_MANIFEST = Path.of("eval/fixtures/synthetic/frames/frames-manifest.json");
    private static final String BASELINE = "synthetic-baseline";
    private static final String GROUP = "synthetic-pair";
    private static final String SOURCE_SHA = "a".repeat(64);

    private static final Map<String, String> PREVIOUS_PROPERTIES = new HashMap<>();

    @BeforeAll
    static void pinProofIdentity() {
        Map.of("eval.approval.group", GROUP,
                "eval.approval.baseline", BASELINE,
                "eval.historical.ledger.sha256", SOURCE_SHA,
                "eval.material.config", CONFIG.toString()).forEach((key, value) ->
                PREVIOUS_PROPERTIES.put(key, System.setProperty(key, value)));
    }

    @AfterAll
    static void restoreProofIdentity() {
        PREVIOUS_PROPERTIES.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    @AfterEach
    void clearGate() {
        EvaluationFeeGate.clear();
        EvaluationVideoCanonLoopback.beginWindow();
        EvaluationVideoCanonLoopback.endWindow();
    }

    @Test
    void aTerminalFailureIsConfirmedAndUnloadsTheGate(@TempDir Path dir) throws Exception {
        Path manifest = copyManifest(dir);
        try (var ledger = ledger(dir.resolve("ledger.json"), Map.of())) {
            RunState run = new RunState(scope(), "synthetic failure");
            run.fail("SYNTHETIC_FAILURE");
            var entry = entry(run);
            AssertionError error = assertThrows(AssertionError.class,
                    () -> entry.runMember(ledger, EvaluationVideoCanonEntry.MODEL_A, 5,
                            new EvaluationVideoCanonEntry.EvidencePaths(dir, dir.resolve("ledger.json"))));
            assertTrue(error.getMessage().contains("run failed"), error.getMessage());
            assertFalse(EvaluationFeeGate.armed(), "a confirmed terminal failure unloads the gate");
            var evidence = JSON.readTree(Files.readString(dir.resolve("failure-a.json"), StandardCharsets.UTF_8));
            assertEquals("CONFIRMED_STOP", evidence.path("cleanup").asText());
            assertEquals(0, evidence.path("open_after_cleanup").asInt());
            Path record = dir.resolve("completed/controller-a-repeat-1.json");
            assertTrue(Files.isRegularFile(record), "A confirmed-stop completion record is written");
            var completed = JSON.readTree(Files.readString(record, StandardCharsets.UTF_8));
            assertEquals("STOPPED_CONFIRMED", completed.path("status").asText());
            var context = new EvaluationVideoCanonEntry.CompletionContext(
                    dir, dir.resolve("ledger.json"), manifest, CONFIG, BASELINE, GROUP, SOURCE_SHA, "");
            assertDoesNotThrow(() -> EvaluationVideoCanonEntry.verifyBStart(
                    EvaluationVideoCanonEntry.MODEL_A, record, ledger, context, true));
            Path attempt = dir.resolve("b-attempt.json");
            EvaluationVideoCanonEntry.declareBAttempt(record, ledger, attempt);
            assertTrue(Files.isRegularFile(attempt));
            IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.declareBAttempt(record, ledger, attempt));
            assertTrue(duplicate.getMessage().contains("B_ATTEMPT_ALREADY_DECLARED"), duplicate.getMessage());
        }
    }

    @Test
    void twoConcurrentAttemptsCannotBothDeclareB(@TempDir Path dir) throws Exception {
        Path manifest = copyManifest(dir);
        try (var ledger = ledger(dir.resolve("ledger.json"), Map.of())) {
            Path record = dir.resolve("a-record.json");
            var completed = JSON.createObjectNode();
            completed.put("run_id", "synthetic-a-run");
            completed.put("controller", EvaluationVideoCanonEntry.MODEL_A);
            completed.put("status", "SUCCEEDED");
            completed.put("pid", 1);
            completed.put("report_file", "");
            completed.put("report_bytes", 0);
            completed.put("report_sha256", "");
            completed.put("pair_id", GROUP);
            completed.put("baseline", BASELINE);
            completed.put("contract_sha256", "");
            completed.put("ledger_source_sha256", SOURCE_SHA);
            completed.put("material_config_sha256", EvaluationVideoCanonContract.sha256(CONFIG));
            completed.put("frames_manifest_sha256", EvaluationVideoCanonContract.sha256(manifest));
            completed.put("ledger_rows", 0);
            completed.put("ledger_rows_sha256", EvaluationFeeLedger.rowsDigest(java.util.List.of()));
            Files.writeString(record, completed.toPrettyString(), StandardCharsets.UTF_8);
            Path attempt = dir.resolve("b-attempt.json");
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            var successes = new java.util.concurrent.atomic.AtomicInteger();
            var failures = new java.util.concurrent.atomic.AtomicInteger();
            try {
                var one = pool.submit(() -> {
                    start.await();
                    try { EvaluationVideoCanonEntry.declareBAttempt(record, ledger, attempt); successes.incrementAndGet(); }
                    catch (Exception error) { failures.incrementAndGet(); }
                    return null;
                });
                var two = pool.submit(() -> {
                    start.await();
                    try { EvaluationVideoCanonEntry.declareBAttempt(record, ledger, attempt); successes.incrementAndGet(); }
                    catch (Exception error) { failures.incrementAndGet(); }
                    return null;
                });
                start.countDown();
                one.get(); two.get();
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, successes.get(), "exactly one B attempt may be declared");
            assertEquals(1, failures.get(), "the competing process is refused");
            assertTrue(Files.isRegularFile(attempt));
        }
    }

    @Test
    void anEmptyStopProofIsRefused(@TempDir Path dir) throws Exception {
        Path manifest = copyManifest(dir);
        try (var ledger = ledger(dir.resolve("ledger.json"), Map.of())) {
            Path stop = dir.resolve("empty-stop.json");
            Files.writeString(stop, "{}", StandardCharsets.UTF_8);
            var record = JSON.createObjectNode();
            record.put("run_id", "synthetic-a-run");
            record.put("controller", EvaluationVideoCanonEntry.MODEL_A);
            record.put("status", "STOPPED_CONFIRMED");
            record.put("pid", 1);
            record.put("stop_file", "empty-stop.json");
            record.put("proof_sha256", EvaluationVideoCanonEvidence.sha256(Files.readAllBytes(stop)));
            record.put("pair_id", GROUP);
            record.put("baseline", BASELINE);
            record.put("contract_sha256", "");
            record.put("ledger_source_sha256", SOURCE_SHA);
            record.put("material_config_sha256", EvaluationVideoCanonContract.sha256(CONFIG));
            record.put("frames_manifest_sha256", EvaluationVideoCanonContract.sha256(manifest));
            record.put("ledger_rows", 0);
            record.put("ledger_rows_sha256", EvaluationFeeLedger.rowsDigest(java.util.List.of()));
            Path recordFile = dir.resolve("a-record.json");
            Files.writeString(recordFile, record.toPrettyString(), StandardCharsets.UTF_8);
            var context = new EvaluationVideoCanonEntry.CompletionContext(
                    dir, dir.resolve("ledger.json"), manifest, CONFIG, BASELINE, GROUP, SOURCE_SHA, "");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.verifyBStart(
                            EvaluationVideoCanonEntry.MODEL_A, recordFile, ledger, context));
            assertTrue(error.getMessage().contains("COMPLETION_STOP_NOT_CONFIRMED"), error.getMessage());
        }
    }

    @Test
    void aWaitingRunThatCancelCannotStopKeepsTheGateArmed(@TempDir Path dir) throws Exception {
        try (var ledger = ledger(dir.resolve("ledger.json"), Map.of())) {
            RunState run = new RunState(scope(), "synthetic wait");
            var entry = entry(run);
            assertThrows(AssertionError.class,
                    () -> entry.runMember(ledger, EvaluationVideoCanonEntry.MODEL_A, 1,
                            new EvaluationVideoCanonEntry.EvidencePaths(dir, dir.resolve("ledger.json"))));
            assertTrue(EvaluationFeeGate.armed(), "an unconfirmed stop keeps the gate armed");
            var evidence = JSON.readTree(Files.readString(dir.resolve("failure-a.json"), StandardCharsets.UTF_8));
            assertEquals("REFUSED", evidence.path("cleanup").asText());
            assertTrue(evidence.path("cleanup_error").asText().contains("did not reach terminal"));
        }
    }

    @Test
    void anOpenReservationKeepsTheGateArmedEvenAfterTerminalFailure(@TempDir Path dir) throws Exception {
        try (var ledger = ledger(dir.resolve("ledger.json"),
                Map.of("chat:127.0.0.1:synthetic", new EvaluationFeeLedger.Price(200, 800)))) {
            ledger.admitChat("synthetic", "127.0.0.1", 10, 10);
            RunState run = new RunState(scope(), "synthetic failure with open row");
            run.fail("SYNTHETIC_FAILURE");
            var entry = entry(run);
            assertThrows(AssertionError.class,
                    () -> entry.runMember(ledger, EvaluationVideoCanonEntry.MODEL_A, 5,
                            new EvaluationVideoCanonEntry.EvidencePaths(dir, dir.resolve("ledger.json"))));
            assertTrue(EvaluationFeeGate.armed(), "an OPEN reservation is never silently released");
            assertEquals(1, ledger.openReservations().size());
            var evidence = JSON.readTree(Files.readString(dir.resolve("failure-a.json"), StandardCharsets.UTF_8));
            assertEquals("REFUSED", evidence.path("cleanup").asText());
            assertTrue(evidence.path("cleanup_error").asText().contains("OPEN"));
        }
    }

    @Test
    void aFreshUnexpiredLateRequestIsRefusedByTheStopFlag(@TempDir Path dir) throws Exception {
        try (var ledger = ledger(dir.resolve("ledger.json"), Map.of())) {
            var admission = new EvaluationVideoCanonEntry.StopAdmission(ledger, dir);
            EvaluationFeeGate.install(admission);
            admission.stop();
            var token = new com.tianmu.agent.runtime.control.CancellationToken(); // deliberately fresh
            var control = EvaluationAOnlyCanonEntry.liveControl(token);
            int receivedBefore = EvaluationVideoCanonLoopback.receivedCount();
            var error = EvaluationAOnlyCanonEntry.lateDispatch(EvaluationAOnlyCanonEntry.client(),
                    EvaluationVideoCanonEntry.MODEL_A, control);
            assertNotNull(error, "the stopped entry must refuse the late request");
            assertEquals(EvaluationVideoCanonEntry.StopAdmission.STOPPED, error.code());
            assertEquals(receivedBefore, EvaluationVideoCanonLoopback.receivedCount(),
                    "the refused late request must not reach the loopback double");
            assertEquals(0, ledger.openReservations().size(), "no reservation is created");
            assertTrue(java.time.Duration.between(java.time.Instant.now(), control.deadline()).toMillis() > 0,
                    "the token is still unexpired");
        }
    }

    private static Path copyManifest(Path dir) throws Exception {
        Path manifest = dir.resolve("frames/frames-manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.copy(REPO_MANIFEST, manifest);
        return manifest;
    }

    private static EvaluationVideoCanonEntry entry(RunState run) {
        RunService runs = mock(RunService.class);
        when(runs.create(anyString(), any(AccessScope.class))).thenReturn(run);
        when(runs.cancel(any(UUID.class), any(AccessScope.class))).thenReturn(run.snapshot());
        return new EvaluationVideoCanonEntry(runs, mock(FeedbackAnalysisService.class), mock(MockMvc.class));
    }

    private static EvaluationFeeLedger ledger(Path file, Map<String, EvaluationFeeLedger.Price> prices) throws Exception {
        ObjectMapper json = new ObjectMapper();
        var doc = json.createObjectNode();
        doc.put("schema", "fee-ledger-v2");
        doc.put("total_fen", 100000);
        doc.put("pause_fen", 10000);
        doc.putArray("reservations");
        Files.writeString(file, doc.toPrettyString(), StandardCharsets.UTF_8);
        return new EvaluationFeeLedger(file, 100000, 10000, prices);
    }

    private static AccessScope scope() {
        return new AccessScope("demo", "alice", Set.of(EvaluationVideoCanonEvidence.CANON_VIDEO));
    }
}
