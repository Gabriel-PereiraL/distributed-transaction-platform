package dev.dtp.application;

public final class QuotaUnavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public QuotaUnavailable() { super("Mutation quota is unavailable."); }
}
