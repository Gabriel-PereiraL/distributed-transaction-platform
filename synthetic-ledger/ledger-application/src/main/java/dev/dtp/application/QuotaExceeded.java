package dev.dtp.application;

public final class QuotaExceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final long retryAfterSeconds;
    public QuotaExceeded(long retryAfterSeconds) { super("Mutation quota exceeded."); this.retryAfterSeconds = Math.max(1, retryAfterSeconds); }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
