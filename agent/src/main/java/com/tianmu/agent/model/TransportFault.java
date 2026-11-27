package com.tianmu.agent.model;

/**
 * One transport failure, classified where the cause is actually known: the status code is recorded
 * at the response, the wait outcome at the wait, never inferred from message text.
 *
 * <p>Only three kinds are recoverable, and only with a complete response or a confirmed
 * not-sent connection: an explicit 429, a temporary 5xx, and a connect failure that never put a
 * body on the wire. A timeout after the request left the process, a read that broke mid-body, a
 * parse failure, an oversize body, and every auth/quota/cancel outcome are terminal: the provider
 * may already have billed them, so they are retained and reported instead of re-sent.
 *
 * <p>{@code retryAfterMillis} is -1 when the provider sent no usable {@code Retry-After};
 * {@code elapsedMillis} is measured from the attempt's own start, not from the logical call.
 */
public record TransportFault(Kind kind, int httpStatus, long retryAfterMillis, long elapsedMillis) {
    public enum Kind {
        CONNECT_FAILED, WAIT_TIMEOUT, HTTP_429, HTTP_5XX, READ_BROKEN,
        PARSE_FAILED, OVERSIZE, AUTH, QUOTA, REJECTED, CANCELLED, UNKNOWN
    }

    public TransportFault {
        if (kind == null) throw new IllegalArgumentException("Transport fault kind is required");
        if (httpStatus < 0 || retryAfterMillis < -1 || elapsedMillis < 0)
            throw new IllegalArgumentException("Invalid transport fault measurements");
    }

    /** At most one compensation is allowed, and only for these. */
    public boolean recoverable() {
        return switch (kind) {
            case HTTP_429, HTTP_5XX, CONNECT_FAILED -> true;
            default -> false;
        };
    }

    public TransportFault withStatus(int status) {
        return new TransportFault(kind, status, retryAfterMillis, elapsedMillis);
    }

    public static TransportFault of(Kind kind, long elapsedMillis) {
        return new TransportFault(kind, 0, -1, elapsedMillis);
    }

    public static TransportFault http(Kind kind, int status, long retryAfterMillis, long elapsedMillis) {
        return new TransportFault(kind, status, retryAfterMillis, elapsedMillis);
    }
}
