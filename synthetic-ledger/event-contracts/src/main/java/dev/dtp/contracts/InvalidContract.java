package dev.dtp.contracts;

public final class InvalidContract extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public InvalidContract() { super("Unsupported or inconsistent transfer event contract."); }
}
