package com.tianmu.agent.evaluation;

import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentTypes.Status;
import com.tianmu.agent.evaluation.admission.EvaluationFeeGate;
import com.tianmu.agent.evaluation.admission.EvaluationFeeLedger;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch-3 stop chain, local doubles only. The release sequence below is a line-faithful extraction
 * of the provider-admission tree's {@code EvaluationRealFirstPairRunner.confirmStopBeforeGateUnload}
 * (source lines 188-220): cancel, bounded confirm wait, terminal check, per-dispatch reservation
 * check, OPEN-reservation refusal, interrupt check, and ONLY THEN {@code EvaluationFeeGate.clear()}.
 * The real launcher itself (1881 lines, bound to the old compressed path and the historical batch)
 * is deliberately not migrated; this harness keeps its release semantics on the REAL gate, ledger
 * and DeepSeekClient. A local HTTP double is the receiving side, so "late request not sent" is
 * measured by bytes actually read, not by a mock invocation count.
 */
class EvaluationStopSequenceTest {
    private static final String KEY = "synthetic-test-key";
    private static final String MODEL = "deepseek-test";
    private static final String HOST = "127.0.0.1";
    private static final Map<String, EvaluationFeeLedger.Price> PRICES = Map.of(
            "chat:" + HOST + ":" + MODEL, new EvaluationFeeLedger.Price(210, 840));

    private HttpServer server;
    private final AtomicInteger received = new AtomicInteger();
    private final CopyOnWriteArrayList<byte[]> receivedBodies = new CopyOnWriteArrayList<>();
    private int port;
    private Path ledgerFile;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            receivedBodies.add(exchange.getRequestBody().readAllBytes());
            received.incrementAndGet();
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        port = server.getAddress().getPort();
        ledgerFile = Files.createTempFile("stop-sequence-", ".json");
        Files.deleteIfExists(ledgerFile);
    }

    @AfterEach void stop() {
        EvaluationFeeGate.clear();
        Thread.interrupted();
        server.stop(0);
    }

    private EvaluationFeeLedger ledger() {
        return new EvaluationFeeLedger(ledgerFile, 5000, 2000, PRICES);
    }

    private DeepSeekClient client() {
        return new DeepSeekClient(new DeepSeekProperties(URI.create("http://127.0.0.1:" + port), KEY,
                MODEL, MODEL, Duration.ofSeconds(1), 384, 1024), json);
    }

    private static CallControl control() {
        return new CallControl(Instant.now().plusSeconds(10), new CancellationToken());
    }

    /** The minimal bridge the release sequence reads: its ledger, its dispatch rows, and a finish()
     *  that returns exactly the dispatches this batch recorded. */
    interface StopBridge {
        EvaluationFeeLedger ledger();
        List<Map<String, Object>> finish();
    }

    static final class Bridge implements StopBridge {
        private final EvaluationFeeLedger ledger;
        private final List<Map<String, Object>> rows = new ArrayList<>();
        Bridge(EvaluationFeeLedger ledger) { this.ledger = ledger; }
        void record(String reservationId) { rows.add(Map.of("reservation_id", reservationId)); }
        @Override public EvaluationFeeLedger ledger() { return ledger; }
        @Override public List<Map<String, Object>> finish() { return List.copyOf(rows); }
    }

    record ConfirmedStop(Status status, List<Map<String, Object>> dispatches) {}

    /** Extracted verbatim in sequence from the real launcher's release path. */
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
        if (!com.tianmu.agent.core.AgentTypes.terminal(status)) {
            throw new IllegalStateException("launch deadline: task did not reach terminal "
                    + "— the fee gate stays armed; manual review required");
        }
        var dispatches = bridge.finish();
        for (var dispatch : dispatches) {
            ledger.reservation(dispatch.get("reservation_id").toString());
        }
        if (!ledger.openReservations().isEmpty()) {
            throw new IllegalStateException("launch stop not confirmed: an OPEN reservation remains "
                    + "— the fee gate stays armed; manual review required");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("interrupted before the fee gate could be released");
        }
        EvaluationFeeGate.clear();
        return new ConfirmedStop(status, dispatches);
    }

    /** The launcher's launch-wait loop (source lines 1578-1590): bounded polling; an interrupt
     *  during the sleep propagates and the caller must keep the gate armed. */
    static Status awaitLaunch(Supplier<Status> statusView, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!com.tianmu.agent.core.AgentTypes.terminal(statusView.get())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return statusView.get();
    }

    @Test void launchWaitInterruptLeavesTheGateArmedAndSendsNothing() throws Exception {
        var ledger = ledger();
        EvaluationFeeGate.install(ledger);
        var bridge = new Bridge(ledger);
        var open = ledger.admitChat(MODEL, HOST, 4096, 1024);
        bridge.record(open.reservationId());

        Thread.currentThread().interrupt();
        var interrupted = assertThrows(InterruptedException.class,
                () -> awaitLaunch(() -> Status.RUNNING, 30));
        assertEquals(0, received.get(), "an interrupted launch wait sends nothing");
        assertTrue(EvaluationFeeGate.armed(), "the gate stays armed after the launch-wait interrupt");
        assertEquals(1, ledger.openReservations().size(), "the in-flight reservation survives");

        ledger.fail(open.reservationId(), "retained after launch-wait interrupt");
        confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0);
        assertFalse(EvaluationFeeGate.armed(), "only the confirmed stop unloads the gate");
        ledger.close();
    }

    @Test void launchDeadlineAndInterruptNeverUnloadTheGate() throws Exception {
        var ledger = ledger();
        EvaluationFeeGate.install(ledger);
        var bridge = new Bridge(ledger);
        var open = ledger.admitChat(MODEL, HOST, 4096, 1024);
        bridge.record(open.reservationId());

        // 1) launch wait never sees a terminal task: the gate stays armed, the reservation OPEN.
        var deadline = assertThrows(IllegalStateException.class,
                () -> confirmStopBeforeGateUnload(null, () -> Status.RUNNING, bridge, 0));
        assertTrue(deadline.getMessage().contains("launch deadline"), deadline.getMessage());
        assertTrue(EvaluationFeeGate.armed(), "an unconfirmed launch must keep the gate armed");
        assertEquals(1, ledger.openReservations().size(), "the in-flight reservation is not lost");

        // 2) a late dispatch after the stop decision is refused BEFORE any byte leaves: the expired
        //    control fails first, so no reservation is added and the double receives nothing.
        assertThrows(RuntimeException.class,
                () -> client().complete(MODEL, List.of(Map.of("role", "user", "content", "late")), 256,
                        new CallControl(Instant.now().minusSeconds(1), new CancellationToken()), true));
        assertEquals(0, received.get(), "a late request must not reach the double");
        assertEquals(1, ledger.openReservations().size(), "the late attempt creates no new reservation");
        assertEquals(1, countRows(ledgerFile), "the ledger still carries exactly the original row");

        // 3) the task finally terminates and the reservation is retained, but the thread is
        //    interrupted before release: the gate must NOT be cleared.
        ledger.fail(open.reservationId(), "retained at stop");
        Thread.currentThread().interrupt();
        var interrupted = assertThrows(InterruptedException.class,
                () -> confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0));
        assertTrue(interrupted.getMessage().contains("interrupted before the fee gate"),
                interrupted.getMessage());
        assertTrue(EvaluationFeeGate.armed(), "an interrupt before release keeps the gate armed");
        assertTrue(Thread.interrupted(), "the test consumes the interrupt the sequence left set");

        // 4) only a clean repeat of the confirmed stop unloads the gate.
        confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0);
        assertFalse(EvaluationFeeGate.armed(), "only a confirmed stop unloads the gate");
        ledger.close();
    }

    @Test void cancelExceptionLeavesTheGateArmedAndTheReservationIntact() throws Exception {
        var ledger = ledger();
        EvaluationFeeGate.install(ledger);
        var bridge = new Bridge(ledger);
        var open = ledger.admitChat(MODEL, HOST, 4096, 1024);
        bridge.record(open.reservationId());

        var cancelFailure = assertThrows(IllegalStateException.class,
                () -> confirmStopBeforeGateUnload(() -> {
                    throw new IllegalStateException("cancel transport failed");
                }, () -> Status.RUNNING, bridge, 0));
        assertEquals("cancel transport failed", cancelFailure.getMessage());
        assertTrue(EvaluationFeeGate.armed(), "a cancel exception must not unload the gate");
        assertEquals(1, ledger.openReservations().size(), "the reservation survives the cancel failure");
        assertEquals(0, received.get(), "the cancel failure must not trigger a send");

        // The still-armed gate refuses a late dispatch before the network.
        assertThrows(RuntimeException.class,
                () -> client().complete(MODEL, List.of(Map.of("role", "user", "content", "late")), 256,
                        new CallControl(Instant.now().minusSeconds(1), new CancellationToken()), true));
        assertEquals(0, received.get(), "no late packet reaches the double after the cancel exception");

        ledger.fail(open.reservationId(), "retained after cancel failure");
        confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0);
        assertFalse(EvaluationFeeGate.armed());
        ledger.close();
    }

    @Test void unconfirmableStopKeepsEveryReservationAndTheGateUntilTheLastOpenCloses() throws Exception {
        var ledger = ledger();
        EvaluationFeeGate.install(ledger);
        var bridge = new Bridge(ledger);
        var first = ledger.admitChat(MODEL, HOST, 4096, 1024);
        var second = ledger.admitChat(MODEL, HOST, 4096, 1024);
        bridge.record(first.reservationId());
        bridge.record(second.reservationId());

        // 1) the terminal read itself fails: the exception propagates, nothing is released.
        var readFailure = assertThrows(IllegalStateException.class,
                () -> confirmStopBeforeGateUnload(null, () -> {
                    throw new IllegalStateException("terminal read failed");
                }, bridge, 0));
        assertEquals("terminal read failed", readFailure.getMessage());
        assertTrue(EvaluationFeeGate.armed(), "a failed terminal read keeps the gate armed");
        assertEquals(2, ledger.openReservations().size());

        // 2) the task still runs: launch deadline, gate armed.
        var deadline = assertThrows(IllegalStateException.class,
                () -> confirmStopBeforeGateUnload(null, () -> Status.RUNNING, bridge, 0));
        assertTrue(deadline.getMessage().contains("launch deadline"), deadline.getMessage());
        assertTrue(EvaluationFeeGate.armed());

        // 3) terminal but one OPEN reservation remains (another batch member): refusal by name.
        ledger.fail(first.reservationId(), "retained at stop");
        var openRemains = assertThrows(IllegalStateException.class,
                () -> confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0));
        assertTrue(openRemains.getMessage().contains("an OPEN reservation remains"),
                openRemains.getMessage());
        assertTrue(EvaluationFeeGate.armed(), "the gate still protects the remaining OPEN reservation");
        assertEquals(0, received.get(), "no send happened through any of the refusal paths");

        // 4) the last open reservation is retained; only then does the confirmed stop release.
        ledger.fail(second.reservationId(), "retained at stop");
        confirmStopBeforeGateUnload(null, () -> Status.SUCCEEDED, bridge, 0);
        assertFalse(EvaluationFeeGate.armed());
        ledger.close();
    }

    private static int countRows(Path file) throws IOException {
        var doc = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        return doc.path("reservations").size();
    }
}
