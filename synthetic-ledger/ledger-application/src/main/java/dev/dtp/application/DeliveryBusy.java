package dev.dtp.application;

public final class DeliveryBusy extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public DeliveryBusy() { super("Canonical delivery has a live publishing lease."); }
}
