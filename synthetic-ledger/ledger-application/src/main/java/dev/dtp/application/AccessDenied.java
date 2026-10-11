package dev.dtp.application;

/** Object-level denial uses a uniform not-found response to avoid account enumeration. */
public final class AccessDenied extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public AccessDenied() { super("Resource not found."); }
}
