package dev.dtp.application;

public final class IdempotencyMismatch extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public IdempotencyMismatch() { super("Idempotency key already identifies a different request."); }
}
