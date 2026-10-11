package dev.dtp.domain;

public final class RuleViolation extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public RuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
