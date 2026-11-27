package com.tianmu.agent.model;

import com.tianmu.agent.core.AgentException;

/**
 * The existing stop code plus the typed cause it was derived from. The code stays exactly what
 * every accepted contract and diagnostic already asserts; the fault is the extra evidence that a
 * recovery decision and the durable diagnostic row need.
 *
 * <p>When the send boundary that failed also knew the dispatch it belonged to (role, model,
 * attempt, reservation, request digest), that identity travels with the failure so a layer that
 * re-dispatches later can record the same facts instead of blanks. It is optional: an ordinary
 * stop code carries none and stays exactly as it was.
 */
public final class TransportFailure extends AgentException {
    private final TransportFault fault;
    private final ModelRecovery.CallAttempt call;

    public TransportFailure(String code, String message, TransportFault fault) {
        this(code, message, fault, null);
    }

    public TransportFailure(String code, String message, TransportFault fault, ModelRecovery.CallAttempt call) {
        super(code, message);
        if (fault == null) throw new IllegalArgumentException("Transport fault is required");
        this.fault = fault;
        this.call = call;
    }

    public TransportFault fault() { return fault; }

    public ModelRecovery.CallAttempt call() { return call; }

    /**
     * Same code, same message, same fault, plus the dispatch identity. Used where a failure is
     * handed to a layer that owns a retry fence, so that layer can record and re-send precisely.
     */
    public TransportFailure withCall(ModelRecovery.CallAttempt attempt) {
        return call == null && attempt != null
                ? new TransportFailure(code(), getMessage(), fault, attempt) : this;
    }

    /** Typed cause when this failure carries one; null for every ordinary stop code. */
    public static TransportFault faultOf(Throwable failure) {
        return failure instanceof TransportFailure typed ? typed.fault() : null;
    }

    /** Dispatch identity when the failing boundary knew it; null otherwise. */
    public static ModelRecovery.CallAttempt callOf(Throwable failure) {
        return failure instanceof TransportFailure typed ? typed.call() : null;
    }
}
