package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.Status;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch-5 stop cases driven through the SAME launch entry the two processes use
 * ({@link EvaluationVideoCanonEntry#awaitLaunch} and
 * {@link EvaluationVideoCanonEntry#confirmStopBeforeGateUnload}). The gate, ledger and model client
 * are real; the only local part is the supplier double, so "the late request was not sent" is
 * measured by bytes the double actually read.
 *
 * <p>The late request is deliberately NOT expired ({@code deadline = now + 30s}): an expired
 * control is refused by the clock inside {@code DeepSeekClient.invoke} before the stop signal is
 * ever consulted, which would prove nothing about the stop protection. A cancelled token plus a
 * live deadline is refused by {@code control.checkpoint()} — and that checkpoint runs BEFORE the
 * fee reservation, so a refused late dispatch cannot grow the ledger either.
 */
@EnabledIfSystemProperty(named = "eval.canon.enabled", matches = "true")
class EvaluationVideoCanonStopTest {
    private static final String KEY = "synthetic-loopback-only-not-a-credential";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HOST = "127.0.0.1";
    private Path ledgerFile;

    @BeforeEach
    void prepare() throws Exception {
        Files.createDirectories(EvaluationVideoCanonEvidence.STOP);
        ledgerFile = EvaluationVideoCanonEvidence.requireOwned(
                EvaluationVideoCanonEvidence.STOP.resolve("stop-ledger.json"));
        Files.deleteIfExists(ledgerFile);
        Files.deleteIfExists(ledgerFile.resolveSibling(ledgerFile.getFileName() + ".lock"));
    }

    @AfterEach
    void onlyAConfirmedStopUnloadsTheGate() {
        assertFalse(EvaluationFeeGate.armed(),
                "the gate must be unloaded by the confirmed stop, never by test cleanup");
        Thread.interrupted();
    }

    private EvaluationFeeLedger ledger() {
        return new EvaluationFeeLedger(ledgerFile, 5000, 2000, EvaluationVideoCanonEntry.prices());
    }

    private DeepSeekClient client(String model) {
        var properties = new DeepSeekProperties(URI.create(EvaluationVideoCanonLoopback.BASE_URL), KEY,
                EvaluationVideoCanonEntry.MODEL_A, model, Duration.ofSeconds(2), 384, 1024,
                EvaluationVideoCanonEntry.ANALYSIS_MODEL, true, "high", "low", 16384, "siliconflow");
        return new DeepSeekClient(properties, JSON);
    }

    /** The stop signal is out and the deadline is still in the future: only cancellation can
     *  explain a refusal. */
    private static CallControl stopped(CancellationToken token) {
        token.cancel(StopReason.USER_CANCELLED);
        return new CallControl(Instant.now().plusSeconds(30), token);
    }

    private static void assertLateDispatchCancelled(DeepSeekClient client, String model, CallControl control) {
        assertTrue(control.deadline().isAfter(Instant.now()),
                "the late request must still be inside its deadline, or the clock would be the reason");
        EvaluationVideoCanonLoopback.beginWindow();
        var thrown = assertThrows(AgentException.class,
                () -> client.complete(model,
                        List.of(Map.of("role", "user", "content", "late")), 256, control, true));
        var window = EvaluationVideoCanonLoopback.endWindow();
        assertEquals("CANCELLED", thrown.code(),
                "the stop signal, not the clock, must refuse this dispatch");
        assertEquals(0, window.size(), "a late request must not reach the double");
    }

    private static String rowStatus(Path file, String reservationId) throws Exception {
        JsonNode doc = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        for (JsonNode row : doc.path("reservations"))
            if (reservationId.equals(row.path("reservation_id").asText()))
                return row.path("status").asText();
        return "MISSING";
    }

    @Test
    void launchWaitInterruptLeavesTheGateArmedAndSendsNothing() throws Exception {
        try (var ledger = ledger()) {
            EvaluationFeeGate.install(ledger);
            var bridge = new EvaluationVideoCanonEntry.Bridge(ledger);
            var open = ledger.admitChat(EvaluationVideoCanonEntry.MODEL_A, HOST, 4096, 1024);
            bridge.record(open.reservationId());

            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class,
                    () -> EvaluationVideoCanonEntry.awaitLaunch(() -> Status.RUNNING, 30));
            assertTrue(EvaluationFeeGate.armed(), "the gate stays armed after the launch-wait interrupt");
            assertEquals(1, ledger.openReservations().size(), "the in-flight reservation survives");

            ledger.fail(open.reservationId(), "retained after launch-wait interrupt");
            EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0);
            assertFalse(EvaluationFeeGate.armed(), "only the confirmed stop unloads the gate");

            write("launch-wait.json", Map.of("case", "launch-wait-interrupt", "armed_at_end", false,
                    "received", 0, "open_before_release", 1, "reservation_state", "FAILED_RETAINED",
                    "late_request", "NOT_ATTEMPTED"));
        }
    }

    @Test
    void cancelExceptionLeavesTheGateArmedAndTheReservationIntact() throws Exception {
        try (var ledger = ledger()) {
            EvaluationFeeGate.install(ledger);
            var bridge = new EvaluationVideoCanonEntry.Bridge(ledger);
            var open = ledger.admitChat(EvaluationVideoCanonEntry.MODEL_A, HOST, 4096, 1024);
            bridge.record(open.reservationId());
            var control = stopped(new CancellationToken());
            long remaining = Duration.between(Instant.now(), control.deadline()).toMillis();

            var cancelFailure = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(() -> {
                        throw new IllegalStateException("cancel transport failed");
                    }, () -> Status.RUNNING, bridge, 0));
            assertEquals("cancel transport failed", cancelFailure.getMessage());
            assertTrue(EvaluationFeeGate.armed(), "a cancel exception must not unload the gate");
            assertEquals(1, ledger.openReservations().size(), "the reservation survives the cancel failure");

            int rowsBefore = EvaluationVideoCanonEntry.reservationCount(ledgerFile);
            assertLateDispatchCancelled(client(EvaluationVideoCanonEntry.MODEL_A),
                    EvaluationVideoCanonEntry.MODEL_A, control);
            assertEquals(rowsBefore, EvaluationVideoCanonEntry.reservationCount(ledgerFile),
                    "a refused late dispatch must not add a ledger row");
            assertEquals(1, ledger.openReservations().size(), "the old reservation is still counted");

            ledger.fail(open.reservationId(), "retained after cancel failure");
            EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0);
            assertFalse(EvaluationFeeGate.armed());

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("case", "cancel-exception");
            evidence.put("armed_after_exception", true);
            evidence.put("received", 0);
            evidence.put("open_before_release", 1);
            evidence.put("late_dispatch_rows_added", EvaluationVideoCanonEntry.reservationCount(ledgerFile) - rowsBefore);
            evidence.put("late_deadline_remaining_ms", remaining);
            evidence.put("late_error_code", "CANCELLED");
            evidence.put("late_received", 0);
            evidence.put("reservation_state", rowStatus(ledgerFile, open.reservationId()));
            write("cancel-exception.json", evidence);
        }
    }

    @Test
    void unconfirmableStopKeepsEveryReservationUntilTheLastOpenCloses() throws Exception {
        try (var ledger = ledger()) {
            EvaluationFeeGate.install(ledger);
            var bridge = new EvaluationVideoCanonEntry.Bridge(ledger);
            var first = ledger.admitChat(EvaluationVideoCanonEntry.MODEL_A, HOST, 4096, 1024);
            var second = ledger.admitChat(EvaluationVideoCanonEntry.MODEL_A, HOST, 4096, 1024);
            bridge.record(first.reservationId());
            bridge.record(second.reservationId());
            var control = stopped(new CancellationToken());
            long remaining = Duration.between(Instant.now(), control.deadline()).toMillis();

            var readFailure = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(null, () -> {
                        throw new IllegalStateException("terminal read failed");
                    }, bridge, 0));
            assertEquals("terminal read failed", readFailure.getMessage());
            assertTrue(EvaluationFeeGate.armed(), "a failed terminal read keeps the gate armed");
            assertEquals(2, ledger.openReservations().size());

            var deadline = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.RUNNING, bridge, 0));
            assertTrue(deadline.getMessage().contains("launch deadline"), deadline.getMessage());
            assertTrue(EvaluationFeeGate.armed());

            ledger.fail(first.reservationId(), "retained at stop");
            var openRemains = assertThrows(IllegalStateException.class,
                    () -> EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0));
            assertTrue(openRemains.getMessage().contains("an OPEN reservation remains"), openRemains.getMessage());
            assertTrue(EvaluationFeeGate.armed(), "the gate still protects the remaining OPEN reservation");

            int rowsBefore = EvaluationVideoCanonEntry.reservationCount(ledgerFile);
            assertLateDispatchCancelled(client(EvaluationVideoCanonEntry.MODEL_A),
                    EvaluationVideoCanonEntry.MODEL_A, control);
            assertEquals(rowsBefore, EvaluationVideoCanonEntry.reservationCount(ledgerFile),
                    "a refused late dispatch must not add a ledger row while the gate is still armed");
            assertEquals(1, ledger.openReservations().size(), "the second reservation is still OPEN");

            ledger.fail(second.reservationId(), "retained at stop");
            EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0);
            assertFalse(EvaluationFeeGate.armed());

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("case", "unconfirmable-stop");
            evidence.put("branches", List.of("terminal-read-failed", "still-running", "open-remains"));
            evidence.put("armed_after_each_branch", true);
            evidence.put("received", 0);
            evidence.put("open_before_release", 2);
            evidence.put("late_deadline_remaining_ms", remaining);
            evidence.put("late_error_code", "CANCELLED");
            evidence.put("late_received", 0);
            write("unconfirmable-stop.json", evidence);
        }
    }

    /**
     * After the stop IS confirmed the gate is unloaded — and a live, unexpired request must STILL be
     * refused. If cancellation were skipped because "the gate is off", this dispatch would leave the
     * process unmetered; the double's received bytes are what prove it did not.
     */
    @Test
    void aNotYetExpiredRequestIsRefusedAfterTheConfirmedStop() throws Exception {
        try (var ledger = ledger()) {
            EvaluationFeeGate.install(ledger);
            var bridge = new EvaluationVideoCanonEntry.Bridge(ledger);
            var open = ledger.admitChat(EvaluationVideoCanonEntry.MODEL_A, HOST, 4096, 1024);
            bridge.record(open.reservationId());
            var token = new CancellationToken();
            var control = new CallControl(Instant.now().plusSeconds(30), token);
            long remaining = Duration.between(Instant.now(), control.deadline()).toMillis();

            ledger.fail(open.reservationId(), "retained at stop");
            EvaluationVideoCanonEntry.confirmStopBeforeGateUnload(
                    () -> token.cancel(StopReason.USER_CANCELLED), () -> Status.SUCCEEDED, bridge, 5);
            assertFalse(EvaluationFeeGate.armed(), "the confirmed stop unloads the gate");

            int rowsBefore = EvaluationVideoCanonEntry.reservationCount(ledgerFile);
            assertLateDispatchCancelled(client(EvaluationVideoCanonEntry.MODEL_A),
                    EvaluationVideoCanonEntry.MODEL_A, control);
            int rowsAfter = EvaluationVideoCanonEntry.reservationCount(ledgerFile);
            assertEquals(rowsBefore, rowsAfter, "the gate is off, yet no unmetered dispatch may leak");
            assertEquals("FAILED_RETAINED", rowStatus(ledgerFile, open.reservationId()),
                    "the pre-stop reservation is retained, never silently released");

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("case", "late-dispatch-after-confirmed-stop");
            evidence.put("gate_armed_at_late_dispatch", false);
            evidence.put("deadline_remaining_ms", remaining);
            evidence.put("error_code", "CANCELLED");
            evidence.put("received", 0);
            evidence.put("rows_before", rowsBefore);
            evidence.put("rows_after", rowsAfter);
            evidence.put("reservation_state", "FAILED_RETAINED");
            write("late-dispatch-after-confirmed-stop.json", evidence);
        }
    }

    private static void write(String name, Map<String, Object> body) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>(body);
        doc.put("verdict", "GATE_STAYED_ARMED_UNTIL_THE_CONFIRMED_STOP");
        Files.writeString(EvaluationVideoCanonEvidence.requireOwned(EvaluationVideoCanonEvidence.STOP.resolve(name)),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc), StandardCharsets.UTF_8);
    }
}
