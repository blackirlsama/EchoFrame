package com.tianmu.agent.evaluation;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.Status;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.runtime.RunService;
import com.tianmu.agent.runtime.control.CancellationToken;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch-6 stop cases, all driven through the A-only entry's own stop semantics.
 *
 * <p>Two different protections are exercised on purpose:
 * <ul>
 *   <li>the real cancellation chain {@code RunService.cancel -> RunState.cancel -> CANCELLED}
 *       (a live run is interrupted while a supplier call is in flight);</li>
 *   <li>the admission-level stop flag, which must refuse an <b>unexpired</b> late request even
 *       when the cancellation call did not take effect (second cancel on a terminal run).</li>
 * </ul>
 * The late request uses a fresh, never-cancelled token, so only the stop flag can explain the
 * refusal. The double's own receive counter and the ledger row count are compared before/after.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "agent.preparation.deterministic-routing=true",
        "agent.policy-snapshot.enabled=true",
        "agent.evaluation.enabled=true",
        "agent.feedback.compression.enabled=false",
        "agent.feedback.embedding.enabled=false",
        "agent.feedback.rerank.enabled=false",
        "agent.video.asr.enabled=false",
        "agent.video.enabled=true",
        "agent.video.provider=minimax",
        "agent.video.vision-transport=openai_chat_vision",
        "agent.video.model=MiniMax-M3",
        "agent.video.api-key=synthetic-loopback-only-not-a-credential",
        "agent.video.transcription-model=",
        "harness.trace.directory=target/evaluation/traces/canon-stop"})
@AutoConfigureMockMvc
@Import(EvaluationAOnlyCanonFixture.class)
@EnabledIfSystemProperty(named = "eval.canon.enabled", matches = "true")
class EvaluationAOnlyStopTest {
    @DynamicPropertySource
    static void loopback(DynamicPropertyRegistry registry) {
        registry.add("agent.video.base-url", () -> EvaluationVideoCanonLoopback.BASE_URL);
    }

    @Autowired
    RunService runs;

    private Path ledgerFile;

    @BeforeEach
    void prepare() throws Exception {
        Files.createDirectories(EvaluationVideoCanonEvidence.STOP);
        EvaluationVideoCanonLoopback.responseDelay(0);
    }

    @AfterEach
    void onlyConfirmedStopUnloads() {
        EvaluationVideoCanonLoopback.responseDelay(0);
        assertFalse(EvaluationFeeGate.armed(),
                "the gate must be unloaded by the entry's confirmed stop, never by test cleanup");
        Thread.interrupted();
    }

    /** Fresh copy of the historical 3.22 CNY ledger for each case: the stop evidence must also
     *  prove the carried-in envelope, and no case may inherit another case's rows. */
    private EvaluationFeeLedger ledger(String name) throws Exception {
        ledgerFile = EvaluationVideoCanonEvidence.requireOwned(
                EvaluationVideoCanonEvidence.STOP.resolve(name));
        Files.deleteIfExists(ledgerFile);
        Files.deleteIfExists(ledgerFile.resolveSibling(ledgerFile.getFileName() + ".lock"));
        Files.copy(EvaluationAOnlyCanonEntry.historicalLedger(), ledgerFile,
                StandardCopyOption.REPLACE_EXISTING);
        var ledger = new EvaluationFeeLedger(ledgerFile, EvaluationAOnlyCanonEntry.TOTAL_FEN,
                EvaluationAOnlyCanonEntry.PAUSE_FEN, EvaluationAOnlyCanonEntry.prices());
        assertEquals(322, ledger.conservativeUsedFen(), "each stop case opens at 3.22 CNY");
        assertEquals(9, EvaluationAOnlyCanonEntry.reservationCount(ledgerFile));
        return ledger;
    }

    private int rows() throws Exception {
        return EvaluationAOnlyCanonEntry.reservationCount(ledgerFile);
    }

    private static void write(String name, Map<String, Object> evidence) throws Exception {
        Path target = EvaluationVideoCanonEvidence.requireOwned(
                EvaluationVideoCanonEvidence.STOP.resolve(name));
        Files.writeString(target, EvaluationAOnlyCanonEntry.JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(evidence), StandardCharsets.UTF_8);
    }

    private record Late(AgentException error, int received, int rowsBefore, int rowsAfter,
                        int openBefore, int openAfter, long deadlineRemainingMs) {}

    /** One unexpired late request through the REAL client; counts are read from the double and
     *  the ledger, never inferred from the exception alone. */
    private Late lateAttempt(EvaluationFeeLedger ledger) throws Exception {
        var client = EvaluationAOnlyCanonEntry.client();
        var token = new CancellationToken(); // deliberately NOT cancelled
        var control = EvaluationAOnlyCanonEntry.liveControl(token);
        long remaining = Duration.between(Instant.now(), control.deadline()).toMillis();
        int receivedBefore = EvaluationVideoCanonLoopback.receivedCount();
        int rowsBefore = rows();
        int openBefore = ledger.openReservations().size();
        AgentException error = EvaluationAOnlyCanonEntry.lateDispatch(client,
                EvaluationAOnlyCanonEntry.MODEL_A, control);
        int receivedAfter = EvaluationVideoCanonLoopback.receivedCount();
        int rowsAfter = rows();
        int openAfter = ledger.openReservations().size();
        assertTrue(remaining > 25_000, "the late request must still be well inside its deadline");
        assertEquals(receivedBefore, receivedAfter, "the refused late request must not reach the double");
        assertEquals(rowsBefore, rowsAfter, "the refused late request must not write a ledger row");
        assertEquals(openBefore, openAfter, "the refused late request must not disturb an open reservation");
        return new Late(error, receivedAfter, rowsBefore, rowsAfter, openBefore, openAfter, remaining);
    }

    @Test
    void launchWaitInterruptLeavesTheGateArmedAndSendsNothing() throws Exception {
        try (var ledger = ledger("launch-wait-ledger.json")) {
            var admission = new EvaluationAOnlyCanonEntry.AOnlyAdmission(ledger);
            EvaluationFeeGate.install(admission);
            var bridge = new EvaluationAOnlyCanonEntry.Bridge(ledger);
            var held = ledger.admitChat(EvaluationAOnlyCanonEntry.MODEL_A, "127.0.0.1", 4096, 1024, "harness");
            bridge.record(held.reservationId());
            EvaluationVideoCanonLoopback.beginWindow();

            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class,
                    () -> EvaluationAOnlyCanonEntry.awaitLaunch(() -> Status.RUNNING, 30));
            assertTrue(EvaluationFeeGate.armed(), "the gate stays armed after the launch-wait interrupt");
            assertEquals(1, ledger.openReservations().size(), "the in-flight reservation survives");

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("case", "LAUNCH_WAIT_INTERRUPT");
            evidence.put("pid", ProcessHandle.current().pid());
            evidence.put("interrupted", true);
            evidence.put("gate_armed", EvaluationFeeGate.armed());
            evidence.put("open_reservations", ledger.openReservations().size());
            evidence.put("rows", rows());
            evidence.put("sent_requests", EvaluationVideoCanonLoopback.receivedCount());
            write("launch-wait.json", evidence);

            Thread.interrupted();
            ledger.fail(held.reservationId(), "retained after launch-wait interrupt");
            assertEquals("FAILED_RETAINED",
                    EvaluationAOnlyCanonEntry.JSON.readTree(Files.readString(ledgerFile, StandardCharsets.UTF_8))
                            .path("reservations").get(9).path("status").asText(),
                    "the interrupted reservation is retained, not released");
            EvaluationAOnlyCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 1);
            assertFalse(EvaluationFeeGate.armed(), "only the confirmed stop unloads the gate");
        }
    }

    /**
     * The run is interrupted through the product's real cancel chain while the first supplier call
     * is still in flight. Then the same run is cancelled a SECOND time: the run is terminal, so the
     * second call demonstrably has no effect. The unexpired late request that follows carries a
     * fresh token, so its refusal can only come from the entry's stop flag.
     */
    @Test
    void aCancelThatDidNotTakeEffectStillCannotLetAnUnexpiredLateRequestThrough() throws Exception {
        try (var ledger = ledger("live-cancel-ledger.json")) {
            var admission = new EvaluationAOnlyCanonEntry.AOnlyAdmission(ledger);
            EvaluationFeeGate.install(admission);
            var bridge = new EvaluationAOnlyCanonEntry.Bridge(ledger);
            EvaluationVideoCanonLoopback.responseDelay(1500);
            EvaluationVideoCanonLoopback.beginWindow();
            var run = runs.create(EvaluationAOnlyCanonEntry.PROMPT, EvaluationAOnlyCanonEntry.OWNER);

            long waitUntil = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (EvaluationVideoCanonLoopback.receivedCount() < 1 && System.nanoTime() < waitUntil)
                Thread.sleep(20);
            assertTrue(EvaluationVideoCanonLoopback.receivedCount() >= 1,
                    "the live run must have a supplier call in flight before it is cancelled");

            var firstCancelRef = new java.util.concurrent.atomic.AtomicReference<com.tianmu.agent.core.AgentTypes.Snapshot>();
            EvaluationAOnlyCanonEntry.declareStop(admission,
                    () -> firstCancelRef.set(runs.cancel(run.snapshot().runId(),
                            EvaluationAOnlyCanonEntry.OWNER)));
            var firstCancel = firstCancelRef.get();
            var reached = EvaluationAOnlyCanonEntry.awaitLaunch(() -> run.snapshot().status(), 15);
            EvaluationVideoCanonLoopback.responseDelay(0);
            assertEquals(Status.CANCELLED, reached, "the real cancel chain must terminate the run");
            int receivedAtStop = EvaluationVideoCanonLoopback.receivedCount();

            var secondCancel = runs.cancel(run.snapshot().runId(), EvaluationAOnlyCanonEntry.OWNER);
            assertEquals(Status.CANCELLED, secondCancel.status(),
                    "the second cancel is a no-op on an already terminal run");

            // A reservation that was admitted but never dispatched: it must survive the late refusal.
            var held = ledger.admitChat(EvaluationAOnlyCanonEntry.MODEL_A, "127.0.0.1", 2048, 512, "harness");
            bridge.record(held.reservationId());
            int rowsBeforeLate = rows();
            int openBeforeLate = ledger.openReservations().size();

            var late = lateAttempt(ledger);
            assertNotNull(late.error(), "the late request must be refused");
            assertEquals(EvaluationAOnlyCanonEntry.AOnlyAdmission.STOPPED, late.error().code(),
                    "only the stop flag can refuse a fresh-token, unexpired dispatch");
            assertEquals(receivedAtStop, late.received(),
                    "no additional supplier bytes after the cancellation");
            assertEquals(rowsBeforeLate, late.rowsAfter(), "the ledger does not grow");
            assertEquals(openBeforeLate, late.openAfter(), "the open reservation is not lost");

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("case", "LIVE_CANCEL_THEN_NOOP_CANCEL_THEN_LATE");
            evidence.put("pid", ProcessHandle.current().pid());
            evidence.put("run_id", run.snapshot().runId().toString());
            evidence.put("cancel_chain", "RunService.cancel -> RunState.cancel -> publish(CANCELLED)");
            evidence.put("first_cancel_status", firstCancel.status().name());
            evidence.put("second_cancel_status", secondCancel.status().name());
            evidence.put("second_cancel_effect", "NONE_ALREADY_TERMINAL");
            evidence.put("status_after_cancel", run.snapshot().status().name());
            evidence.put("received_at_stop", receivedAtStop);
            evidence.put("received_after_late", late.received());
            evidence.put("rows_before_late", late.rowsBefore());
            evidence.put("rows_after_late", late.rowsAfter());
            evidence.put("open_before_late", late.openBefore());
            evidence.put("open_after_late", late.openAfter());
            evidence.put("late_deadline_remaining_ms", late.deadlineRemainingMs());
            evidence.put("late_error_code", late.error().code());
            evidence.put("late_error_message", late.error().getMessage());
            evidence.put("token_was_cancelled", false);
            evidence.put("conservative_fen_at_stop", ledger.conservativeUsedFen());
            write("live-cancel.json", evidence);

            ledger.fail(held.reservationId(), "retained after the live cancellation");
            EvaluationAOnlyCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.CANCELLED, bridge, 1);
            assertFalse(EvaluationFeeGate.armed());
        }
    }

    @Test
    void anUnconfirmableStopKeepsEveryReservationAndStillRefusesLateDispatch() throws Exception {
        try (var ledger = ledger("unconfirmable-ledger.json")) {
            var admission = new EvaluationAOnlyCanonEntry.AOnlyAdmission(ledger);
            EvaluationFeeGate.install(admission);
            var bridge = new EvaluationAOnlyCanonEntry.Bridge(ledger);
            var held = ledger.admitChat(EvaluationAOnlyCanonEntry.MODEL_A, "127.0.0.1", 8192, 1024, "harness");
            bridge.record(held.reservationId());
            EvaluationAOnlyCanonEntry.declareStop(admission, null);
            EvaluationVideoCanonLoopback.beginWindow();

            var refused = assertThrows(IllegalStateException.class,
                    () -> EvaluationAOnlyCanonEntry.confirmStopBeforeGateUnload(
                            null, () -> Status.RUNNING, bridge, 1));
            assertTrue(refused.getMessage().contains("did not reach terminal"), refused.getMessage());
            assertTrue(EvaluationFeeGate.armed(), "an unconfirmed stop keeps the gate armed");
            assertEquals(1, ledger.openReservations().size(), "the open reservation is still there");

            var late = lateAttempt(ledger);
            assertEquals(EvaluationAOnlyCanonEntry.AOnlyAdmission.STOPPED, late.error().code());
            assertEquals(1, late.openAfter(), "the late refusal must not consume the open reservation");

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("case", "UNCONFIRMABLE_STOP");
            evidence.put("pid", ProcessHandle.current().pid());
            evidence.put("status_view", "RUNNING");
            evidence.put("gate_armed", EvaluationFeeGate.armed());
            evidence.put("open_reservations", ledger.openReservations().size());
            evidence.put("refusal", refused.getMessage());
            evidence.put("late_error_code", late.error().code());
            evidence.put("late_deadline_remaining_ms", late.deadlineRemainingMs());
            evidence.put("received_after_late", late.received());
            evidence.put("rows_before_late", late.rowsBefore());
            evidence.put("rows_after_late", late.rowsAfter());
            write("unconfirmable.json", evidence);

            ledger.fail(held.reservationId(), "retained after an unconfirmation");
            EvaluationAOnlyCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 1);
        }
    }
}
