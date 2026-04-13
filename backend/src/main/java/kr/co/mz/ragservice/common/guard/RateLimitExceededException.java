package kr.co.mz.ragservice.common.guard;

public class RateLimitExceededException extends RuntimeException {

    private final int limit;
    private final int remaining;
    private final long retryAfterMs;

    public RateLimitExceededException(String message) {
        this(message, 0, 0, 60_000);
    }

    public RateLimitExceededException(String message, int limit, int remaining, long retryAfterMs) {
        super(message);
        this.limit = limit;
        this.remaining = remaining;
        this.retryAfterMs = retryAfterMs;
    }

    public int getLimit() { return limit; }
    public int getRemaining() { return remaining; }
    public long getRetryAfterMs() { return retryAfterMs; }
}
