package com.tianmu.agent.evaluation.admission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Explicitly-armed CNY fee admission consulted before every real provider dispatch exit.
 *
 * Disarmed by default: production dispatch behavior is byte-identical (admitChat returns null and
 * the clients skip every fee step). Only the stage 4B-1 evaluation wiring arms it. When armed, the
 * client receives a per-dispatch {@link Reservation} handle: the real provider response settles its
 * own reservation, and any dispatch failure keeps it (FAILED_RETAINED). Missing or malformed usage
 * converts the reservation to USAGE_UNKNOWN (tokens stay null) and blocks all further paid
 * dispatch until a human resolves it.
 */
public final class EvaluationFeeGate {
    /** One live dispatch's fee identity; settle/fail must be driven by the dispatch itself. */
    public interface Reservation {
        String reservationId();

        /** Settle from the raw provider response; missing/bad usage marks USAGE_UNKNOWN and blocks. */
        void settle(JsonNode providerResponse);

        /** Timeout/cancel/failure: the reservation stays counted (in-flight cost is never released). */
        void fail(String reason);

        /**
         * Same retention, plus whether this logical call is finished. A typed, recoverable failure
         * (explicit 429 / temporary 5xx / a connection that never sent) retains its reservation but
         * does not seal the envelope, because one compensation attempt may still follow under its
         * own fresh reservation. Every other failure passes {@code terminal = true} and keeps the
         * original single-argument behavior. Default keeps every existing implementer unchanged.
         */
        default void fail(String reason, boolean terminal) { fail(reason); }

        /**
         * 4B-2A content-repair: bind the serialized request bytes to THIS reservation at the
         * client's serialization/send boundary, BEFORE the bytes leave the process. The digest is
         * what the strict reconciliation cross-checks against the receiving stub's own log, so
         * the sent bytes and the recorded request are provably the same bytes. Default no-op
         * keeps every existing implementer (and the disarmed production path, where the gate
         * returns null and this is never called) byte-identical.
         */
        default void bindRequestDigest(long bodyBytes, String bodySha256) {}
    }

    /**
     * The ONE durable-retry decision for an analysis attempt: how many attempts are allowed, how
     * long to wait before the next one, why, and whether it may be enqueued at all. The service
     * uses this record for the store's available-at, its attempt ceiling, its stop cause and its
     * trace; the policy's diagnostic row is written from the same record. Splitting these values
     * across layers is what previously let a client refuse a wait while the queue still re-sent.
     */
    public record AnalysisRetry(int maxAttempts, long delayMillis, String reason, boolean retryable) {
        public AnalysisRetry {
            if (maxAttempts < 1 || delayMillis < 0 || reason == null || reason.isBlank())
                throw new IllegalArgumentException("Invalid analysis retry decision");
        }
    }

    public interface Admission {
        default void beforeFeedbackAttempt(int attempt) {}
        default void feedbackFailed(String code) {}

        /**
         * Diagnostic overload: the exact validation reason and the work stage travel with the
         * coarse stop code so an evaluation observer can persist a structured rejection record
         * before the stop returns. Production implementers keep the two-argument behavior.
         */
        default void feedbackFailed(String code, String reason, String stage) { feedbackFailed(code); }

        /**
         * Compensation-aware overload. {@code compensated} means this same failure has already
         * been granted its one controlled retry (see {@link #analysisRetryCeiling}), so it is NOT
         * terminal: an observer must record it but must not seal the envelope, otherwise the
         * granted retry could never be sent. Production implementers keep the default.
         */
        default void feedbackFailed(String code, String reason, String stage, boolean compensated) {
            if (compensated) return;
            feedbackFailed(code, reason, stage);
        }

        /**
         * Tool-argument rejection, consulted where the decision is made and before any stop
         * returns. Carries only fixed enums, counts and declared schema paths; never argument
         * values, exception text, prompts or responses.
         */
        default void toolRejected(ToolRejection rejection) {}

        /** The existing pre-handler correction granted another model turn; nothing else changed. */
        default void toolCorrected(String runId, String tool, String phase, int correction) {}

        /** The run is stopping on a tool-argument code; {@code phase} is empty when it is unknown. */
        default void toolStopped(String runId, String tool, String phase, String code, int corrections) {}

        /** Consulted before the controller client issues any chat completion; null never returned when armed. */
        Reservation admitChat(String model, String host, long payloadBytes, int maxTokens);

        /**
         * How many durable analysis attempts this failure may use in total. The analysis role owns
         * a durable fence that admits exactly one dispatch per attempt, so its retry is granted by
         * the service that holds the fence instead of inside the HTTP client. Production keeps its
         * three-attempt ceiling; the evaluation policy answers two for one controlled compensation
         * and one for a terminal failure, and can never raise the production ceiling.
         *
         * <p>{@code attempt} is the durable attempt the fence is about to count, carrying the role,
         * model, reservation id and request digest the failing boundary actually knew.
         */
        default int analysisRetryCeiling(com.tianmu.agent.model.TransportFault fault,
                                         com.tianmu.agent.model.ModelRecovery.CallAttempt attempt) { return 3; }

        /**
         * One decision for the fence owner. Production implementations keep the historical three
         * attempts; an evaluation policy narrows the ceiling and records its diagnostic row here,
         * before the service is allowed to enqueue anything. The same record carries the provider's
         * wait, the remaining budget that was used to judge it, and the fixed stop reason.
         */
        default AnalysisRetry planAnalysisRetry(com.tianmu.agent.model.TransportFault fault,
                                                com.tianmu.agent.model.ModelRecovery.CallAttempt attempt,
                                                int durableAttempt, int productionMaxAttempts,
                                                long remainingMillis) {
            int ceiling = analysisRetryCeiling(fault, attempt);
            if (fault == null) {
                boolean retryable = durableAttempt < productionMaxAttempts;
                return new AnalysisRetry(Math.max(ceiling, productionMaxAttempts), 0,
                        retryable ? "RETRYABLE_BACKOFF" : "ATTEMPTS_EXHAUSTED", retryable);
            }
            long delay = com.tianmu.agent.model.ModelRecovery.compensationDelayMillis(fault, remainingMillis);
            boolean recoverable = fault.recoverable();
            boolean attemptsLeft = durableAttempt < ceiling;
            boolean payable = delay != com.tianmu.agent.model.ModelRecovery.UNPAYABLE_DELAY;
            boolean retryable = recoverable && attemptsLeft && payable;
            String reason = !recoverable ? "NOT_RECOVERABLE_" + fault.kind().name()
                    : !attemptsLeft ? "ATTEMPTS_EXHAUSTED"
                    : !payable ? "RETRY_AFTER_BEYOND_WAIT_BUDGET"
                    : "RECOVERABLE_" + fault.kind().name();
            return new AnalysisRetry(retryable ? ceiling : Math.max(1, durableAttempt),
                    retryable ? delay : 0, reason, retryable);
        }

        /**
         * 4B-2A release-gates repair: the CLIENT send boundary names the role the dispatch serves
         * ("analysis" for JSON-mode feedback calls, "harness" for the controller loop's
         * tool-carrying calls) — derived from the call's own shape at the client, never from the
         * receiving stub's annotation, so production mode carries the role too. Default keeps
         * every existing implementation compiling and unchanged.
         */
        default Reservation admitChat(String model, String host, long payloadBytes, int maxTokens, String clientRole) {
            return admitChat(model, host, payloadBytes, maxTokens);
        }

        /** Consulted before any vision or transcription dispatch; path distinguishes the exit. */
        Reservation admitVideo(String provider, String path);

        /**
         * Stage 4B-2A image exit. Only the registered bounded contract (MiniMax identity on the
         * generic image transport with a documented per-image ceiling and a supplier-enforced
         * output cap) can be admitted; every other provider/transport combination is refused
         * before any byte leaves the process. Test doubles that only implement {@link #admitVideo}
         * keep their previous counting semantics instead of silently gaining a new exit.
         */
        default Reservation admitVision(String provider, String transport, String path, String model,
                                        int imageCount, long payloadBytes, int maxOutputTokens) {
            return admitVideo(provider, path);
        }
    }

    /**
     * One tool-argument rejection in evaluator-safe form. {@code phase} is one of SCHEMA,
     * PREFLIGHT, HANDLER, RESULT, BATCH or BATCH_GROUP; {@code problems} are declared
     * {@code ToolArguments.Problem} names; {@code paths} are declared field paths with array
     * indexes collapsed. Handler layers have no structured field, so both lists stay empty and
     * {@code failureClass} names the fixed kind of error instead.
     */
    public record ToolRejection(String runId, int callIndex, String tool, String phase, String code,
                                List<String> paths, List<String> problems, int issueCount,
                                boolean issuesTruncated, String failureClass, boolean handlerExecuted) {
        public ToolRejection {
            paths = paths == null ? List.of() : List.copyOf(paths);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    private static volatile Admission admission;

    private EvaluationFeeGate() {}

    public static void install(Admission value) {
        admission = value;
        com.tianmu.agent.runtime.spi.ToolArgumentObserver.install(
                value == null ? null : new EvaluationToolArgumentObserver());
    }

    public static void clear() {
        admission = null;
        com.tianmu.agent.runtime.spi.ToolArgumentObserver.clear();
    }

    public static boolean armed() { return admission != null; }

    public static void beforeFeedbackAttempt(int attempt) {
        Admission current = admission;
        if (current != null) current.beforeFeedbackAttempt(attempt);
    }

    public static void feedbackFailed(String code) {
        Admission current = admission;
        if (current != null) current.feedbackFailed(code);
    }

    public static void feedbackFailed(String code, String reason, String stage) {
        Admission current = admission;
        if (current != null) current.feedbackFailed(code, reason, stage);
    }

    public static void feedbackFailed(String code, String reason, String stage, boolean compensated) {
        Admission current = admission;
        if (current != null) current.feedbackFailed(code, reason, stage, compensated);
    }

    /**
     * Diagnostics must never replace or widen the tool error being reported, so a faulty observer
     * is contained here instead of escaping into the dispatcher's control flow.
     */
    public static void toolRejected(ToolRejection rejection) {
        Admission current = admission;
        if (current == null) return;
        try { current.toolRejected(rejection); } catch (RuntimeException ignored) { }
    }

    public static void toolCorrected(String runId, String tool, String phase, int correction) {
        Admission current = admission;
        if (current == null) return;
        try { current.toolCorrected(runId, tool, phase, correction); } catch (RuntimeException ignored) { }
    }

    public static void toolStopped(String runId, String tool, String phase, String code, int corrections) {
        Admission current = admission;
        if (current == null) return;
        try { current.toolStopped(runId, tool, phase, code, corrections); } catch (RuntimeException ignored) { }
    }

    public static Reservation admitChat(String model, String host, long payloadBytes, int maxTokens) {
        return admitChat(model, host, payloadBytes, maxTokens, null);
    }

    public static Reservation admitChat(String model, String host, long payloadBytes, int maxTokens, String clientRole) {
        Admission current = admission;
        return current == null ? null : current.admitChat(model, host, payloadBytes, maxTokens, clientRole);
    }

    public static Reservation admitVideo(String provider, String path) {
        Admission current = admission;
        return current == null ? null : current.admitVideo(provider, path);
    }

    /**
     * Durable analysis attempts for this failure. Disarmed keeps the production ceiling of three;
     * armed answers are clamped to 1..3, so an evaluation policy can only narrow, never widen.
     * A faulty observer is treated as disarmed rather than breaking the stop path.
     *
     * <p>The attempt number comes from the fence (it is the durable count), while role, model,
     * reservation id, request digest and elapsed time come from the send boundary that failed —
     * each field is taken from the layer that actually knows it.
     */
    public static int analysisRetryCeiling(com.tianmu.agent.model.TransportFault fault,
                                           com.tianmu.agent.model.ModelRecovery.CallAttempt observed,
                                           int durableAttempt) {
        Admission current = admission;
        if (current == null) return 3;
        try {
            var attempt = new com.tianmu.agent.model.ModelRecovery.CallAttempt(
                    observed == null ? "analysis" : observed.role(),
                    observed == null ? "" : observed.model(), durableAttempt,
                    observed == null ? "" : observed.reservationId(),
                    observed == null ? 0 : observed.bodyBytes(),
                    observed == null ? "" : observed.bodySha256(),
                    observed == null ? (fault == null ? 0 : fault.elapsedMillis()) : observed.elapsedMillis());
            return Math.max(1, Math.min(3, current.analysisRetryCeiling(fault, attempt)));
        } catch (RuntimeException observer) {
            return 3;
        }
    }

    /** Ceiling without an observed dispatch identity; the durable attempt number still governs. */
    public static int analysisRetryCeiling(com.tianmu.agent.model.TransportFault fault, int durableAttempt) {
        return analysisRetryCeiling(fault, null, durableAttempt);
    }

    /**
     * The single decision the analysis service consumes. Unarmed production gets the historical
     * ceiling of three; an armed evaluation policy answers from its own policy and records the row
     * before returning. The remaining budget is the durable job's own deadline, not the client's
     * per-dispatch timeout, because a queued retry gets a fresh dispatch window.
     */
    public static AnalysisRetry planAnalysisRetry(com.tianmu.agent.model.TransportFault fault,
                                                  com.tianmu.agent.model.ModelRecovery.CallAttempt observed,
                                                  int durableAttempt, int productionMaxAttempts,
                                                  long remainingMillis) {
        Admission current = admission;
        if (current == null)
            return productionAnalysisRetry(fault, durableAttempt, productionMaxAttempts, remainingMillis);
        try {
            return current.planAnalysisRetry(fault, observed, durableAttempt, productionMaxAttempts,
                    remainingMillis);
        } catch (RuntimeException observer) {
            // A faulty observer can only refuse a compensation; it may never widen the rule.
            return productionAnalysisRetry(fault, durableAttempt, productionMaxAttempts, remainingMillis);
        }
    }

    /** Historical production rule, with the wait/expiry check used by every other role too. */
    public static AnalysisRetry productionAnalysisRetry(com.tianmu.agent.model.TransportFault fault,
                                                        int durableAttempt, int productionMaxAttempts,
                                                        long remainingMillis) {
        if (fault == null) {
            boolean retryable = durableAttempt < productionMaxAttempts;
            return new AnalysisRetry(productionMaxAttempts, 0,
                    retryable ? "RETRYABLE_BACKOFF" : "ATTEMPTS_EXHAUSTED", retryable);
        }
        long delay = com.tianmu.agent.model.ModelRecovery.compensationDelayMillis(fault, remainingMillis);
        boolean recoverable = fault.recoverable();
        boolean attemptsLeft = durableAttempt < productionMaxAttempts;
        boolean payable = delay != com.tianmu.agent.model.ModelRecovery.UNPAYABLE_DELAY;
        boolean retryable = recoverable && attemptsLeft && payable;
        String reason = !recoverable ? "NOT_RECOVERABLE_" + fault.kind().name()
                : !attemptsLeft ? "ATTEMPTS_EXHAUSTED"
                : !payable ? "RETRY_AFTER_BEYOND_WAIT_BUDGET"
                : "RECOVERABLE_" + fault.kind().name();
        return new AnalysisRetry(retryable ? productionMaxAttempts : Math.max(1, durableAttempt),
                retryable ? delay : 0, reason, retryable);
    }

    public static Reservation admitVision(String provider, String transport, String path, String model,
                                          int imageCount, long payloadBytes, int maxOutputTokens) {
        Admission current = admission;
        return current == null ? null
                : current.admitVision(provider, transport, path, model, imageCount, payloadBytes, maxOutputTokens);
    }
}
