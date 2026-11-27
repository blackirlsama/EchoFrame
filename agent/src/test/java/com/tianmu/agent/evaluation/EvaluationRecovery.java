package com.tianmu.agent.evaluation;

import com.tianmu.agent.model.ModelRecovery;
import com.tianmu.agent.model.TransportFault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evaluation-only compensation policy: the same admission standard for all three roles, up to two
 * attempts per logical call, and a durable fixed-enum row written BEFORE the decision takes effect.
 *
 * <p>The row never depends on trace details: role, logical-call attempt, reservation id, request
 * digest, HTTP status, {@code Retry-After}, elapsed millis and the decision are all known here.
 * No prompt, no response body, no header, no exception text is recorded.
 *
 * <p>A compensation that cannot be recorded does not happen. The counter is rolled back and the
 * failure stops the logical call with {@code DIAGNOSTIC_WRITE_FAILED}, so the ledger and the stub's
 * own received count can prove that nothing extra was sent.
 *
 * <p>Two independent counters bound the spend: {@link #MAX_PER_LOGICAL_CALL} per logical call and
 * {@link #MAX_PER_RUN} per run. With the largest observed call (19 fen) ten compensations cost
 * 190 fen, still far inside the 10000-fen batch cap. The per-run cap, not the per-call one, is what
 * finally bounds a run that never recovers.
 */
final class EvaluationRecovery implements ModelRecovery {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    static final int MAX_PER_LOGICAL_CALL = 2;
    static final int MAX_PER_RUN = 10;
    private static final String FILE = "transport-diagnostic.jsonl";

    private final Path root;
    private final AtomicInteger compensations = new AtomicInteger();
    private final AtomicInteger rows = new AtomicInteger();
    private final AtomicInteger diagnosticFailures = new AtomicInteger();

    EvaluationRecovery(Path root) { this.root = root; }

    int compensations() { return compensations.get(); }

    int rows() { return rows.get(); }

    int diagnosticFailures() { return diagnosticFailures.get(); }

    /** Unknown remaining budget: the provider's interval is still bounded, expiry is not judged. */
    @Override public Decision onFailure(TransportFault fault, CallAttempt attempt) {
        return onFailure(fault, attempt, -1);
    }

    @Override public Decision onFailure(TransportFault fault, CallAttempt attempt, long remainingMillis) {
        // The analysis role owns a durable fence that admits one dispatch per attempt, so a second
        // dispatch inside the client is refused by the job store. The client therefore only says
        // whether the fault is still retryable at all; the fence owner asks planAnalysisRetry once
        // with its own remaining budget and that one record decides enqueue, wait, count and row.
        if ("analysis".equals(attempt.role())) {
            if (!fault.recoverable()) return Decision.stop("NOT_RECOVERABLE_" + fault.kind().name());
            return Decision.defer("DEFERRED_TO_DURABLE_FENCE");
        }
        Decision decision = decide(fault, attempt, remainingMillis);
        if (!decision.compensate()) {
            record(fault, attempt, decision, 0);
            return decision;
        }
        compensations.incrementAndGet();
        if (record(fault, attempt, decision, attempt.attempt() - 1)) return decision;
        // The decision may not outlive the evidence for it.
        compensations.decrementAndGet();
        return Decision.stop("DIAGNOSTIC_WRITE_FAILED");
    }

    /**
     * The fence owner's one decision. It uses the same {@link #decide} as every other role, with the
     * durable job's remaining budget; the row is written before the service is allowed to enqueue,
     * and a failed write turns the grant into a terminal stop.
     */
    public com.tianmu.agent.evaluation.admission.EvaluationFeeGate.AnalysisRetry planAnalysisRetry(
            TransportFault fault, CallAttempt attempt, int durableAttempt, int productionMaxAttempts,
            long remainingMillis) {
        if (fault == null) {
            boolean retryable = durableAttempt < productionMaxAttempts;
            return new com.tianmu.agent.evaluation.admission.EvaluationFeeGate.AnalysisRetry(
                    productionMaxAttempts, 0,
                    retryable ? "RETRYABLE_BACKOFF" : "ATTEMPTS_EXHAUSTED", retryable);
        }
        var observed = new CallAttempt(attempt == null ? "analysis" : attempt.role(),
                attempt == null ? "" : attempt.model(), durableAttempt,
                attempt == null ? "" : attempt.reservationId(),
                attempt == null ? 0 : attempt.bodyBytes(),
                attempt == null ? "" : attempt.bodySha256(),
                attempt == null ? fault.elapsedMillis() : attempt.elapsedMillis());
        Decision decision = decide(fault, observed, remainingMillis);
        if (!decision.compensate()) {
            record(fault, observed, decision, 0);
            return new com.tianmu.agent.evaluation.admission.EvaluationFeeGate.AnalysisRetry(
                    1, 0, decision.reason(), false);
        }
        compensations.incrementAndGet();
        if (record(fault, observed, decision, observed.attempt() - 1))
            return new com.tianmu.agent.evaluation.admission.EvaluationFeeGate.AnalysisRetry(
                    1 + MAX_PER_LOGICAL_CALL, decision.delayMillis(), decision.reason(), true);
        // The decision may not outlive the evidence for it.
        compensations.decrementAndGet();
        return new com.tianmu.agent.evaluation.admission.EvaluationFeeGate.AnalysisRetry(
                1, 0, "DIAGNOSTIC_WRITE_FAILED", false);
    }

    @Override public int analysisRetryCeiling(TransportFault fault, CallAttempt attempt) {
        return planAnalysisRetry(fault, attempt, attempt == null ? 1 : attempt.attempt(), 3, -1).maxAttempts();
    }

    int analysisRetryCeiling(TransportFault fault, CallAttempt attempt, long remainingMillis) {
        return planAnalysisRetry(fault, attempt, attempt == null ? 1 : attempt.attempt(), 3,
                remainingMillis).maxAttempts();
    }

    /** Ceiling for a caller that only knows the durable attempt number (unit tests, old callers). */
    int analysisRetryCeiling(TransportFault fault, int attempt) {
        return analysisRetryCeiling(fault, new CallAttempt("analysis", "", attempt, "", 0, "",
                fault == null ? 0 : fault.elapsedMillis()));
    }

    /** Declared once here, read by both dispatch loops instead of a hard-coded literal. */
    @Override public int maxCompensationsPerCall() { return MAX_PER_LOGICAL_CALL; }

    private Decision decide(TransportFault fault, CallAttempt attempt, long remainingMillis) {
        if (!fault.recoverable()) return Decision.stop("NOT_RECOVERABLE_" + fault.kind().name());
        // attempt is the durable logical-call attempt number: 1 is the first send, so a cap of two
        // compensations admits attempts 1, 2 and 3 and refuses the fourth.
        if (attempt.attempt() > MAX_PER_LOGICAL_CALL) return Decision.stop("COMPENSATION_ALREADY_USED");
        if (compensations.get() >= MAX_PER_RUN) return Decision.stop("RUN_COMPENSATION_CAP");
        // A provider that says "come back in N seconds" is honoured for exactly N seconds when it
        // fits; when it does not, the cause is named and nothing is re-sent. Both recovery paths
        // read the same helper, so this row and the real schedule agree.
        long delay = ModelRecovery.compensationDelayMillis(fault, remainingMillis);
        if (delay == ModelRecovery.UNPAYABLE_DELAY) return Decision.stop("RETRY_AFTER_BEYOND_WAIT_BUDGET");
        return Decision.compensateAfter(delay, "RECOVERABLE_" + fault.kind().name());
    }

    /**
     * One durable row. Returns {@code false} when the row could not be written; the caller then
     * refuses to compensate, because an unrecorded retry cannot be attributed afterwards.
     */
    private boolean record(TransportFault fault, CallAttempt attempt, Decision decision, int callCompensations) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", "canon-transport-diagnostic-v1");
        row.put("at", Instant.now().toString());
        row.put("role", attempt.role());
        row.put("model", attempt.model());
        row.put("attempt", attempt.attempt());
        row.put("reservation_id", attempt.reservationId());
        row.put("body_bytes", attempt.bodyBytes());
        row.put("body_sha256", attempt.bodySha256());
        row.put("kind", fault.kind().name());
        row.put("http_status", fault.httpStatus());
        row.put("retry_after_ms", fault.retryAfterMillis());
        row.put("elapsed_ms", attempt.elapsedMillis());
        row.put("compensate", decision.compensate());
        row.put("delay_ms", decision.delayMillis());
        row.put("call_compensations", callCompensations);
        row.put("run_compensations", compensations.get());
        row.put("decision", decision.reason());
        try (var channel = FileChannel.open(root.resolve(FILE), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            var line = ByteBuffer.wrap((JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8));
            while (line.hasRemaining()) channel.write(line);
            channel.force(true);
            rows.incrementAndGet();
            return true;
        } catch (IOException | RuntimeException failure) {
            // Observation must never replace the dispatch error being reported, but it does decide
            // whether a resend is allowed: mark the cause where a witness can still see it.
            diagnosticFailures.incrementAndGet();
            System.err.println("EVAL_DIAGNOSTIC_WRITE_FAILED " + failure.getClass().getSimpleName());
            return false;
        }
    }
}
