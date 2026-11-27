package com.tianmu.agent.model;

/**
 * Consultation point for the bounded compensation a logical model call may make.
 *
 * <p>The client asks at most {@link #maxCompensationsPerCall()} times (one by default), and only
 * after a typed failure it can prove safe to re-send (see {@link TransportFault#recoverable()}),
 * so an implementation cannot raise the attempt count beyond the declared ceiling — it can only
 * accept or refuse each offer. Production never installs one: {@link #NONE} keeps every dispatch
 * a single attempt, byte-identically to before.
 *
 * <p>Every failure, accepted or not, is offered to the policy so the evaluation can persist a
 * fixed-enum diagnostic row before the decision takes effect. The row must not depend on trace
 * details being enabled: the reason is classified here, not reconstructed later from prose.
 */
public interface ModelRecovery {
    /** One dispatch attempt's identity, as the fee ledger already knows it. */
    record CallAttempt(String role, String model, int attempt, String reservationId,
                       long bodyBytes, String bodySha256, long elapsedMillis) {}

    /**
     * {@code compensate} means THIS client will send again; {@code terminal} means the logical call
     * is finished and its fee reservation may be sealed. They differ for the analysis role, whose
     * retry is granted by the service that owns the durable fence: the client sends nothing more
     * (compensate=false) but must not seal the envelope either (terminal=false).
     */
    record Decision(boolean compensate, long delayMillis, String reason, boolean terminal) {
        public static final Decision STOP = new Decision(false, 0, "TERMINAL", true);

        public Decision {
            if (delayMillis < 0 || delayMillis > 60_000)
                throw new IllegalArgumentException("Compensation delay must be 0..60000ms");
            if (reason == null || reason.isBlank())
                throw new IllegalArgumentException("Compensation reason is required");
        }

        public static Decision compensateAfter(long delayMillis, String reason) {
            return new Decision(true, delayMillis, reason, false);
        }

        /** Finished here, but a retry is still possible at a layer that owns the retry fence. */
        public static Decision defer(String reason) { return new Decision(false, 0, reason, false); }

        /** Finished, and nothing may be re-sent under this logical call. */
        public static Decision stop(String reason) { return new Decision(false, 0, reason, true); }
    }

    /** Longest provider-stated wait a compensation honours inside one logical call. */
    long MAX_COMPENSATION_DELAY_MILLIS = 5_000;
    /**
     * Returned when the provider's own interval cannot be honoured. The caller must STOP with this
     * as a recognisable cause — never shorten the wait and re-send early, which is neither the
     * provider's schedule nor a proof that the retry was allowed.
     */
    long UNPAYABLE_DELAY = -1L;
    /** Wait used when the provider states no interval, so one attempt stays inside one budget. */
    long SHORT_BACKOFF_MILLIS = 500;

    /**
     * Wait before re-sending a failed logical call, read by BOTH recovery paths — the client
     * resend and the durable analysis retry the service owns — so the recorded delay and the
     * schedule actually used cannot drift apart.
     *
     * <p>{@code remainingMillis} is how much budget the logical call still has; a negative value
     * means "unknown, do not judge expiry". A wait that does not fit returns {@link #UNPAYABLE_DELAY}
     * so the caller stops for a named reason instead of waiting a truncated interval.
     */
    static long compensationDelayMillis(TransportFault fault, long remainingMillis) {
        if (fault == null) return 0;
        long stated = fault.retryAfterMillis();
        long wait = stated < 0 ? SHORT_BACKOFF_MILLIS : stated;
        if (wait > MAX_COMPENSATION_DELAY_MILLIS) return UNPAYABLE_DELAY;
        if (remainingMillis >= 0 && wait >= remainingMillis) return UNPAYABLE_DELAY;
        return wait;
    }

    static long compensationDelayMillis(TransportFault fault) {
        return compensationDelayMillis(fault, -1);
    }

    Decision onFailure(TransportFault fault, CallAttempt attempt);

    /**
     * Same consultation with the caller's remaining budget. The default forwards to the two-argument
     * form so an existing policy keeps its exact behaviour; a policy that honours the provider's
     * {@code Retry-After} overrides this one.
     */
    default Decision onFailure(TransportFault fault, CallAttempt attempt, long remainingMillis) {
        return onFailure(fault, attempt);
    }

    /**
     * Durable analysis attempts this failure may use in total. The analysis role owns a fence that
     * admits one dispatch per attempt, so its retry is granted by the service holding the fence.
     * Production keeps three; an evaluation policy may narrow this to two (one compensation) or one.
     *
     * <p>{@code attempt} is the durable attempt the fence is counting, and it carries the role,
     * model, reservation id and request digest the failing send boundary actually knew.
     */
    default int analysisRetryCeiling(TransportFault fault, CallAttempt attempt) { return 3; }

    /**
     * How many times one logical call may be re-offered after a recoverable fault. The dispatch
     * loop reads this instead of a literal, so the bound is declared once: production keeps 1 (the
     * historical behaviour), while an evaluation policy may admit two when a provider is known to
     * rate-limit twice before letting a request through.
     *
     * <p>This is a ceiling, not a promise: the policy's own per-attempt decision still has to say
     * yes, and the per-run spend guard still applies.
     */
    default int maxCompensationsPerCall() { return 1; }

    ModelRecovery NONE = (fault, attempt) -> Decision.STOP;
}
