package dev.dtp.application;

/** Poison/unsupported record; technical storage failures must not be classified as this. */
public final class InvalidEvent extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public InvalidEvent() { super("Event does not match a supported canonical admission."); }
}
