package dev.dtp.application;

/** Controlled unavailability; never contains remote response bodies or credentials. */
public final class LegacyUnavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public LegacyUnavailable() { super("Legacy directory unavailable."); }
}
